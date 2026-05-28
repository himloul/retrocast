#include <jni.h>
#include <string>
#include <android/log.h>
#include <dlfcn.h>
#include <vector>
#include <mutex>
#include <atomic>
#include <cstdarg>
#include <thread>
#include <cstring>
#include <cstdlib>
#include <chrono>
#include <condition_variable>
#include <fstream>

#include <oboe/Oboe.h>

#include "libretro.h"

#define LOG_TAG "ZenithEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define RETRO_ENVIRONMENT_SET_PIXEL_FORMAT 1
#define RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY 9
#define RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY 31
#define RETRO_ENVIRONMENT_GET_LOG_INTERFACE 27
#define RETRO_ENVIRONMENT_GET_VARIABLE 15
#define RETRO_ENVIRONMENT_GET_CAN_DUPE 10
#define RETRO_MEMORY_SAVE_RAM 0

struct retro_variable { const char *key; const char *value; };
typedef void (*retro_log_printf_t)(enum retro_log_level level, const char *fmt, ...);
struct retro_log_callback { retro_log_printf_t log; };

struct RingBuffer {
    std::vector<int16_t> buffer;
    std::atomic<size_t> head{0}, tail{0};
    const size_t capacity;
    explicit RingBuffer(size_t cap = 65536) : capacity(cap) { buffer.resize(cap); }
    size_t available_to_write() const { size_t h = head.load(std::memory_order_relaxed), t = tail.load(std::memory_order_acquire); return (h >= t) ? (capacity - (h - t) - 1) : (t - h - 1); }
    void write(const int16_t* d, size_t c) { size_t h = head.load(std::memory_order_relaxed); for (size_t i = 0; i < c; i++) { buffer[h] = d[i]; h = (h + 1) % capacity; } head.store(h, std::memory_order_release); }
    size_t read(int16_t* d, size_t c) { size_t t = tail.load(std::memory_order_relaxed), h = head.load(std::memory_order_acquire), r = 0; while (r < c && t != h) { d[r++] = buffer[t]; t = (t + 1) % capacity; } tail.store(t, std::memory_order_release); return r; }
};

struct ZenithEngine {
    RingBuffer audio_rb;
    std::atomic<bool> emu_running{false};
    std::thread emu_thread;
    std::condition_variable audio_cv;
    std::mutex audio_cv_mtx;
    void *core_handle = nullptr;
    retro_run_t core_run = nullptr;
    std::atomic<retro_pixel_format> pixel_fmt{RETRO_PIXEL_FORMAT_0RGB1555};
    std::atomic<uint16_t> input_state{0};
    std::atomic<bool> local_audio_muted{false};
    std::recursive_mutex core_mutex;
    std::string system_dir = ".", save_dir = ".", rom_path = "";
    typedef void *(*get_mem_data_t)(unsigned id);
    typedef size_t (*get_mem_size_t)(unsigned id);
    get_mem_data_t get_mem_data = nullptr;
    get_mem_size_t get_mem_size = nullptr;
    JavaVM* jvm = nullptr;
    jobject callback_obj = nullptr;
    jobject argb_buf = nullptr;
    jobject i420_buf = nullptr;
    jmethodID on_frame_mid = nullptr;
    uint8_t* argb_ptr = nullptr;
    uint8_t* i420_ptr = nullptr;
    std::shared_ptr<oboe::AudioStream> audio_stream;
    jobject audio_cb_obj = nullptr;
    jmethodID on_audio_mid = nullptr;
    jobject audio_buf = nullptr;
    double av_fps = 60.0;
    double av_sample_rate = 44100.0;
};
static ZenithEngine g_engine;

static void libretro_log(enum retro_log_level, const char *fmt, ...) { va_list a; va_start(a,fmt); __android_log_vprint(ANDROID_LOG_INFO,"Libretro",fmt,a); va_end(a); }

class OboeAudioCallback : public oboe::AudioStreamCallback {
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* d, int32_t f) override {
        int16_t *o = (int16_t*)d;
        size_t r = g_engine.audio_rb.read(o, f*2);
        if (r < (size_t)f*2) memset(o+r, 0, (f*2-r)*2);
        if (g_engine.local_audio_muted.load()) {
            memset(o, 0, f * 2 * sizeof(int16_t));
        }
        g_engine.audio_cv.notify_one();
        return oboe::DataCallbackResult::Continue;
    }
};
static OboeAudioCallback g_oboe_callback;

static bool setupOboeStream(int32_t sampleRate) {
    if (g_engine.audio_stream) {
        g_engine.audio_stream->stop();
        g_engine.audio_stream->close();
        g_engine.audio_stream.reset();
    }
    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Output)
     ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
     ->setFormat(oboe::AudioFormat::I16)
     ->setChannelCount(oboe::ChannelCount::Stereo)
     ->setSampleRate(sampleRate)
     ->setCallback(&g_oboe_callback);
    oboe::Result result = b.openStream(g_engine.audio_stream);
    if (result != oboe::Result::OK) {
        LOGE("setupOboeStream: failed at %d Hz", sampleRate);
        return false;
    }
    g_engine.audio_stream->requestStart();
    LOGI("setupOboeStream: opened at %d Hz", sampleRate);
    return true;
}

void video_refresh_cb(const void *data, unsigned w, unsigned h, size_t pitch) {
    if (!data || !g_engine.argb_ptr) return;

    retro_pixel_format fmt = g_engine.pixel_fmt.load();
    uint8_t* argb = g_engine.argb_ptr;

    for (unsigned y = 0; y < h; y++) {
        for (unsigned x = 0; x < w; x++) {
            uint32_t color;
            if (fmt == RETRO_PIXEL_FORMAT_XRGB8888) {
                uint32_t pix = ((const uint32_t*)data)[y * (pitch / 4) + x];
                uint8_t r = pix & 0xFF, g = (pix >> 8) & 0xFF, b = (pix >> 16) & 0xFF;
                color = 0xFF000000 | ((uint32_t)b << 16) | ((uint32_t)g << 8) | (uint32_t)r;
            } else {
                uint16_t pix = ((const uint16_t*)data)[y * (pitch / 2) + x];
                uint8_t R = (pix >> 11) & 0x1F;
                uint8_t G = (pix >> 5) & 0x3F;
                uint8_t B = pix & 0x1F;
                R = (R << 3) | (R >> 2);
                G = (G << 2) | (G >> 4);
                B = (B << 3) | (B >> 2);
                color = 0xFF000000 | ((uint32_t)B << 16) | ((uint32_t)G << 8) | (uint32_t)R;
            }
            *(uint32_t*)argb = color;
            argb += 4;
        }
    }

    if (g_engine.i420_ptr) {
        uint8_t* y_plane = g_engine.i420_ptr;
        uint8_t* u_plane = g_engine.i420_ptr + w * h;
        uint8_t* v_plane = u_plane + (w / 2) * (h / 2);
        uint32_t* argb_pixels = (uint32_t*)g_engine.argb_ptr;

        for (unsigned y = 0; y < h; y++) {
            for (unsigned x = 0; x < w; x++) {
                uint32_t pixel = argb_pixels[y * w + x];
                int r = pixel & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = (pixel >> 16) & 0xFF;

                int yy = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                y_plane[y * w + x] = (uint8_t)(yy < 0 ? 0 : yy > 255 ? 255 : yy);

                if ((y & 1) == 0 && (x & 1) == 0) {
                    int uu = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int vv = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    u_plane[(y / 2) * (w / 2) + (x / 2)] = (uint8_t)(uu < 0 ? 0 : uu > 255 ? 255 : uu);
                    v_plane[(y / 2) * (w / 2) + (x / 2)] = (uint8_t)(vv < 0 ? 0 : vv > 255 ? 255 : vv);
                }
            }
        }
    }

    JNIEnv* env; g_engine.jvm->AttachCurrentThread(&env, nullptr);
    env->CallVoidMethod(g_engine.callback_obj, g_engine.on_frame_mid,
        g_engine.argb_buf, (jint)w, (jint)h,
        g_engine.i420_buf, (jint)w, (jint)(w / 2));
}

size_t audio_batch_cb(const int16_t *d, size_t f) {
    size_t needed = f * 2;
    while (g_engine.audio_rb.available_to_write() < needed && g_engine.emu_running) {
        std::this_thread::yield();
    }
    if (!g_engine.emu_running) return 0;
    g_engine.audio_rb.write(d, needed);
    if (g_engine.audio_cb_obj && g_engine.audio_buf) {
        JNIEnv* env;
        g_engine.jvm->AttachCurrentThread(&env, nullptr);
        size_t ns = f * 2;
        jsize cap = env->GetDirectBufferCapacity(g_engine.audio_buf);
        if ((jsize)(ns * 2) <= cap) {
            void* buf_ptr = env->GetDirectBufferAddress(g_engine.audio_buf);
            memcpy(buf_ptr, d, ns * 2);
            env->CallVoidMethod(g_engine.audio_cb_obj, g_engine.on_audio_mid, g_engine.audio_buf, (jint)ns);
        }
    }
    return f;
}
int16_t input_state_cb(unsigned p, unsigned d, unsigned i, unsigned id) { return (p==0 && d==RETRO_DEVICE_JOYPAD) ? ((g_engine.input_state.load() & (1<<id))?1:0) : (int16_t)0; }
bool env_cb(unsigned cmd, void *data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: { retro_pixel_format pf = *(const retro_pixel_format *)data; g_engine.pixel_fmt.store(pf); LOGI("SET_PIXEL_FORMAT: %d (0=0RGB1555, 1=XRGB8888, 2=RGB565)", pf); return true; }
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: ((struct retro_log_callback *)data)->log = libretro_log; return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE: { auto *v = (struct retro_variable *)data; if (v->key && strcmp(v->key, "mgba_color_correction") == 0) { v->value = "GBA"; return true; } return false; }
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: *(const char**)data = g_engine.system_dir.c_str(); return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: *(const char**)data = g_engine.save_dir.c_str(); return true;
        case RETRO_ENVIRONMENT_GET_CAN_DUPE: *(bool*)data = true; return true;
    }
    return false;
}

extern "C" {
JNIEXPORT void JNICALL Java_com_sharescreen_emulator_NativeRetro_init(JNIEnv* env, jobject, jstring path) {
    LOGI("=== init() called ===");
    const char* p = env->GetStringUTFChars(path, nullptr); g_engine.core_handle = dlopen(p, RTLD_LAZY); env->ReleaseStringUTFChars(path, p);
    LOGI("init: dlopen(%s) = %p", p, g_engine.core_handle);
    ((retro_set_environment_t)dlsym(g_engine.core_handle, "retro_set_environment"))(env_cb);
    ((retro_set_video_refresh_t)dlsym(g_engine.core_handle, "retro_set_video_refresh"))(video_refresh_cb);
    ((retro_set_audio_sample_batch_t)dlsym(g_engine.core_handle, "retro_set_audio_sample_batch"))(audio_batch_cb);
    ((retro_set_input_state_t)dlsym(g_engine.core_handle, "retro_set_input_state"))(input_state_cb);
    ((retro_set_input_poll_t)dlsym(g_engine.core_handle, "retro_set_input_poll"))([](){});
    g_engine.get_mem_data = (decltype(g_engine.get_mem_data))dlsym(g_engine.core_handle, "retro_get_memory_data");
    g_engine.get_mem_size = (decltype(g_engine.get_mem_size))dlsym(g_engine.core_handle, "retro_get_memory_size");
    ((retro_init_t)dlsym(g_engine.core_handle, "retro_init"))();
    LOGI("init: done (Oboe deferred to loadGame)");
}

JNIEXPORT void JNICALL Java_com_sharescreen_emulator_NativeRetro_setPaths(JNIEnv* env, jobject, jstring sys, jstring sav) { const char *s1 = env->GetStringUTFChars(sys,0), *s2 = env->GetStringUTFChars(sav,0); g_engine.system_dir = s1; g_engine.save_dir = s2; env->ReleaseStringUTFChars(sys,s1); env->ReleaseStringUTFChars(sav,s2); }

JNIEXPORT void JNICALL Java_com_sharescreen_emulator_NativeRetro_saveSram(JNIEnv*, jobject) {
    if (!g_engine.get_mem_data || g_engine.rom_path.empty()) return;
    void* d = g_engine.get_mem_data(RETRO_MEMORY_SAVE_RAM); size_t s = g_engine.get_mem_size(RETRO_MEMORY_SAVE_RAM);
    if (d && s > 0) { std::string p = g_engine.save_dir + "/" + g_engine.rom_path.substr(g_engine.rom_path.find_last_of("/\\") + 1) + ".sav"; std::ofstream f(p, std::ios::binary); if (f.is_open()) { f.write((const char*)d, s); f.close(); } }
}

JNIEXPORT jboolean JNICALL Java_com_sharescreen_emulator_NativeRetro_loadGame(JNIEnv* env, jobject, jstring path) {
    LOGI("loadGame called");
    const char* p = env->GetStringUTFChars(path, nullptr); g_engine.rom_path = p;
    struct retro_game_info info = { p, nullptr, 0, nullptr }; bool ok = ((bool (*)(const struct retro_game_info*))dlsym(g_engine.core_handle, "retro_load_game"))(&info);
    LOGI("loadGame: retro_load_game returned %d", ok);
    if (ok) {
        auto get_av = (void (*)(struct retro_system_av_info*))dlsym(g_engine.core_handle, "retro_get_system_av_info");
        if (get_av) {
            struct retro_system_av_info av;
            get_av(&av);
            g_engine.av_fps = av.timing.fps;
            g_engine.av_sample_rate = av.timing.sample_rate;
            LOGI("loadGame: fps=%.4f, sample_rate=%.0f", g_engine.av_fps, g_engine.av_sample_rate);
        }
        setupOboeStream((int32_t)g_engine.av_sample_rate);
        std::string sp = g_engine.save_dir + "/" + g_engine.rom_path.substr(g_engine.rom_path.find_last_of("/\\") + 1) + ".sav";
        std::ifstream f(sp, std::ios::binary);
        if (f.is_open()) { void* d = g_engine.get_mem_data(RETRO_MEMORY_SAVE_RAM); if (d) f.read((char*)d, g_engine.get_mem_size(RETRO_MEMORY_SAVE_RAM)); f.close(); }
    }
    env->ReleaseStringUTFChars(path, p); g_engine.core_run = (retro_run_t)dlsym(g_engine.core_handle, "retro_run"); LOGI("loadGame: core_run = %p", g_engine.core_run); return ok;
}

JNIEXPORT void JNICALL Java_com_sharescreen_emulator_NativeRetro_start(JNIEnv*, jobject) {
    g_engine.emu_running = true;
    g_engine.emu_thread = std::thread([]{
        auto frame_duration = std::chrono::duration<double, std::nano>(1.0 / g_engine.av_fps * 1e9);
        auto start_time = std::chrono::steady_clock::now();
        uint64_t frame_count = 0;
        while (g_engine.emu_running) {
            {
                std::lock_guard<std::recursive_mutex> l(g_engine.core_mutex);
                if (g_engine.core_run) g_engine.core_run();
            }
            frame_count++;
            auto expected_end = start_time + frame_duration * frame_count;
            auto now = std::chrono::steady_clock::now();
            if (now < expected_end) {
                std::this_thread::sleep_for(expected_end - now);
            }
        }
    });
}

JNIEXPORT void JNICALL Java_com_sharescreen_emulator_NativeRetro_stop(JNIEnv*, jobject) { g_engine.emu_running = false; g_engine.audio_cv.notify_all(); if(g_engine.emu_thread.joinable()) g_engine.emu_thread.join(); }

JNIEXPORT void JNICALL Java_com_sharescreen_emulator_NativeRetro_unloadGame(JNIEnv*, jobject) {
    LOGI("=== unloadGame() called ===");
    if (g_engine.core_handle) {
        auto retro_unload_game = (void (*)())dlsym(g_engine.core_handle, "retro_unload_game");
        if (retro_unload_game) retro_unload_game();
    }
    g_engine.core_run = nullptr;
    g_engine.rom_path = "";
    g_engine.audio_rb.head = 0;
    g_engine.audio_rb.tail = 0;
}

JNIEXPORT void JNICALL Java_com_sharescreen_emulator_NativeRetro_setLocalAudioMuted(JNIEnv*, jobject, jboolean muted) {
    g_engine.local_audio_muted.store(muted);
}

JNIEXPORT void JNICALL Java_com_sharescreen_emulator_NativeRetro_setCallback(JNIEnv* env, jobject, jobject cb, jobject pixels, jobject i420) {
    if(g_engine.callback_obj) env->DeleteGlobalRef(g_engine.callback_obj);
    if(g_engine.argb_buf) env->DeleteGlobalRef(g_engine.argb_buf);
    if(g_engine.i420_buf) env->DeleteGlobalRef(g_engine.i420_buf);
    if(cb) {
        g_engine.callback_obj = env->NewGlobalRef(cb);
        g_engine.argb_buf = env->NewGlobalRef(pixels);
        g_engine.argb_ptr = (uint8_t*)env->GetDirectBufferAddress(pixels);
        g_engine.i420_buf = env->NewGlobalRef(i420);
        g_engine.i420_ptr = (uint8_t*)env->GetDirectBufferAddress(i420);
        env->GetJavaVM(&g_engine.jvm);
        g_engine.on_frame_mid = env->GetMethodID(env->GetObjectClass(cb), "onFrameReady", "(Ljava/nio/ByteBuffer;IILjava/nio/ByteBuffer;II)V");
    } else {
        g_engine.callback_obj = nullptr;
        g_engine.argb_buf = nullptr;
        g_engine.argb_ptr = nullptr;
        g_engine.i420_buf = nullptr;
        g_engine.i420_ptr = nullptr;
    }
}

JNIEXPORT void JNICALL Java_com_sharescreen_emulator_NativeRetro_setAudioCallback(JNIEnv* env, jobject, jobject cb, jobject buf) {
    if (g_engine.audio_cb_obj) env->DeleteGlobalRef(g_engine.audio_cb_obj);
    if (g_engine.audio_buf) env->DeleteGlobalRef(g_engine.audio_buf);
    if (cb) {
        g_engine.audio_cb_obj = env->NewGlobalRef(cb);
        g_engine.audio_buf = env->NewGlobalRef(buf);
        env->GetJavaVM(&g_engine.jvm);
        g_engine.on_audio_mid = env->GetMethodID(env->GetObjectClass(cb), "onAudioReady", "(Ljava/nio/ByteBuffer;I)V");
    } else {
        g_engine.audio_cb_obj = nullptr;
        g_engine.audio_buf = nullptr;
        g_engine.on_audio_mid = nullptr;
    }
}

JNIEXPORT void JNICALL Java_com_sharescreen_emulator_NativeRetro_setInputState(JNIEnv*, jobject, jint s) { LOGI("setInputState: %d", s); g_engine.input_state = (uint16_t)s; }

JNIEXPORT jint JNICALL Java_com_sharescreen_emulator_NativeRetro_getSampleRate(JNIEnv*, jobject) { return (jint)g_engine.av_sample_rate; }
JNIEXPORT jstring JNICALL Java_com_sharescreen_emulator_NativeRetro_getCoreVersion(JNIEnv* env, jobject) { return env->NewStringUTF("Zenith Engine v5.0"); }
}

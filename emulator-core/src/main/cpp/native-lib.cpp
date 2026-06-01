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
#include <fstream>
#include <sys/stat.h>
#include <algorithm>
#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif

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
    typedef size_t (*state_size_fn)();
    typedef bool (*serialize_fn)(void*, size_t);
    typedef bool (*unserialize_fn)(const void*, size_t);
    state_size_fn state_size = nullptr;
    serialize_fn state_serialize = nullptr;
    unserialize_fn state_unserialize = nullptr;
    std::atomic<bool> is_casting{false};
    std::atomic<bool> local_display_active{true};
    std::vector<uint8_t> raw_frame_buf;
    unsigned last_w = 0, last_h = 0;
    size_t last_pitch = 0;
    const uint8_t* current_frame_data = nullptr;
    JavaVM* jvm = nullptr;
    jobject callback_obj = nullptr;
    jobject argb_buf = nullptr;
    jmethodID on_frame_mid = nullptr;
    uint8_t* argb_ptr = nullptr;
    std::shared_ptr<oboe::AudioStream> audio_stream;
    jobject audio_cb_obj = nullptr;
    jmethodID on_audio_mid = nullptr;
    jobject audio_buf = nullptr;
    double av_fps = 60.0;
    double av_sample_rate = 44100.0;
};
static ZenithEngine g_engine;

static void libretro_log(enum retro_log_level, const char *fmt, ...) { va_list a; va_start(a,fmt); __android_log_vprint(ANDROID_LOG_INFO,"Libretro",fmt,a); va_end(a); }

static JNIEnv* getJniEnv() {
    JNIEnv* env;
    if (g_engine.jvm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        g_engine.jvm->AttachCurrentThread(&env, nullptr);
    }
    return env;
}

class OboeAudioCallback : public oboe::AudioStreamCallback {
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* d, int32_t f) override {
        int16_t *o = (int16_t*)d;
        if (g_engine.local_audio_muted.load()) {
            memset(o, 0, f * 2 * sizeof(int16_t));
        } else {
            size_t r = g_engine.audio_rb.read(o, f*2);
            if (r < (size_t)f*2) memset(o+r, 0, (f*2-r)*2);
        }
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

static inline uint8_t clamp_uint8(int v) {
    return uint8_t(v < 0 ? 0 : v > 255 ? 255 : v);
}

void video_refresh_cb(const void *data, unsigned w, unsigned h, size_t pitch) {
    if (!data || !g_engine.argb_ptr) return;

    retro_pixel_format fmt = g_engine.pixel_fmt.load();

    if (g_engine.is_casting.load()) {
        g_engine.last_w = w;
        g_engine.last_h = h;
        g_engine.last_pitch = pitch;
        g_engine.current_frame_data = (const uint8_t*)data;
    }

    if (!g_engine.is_casting.load() || g_engine.local_display_active.load()) {
        uint8_t* argb = g_engine.argb_ptr;
        const uint8_t* raw = (const uint8_t*)data;

        for (unsigned y = 0; y < h; y++) {
            if (fmt == RETRO_PIXEL_FORMAT_XRGB8888) {
                const uint32_t* src = (const uint32_t*)(raw + y * pitch);
                uint32_t* dst = (uint32_t*)(argb + y * w * 4);
                unsigned x = 0;
#if defined(__ARM_NEON)
                uint32x4_t alpha = vdupq_n_u32(0xFF000000);
                for (; x + 4 <= w; x += 4) {
                    vst1q_u32(dst + x, vorrq_u32(vld1q_u32(src + x), alpha));
                }
#endif
                for (; x < w; x++) {
                    dst[x] = src[x] | 0xFF000000;
                }
            } else {
                const uint16_t* src = (const uint16_t*)(raw + y * pitch);
                uint32_t* dst = (uint32_t*)(argb + y * w * 4);
                for (unsigned x = 0; x < w; x++) {
                    uint16_t pix = src[x];
                    uint8_t R = (pix >> 11) & 0x1F;
                    uint8_t G = (pix >> 5) & 0x3F;
                    uint8_t B = pix & 0x1F;
                    R = (R << 3) | (R >> 2);
                    G = (G << 2) | (G >> 4);
                    B = (B << 3) | (B >> 2);
                    dst[x] = 0xFF000000 | ((uint32_t)B << 16) | ((uint32_t)G << 8) | (uint32_t)R;
                }
            }
        }
    }

    JNIEnv* env = getJniEnv();
    if (env) {
        env->CallVoidMethod(g_engine.callback_obj, g_engine.on_frame_mid,
            g_engine.argb_buf, (jint)w, (jint)h);
    }
    g_engine.current_frame_data = nullptr;
}

static void sendAudioToJava(const int16_t *d, size_t f) {
    if (g_engine.audio_cb_obj && g_engine.audio_buf) {
        JNIEnv* env = getJniEnv();
        if (!env) return;
        size_t bytes = f * 2 * sizeof(int16_t);
        jsize cap = env->GetDirectBufferCapacity(g_engine.audio_buf);
        if ((jsize)bytes <= cap) {
            void* buf_ptr = env->GetDirectBufferAddress(g_engine.audio_buf);
            memcpy(buf_ptr, d, bytes);
            env->CallVoidMethod(g_engine.audio_cb_obj, g_engine.on_audio_mid, g_engine.audio_buf, (jint)(f * 2));
        }
    }
}

size_t audio_batch_cb(const int16_t *d, size_t f) {
    if (g_engine.local_audio_muted.load()) {
        sendAudioToJava(d, f);
        return f;
    }

    size_t needed = f * 2;
    while (g_engine.audio_rb.available_to_write() < needed && g_engine.emu_running) {
        std::this_thread::sleep_for(std::chrono::microseconds(100));
    }
    if (!g_engine.emu_running) return 0;
    g_engine.audio_rb.write(d, needed);
    sendAudioToJava(d, f);
    return f;
}
int16_t input_state_cb(unsigned p, unsigned d, unsigned i, unsigned id) {
    return (p == 0 && d == RETRO_DEVICE_JOYPAD)
        ? ((g_engine.input_state.load() & (1 << id)) ? 1 : 0)
        : 0;
}
bool env_cb(unsigned cmd, void *data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            auto pf = *(const retro_pixel_format *)data;
            g_engine.pixel_fmt.store(pf);
            LOGI("SET_PIXEL_FORMAT: %d (0=0RGB1555, 1=XRGB8888, 2=RGB565)", pf);
            return true;
        }
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
            ((retro_log_callback *)data)->log = libretro_log;
            return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            auto v = (retro_variable *)data;
            if (v->key && strcmp(v->key, "mgba_color_correction") == 0) {
                v->value = "GBA"; return true;
            }
            return false;
        }
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            *(const char**)data = g_engine.system_dir.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            *(const char**)data = g_engine.save_dir.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            *(bool*)data = true;
            return true;
    }
    return false;
}

extern "C" {
JNIEXPORT void JNICALL Java_com_retrocast_emulator_NativeRetro_init(JNIEnv* env, jobject, jstring path) {
    LOGI("=== init() called ===");
    const char* p = env->GetStringUTFChars(path, nullptr); g_engine.core_handle = dlopen(p, RTLD_LAZY); env->ReleaseStringUTFChars(path, p);
    LOGI("init: dlopen(%s) = %p", p, g_engine.core_handle);
    if (!g_engine.core_handle) { LOGE("init: dlopen failed"); return; }
    auto setEnv = (retro_set_environment_t)dlsym(g_engine.core_handle, "retro_set_environment");
    auto setVideo = (retro_set_video_refresh_t)dlsym(g_engine.core_handle, "retro_set_video_refresh");
    auto setAudio = (retro_set_audio_sample_batch_t)dlsym(g_engine.core_handle, "retro_set_audio_sample_batch");
    auto setInput = (retro_set_input_state_t)dlsym(g_engine.core_handle, "retro_set_input_state");
    auto setPoll = (retro_set_input_poll_t)dlsym(g_engine.core_handle, "retro_set_input_poll");
    if (!setEnv || !setVideo || !setAudio || !setInput || !setPoll) {
        LOGE("init: missing required core symbols"); return;
    }
    setEnv(env_cb);
    setVideo(video_refresh_cb);
    setAudio(audio_batch_cb);
    setInput(input_state_cb);
    setPoll([](){});
    g_engine.get_mem_data = (decltype(g_engine.get_mem_data))dlsym(g_engine.core_handle, "retro_get_memory_data");
    g_engine.get_mem_size = (decltype(g_engine.get_mem_size))dlsym(g_engine.core_handle, "retro_get_memory_size");
    g_engine.state_size = (decltype(g_engine.state_size))dlsym(g_engine.core_handle, "retro_serialize_size");
    g_engine.state_serialize = (decltype(g_engine.state_serialize))dlsym(g_engine.core_handle, "retro_serialize");
    g_engine.state_unserialize = (decltype(g_engine.state_unserialize))dlsym(g_engine.core_handle, "retro_unserialize");
    LOGI("init: state_size=%p serialize=%p unserialize=%p", g_engine.state_size, g_engine.state_serialize, g_engine.state_unserialize);
    LOGI("init: done (retro_init deferred to loadGame)");
}

JNIEXPORT void JNICALL Java_com_retrocast_emulator_NativeRetro_setPaths(JNIEnv* env, jobject, jstring sys, jstring sav) {
    const char *s1 = env->GetStringUTFChars(sys, 0);
    const char *s2 = env->GetStringUTFChars(sav, 0);
    g_engine.system_dir = s1;
    g_engine.save_dir = s2;
    env->ReleaseStringUTFChars(sys, s1);
    env->ReleaseStringUTFChars(sav, s2);
}

static void registerCoreCallbacks() {
    if (!g_engine.core_handle) return;
    auto setEnv = (retro_set_environment_t)dlsym(g_engine.core_handle, "retro_set_environment");
    auto setVideo = (retro_set_video_refresh_t)dlsym(g_engine.core_handle, "retro_set_video_refresh");
    auto setAudio = (retro_set_audio_sample_batch_t)dlsym(g_engine.core_handle, "retro_set_audio_sample_batch");
    auto setInput = (retro_set_input_state_t)dlsym(g_engine.core_handle, "retro_set_input_state");
    auto setPoll = (retro_set_input_poll_t)dlsym(g_engine.core_handle, "retro_set_input_poll");
    if (setEnv) setEnv(env_cb);
    if (setVideo) setVideo(video_refresh_cb);
    if (setAudio) setAudio(audio_batch_cb);
    if (setInput) setInput(input_state_cb);
    if (setPoll) setPoll([](){});
}

static std::string getSavePath() {
    return g_engine.save_dir + "/" +
        g_engine.rom_path.substr(g_engine.rom_path.find_last_of("/\\") + 1) + ".sav";
}

JNIEXPORT void JNICALL Java_com_retrocast_emulator_NativeRetro_saveSram(JNIEnv*, jobject) {
    if (!g_engine.get_mem_data || g_engine.rom_path.empty()) { LOGE("saveSram: no mem_data or rom_path empty"); return; }
    std::lock_guard<std::recursive_mutex> lk(g_engine.core_mutex);
    void* d = g_engine.get_mem_data(0); size_t s = g_engine.get_mem_size(0);
    if (d && s > 0) {
        std::string p = getSavePath();
        mkdir(g_engine.save_dir.c_str(), 0777);
        std::ofstream f(p, std::ios::binary);
        if (f.is_open()) { f.write((const char*)d, s); f.close(); LOGI("saveSram: wrote %zu bytes to %s", s, p.c_str()); }
        else { LOGE("saveSram: failed to open %s for writing", p.c_str()); }
    } else { LOGE("saveSram: mem_data=%p size=%zu", d, s); }
}

JNIEXPORT jboolean JNICALL Java_com_retrocast_emulator_NativeRetro_saveState(JNIEnv* env, jobject, jstring path) {
    LOGI("saveState called");
    if (!g_engine.state_serialize || !g_engine.state_size) return JNI_FALSE;
    size_t sz = g_engine.state_size();
    std::vector<uint8_t> buf(sz);
    {
        std::lock_guard<std::recursive_mutex> lk(g_engine.core_mutex);
        if (!g_engine.state_serialize(buf.data(), sz)) { LOGE("saveState: serialize failed"); return JNI_FALSE; }
    }
    const char* p = env->GetStringUTFChars(path, nullptr);
    std::ofstream f(p, std::ios::binary);
    bool ok = f.is_open();
    if (ok) { f.write((const char*)buf.data(), sz); f.close(); LOGI("saveState: wrote %zu bytes to %s", sz, p); }
    else { LOGE("saveState: failed to open %s", p); }
    env->ReleaseStringUTFChars(path, p);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_retrocast_emulator_NativeRetro_loadState(JNIEnv* env, jobject, jstring path) {
    LOGI("loadState called");
    if (!g_engine.state_unserialize) return JNI_FALSE;
    const char* p = env->GetStringUTFChars(path, nullptr);
    std::ifstream f(p, std::ios::binary | std::ios::ate);
    if (!f.is_open()) { LOGE("loadState: no file at %s", p); env->ReleaseStringUTFChars(path, p); return JNI_FALSE; }
    size_t sz = f.tellg(); f.seekg(0);
    std::vector<uint8_t> buf(sz);
    f.read((char*)buf.data(), sz); f.close();
    bool ok;
    {
        std::lock_guard<std::recursive_mutex> lk(g_engine.core_mutex);
        ok = g_engine.state_unserialize(buf.data(), sz);
    }
    LOGI("loadState: read %zu bytes from %s -> %d", sz, p, ok);
    env->ReleaseStringUTFChars(path, p);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_retrocast_emulator_NativeRetro_loadGame(JNIEnv* env, jobject, jstring path) {
    LOGI("loadGame called");
    registerCoreCallbacks();
    auto retro_init_fn = (void (*)())dlsym(g_engine.core_handle, "retro_init");
    if (retro_init_fn) retro_init_fn();
    const char* p = env->GetStringUTFChars(path, nullptr); g_engine.rom_path = p;
    auto loadGameFn = (bool (*)(const struct retro_game_info*))dlsym(g_engine.core_handle, "retro_load_game");
    retro_game_info info = { p, nullptr, 0, nullptr };
    bool ok = loadGameFn ? loadGameFn(&info) : false;
    LOGI("loadGame: retro_load_game returned %d", ok);
    if (ok) {
        auto get_av = (void (*)(retro_system_av_info*))dlsym(g_engine.core_handle, "retro_get_system_av_info");
        if (get_av) {
            retro_system_av_info av;
            get_av(&av);
            g_engine.av_fps = av.timing.fps;
            g_engine.av_sample_rate = av.timing.sample_rate;
            LOGI("loadGame: fps=%.4f, sample_rate=%.0f", g_engine.av_fps, g_engine.av_sample_rate);
        }
        setupOboeStream((int32_t)g_engine.av_sample_rate);
        std::string sp = getSavePath();
        std::ifstream f(sp, std::ios::binary);
        if (f.is_open()) {
            std::lock_guard<std::recursive_mutex> lk(g_engine.core_mutex);
            void* d = g_engine.get_mem_data(0);
            size_t sz = g_engine.get_mem_size(0);
            if (d && sz > 0) {
                f.read((char*)d, sz);
                if (f.gcount() == (std::streamsize)sz) {
                    LOGI("loadGame: read save %zu bytes from %s", sz, sp.c_str());
                } else {
                    LOGE("loadGame: truncated save file, read %zu of %zu", f.gcount(), sz);
                }
            } else { LOGE("loadGame: get_mem_data returned null"); }
            f.close();
        } else { LOGI("loadGame: no save file at %s", sp.c_str()); }
    }
    env->ReleaseStringUTFChars(path, p);
    g_engine.core_run = (retro_run_t)(g_engine.core_handle ? dlsym(g_engine.core_handle, "retro_run") : nullptr);
    LOGI("loadGame: core_run = %p", g_engine.core_run); return ok;
}

JNIEXPORT void JNICALL Java_com_retrocast_emulator_NativeRetro_start(JNIEnv*, jobject) {
    g_engine.emu_running = true;
    g_engine.emu_thread = std::thread([]{
        auto frame_duration = std::chrono::nanoseconds((long long)(1.0 / g_engine.av_fps * 1e9));
        auto next_frame = std::chrono::steady_clock::now();
        while (g_engine.emu_running) {
            {
                std::lock_guard<std::recursive_mutex> l(g_engine.core_mutex);
                if (g_engine.core_run) g_engine.core_run();
            }
            next_frame += frame_duration;
            auto now = std::chrono::steady_clock::now();
            if (now < next_frame) {
                std::this_thread::sleep_for(next_frame - now);
            } else if (now - next_frame > frame_duration) {
                next_frame = now;
            }
        }
    });
}

JNIEXPORT void JNICALL Java_com_retrocast_emulator_NativeRetro_stop(JNIEnv*, jobject) {
    g_engine.emu_running = false;
    if (g_engine.emu_thread.joinable()) g_engine.emu_thread.join();
}

JNIEXPORT void JNICALL Java_com_retrocast_emulator_NativeRetro_unloadGame(JNIEnv*, jobject) {
    LOGI("=== unloadGame() called ===");
    g_engine.emu_running = false;
    if(g_engine.emu_thread.joinable()) g_engine.emu_thread.join();
    if (g_engine.core_handle) {
        auto retro_unload_game = (void (*)())dlsym(g_engine.core_handle, "retro_unload_game");
        if (retro_unload_game) retro_unload_game();
        auto retro_deinit_fn = (void (*)())dlsym(g_engine.core_handle, "retro_deinit");
        if (retro_deinit_fn) retro_deinit_fn();
    }
    g_engine.core_run = nullptr;
    g_engine.rom_path = "";
    g_engine.audio_rb.head = 0;
    g_engine.audio_rb.tail = 0;
}

JNIEXPORT void JNICALL Java_com_retrocast_emulator_NativeRetro_setLocalAudioMuted(JNIEnv*, jobject, jboolean muted) {
    g_engine.local_audio_muted.store(muted);
    if (muted && g_engine.audio_stream) {
        g_engine.audio_stream->stop();
        g_engine.audio_stream->close();
        g_engine.audio_stream.reset();
    } else if (!muted) {
        setupOboeStream((int32_t)g_engine.av_sample_rate);
    }
}

JNIEXPORT void JNICALL Java_com_retrocast_emulator_NativeRetro_setCallback(JNIEnv* env, jobject, jobject cb, jobject pixels) {
    if(g_engine.callback_obj) env->DeleteGlobalRef(g_engine.callback_obj);
    if(g_engine.argb_buf) env->DeleteGlobalRef(g_engine.argb_buf);
    if(cb) {
        g_engine.callback_obj = env->NewGlobalRef(cb);
        g_engine.argb_buf = env->NewGlobalRef(pixels);
        g_engine.argb_ptr = (uint8_t*)env->GetDirectBufferAddress(pixels);
        env->GetJavaVM(&g_engine.jvm);
        g_engine.on_frame_mid = env->GetMethodID(env->GetObjectClass(cb), "onFrameReady", "(Ljava/nio/ByteBuffer;II)V");
    } else {
        g_engine.callback_obj = nullptr;
        g_engine.argb_buf = nullptr;
        g_engine.argb_ptr = nullptr;
    }
}

JNIEXPORT void JNICALL Java_com_retrocast_emulator_NativeRetro_setAudioCallback(JNIEnv* env, jobject, jobject cb, jobject buf) {
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

JNIEXPORT void JNICALL Java_com_retrocast_emulator_NativeRetro_setInputState(JNIEnv*, jobject, jint s) {
    g_engine.input_state = (uint16_t)s;
}

JNIEXPORT jint JNICALL Java_com_retrocast_emulator_NativeRetro_getSampleRate(JNIEnv*, jobject) {
    return (jint)g_engine.av_sample_rate;
}

JNIEXPORT void JNICALL Java_com_retrocast_emulator_NativeRetro_fillI420Buffer(
    JNIEnv* env, jobject,
    jobject yBuf, jobject uBuf, jobject vBuf,
    jint width, jint height, jint yStride, jint uvStride)
{
    const uint8_t* frame_data = g_engine.current_frame_data;
    if (!frame_data) return;
    uint8_t* y_plane = (uint8_t*)env->GetDirectBufferAddress(yBuf);
    uint8_t* u_plane = (uint8_t*)env->GetDirectBufferAddress(uBuf);
    uint8_t* v_plane = (uint8_t*)env->GetDirectBufferAddress(vBuf);
    if (!y_plane || !u_plane || !v_plane) return;

    unsigned fw = g_engine.last_w;
    unsigned fh = g_engine.last_h;
    size_t pitch = g_engine.last_pitch;
    retro_pixel_format fmt = g_engine.pixel_fmt.load();

    unsigned conv_w = std::min((unsigned)width, fw);
    unsigned conv_h = std::min((unsigned)height, fh);

    for (unsigned y = 0; y < conv_h; y++) {
        for (unsigned x = 0; x < conv_w; x++) {
            int r, g, b;
            if (fmt == RETRO_PIXEL_FORMAT_XRGB8888) {
                uint32_t pix = ((const uint32_t*)frame_data)[y * (pitch / 4) + x];
                r = pix & 0xFF;
                g = (pix >> 8) & 0xFF;
                b = (pix >> 16) & 0xFF;
            } else {
                uint16_t pix = ((const uint16_t*)frame_data)[y * (pitch / 2) + x];
                r = ((pix >> 11) & 0x1F) << 3;
                g = ((pix >> 5) & 0x3F) << 2;
                b = (pix & 0x1F) << 3;
            }
            int yy = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
            y_plane[y * yStride + x] = clamp_uint8(yy);
            if ((y & 1) == 0 && (x & 1) == 0) {
                int uu = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                int vv = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                u_plane[(y / 2) * uvStride + (x / 2)] = clamp_uint8(uu);
                v_plane[(y / 2) * uvStride + (x / 2)] = clamp_uint8(vv);
            }
        }
    }
}

JNIEXPORT void JNICALL Java_com_retrocast_emulator_NativeRetro_setCasting(JNIEnv*, jobject, jboolean casting) {
    g_engine.is_casting.store(casting);
}

JNIEXPORT void JNICALL Java_com_retrocast_emulator_NativeRetro_setLocalDisplayActive(JNIEnv*, jobject, jboolean active) {
    g_engine.local_display_active.store(active);
}
}

#include <jni.h>
#include <string>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <vector>
#include <mutex>
#include <atomic>
#include <cmath>
#include <cstdarg>
#include "libretro.h"

#define LOG_TAG "RetroCastNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// --- LIBRETRO ENVIRONMENT CONSTANTS ---
#define RETRO_ENVIRONMENT_SET_PIXEL_FORMAT 1
#define RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY 9
#define RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY 31
#define RETRO_ENVIRONMENT_GET_LOG_INTERFACE 27

// --- LIBRETRO TYPES ---
typedef void (*retro_log_printf_t)(enum retro_log_level level, const char *fmt, ...);
struct retro_log_callback { retro_log_printf_t log; };

// --- CORE FUNCTION POINTERS ---
retro_run_t core_run = nullptr;
retro_unload_game_t core_unload_game_ptr = nullptr;
void *core_handle = nullptr;

// --- EXPERT STATE MANAGEMENT ---
std::recursive_mutex core_mutex; 
std::atomic<retro_pixel_format> current_pixel_format{RETRO_PIXEL_FORMAT_0RGB1555};
std::atomic<uint16_t> atomic_input_state{0}; 

ANativeWindow *native_window = nullptr;
std::mutex window_mutex;

JavaVM* g_vm = nullptr;
jobject g_nativeRetroObj = nullptr;
jmethodID g_onNativeFrameMethod = nullptr;
jintArray g_pixel_array = nullptr; 
std::vector<int32_t> g_pixel_buffer_upscaled; // Upscaled buffer for WebRTC (720x480)

// --- HIGH-PERFORMANCE AUDIO ---
std::vector<int16_t> g_audio_buffer;
std::mutex audio_mutex;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

// --- LOGGING BRIDGE ---
void cb_log(enum retro_log_level level, const char *fmt, ...) {
    char buffer[4096];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);
    switch (level) {
        case RETRO_LOG_DEBUG: LOGI("[mGBA Debug] %s", buffer); break;
        case RETRO_LOG_INFO:  LOGI("[mGBA Info] %s", buffer); break;
        case RETRO_LOG_WARN:  LOGI("[mGBA Warn] %s", buffer); break;
        case RETRO_LOG_ERROR: LOGE("[mGBA Error] %s", buffer); break;
        default: break;
    }
}

/**
 * Staff-Level Optimized Color Correction
 * Corrects for GBA LCD response.
 */
inline uint32_t process_color(uint32_t r, uint32_t g, uint32_t b) {
    // Precise 5-to-8 bit mirroring (RRRRR -> RRRRRRRR)
    uint32_t fr = (r << 3) | (r >> 2);
    uint32_t fg = (g << 3) | (g >> 2);
    uint32_t fb = (b << 3) | (b >> 2);

    // Color Correction Matrix (Integer Fixpoint 1.8.7)
    // mimics original GBA color response to prevent "oversaturation" on modern screens
    uint32_t out_r = (fr * 13 + fg * 2 + fb * 1) >> 4;
    uint32_t out_g = (fg * 14 + fb * 2) >> 4;
    uint32_t out_b = (fr * 1 + fg * 2 + fb * 13) >> 4;

    return (0xFF << 24) | (out_b << 16) | (out_g << 8) | out_r;
}

/**
 * Universal conversion loop.
 * Also performs 3x Integer Upscaling (Nearest Neighbor) for the WebRTC pipe.
 */
void convert_and_upscale_3x(uint32_t* dst, const void* src, unsigned width, unsigned height, size_t pitch) {
    retro_pixel_format fmt = current_pixel_format.load();
    unsigned dst_w = width * 3;
    
    for (unsigned y = 0; y < height; y++) {
        for (unsigned x = 0; x < width; x++) {
            uint32_t pixel;
            if (fmt == RETRO_PIXEL_FORMAT_XRGB8888) {
                uint32_t p = ((const uint32_t*)src)[y * (pitch/4) + x];
                pixel = process_color((p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF);
            } else if (fmt == RETRO_PIXEL_FORMAT_RGB565) {
                uint16_t p = ((const uint16_t*)src)[y * (pitch/2) + x];
                pixel = process_color(((p >> 11) & 0x1F) << 3, ((p >> 5) & 0x3F) << 2, (p & 0x1F) << 3);
            } else {
                uint16_t p = ((const uint16_t*)src)[y * (pitch/2) + x];
                pixel = process_color((p >> 10) & 0x1F, (p >> 5) & 0x1F, p & 0x1F);
            }

            // Fill 3x3 block in destination (Nearest Neighbor)
            for (int dy = 0; y * 3 + dy < height * 3 && dy < 3; dy++) {
                uint32_t* line = dst + ((y * 3 + dy) * dst_w);
                line[x * 3] = pixel;
                line[x * 3 + 1] = pixel;
                line[x * 3 + 2] = pixel;
            }
        }
    }
}

/**
 * Direct 1:1 conversion for local hardware scaler.
 */
void convert_and_copy_1x(uint32_t* dst, const void* src, unsigned width, unsigned height, size_t pitch, unsigned dst_stride) {
    retro_pixel_format fmt = current_pixel_format.load();
    if (fmt == RETRO_PIXEL_FORMAT_XRGB8888) {
        const uint32_t* s32 = (const uint32_t*)src;
        size_t s_stride = pitch / 4;
        for (unsigned y = 0; y < height; y++) {
            uint32_t* d = dst + (y * dst_stride);
            const uint32_t* s = s32 + (y * s_stride);
            for (unsigned x = 0; x < width; x++) {
                uint32_t p = s[x];
                d[x] = process_color((p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF);
            }
        }
    } else if (fmt == RETRO_PIXEL_FORMAT_RGB565) {
        const uint16_t* s16 = (const uint16_t*)src;
        size_t s_stride = pitch / 2;
        for (unsigned y = 0; y < height; y++) {
            uint32_t* d = dst + (y * dst_stride);
            const uint16_t* s = s16 + (y * s_stride);
            for (unsigned x = 0; x < width; x++) {
                uint16_t p = s[x];
                d[x] = process_color(((p >> 11) & 0x1F) << 3, ((p >> 5) & 0x3F) << 2, (p & 0x1F) << 3);
            }
        }
    } else {
        const uint16_t* s15 = (const uint16_t*)src;
        size_t s_stride = pitch / 2;
        for (unsigned y = 0; y < height; y++) {
            uint32_t* d = dst + (y * dst_stride);
            const uint16_t* s = s15 + (y * s_stride);
            for (unsigned x = 0; x < width; x++) {
                uint16_t p = s[x];
                d[x] = process_color((p >> 10) & 0x1F, (p >> 5) & 0x1F, p & 0x1F);
            }
        }
    }
}

// --- LIBRETRO CALLBACKS ---
void video_refresh_callback(const void *data, unsigned width, unsigned height, size_t pitch) {
    if (!data || width == 0 || height == 0 || pitch == 0) return;

    // 1. Local Rendering (1x Raw -> Hardware Scaler)
    {
        std::lock_guard<std::mutex> lock(window_mutex);
        if (native_window) {
            ANativeWindow_Buffer buffer;
            if (ANativeWindow_lock(native_window, &buffer, nullptr) >= 0) {
                unsigned copy_w = (width < (unsigned)buffer.width) ? width : (unsigned)buffer.width;
                unsigned copy_h = (height < (unsigned)buffer.height) ? height : (unsigned)buffer.height;
                convert_and_copy_1x((uint32_t*)buffer.bits, data, copy_w, copy_h, pitch, (unsigned)buffer.stride);
                ANativeWindow_unlockAndPost(native_window);
            }
        }
    }

    // 2. WebRTC High-Fidelity Pipe (3x Integer Upscale -> Encoder)
    if (g_nativeRetroObj && g_onNativeFrameMethod) {
        JNIEnv* env;
        if (g_vm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK) {
            unsigned upscale_w = width * 3;
            unsigned upscale_h = height * 3;
            size_t count = upscale_w * upscale_h;
            
            if (g_pixel_buffer_upscaled.size() < count) g_pixel_buffer_upscaled.resize(count);
            
            // Perform 3x Nearest Neighbor scaling for sharp text on TV
            convert_and_upscale_3x((uint32_t*)g_pixel_buffer_upscaled.data(), data, width, height, pitch);

            if (!g_pixel_array || env->GetArrayLength(g_pixel_array) < (jsize)count) {
                if (g_pixel_array) env->DeleteGlobalRef(g_pixel_array);
                jintArray localLocal = env->NewIntArray(count);
                g_pixel_array = (jintArray)env->NewGlobalRef(localLocal);
                env->DeleteLocalRef(localLocal);
            }
            env->SetIntArrayRegion(g_pixel_array, 0, count, (const jint*)g_pixel_buffer_upscaled.data());
            env->CallVoidMethod(g_nativeRetroObj, g_onNativeFrameMethod, g_pixel_array, (jint)upscale_w, (jint)upscale_h);
        }
    }
}

void audio_sample_callback(int16_t left, int16_t right) {
    std::lock_guard<std::mutex> lock(audio_mutex);
    g_audio_buffer.push_back(left);
    g_audio_buffer.push_back(right);
}

size_t audio_sample_batch_callback(const int16_t *data, size_t frames) {
    std::lock_guard<std::mutex> lock(audio_mutex);
    g_audio_buffer.insert(g_audio_buffer.end(), data, data + (frames * 2));
    return frames;
}

void input_poll_callback() {}

int16_t input_state_callback(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port == 0 && device == RETRO_DEVICE_JOYPAD) {
        return (atomic_input_state.load() & (1 << id)) ? 1 : 0;
    }
    return 0;
}

bool environment_callback(unsigned cmd, void *data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
            current_pixel_format.store(*(const enum retro_pixel_format *)data);
            return true;
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: {
            auto cb = (struct retro_log_callback *)data;
            cb->log = cb_log;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            *(const char **)data = ".";
            return true;
        default:
            return false;
    }
}

// --- JNI EXPORTS ---
extern "C" JNIEXPORT void JNICALL
Java_com_sharescreen_emulator_NativeRetro_setInputState(JNIEnv* env, jobject thiz, jint port, jint device, jint index, jint id, jint value) {
    if (port == 0) {
        uint16_t current = atomic_input_state.load();
        if (value) current |= (1 << id);
        else current &= ~(1 << id);
        atomic_input_state.store(current);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_sharescreen_emulator_NativeRetro_setSurface(JNIEnv* env, jobject thiz, jobject surface) {
    std::lock_guard<std::mutex> lock(window_mutex);
    if (native_window) { ANativeWindow_release(native_window); native_window = nullptr; }
    if (surface) {
        native_window = ANativeWindow_fromSurface(env, surface);
        ANativeWindow_setBuffersGeometry(native_window, 240, 160, WINDOW_FORMAT_RGBA_8888);
    }
    if (!g_nativeRetroObj) {
        g_nativeRetroObj = env->NewGlobalRef(thiz);
        jclass clazz = env->GetObjectClass(thiz);
        g_onNativeFrameMethod = env->GetMethodID(clazz, "onNativeFrame", "([III)V");
    }
}

void unload_current_core() {
    if (core_handle) {
        if (core_unload_game_ptr) core_unload_game_ptr();
        auto deinit = (retro_deinit_t)dlsym(core_handle, "retro_deinit");
        if (deinit) deinit();
        dlclose(core_handle);
        core_handle = nullptr;
        core_run = nullptr;
        core_unload_game_ptr = nullptr;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sharescreen_emulator_NativeRetro_loadCore(JNIEnv* env, jobject thiz, jstring corePath) {
    std::lock_guard<std::recursive_mutex> lock(core_mutex);
    const char *path = env->GetStringUTFChars(corePath, nullptr);
    unload_current_core();
    core_handle = dlopen(path, RTLD_LAZY);
    if (!core_handle) { env->ReleaseStringUTFChars(corePath, path); return JNI_FALSE; }
    auto set_env = (retro_set_environment_t)dlsym(core_handle, "retro_set_environment");
    auto set_video = (retro_set_video_refresh_t)dlsym(core_handle, "retro_set_video_refresh");
    auto set_audio = (retro_set_audio_sample_batch_t)dlsym(core_handle, "retro_set_audio_sample_batch");
    auto set_input_poll = (retro_set_input_poll_t)dlsym(core_handle, "retro_set_input_poll");
    auto set_input_state = (retro_set_input_state_t)dlsym(core_handle, "retro_set_input_state");
    core_run = (retro_run_t)dlsym(core_handle, "retro_run");
    core_unload_game_ptr = (retro_unload_game_t)dlsym(core_handle, "retro_unload_game");
    if (set_env) set_env(environment_callback);
    if (set_video) set_video(video_refresh_callback);
    if (set_audio) set_audio(audio_sample_batch_callback);
    if (set_input_poll) set_input_poll(input_poll_callback);
    if (set_input_state) set_input_state(input_state_callback);
    auto init = (retro_init_t)dlsym(core_handle, "retro_init");
    if (init) init();
    env->ReleaseStringUTFChars(corePath, path);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sharescreen_emulator_NativeRetro_loadGame(JNIEnv* env, jobject thiz, jstring gamePath) {
    std::lock_guard<std::recursive_mutex> lock(core_mutex);
    auto load = (retro_load_game_t)dlsym(core_handle, "retro_load_game");
    if (!load) return JNI_FALSE;
    const char *path = env->GetStringUTFChars(gamePath, nullptr);
    struct retro_game_info info = {0};
    info.path = path;
    bool success = load(&info);
    env->ReleaseStringUTFChars(gamePath, path);
    return success ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_sharescreen_emulator_NativeRetro_runFrame(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::recursive_mutex> lock(core_mutex);
    if (core_run) core_run();
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_com_sharescreen_emulator_NativeRetro_pullAudio(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(audio_mutex);
    if (g_audio_buffer.empty()) return nullptr;
    jshortArray result = env->NewShortArray(g_audio_buffer.size());
    env->SetShortArrayRegion(result, 0, g_audio_buffer.size(), (const jshort*)g_audio_buffer.data());
    g_audio_buffer.clear();
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sharescreen_emulator_NativeRetro_getCoreVersion(JNIEnv* env, jobject /* this */) {
    return env->NewStringUTF("RetroCast Engine v2.1 (3x Integer Upscale)");
}

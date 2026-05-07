#include <jni.h>
#include <string>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <vector>
#include <mutex>
#include <atomic>
#include "libretro.h"

#define LOG_TAG "EmulatorCore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

enum retro_pixel_format {
   RETRO_PIXEL_FORMAT_0RGB1555 = 0,
   RETRO_PIXEL_FORMAT_XRGB8888 = 1,
   RETRO_PIXEL_FORMAT_RGB565   = 2
};

// --- CORE FUNCTION POINTERS ---
retro_run_t core_run = nullptr;
void *core_handle = nullptr;

// --- STATE MANAGEMENT ---
std::recursive_mutex core_mutex; 
std::atomic<retro_pixel_format> current_pixel_format{RETRO_PIXEL_FORMAT_XRGB8888};
ANativeWindow *native_window = nullptr;
std::mutex window_mutex;

// --- JNI HELPERS ---
JavaVM* g_vm = nullptr;
jobject g_nativeRetroObj = nullptr;
jmethodID g_onNativeFrameMethod = nullptr;
jintArray g_pixel_array = nullptr; 
std::vector<int32_t> g_pixel_buffer; 

// --- AUDIO BUFFERING ---
std::vector<int16_t> g_audio_buffer;
std::mutex audio_mutex;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

/**
 * Universal Pixel Converter
 * Android (Little Endian) int 0xAABBGGRR means bytes are R, G, B, A.
 */
void convert_and_copy(uint32_t* dst, const void* src, unsigned width, unsigned height, size_t pitch, unsigned dst_stride) {
    retro_pixel_format fmt = current_pixel_format.load();
    
    if (fmt == RETRO_PIXEL_FORMAT_XRGB8888) {
        const uint32_t* s32 = (const uint32_t*)src;
        size_t s_stride = pitch / 4;
        for (unsigned y = 0; y < height; y++) {
            uint32_t* d = dst + (y * dst_stride);
            const uint32_t* s = s32 + (y * s_stride);
            for (unsigned x = 0; x < width; x++) {
                uint32_t p = s[x];
                // mGBA XRGB8888 is 0xRRGGBB.
                // We want 0xFFBBGGRR for Android RGBA_8888.
                uint32_t r = (p >> 16) & 0xFF;
                uint32_t g = (p >> 8) & 0xFF;
                uint32_t b = p & 0xFF;
                d[x] = (0xFF << 24) | (b << 16) | (g << 8) | r; 
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
                uint32_t r = ((p >> 11) & 0x1F) << 3;
                uint32_t g = ((p >> 5) & 0x3F) << 2;
                uint32_t b = (p & 0x1F) << 3;
                d[x] = (0xFF << 24) | (b << 16) | (g << 8) | r;
            }
        }
    }
}

void video_refresh_callback(const void *data, unsigned width, unsigned height, size_t pitch) {
    if (!data || width == 0 || height == 0 || pitch == 0) return;

    {
        std::lock_guard<std::mutex> lock(window_mutex);
        if (native_window) {
            ANativeWindow_Buffer buffer;
            if (ANativeWindow_lock(native_window, &buffer, nullptr) >= 0) {
                unsigned copy_w = (width < (unsigned)buffer.width) ? width : (unsigned)buffer.width;
                unsigned copy_h = (height < (unsigned)buffer.height) ? height : (unsigned)buffer.height;
                convert_and_copy((uint32_t*)buffer.bits, data, copy_w, copy_h, pitch, (unsigned)buffer.stride);
                ANativeWindow_unlockAndPost(native_window);
            }
        }
    }

    if (g_nativeRetroObj && g_onNativeFrameMethod) {
        JNIEnv* env;
        if (g_vm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK) {
            size_t count = width * height;
            if (g_pixel_buffer.size() < count) g_pixel_buffer.resize(count);
            convert_and_copy((uint32_t*)g_pixel_buffer.data(), data, width, height, pitch, width);

            if (!g_pixel_array || env->GetArrayLength(g_pixel_array) < (jsize)count) {
                if (g_pixel_array) env->DeleteGlobalRef(g_pixel_array);
                jintArray local = env->NewIntArray(count);
                g_pixel_array = (jintArray)env->NewGlobalRef(local);
                env->DeleteLocalRef(local);
            }
            env->SetIntArrayRegion(g_pixel_array, 0, count, (const jint*)g_pixel_buffer.data());
            env->CallVoidMethod(g_nativeRetroObj, g_onNativeFrameMethod, g_pixel_array, (jint)width, (jint)height);
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

uint16_t current_input_state = 0;
int16_t input_state_callback(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port == 0 && device == RETRO_DEVICE_JOYPAD) return (current_input_state & (1 << id)) ? 1 : 0;
    return 0;
}

#define RETRO_ENVIRONMENT_SET_PIXEL_FORMAT 1
#define RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY 9
#define RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY 31

bool environment_callback(unsigned cmd, void *data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
            current_pixel_format.store(*(const enum retro_pixel_format *)data);
            LOGI("Core selected pixel format: %d", (int)current_pixel_format.load());
            return true;
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            *(const char **)data = ".";
            return true;
        default:
            return false;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_sharescreen_emulator_NativeRetro_setInputState(JNIEnv* env, jobject thiz, jint port, jint device, jint index, jint id, jint value) {
    if (port == 0) {
        if (value) current_input_state |= (1 << id);
        else current_input_state &= ~(1 << id);
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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sharescreen_emulator_NativeRetro_loadCore(JNIEnv* env, jobject thiz, jstring corePath) {
    std::lock_guard<std::recursive_mutex> lock(core_mutex);
    const char *path = env->GetStringUTFChars(corePath, nullptr);
    if (core_handle) dlclose(core_handle);
    core_handle = dlopen(path, RTLD_LAZY);
    if (!core_handle) { env->ReleaseStringUTFChars(corePath, path); return JNI_FALSE; }

    auto set_env = (retro_set_environment_t)dlsym(core_handle, "retro_set_environment");
    auto set_video = (retro_set_video_refresh_t)dlsym(core_handle, "retro_set_video_refresh");
    auto set_audio = (retro_set_audio_sample_batch_t)dlsym(core_handle, "retro_set_audio_sample_batch");
    auto set_input_poll = (retro_set_input_poll_t)dlsym(core_handle, "retro_set_input_poll");
    auto set_input_state = (retro_set_input_state_t)dlsym(core_handle, "retro_set_input_state");
    core_run = (retro_run_t)dlsym(core_handle, "retro_run");

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
    return env->NewStringUTF("Libretro Bridge 1.4 (Karpathy Correct Colors)");
}

#ifndef LIBRETRO_H__
#define LIBRETRO_H__

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#define RETRO_API_VERSION 1

#define RETRO_DEVICE_JOYPAD 1
#define RETRO_DEVICE_ID_JOYPAD_B 0
#define RETRO_DEVICE_ID_JOYPAD_Y 1
#define RETRO_DEVICE_ID_JOYPAD_SELECT 2
#define RETRO_DEVICE_ID_JOYPAD_START 3
#define RETRO_DEVICE_ID_JOYPAD_UP 4
#define RETRO_DEVICE_ID_JOYPAD_DOWN 5
#define RETRO_DEVICE_ID_JOYPAD_LEFT 6
#define RETRO_DEVICE_ID_JOYPAD_RIGHT 7
#define RETRO_DEVICE_ID_JOYPAD_A 8
#define RETRO_DEVICE_ID_JOYPAD_X 9
#define RETRO_DEVICE_ID_JOYPAD_L 10
#define RETRO_DEVICE_ID_JOYPAD_R 11

enum retro_pixel_format {
   RETRO_PIXEL_FORMAT_0RGB1555 = 0,
   RETRO_PIXEL_FORMAT_XRGB8888 = 1,
   RETRO_PIXEL_FORMAT_RGB565   = 2,
   RETRO_PIXEL_FORMAT_UNKNOWN  = 0x7fffffff
};

enum retro_log_level {
   RETRO_LOG_DEBUG = 0,
   RETRO_LOG_INFO,
   RETRO_LOG_WARN,
   RETRO_LOG_ERROR,
   RETRO_LOG_DUMMY = 0x7fffffff
};

struct retro_game_geometry {
   unsigned base_width;
   unsigned base_height;
   unsigned max_width;
   unsigned max_height;
   float aspect_ratio;
};

struct retro_system_timing {
   double fps;
   double sample_rate;
};

struct retro_system_av_info {
   struct retro_game_geometry geometry;
   struct retro_system_timing timing;
};

struct retro_system_info {
   const char *library_name;
   const char *library_version;
   const char *valid_extensions;
   bool need_fullpath;
   bool block_extract;
};

struct retro_game_info {
   const char *path;
   const void *data;
   size_t size;
   const char *meta;
};

typedef void (*retro_video_refresh_t)(const void *data, unsigned width, unsigned height, size_t pitch);
typedef void (*retro_audio_sample_t)(int16_t left, int16_t right);
typedef size_t (*retro_audio_sample_batch_t)(const int16_t *data, size_t frames);
typedef void (*retro_input_poll_t)(void);
typedef int16_t (*retro_input_state_t)(unsigned port, unsigned device, unsigned index, unsigned id);
typedef bool (*retro_environment_t)(unsigned cmd, void *data);

typedef void (*retro_set_environment_t)(retro_environment_t);
typedef void (*retro_set_video_refresh_t)(retro_video_refresh_t);
typedef void (*retro_set_audio_sample_t)(retro_audio_sample_t);
typedef void (*retro_set_audio_sample_batch_t)(retro_audio_sample_batch_t);
typedef void (*retro_set_input_poll_t)(retro_input_poll_t);
typedef void (*retro_set_input_state_t)(retro_input_state_t);

typedef void (*retro_init_t)(void);
typedef void (*retro_deinit_t)(void);
typedef unsigned (*retro_api_version_t)(void);
typedef void (*retro_get_system_info_t)(struct retro_system_info *info);
typedef void (*retro_get_system_av_info_t)(struct retro_system_av_info *info);
typedef void (*retro_set_controller_port_device_t)(unsigned port, unsigned device);
typedef void (*retro_reset_t)(void);
typedef bool (*retro_load_game_t)(const struct retro_game_info *game);
typedef void (*retro_run_t)(void);
typedef void (*retro_unload_game_t)(void);

#ifdef __cplusplus
}
#endif

#endif

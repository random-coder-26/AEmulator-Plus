/*
 * AEmulator Plus: the audio HAL structs audio_hal_v2.c needs, standalone (no Android header, no libc header).
 * GPL-3.0; see LICENSE. Rebuilt 2026-11-14: the file that audio_hal_v2.c was written against was not in the source tree.
 *
 * Layouts are those of AOSP android11-release (hardware/libhardware/include/hardware/{hardware,audio}.h,
 * system/media/audio/include/system/audio{,-base}.h), LP64. tests/abi-check.sh compiles this header AND the real AOSP headers
 * for the same target and compares sizeof / offsetof of every struct and the value of every constant below, so a drift
 * in either one stops the build. The static asserts here pin the offsets the stock android.hardware.audio@2.0-impl.so
 * (PrimaryDevice, StreamOut, StreamIn) reads.
 */
#ifndef AEMU_AUDIO_ABI_H
#define AEMU_AUDIO_ABI_H

#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>

#if !defined(__LP64__)
#error "aemu_audio_abi.h is the LP64 (x86-64 / arm64) layout; the 32-bit HAL is audio_hal.c"
#endif

#ifndef AEMU_ABI_HOST_LIBC   /* host test code that includes libc headers defines this and gets ssize_t / timespec from them */
typedef long ssize_t;

/* a struct timespec as bionic LP64 has it; audio_hal_v2.c casts it to its own aemu_timespec */
struct timespec { long tv_sec; long tv_nsec; };
#endif

/* ---- hardware.h ------------------------------------------------------------------------------------------- */
#define MAKE_TAG_CONSTANT(A,B,C,D) (((A) << 24) | ((B) << 16) | ((C) << 8) | (D))
#define HARDWARE_MODULE_TAG MAKE_TAG_CONSTANT('H', 'W', 'M', 'T')
#define HARDWARE_DEVICE_TAG MAKE_TAG_CONSTANT('H', 'W', 'D', 'T')
#define HARDWARE_MAKE_API_VERSION(maj,min) ((((maj) & 0xff) << 8) | ((min) & 0xff))
#define HARDWARE_MODULE_API_VERSION(maj,min) HARDWARE_MAKE_API_VERSION(maj,min)
#define HARDWARE_DEVICE_API_VERSION(maj,min) HARDWARE_MAKE_API_VERSION(maj,min)
#define HAL_MODULE_INFO_SYM HMI

struct hw_module_t;
struct hw_device_t;

typedef struct hw_module_methods_t {
    int (*open)(const struct hw_module_t *module, const char *id, struct hw_device_t **device);
} hw_module_methods_t;

typedef struct hw_module_t {
    uint32_t tag;
    uint16_t module_api_version;
    uint16_t hal_api_version;
    const char *id;
    const char *name;
    const char *author;
    hw_module_methods_t *methods;
    void *dso;
    uint64_t reserved[32 - 7];
} hw_module_t;

typedef struct hw_device_t {
    uint32_t tag;
    uint32_t version;
    struct hw_module_t *module;
    uint64_t reserved[12];
    int (*close)(struct hw_device_t *device);
} hw_device_t;

/* ---- audio.h / audio-base.h ------------------------------------------------------------------------------- */
#define AUDIO_HARDWARE_MODULE_ID "audio"
#define AUDIO_HARDWARE_INTERFACE "audio_hw_if"
#define AUDIO_MODULE_API_VERSION_0_1 HARDWARE_MODULE_API_VERSION(0, 1)
#define AUDIO_DEVICE_API_VERSION_2_0 HARDWARE_DEVICE_API_VERSION(2, 0)

typedef uint32_t audio_devices_t;
typedef uint32_t audio_channel_mask_t;
typedef uint32_t audio_format_t;
typedef uint32_t audio_stream_type_t;
typedef uint32_t audio_usage_t;
typedef uint32_t audio_encapsulation_mode_t;
typedef uint32_t audio_output_flags_t;
typedef uint32_t audio_input_flags_t;
typedef uint32_t audio_source_t;
typedef int32_t  audio_io_handle_t;
typedef int32_t  audio_mode_t;
typedef int32_t  audio_patch_handle_t;
typedef int32_t  audio_port_handle_t;
typedef void *   effect_handle_t;                 /* struct effect_interface_s ** in AOSP: a pointer either way */

enum {
    AUDIO_FORMAT_DEFAULT          = 0,
    AUDIO_FORMAT_PCM_16_BIT       = 0x1u,
    AUDIO_CHANNEL_NONE            = 0x0u,
    AUDIO_CHANNEL_OUT_STEREO      = 0x3u,
    AUDIO_CHANNEL_IN_MONO         = 0x10u,
    AUDIO_CHANNEL_IN_STEREO       = 0xCu,
    AUDIO_DEVICE_OUT_SPEAKER      = 0x2u,
    AUDIO_DEVICE_IN_BUILTIN_MIC   = 0x80000004u,
};

typedef struct {
    uint16_t version;
    uint16_t size;
    uint32_t sample_rate;
    audio_channel_mask_t channel_mask;
    audio_format_t format;
    audio_stream_type_t stream_type;
    uint32_t bit_rate;
    int64_t duration_us;
    bool has_video;
    bool is_streaming;
    uint32_t bit_width;
    uint32_t offload_buffer_size;
    audio_usage_t usage;
    audio_encapsulation_mode_t encapsulation_mode;
    int32_t content_id;
    int32_t sync_id;
} __attribute__((aligned(8))) audio_offload_info_t;

struct __attribute__((aligned(8))) audio_config {
    uint32_t sample_rate;
    audio_channel_mask_t channel_mask;
    audio_format_t format;
    audio_offload_info_t offload_info;
    uint32_t frame_count;
};

/* the slots whose argument types this HAL never touches are opaque pointers here */
struct audio_stream {
    uint32_t (*get_sample_rate)(const struct audio_stream *stream);
    int (*set_sample_rate)(struct audio_stream *stream, uint32_t rate);
    size_t (*get_buffer_size)(const struct audio_stream *stream);
    audio_channel_mask_t (*get_channels)(const struct audio_stream *stream);
    audio_format_t (*get_format)(const struct audio_stream *stream);
    int (*set_format)(struct audio_stream *stream, audio_format_t format);
    int (*standby)(struct audio_stream *stream);
    int (*dump)(const struct audio_stream *stream, int fd);
    audio_devices_t (*get_device)(const struct audio_stream *stream);
    int (*set_device)(struct audio_stream *stream, audio_devices_t device);
    int (*set_parameters)(struct audio_stream *stream, const char *kv_pairs);
    char *(*get_parameters)(const struct audio_stream *stream, const char *keys);
    int (*add_audio_effect)(const struct audio_stream *stream, effect_handle_t effect);
    int (*remove_audio_effect)(const struct audio_stream *stream, effect_handle_t effect);
};

struct audio_stream_out {
    struct audio_stream common;
    uint32_t (*get_latency)(const struct audio_stream_out *stream);
    int (*set_volume)(struct audio_stream_out *stream, float left, float right);
    ssize_t (*write)(struct audio_stream_out *stream, const void *buffer, size_t bytes);
    int (*get_render_position)(const struct audio_stream_out *stream, uint32_t *dsp_frames);
    int (*get_next_write_timestamp)(const struct audio_stream_out *stream, int64_t *timestamp);
    int (*set_callback)(struct audio_stream_out *stream, void *callback, void *cookie);
    int (*pause)(struct audio_stream_out *stream);
    int (*resume)(struct audio_stream_out *stream);
    int (*drain)(struct audio_stream_out *stream, int type);
    int (*flush)(struct audio_stream_out *stream);
    int (*get_presentation_position)(const struct audio_stream_out *stream, uint64_t *frames, struct timespec *timestamp);
    int (*start)(const struct audio_stream_out *stream);
    int (*stop)(const struct audio_stream_out *stream);
    int (*create_mmap_buffer)(const struct audio_stream_out *stream, int32_t min_size_frames, void *info);
    int (*get_mmap_position)(const struct audio_stream_out *stream, void *position);
    void (*update_source_metadata)(struct audio_stream_out *stream, const void *source_metadata);
    int (*set_event_callback)(struct audio_stream_out *stream, void *callback, void *cookie);
};

struct audio_stream_in {
    struct audio_stream common;
    int (*set_gain)(struct audio_stream_in *stream, float gain);
    ssize_t (*read)(struct audio_stream_in *stream, void *buffer, size_t bytes);
    uint32_t (*get_input_frames_lost)(struct audio_stream_in *stream);
    int (*get_capture_position)(const struct audio_stream_in *stream, int64_t *frames, int64_t *time);
    int (*start)(const struct audio_stream_in *stream);
    int (*stop)(const struct audio_stream_in *stream);
    int (*create_mmap_buffer)(const struct audio_stream_in *stream, int32_t min_size_frames, void *info);
    int (*get_mmap_position)(const struct audio_stream_in *stream, void *position);
    int (*get_active_microphones)(const struct audio_stream_in *stream, void *mic_array, size_t *mic_count);
    int (*set_microphone_direction)(const struct audio_stream_in *stream, int direction);
    int (*set_microphone_field_dimension)(const struct audio_stream_in *stream, float zoom);
    void (*update_sink_metadata)(struct audio_stream_in *stream, const void *sink_metadata);
};

struct audio_hw_device {
    struct hw_device_t common;
    uint32_t (*get_supported_devices)(const struct audio_hw_device *dev);
    int (*init_check)(const struct audio_hw_device *dev);
    int (*set_voice_volume)(struct audio_hw_device *dev, float volume);
    int (*set_master_volume)(struct audio_hw_device *dev, float volume);
    int (*get_master_volume)(struct audio_hw_device *dev, float *volume);
    int (*set_mode)(struct audio_hw_device *dev, audio_mode_t mode);
    int (*set_mic_mute)(struct audio_hw_device *dev, bool state);
    int (*get_mic_mute)(const struct audio_hw_device *dev, bool *state);
    int (*set_parameters)(struct audio_hw_device *dev, const char *kv_pairs);
    char *(*get_parameters)(const struct audio_hw_device *dev, const char *keys);
    size_t (*get_input_buffer_size)(const struct audio_hw_device *dev, const struct audio_config *config);
    int (*open_output_stream)(struct audio_hw_device *dev, audio_io_handle_t handle, audio_devices_t devices,
                              audio_output_flags_t flags, struct audio_config *config,
                              struct audio_stream_out **stream_out, const char *address);
    void (*close_output_stream)(struct audio_hw_device *dev, struct audio_stream_out *stream_out);
    int (*open_input_stream)(struct audio_hw_device *dev, audio_io_handle_t handle, audio_devices_t devices,
                             struct audio_config *config, struct audio_stream_in **stream_in,
                             audio_input_flags_t flags, const char *address, audio_source_t source);
    void (*close_input_stream)(struct audio_hw_device *dev, struct audio_stream_in *stream_in);
    int (*get_microphones)(const struct audio_hw_device *dev, void *mic_array, size_t *mic_count);
    int (*dump)(const struct audio_hw_device *dev, int fd);
    int (*set_master_mute)(struct audio_hw_device *dev, bool mute);
    int (*get_master_mute)(struct audio_hw_device *dev, bool *mute);
    int (*create_audio_patch)(struct audio_hw_device *dev, unsigned int num_sources, const void *sources,
                              unsigned int num_sinks, const void *sinks, audio_patch_handle_t *handle);
    int (*release_audio_patch)(struct audio_hw_device *dev, audio_patch_handle_t handle);
    int (*get_audio_port)(struct audio_hw_device *dev, void *port);
    int (*set_audio_port_config)(struct audio_hw_device *dev, const void *config);
    int (*add_device_effect)(struct audio_hw_device *dev, audio_port_handle_t device, effect_handle_t effect);
    int (*remove_device_effect)(struct audio_hw_device *dev, audio_port_handle_t device, effect_handle_t effect);
};

/* Room the public part of a stream struct may grow to in a newer header; the private state starts behind it. */
#define STREAM_PUBLIC_BYTES 0x100

_Static_assert(sizeof(struct hw_module_t) == 248, "hw_module_t");
_Static_assert(sizeof(struct hw_device_t) == 120, "hw_device_t");
_Static_assert(offsetof(struct hw_device_t, close) == 112, "hw_device_t.close");
_Static_assert(sizeof(struct audio_stream) == 14 * 8, "audio_stream");
_Static_assert(offsetof(struct audio_stream_out, write) == 14 * 8 + 2 * 8, "audio_stream_out.write");
_Static_assert(sizeof(struct audio_stream_out) < STREAM_PUBLIC_BYTES, "audio_stream_out outgrew STREAM_PUBLIC_BYTES");
_Static_assert(offsetof(struct audio_stream_in, read) == 14 * 8 + 8, "audio_stream_in.read");
_Static_assert(sizeof(struct audio_stream_in) < STREAM_PUBLIC_BYTES, "audio_stream_in outgrew STREAM_PUBLIC_BYTES");
_Static_assert(offsetof(struct audio_hw_device, get_master_volume) == 120 + 4 * 8, "get_master_volume slot");
_Static_assert(offsetof(struct audio_hw_device, open_output_stream) == 120 + 11 * 8, "open_output_stream slot");
_Static_assert(sizeof(audio_offload_info_t) == 64, "audio_offload_info_t");
_Static_assert(sizeof(struct audio_config) == 88, "audio_config");

#endif /* AEMU_AUDIO_ABI_H */

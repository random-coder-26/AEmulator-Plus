/* Compiled twice by abi-check.sh: -DREAL uses AOSP's headers, otherwise aemu_audio_abi.h. Prints one "name value" line per fact. */
#include <stdio.h>
#ifdef REAL
#include <hardware/audio.h>
#else
#include "aemu_audio_abi.h"
#endif
#define S(t) printf("sizeof(%s) %zu\n", #t, sizeof(t))
#define O(t, m) printf("offsetof(%s,%s) %zu\n", #t, #m, offsetof(t, m))
#define V(c) printf("%s %lld\n", #c, (long long)(c))
int main(void) {
    S(struct hw_module_t); S(struct hw_device_t); S(struct audio_stream); S(struct audio_stream_out); S(struct audio_stream_in);
    S(struct audio_hw_device); S(struct audio_config); S(audio_offload_info_t);
    O(struct hw_module_t, id); O(struct hw_module_t, methods); O(struct hw_module_t, dso); O(struct hw_device_t, module); O(struct hw_device_t, close);
    O(struct audio_config, channel_mask); O(struct audio_config, format); O(struct audio_config, offload_info); O(struct audio_config, frame_count);
    O(struct audio_stream, add_audio_effect); O(struct audio_stream, remove_audio_effect);
    O(struct audio_stream_out, get_latency); O(struct audio_stream_out, write); O(struct audio_stream_out, get_render_position);
    O(struct audio_stream_out, get_next_write_timestamp); O(struct audio_stream_out, set_callback); O(struct audio_stream_out, get_presentation_position);
    O(struct audio_stream_out, update_source_metadata); O(struct audio_stream_out, set_event_callback);
    O(struct audio_stream_in, set_gain); O(struct audio_stream_in, read); O(struct audio_stream_in, get_input_frames_lost); O(struct audio_stream_in, update_sink_metadata);
    O(struct audio_hw_device, get_supported_devices); O(struct audio_hw_device, init_check); O(struct audio_hw_device, set_master_volume);
    O(struct audio_hw_device, get_master_volume); O(struct audio_hw_device, set_mode); O(struct audio_hw_device, get_mic_mute);
    O(struct audio_hw_device, get_input_buffer_size); O(struct audio_hw_device, open_output_stream); O(struct audio_hw_device, close_output_stream);
    O(struct audio_hw_device, open_input_stream); O(struct audio_hw_device, close_input_stream); O(struct audio_hw_device, get_microphones);
    O(struct audio_hw_device, dump); O(struct audio_hw_device, set_master_mute); O(struct audio_hw_device, get_master_mute);
    O(struct audio_hw_device, create_audio_patch); O(struct audio_hw_device, set_audio_port_config); O(struct audio_hw_device, remove_device_effect);
    V(HARDWARE_MODULE_TAG); V(HARDWARE_DEVICE_TAG); V(AUDIO_MODULE_API_VERSION_0_1); V(AUDIO_DEVICE_API_VERSION_2_0);
    V(AUDIO_FORMAT_DEFAULT); V(AUDIO_FORMAT_PCM_16_BIT); V(AUDIO_CHANNEL_NONE); V(AUDIO_CHANNEL_OUT_STEREO); V(AUDIO_CHANNEL_IN_MONO);
    V(AUDIO_CHANNEL_IN_STEREO); V(AUDIO_DEVICE_OUT_SPEAKER); V(AUDIO_DEVICE_IN_BUILTIN_MIC);
    printf("%s %s\n", AUDIO_HARDWARE_MODULE_ID, AUDIO_HARDWARE_INTERFACE);
    return 0;
}

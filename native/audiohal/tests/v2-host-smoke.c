/* Host smoke test for audio_hal_v2.c: dlopen()s the glibc build, plays PCM through /tmp FIFOs, checks pacing, volume slots and the mic path. */
#define AEMU_ABI_HOST_LIBC 1
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/types.h>
#include "aemu_audio_abi.h"
#define CHECK(c) do { if (!(c)) { printf("FAIL line %d: %s\n", __LINE__, #c); return 1; } } while (0)
static double now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec / 1e9; }
int main(int argc, char **argv) {
    void *h = dlopen(argv[1], RTLD_NOW); CHECK(h);
    struct hw_module_t *m = dlsym(h, "HMI"); CHECK(m);
    CHECK(m->tag == HARDWARE_MODULE_TAG && !strcmp(m->id, "audio"));
    struct hw_device_t *hd; struct audio_hw_device *d;
    CHECK(m->methods->open(m, "bogus", &hd) != 0);
    CHECK(m->methods->open(m, AUDIO_HARDWARE_INTERFACE, &hd) == 0); d = (struct audio_hw_device *)hd;
    CHECK(d->common.version == AUDIO_DEVICE_API_VERSION_2_0 && d->init_check(d) == 0);
    float v = 0; bool b = true;
    CHECK(d->get_master_volume(d, &v) != 0 && d->set_master_volume(d, 1.f) != 0);   /* -ENOSYS, no crash (the old stand-in died here) */
    CHECK(d->set_mic_mute(d, true) == 0 && d->get_mic_mute(d, &b) == 0 && b);
    CHECK(d->create_audio_patch == 0 && d->get_microphones == 0);
    char *p = d->get_parameters(d, "x"); CHECK(p && !*p); free(p);
    /* output: 3 periods must take ~2 periods of wall time (one period of lead), and arrive intact in the FIFO */
    struct audio_config cfg = { .sample_rate = 44100, .channel_mask = 1, .format = 2 };
    struct audio_stream_out *o = 0;
    CHECK(d->open_output_stream(d, 1, AUDIO_DEVICE_OUT_SPEAKER, 0, &cfg, &o, "") == 0 && o);
    CHECK(cfg.sample_rate == 48000 && cfg.channel_mask == AUDIO_CHANNEL_OUT_STEREO && cfg.format == AUDIO_FORMAT_PCM_16_BIT);
    size_t bs = o->common.get_buffer_size(&o->common); CHECK(bs == 2048 * 4);
    CHECK(o->common.get_sample_rate(&o->common) == 48000 && o->get_latency(o) == 100);
    CHECK(o->set_callback == 0 && o->pause == 0 && o->update_source_metadata == 0);
    char *buf = malloc(bs); for (size_t i = 0; i < bs; i++) buf[i] = (char)i;
    int rfd = open(getenv("AEMU_PCM"), O_RDONLY | O_NONBLOCK); CHECK(rfd >= 0);
    double t0 = now();
    for (int i = 0; i < 3; i++) CHECK(o->write(o, buf, bs) == (ssize_t)bs);
    double dt = now() - t0, per = 2048 / 48000.0;
    printf("3 writes took %.1f ms (period %.1f ms)\n", dt * 1e3, per * 1e3);
    CHECK(dt > per * 1.5 && dt < per * 2.6);
    char *got = malloc(bs * 3); size_t n = 0; long r;
    while (n < bs * 3 && (r = read(rfd, got + n, bs * 3 - n)) > 0) n += r;
    CHECK(n == bs * 3 && !memcmp(got, buf, bs));
    uint32_t dsp = 0; CHECK(o->get_render_position(o, &dsp) == 0 && dsp == 3 * 2048);
    uint64_t fr = 0; struct timespec ts; CHECK(o->get_presentation_position(o, &fr, &ts) == 0 && fr <= 3 * 2048 && fr >= 2048);
    CHECK(o->common.standby(&o->common) == 0);
    d->close_output_stream(d, o);
    /* input: no host behind the FIFOs -> silence at the right pace, control channel told start/stop */
    struct audio_config ic = { .sample_rate = 16000, .channel_mask = AUDIO_CHANNEL_IN_MONO, .format = AUDIO_FORMAT_PCM_16_BIT };
    size_t ibs = d->get_input_buffer_size(d, &ic); CHECK(ibs == 320 * 2);
    struct audio_stream_in *in = 0;
    CHECK(d->open_input_stream(d, 2, AUDIO_DEVICE_IN_BUILTIN_MIC, &ic, &in, 0, "", 0) == 0 && in);
    char *ib = malloc(ibs); memset(ib, 0x55, ibs);
    int cfd = open(getenv("AEMU_MIC_CTL"), O_RDONLY | O_NONBLOCK); CHECK(cfd >= 0);
    t0 = now(); for (int i = 0; i < 5; i++) CHECK(in->read(in, ib, ibs) == (ssize_t)ibs);
    dt = now() - t0; printf("5 mic reads took %.1f ms (want ~100)\n", dt * 1e3);
    CHECK(dt > 0.08 && dt < 0.14); for (size_t i = 0; i < ibs; i++) CHECK(ib[i] == 0);
    char cmd[64] = {0}; CHECK(read(cfd, cmd, sizeof cmd - 1) > 0); CHECK(!strncmp(cmd, "start 16000 1\n", 14));
    CHECK(in->common.standby(&in->common) == 0);
    memset(cmd, 0, sizeof cmd); CHECK(read(cfd, cmd, sizeof cmd - 1) > 0 && !strncmp(cmd, "stop\n", 5));
    d->close_input_stream(d, in);
    struct audio_config bad = { .sample_rate = 96000, .channel_mask = AUDIO_CHANNEL_IN_MONO, .format = AUDIO_FORMAT_PCM_16_BIT };
    CHECK(d->get_input_buffer_size(d, &bad) == 0);
    CHECK(hd->close(hd) == 0);
    puts("v2 host smoke: OK"); return 0;
}

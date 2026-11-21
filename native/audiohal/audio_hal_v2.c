/*
 * AEmulator Plus: primary audio HAL for Android 11 guests ("audio.primary.default.so", LP64).
 * GPL-3.0; see LICENSE and NOTICE.md. Written 2026-11-14 to replace the x86-64 stand-in that crashed
 * android.hardware.audio@2.0-service (SIGSEGV in PrimaryDevice::getMasterVolume, see aemu_audio_abi.h).
 *
 * It is the legacy audio_hw_device that the stock android.hardware.audio@2.0-impl.so wraps:
 *
 *   output  PCM16 / stereo / 48 kHz  ->  /dev/eac            (FIFO; app/.../core/AudioOut.kt plays it with AudioTrack)
 *   input   PCM16 / mono or stereo   <-  /dev/aemu_mic       (FIFO; app/.../core/AudioIn.kt records with AudioRecord)
 *                                        /dev/aemu_mic_ctl   ("start <rate> <channels>\n" / "stop\n")
 *
 * Rules this file keeps (every one of them was broken or missing in the stand-in):
 *   - the structs come from aemu_audio_abi.h: LP64 offsets, pinned by static asserts, checked against the impl;
 *   - private state sits AFTER the public part of a stream struct, never inside it;
 *   - every optional slot a newer header added is NULL (= "not supported" for every Android release);
 *   - write()/read() are paced against CLOCK_MONOTONIC with an absolute deadline (no drift), standby restarts the clock;
 *   - nothing blocks for ever: a full FIFO drops after 20 ms, a mic that never delivers is padded with silence;
 *   - no libc beyond a handful of functions (open/read/write/close/ioctl/calloc/free/clock_*), no stdio, no malloc
 *     of long-lived state outside open/close, no static constructors, no TLS.
 */
#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>

#include "aemu_audio_abi.h"

/* ---- libc, declared by hand so the file builds without any Android header (see build.sh) ------------------ */
struct aemu_timespec { long tv_sec; long tv_nsec; };
int open(const char *path, int flags, ...);
int close(int fd);
long read(int fd, void *buf, unsigned long n);
long write(int fd, const void *buf, unsigned long n);
int ioctl(int fd, unsigned long req, ...);
void *calloc(unsigned long n, unsigned long size);
void free(void *p);
int clock_gettime(int clk, struct aemu_timespec *ts);
int clock_nanosleep(int clk, int flags, const struct aemu_timespec *req, struct aemu_timespec *rem);
void *memcpy(void *dst, const void *src, unsigned long n);
void *memset(void *dst, int c, unsigned long n);
#ifdef AEMU_BIONIC
int *__errno(void);
#define AEMU_ERRNO (*__errno())
#else
int *__errno_location(void);
#define AEMU_ERRNO (*__errno_location())
#endif

#define EXPORT __attribute__((visibility("default")))

enum { K_EINTR = 4, K_EAGAIN = 11, K_EINVAL = 22, K_ENOSYS = 38 };
enum { K_O_RDONLY = 0, K_O_RDWR = 2, K_O_NONBLOCK = 0x800, K_O_CLOEXEC = 0x80000 };
#define K_FIONREAD 0x541Bul
#define K_CLOCK_MONOTONIC 1
#define K_TIMER_ABSTIME 1

#ifndef AEMU_PCM_PATH
#define AEMU_PCM_PATH "/dev/eac"
#endif
#ifndef AEMU_MIC_PATH
#define AEMU_MIC_PATH "/dev/aemu_mic"
#endif
#ifndef AEMU_MIC_CTL_PATH
#define AEMU_MIC_CTL_PATH "/dev/aemu_mic_ctl"
#endif

#define OUT_RATE        48000u
#define OUT_FRAME_BYTES 4u                  /* stereo, 16 bit */
#ifndef AEMU_OUT_FRAMES
#define AEMU_OUT_FRAMES 2048u               /* 42.7 ms per period; fewer wake-ups matter under qemu-user */
#endif
#define OUT_LATENCY_MS  100u
#define FIFO_STALL_NS   20000000ll          /* a full FIFO means the host is not listening: drop after this */
#define REOPEN_NS       500000000ll         /* do not hammer open() while /dev/eac is missing */
#define IN_PERIOD_MS    20u
#define NS_PER_SEC      1000000000ll

/* ---- time ------------------------------------------------------------------------------------------------- */
static int64_t now_ns(void) {
    struct aemu_timespec ts;
    clock_gettime(K_CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * NS_PER_SEC + ts.tv_nsec;
}

static void sleep_until(int64_t deadline) {
    struct aemu_timespec ts;
    ts.tv_sec = (long)(deadline / NS_PER_SEC);
    ts.tv_nsec = (long)(deadline % NS_PER_SEC);
    /* clock_nanosleep returns the error number itself; retry only when a signal cut the sleep short */
    while (clock_nanosleep(K_CLOCK_MONOTONIC, K_TIMER_ABSTIME, &ts, 0) == K_EINTR) {}
}

static void sleep_ns(int64_t ns) { sleep_until(now_ns() + ns); }

static int64_t frames_to_ns(uint64_t frames, uint32_t rate) { return (int64_t)((frames * (uint64_t)NS_PER_SEC) / rate); }

/* ---- small string helpers (no libc) ---------------------------------------------------------------------- */
static size_t str_len(const char *s) { size_t n = 0; while (s[n]) n++; return n; }

static bool has_key(const char *keys, const char *key) {
    if (!keys) return false;
    size_t kl = str_len(key);
    for (const char *p = keys; *p; p++) {
        size_t i = 0;
        while (i < kl && p[i] == key[i]) i++;
        if (i == kl && (p[i] == 0 || p[i] == ';' || p[i] == '=')) return true;
    }
    return false;
}

static void append(char *buf, size_t cap, size_t *len, const char *s) {
    size_t l = str_len(s);
    if (*len + l + 1 > cap) return;
    memcpy(buf + *len, s, l);
    *len += l;
    buf[*len] = 0;
}

static size_t utoa_dec(char *out, unsigned v) {
    char tmp[12];
    size_t n = 0;
    do { tmp[n++] = (char)('0' + v % 10); v /= 10; } while (v);
    for (size_t i = 0; i < n; i++) out[i] = tmp[n - 1 - i];
    out[n] = 0;
    return n;
}

/* ---- device ----------------------------------------------------------------------------------------------- */
struct aemu_dev {
    struct audio_hw_device d;
    uint8_t pad[0x100 - (sizeof(struct audio_hw_device) & 0xff)];   /* room for slots a newer header adds */
    volatile int mic_mute;
};

struct aemu_out {
    struct audio_stream_out s;
    uint8_t pad[STREAM_PUBLIC_BYTES - sizeof(struct audio_stream_out)];
    /* private state starts here, behind the public struct */
    int fd;
    audio_devices_t device;
    uint32_t period_frames;
    bool standby;
    int64_t deadline_ns;        /* when everything written so far has been played */
    int64_t next_open_ns;
    uint64_t frames_total;      /* presentation / render position */
};

struct aemu_in {
    struct audio_stream_in s;
    uint8_t pad[STREAM_PUBLIC_BYTES - sizeof(struct audio_stream_in)];
    int pcm_fd;
    int ctl_fd;
    audio_devices_t device;
    uint32_t rate;
    uint32_t channels;          /* 1 or 2 */
    audio_channel_mask_t mask;
    uint32_t period_frames;
    bool active;
    int64_t deadline_ns;
};

_Static_assert(sizeof(struct aemu_out) > STREAM_PUBLIC_BYTES, "private out state must follow the public part");
_Static_assert(offsetof(struct aemu_out, fd) >= STREAM_PUBLIC_BYTES, "out.fd inside the public part");
_Static_assert(offsetof(struct aemu_in, pcm_fd) >= STREAM_PUBLIC_BYTES, "in.pcm_fd inside the public part");

/* ---- output stream ---------------------------------------------------------------------------------------- */
static uint32_t out_get_sample_rate(const struct audio_stream *s) { (void)s; return OUT_RATE; }
static int out_set_sample_rate(struct audio_stream *s, uint32_t rate) { (void)s; return rate == OUT_RATE ? 0 : -K_EINVAL; }
static size_t out_get_buffer_size(const struct audio_stream *s) {
    return (size_t)((const struct aemu_out *)s)->period_frames * OUT_FRAME_BYTES;
}
static audio_channel_mask_t out_get_channels(const struct audio_stream *s) { (void)s; return AUDIO_CHANNEL_OUT_STEREO; }
static audio_format_t out_get_format(const struct audio_stream *s) { (void)s; return AUDIO_FORMAT_PCM_16_BIT; }
static int out_set_format(struct audio_stream *s, audio_format_t f) { (void)s; return f == AUDIO_FORMAT_PCM_16_BIT ? 0 : -K_EINVAL; }

static int out_standby(struct audio_stream *s) {
    struct aemu_out *o = (struct aemu_out *)s;
    o->standby = true;          /* the pacing clock restarts with the next write */
    o->deadline_ns = 0;
    return 0;
}

static int out_dump(const struct audio_stream *s, int fd) { (void)s; (void)fd; return 0; }
static audio_devices_t out_get_device(const struct audio_stream *s) { return ((const struct aemu_out *)s)->device; }
static int out_set_device(struct audio_stream *s, audio_devices_t d) { ((struct aemu_out *)s)->device = d; return 0; }
static int out_set_parameters(struct audio_stream *s, const char *kv) { (void)s; (void)kv; return 0; }

static char *out_get_parameters(const struct audio_stream *s, const char *keys) {
    (void)s;
    enum { CAP = 192 };
    char *r = calloc(1, CAP);        /* the caller free()s it */
    if (!r) return 0;
    size_t len = 0;
    if (has_key(keys, "sup_sampling_rates")) append(r, CAP, &len, "sup_sampling_rates=48000;");
    if (has_key(keys, "sup_channels")) append(r, CAP, &len, "sup_channels=AUDIO_CHANNEL_OUT_STEREO;");
    if (has_key(keys, "sup_formats")) append(r, CAP, &len, "sup_formats=AUDIO_FORMAT_PCM_16_BIT;");
    if (len && r[len - 1] == ';') r[len - 1] = 0;
    return r;
}

static int out_effect(const struct audio_stream *s, void *effect) { (void)s; (void)effect; return 0; }
static uint32_t out_get_latency(const struct audio_stream_out *s) { (void)s; return OUT_LATENCY_MS; }
static int out_set_volume(struct audio_stream_out *s, float l, float r) { (void)s; (void)l; (void)r; return -K_ENOSYS; } /* AudioFlinger applies it in software */

/* Pushes PCM to the host. Never blocks longer than FIFO_STALL_NS, never raises SIGPIPE (we hold the FIFO read-write). */
static void out_push(struct aemu_out *o, const uint8_t *p, size_t n) {
    if (o->fd < 0) {
        int64_t t = now_ns();
        if (t < o->next_open_ns) return;
        o->fd = open(AEMU_PCM_PATH, K_O_RDWR | K_O_NONBLOCK | K_O_CLOEXEC);
        if (o->fd < 0) { o->next_open_ns = t + REOPEN_NS; return; }
    }
    int64_t stalled = 0;
    while (n) {
        long w = write(o->fd, p, n);
        if (w > 0) { p += w; n -= (size_t)w; stalled = 0; continue; }
        int e = AEMU_ERRNO;
        if (w < 0 && e == K_EINTR) continue;
        if (w == 0 || e == K_EAGAIN) {
            if (stalled >= FIFO_STALL_NS) return;         /* host not draining: drop the rest of this buffer */
            sleep_ns(2000000);
            stalled += 2000000;
            continue;
        }
        close(o->fd);                                       /* the channel went away: reopen later */
        o->fd = -1;
        o->next_open_ns = now_ns() + REOPEN_NS;
        return;
    }
}

static ssize_t out_write(struct audio_stream_out *s, const void *buffer, size_t bytes) {
    struct aemu_out *o = (struct aemu_out *)s;
    size_t n = bytes - bytes % OUT_FRAME_BYTES;
    if (n == 0) return (ssize_t)bytes;
    int64_t now = now_ns();
    if (o->standby) o->standby = false;
    if (o->deadline_ns < now) o->deadline_ns = now;        /* first write, or an underrun: do not try to catch up */
    out_push(o, (const uint8_t *)buffer, n);
    uint64_t frames = n / OUT_FRAME_BYTES;
    o->frames_total += frames;
    o->deadline_ns += frames_to_ns(frames, OUT_RATE);
    /* stay at most one period ahead of what has been played: that is what a real DMA ring does to the caller */
    int64_t wake = o->deadline_ns - frames_to_ns(o->period_frames, OUT_RATE);
    if (wake > now_ns()) sleep_until(wake);
    return (ssize_t)bytes;
}

static int out_get_render_position(const struct audio_stream_out *s, uint32_t *dsp_frames) {
    if (!dsp_frames) return -K_EINVAL;
    *dsp_frames = (uint32_t)((const struct aemu_out *)s)->frames_total;
    return 0;
}

static int out_get_next_write_timestamp(const struct audio_stream_out *s, int64_t *ts) { (void)s; (void)ts; return -K_EINVAL; }

static int out_get_presentation_position(const struct audio_stream_out *s, uint64_t *frames, struct timespec *timestamp) {
    const struct aemu_out *o = (const struct aemu_out *)s;
    if (!frames || !timestamp) return -K_EINVAL;
    struct aemu_timespec *ts = (struct aemu_timespec *)timestamp;
    int64_t t = now_ns();
    /* what has been played = everything written minus what is still ahead of the clock */
    uint64_t ahead = 0;
    if (!o->standby && o->deadline_ns > t) ahead = (uint64_t)(o->deadline_ns - t) * OUT_RATE / (uint64_t)NS_PER_SEC;
    *frames = o->frames_total > ahead ? o->frames_total - ahead : 0;
    clock_gettime(K_CLOCK_MONOTONIC, ts);
    return 0;
}

/* ---- input stream ----------------------------------------------------------------------------------------- */
static bool rate_ok(uint32_t r) { return r >= 4000 && r <= 48000; }

static uint32_t in_get_sample_rate(const struct audio_stream *s) { return ((const struct aemu_in *)s)->rate; }
static int in_set_sample_rate(struct audio_stream *s, uint32_t r) { return r == ((struct aemu_in *)s)->rate ? 0 : -K_EINVAL; }
static size_t in_get_buffer_size(const struct audio_stream *s) {
    const struct aemu_in *i = (const struct aemu_in *)s;
    return (size_t)i->period_frames * i->channels * 2;
}
static audio_channel_mask_t in_get_channels(const struct audio_stream *s) { return ((const struct aemu_in *)s)->mask; }
static audio_format_t in_get_format(const struct audio_stream *s) { (void)s; return AUDIO_FORMAT_PCM_16_BIT; }
static int in_set_format(struct audio_stream *s, audio_format_t f) { (void)s; return f == AUDIO_FORMAT_PCM_16_BIT ? 0 : -K_EINVAL; }

static void mic_stop(struct aemu_in *i) {
    if (i->ctl_fd >= 0) { write(i->ctl_fd, "stop\n", 5); close(i->ctl_fd); i->ctl_fd = -1; }
    if (i->pcm_fd >= 0) { close(i->pcm_fd); i->pcm_fd = -1; }
    i->active = false;
}

static void mic_start(struct aemu_in *i) {
    /* O_RDWR on a FIFO never fails for want of a reader, and it keeps the host's control loop from seeing EOF */
    i->ctl_fd = open(AEMU_MIC_CTL_PATH, K_O_RDWR | K_O_NONBLOCK | K_O_CLOEXEC);
    i->pcm_fd = open(AEMU_MIC_PATH, K_O_RDONLY | K_O_NONBLOCK | K_O_CLOEXEC);
    if (i->ctl_fd >= 0) {
        char cmd[40];
        size_t n = 0;
        memcpy(cmd, "start ", 6); n = 6;
        n += utoa_dec(cmd + n, i->rate);
        cmd[n++] = ' ';
        n += utoa_dec(cmd + n, i->channels);
        cmd[n++] = '\n';
        write(i->ctl_fd, cmd, n);
    }
    /* anything still in the FIFO from an earlier session is old sound: throw it away */
    if (i->pcm_fd >= 0) {
        uint8_t junk[512];
        for (int guard = 0; guard < 256; guard++) if (read(i->pcm_fd, junk, sizeof junk) <= 0) break;
    }
    i->active = true;
    i->deadline_ns = now_ns();
}

static int in_standby(struct audio_stream *s) { mic_stop((struct aemu_in *)s); return 0; }
static int in_dump(const struct audio_stream *s, int fd) { (void)s; (void)fd; return 0; }
static audio_devices_t in_get_device(const struct audio_stream *s) { return ((const struct aemu_in *)s)->device; }
static int in_set_device(struct audio_stream *s, audio_devices_t d) { ((struct aemu_in *)s)->device = d; return 0; }
static int in_set_parameters(struct audio_stream *s, const char *kv) { (void)s; (void)kv; return 0; }

static char *in_get_parameters(const struct audio_stream *s, const char *keys) {
    (void)s;
    enum { CAP = 256 };
    char *r = calloc(1, CAP);
    if (!r) return 0;
    size_t len = 0;
    if (has_key(keys, "sup_sampling_rates")) append(r, CAP, &len, "sup_sampling_rates=8000|11025|16000|22050|24000|32000|44100|48000;");
    if (has_key(keys, "sup_channels")) append(r, CAP, &len, "sup_channels=AUDIO_CHANNEL_IN_MONO|AUDIO_CHANNEL_IN_STEREO;");
    if (has_key(keys, "sup_formats")) append(r, CAP, &len, "sup_formats=AUDIO_FORMAT_PCM_16_BIT;");
    if (len && r[len - 1] == ';') r[len - 1] = 0;
    return r;
}

static int in_effect(const struct audio_stream *s, void *effect) { (void)s; (void)effect; return 0; }
static int in_set_gain(struct audio_stream_in *s, float g) { (void)s; (void)g; return 0; }
static uint32_t in_get_input_frames_lost(struct audio_stream_in *s) { (void)s; return 0; }

static ssize_t in_read(struct audio_stream_in *s, void *buffer, size_t bytes) {
    struct aemu_in *i = (struct aemu_in *)s;
    uint32_t fb = i->channels * 2;
    size_t n = bytes - bytes % fb;
    if (n == 0) return (ssize_t)bytes;
    if (!i->active) mic_start(i);

    int64_t now = now_ns();
    if (i->deadline_ns < now) i->deadline_ns = now;
    i->deadline_ns += frames_to_ns(n / fb, i->rate);

    /* a backlog (the host wrote faster than we read) is latency: keep the newest two periods only */
    if (i->pcm_fd >= 0) {
        int avail = 0;
        size_t keep = (size_t)i->period_frames * fb * 2;
        if (ioctl(i->pcm_fd, K_FIONREAD, &avail) == 0 && avail > 0 && (size_t)avail > keep + n) {
            uint8_t junk[1024];
            size_t drop = (size_t)avail - keep - n;
            while (drop) {
                long r = read(i->pcm_fd, junk, drop < sizeof junk ? drop : sizeof junk);
                if (r <= 0) break;
                drop -= (size_t)r;
            }
        }
    }

    uint8_t *dst = (uint8_t *)buffer;
    size_t got = 0;
    while (got < n) {
        long r = i->pcm_fd >= 0 ? read(i->pcm_fd, dst + got, n - got) : 0;
        if (r > 0) { got += (size_t)r; continue; }
        if (r < 0 && AEMU_ERRNO == K_EINTR) continue;
        /* nothing yet (EAGAIN, or the host has not opened its end): wait, but only until this buffer is due */
        int64_t t = now_ns();
        if (t >= i->deadline_ns) break;
        int64_t step = i->deadline_ns - t;
        sleep_ns(step < 2000000 ? step : 2000000);
    }
    if (got < n) {                                         /* no microphone data: silence, at the right pace */
        memset(dst + got, 0, n - got);
        got = n - got < n ? got : 0;                       /* (no-op, keeps got <= n obvious to the reader) */
        int64_t t = now_ns();
        if (t < i->deadline_ns) sleep_until(i->deadline_ns);
    }
    if (bytes > n) memset(dst + n, 0, bytes - n);
    return (ssize_t)bytes;
}

/* ---- device ----------------------------------------------------------------------------------------------- */
static int dev_init_check(const struct audio_hw_device *d) { (void)d; return 0; }
static int dev_set_voice_volume(struct audio_hw_device *d, float v) { (void)d; (void)v; return -K_ENOSYS; }
static int dev_set_master_volume(struct audio_hw_device *d, float v) { (void)d; (void)v; return -K_ENOSYS; } /* software volume */
static int dev_get_master_volume(struct audio_hw_device *d, float *v) { (void)d; (void)v; return -K_ENOSYS; }
static int dev_set_mode(struct audio_hw_device *d, int mode) { (void)d; (void)mode; return 0; }
static int dev_set_mic_mute(struct audio_hw_device *d, bool m) { ((struct aemu_dev *)d)->mic_mute = m ? 1 : 0; return 0; }
static int dev_get_mic_mute(const struct audio_hw_device *d, bool *m) {
    if (!m) return -K_EINVAL;
    *m = ((const struct aemu_dev *)d)->mic_mute != 0;
    return 0;
}
static int dev_set_parameters(struct audio_hw_device *d, const char *kv) { (void)d; (void)kv; return 0; }
static char *dev_get_parameters(const struct audio_hw_device *d, const char *keys) { (void)d; (void)keys; return calloc(1, 1); }
static int dev_dump(const struct audio_hw_device *d, int fd) { (void)d; (void)fd; return 0; }
static int dev_set_master_mute(struct audio_hw_device *d, bool m) { (void)d; (void)m; return -K_ENOSYS; }  /* software mute */
static int dev_get_master_mute(struct audio_hw_device *d, bool *m) { (void)d; (void)m; return -K_ENOSYS; }

/* Validates a requested input config. Zero fields mean "your choice". On a mismatch the config is rewritten to the
 * nearest thing this HAL can do and false is returned, which the caller turns into -EINVAL (AudioFlinger retries). */
static bool in_config(struct audio_config *c, uint32_t *rate, uint32_t *channels, audio_channel_mask_t *mask) {
    bool ok = true;
    uint32_t r = c ? c->sample_rate : 0;
    audio_channel_mask_t m = c ? c->channel_mask : 0;
    audio_format_t f = c ? c->format : 0;
    if (r == 0) r = 16000;
    else if (!rate_ok(r)) { r = 16000; ok = false; }
    if (m == AUDIO_CHANNEL_NONE) m = AUDIO_CHANNEL_IN_MONO;
    else if (m != AUDIO_CHANNEL_IN_MONO && m != AUDIO_CHANNEL_IN_STEREO) { m = AUDIO_CHANNEL_IN_MONO; ok = false; }
    if (f != AUDIO_FORMAT_DEFAULT && f != AUDIO_FORMAT_PCM_16_BIT) ok = false;
    if (c) { c->sample_rate = r; c->channel_mask = m; c->format = AUDIO_FORMAT_PCM_16_BIT; }
    *rate = r; *mask = m; *channels = m == AUDIO_CHANNEL_IN_STEREO ? 2 : 1;
    return ok;
}

static size_t dev_get_input_buffer_size(const struct audio_hw_device *d, const struct audio_config *config) {
    (void)d;
    struct audio_config c = {0};
    if (config) c = *config;
    uint32_t rate, ch; audio_channel_mask_t mask;
    if (!in_config(&c, &rate, &ch, &mask)) return 0;
    uint32_t frames = (rate * IN_PERIOD_MS / 1000 + 15) & ~15u;    /* AudioFlinger wants a multiple of 16 frames */
    return (size_t)frames * ch * 2;
}

static int dev_open_output_stream(struct audio_hw_device *d, audio_io_handle_t handle, audio_devices_t devices,
                                  audio_output_flags_t flags, struct audio_config *config,
                                  struct audio_stream_out **stream_out, const char *address) {
    (void)d; (void)handle; (void)flags; (void)address;
    if (!stream_out) return -K_EINVAL;
    *stream_out = 0;
    /* One fixed output format. A policy that asks for something else (the built-in default policy asks for 44.1 kHz)
     * gets this one reported back and AudioFlinger runs the thread at the stream's real rate. */
    if (config) {
        config->sample_rate = OUT_RATE;
        config->channel_mask = AUDIO_CHANNEL_OUT_STEREO;
        config->format = AUDIO_FORMAT_PCM_16_BIT;
    }
    struct aemu_out *o = calloc(1, sizeof *o);
    if (!o) return -12;
    o->fd = -1;
    o->device = devices ? devices : AUDIO_DEVICE_OUT_SPEAKER;
    o->period_frames = AEMU_OUT_FRAMES;
    o->standby = true;
    struct audio_stream *c = &o->s.common;
    c->get_sample_rate = out_get_sample_rate;
    c->set_sample_rate = out_set_sample_rate;
    c->get_buffer_size = out_get_buffer_size;
    c->get_channels = out_get_channels;
    c->get_format = out_get_format;
    c->set_format = out_set_format;
    c->standby = out_standby;
    c->dump = out_dump;
    c->get_device = out_get_device;
    c->set_device = out_set_device;
    c->set_parameters = out_set_parameters;
    c->get_parameters = out_get_parameters;
    c->add_audio_effect = out_effect;
    c->remove_audio_effect = out_effect;
    o->s.get_latency = out_get_latency;
    o->s.set_volume = out_set_volume;
    o->s.write = out_write;
    o->s.get_render_position = out_get_render_position;
    o->s.get_next_write_timestamp = out_get_next_write_timestamp;
    o->s.get_presentation_position = out_get_presentation_position;
    /* set_callback, pause, resume, drain, flush, update_source_metadata and everything after: NULL (no offload) */
    *stream_out = &o->s;
    return 0;
}

static void dev_close_output_stream(struct audio_hw_device *d, struct audio_stream_out *s) {
    (void)d;
    struct aemu_out *o = (struct aemu_out *)s;
    if (!o) return;
    if (o->fd >= 0) close(o->fd);
    free(o);
}

static int dev_open_input_stream(struct audio_hw_device *d, audio_io_handle_t handle, audio_devices_t devices,
                                 struct audio_config *config, struct audio_stream_in **stream_in,
                                 audio_input_flags_t flags, const char *address, audio_source_t source) {
    (void)d; (void)handle; (void)flags; (void)address; (void)source;
    if (!stream_in) return -K_EINVAL;
    *stream_in = 0;
    uint32_t rate, ch; audio_channel_mask_t mask;
    if (!in_config(config, &rate, &ch, &mask)) return -K_EINVAL;
    struct aemu_in *i = calloc(1, sizeof *i);
    if (!i) return -12;
    i->pcm_fd = -1;
    i->ctl_fd = -1;
    i->device = devices ? devices : AUDIO_DEVICE_IN_BUILTIN_MIC;
    i->rate = rate;
    i->channels = ch;
    i->mask = mask;
    i->period_frames = (rate * IN_PERIOD_MS / 1000 + 15) & ~15u;
    struct audio_stream *c = &i->s.common;
    c->get_sample_rate = in_get_sample_rate;
    c->set_sample_rate = in_set_sample_rate;
    c->get_buffer_size = in_get_buffer_size;
    c->get_channels = in_get_channels;
    c->get_format = in_get_format;
    c->set_format = in_set_format;
    c->standby = in_standby;
    c->dump = in_dump;
    c->get_device = in_get_device;
    c->set_device = in_set_device;
    c->set_parameters = in_set_parameters;
    c->get_parameters = in_get_parameters;
    c->add_audio_effect = in_effect;
    c->remove_audio_effect = in_effect;
    i->s.set_gain = in_set_gain;
    i->s.read = in_read;
    i->s.get_input_frames_lost = in_get_input_frames_lost;
    *stream_in = &i->s;
    return 0;
}

static void dev_close_input_stream(struct audio_hw_device *d, struct audio_stream_in *s) {
    (void)d;
    struct aemu_in *i = (struct aemu_in *)s;
    if (!i) return;
    mic_stop(i);
    free(i);
}

static int dev_close(struct hw_device_t *d) {
    free(d);
    return 0;
}

static int hal_open(const struct hw_module_t *module, const char *id, struct hw_device_t **device) {
    static const char want[] = AUDIO_HARDWARE_INTERFACE;
    size_t k = 0;
    if (!id || !device) return -K_EINVAL;
    while (k < sizeof want && id[k] == want[k]) k++;
    if (k != sizeof want) return -K_EINVAL;               /* also compares the terminating NUL */
    struct aemu_dev *dev = calloc(1, sizeof *dev);
    if (!dev) return -12;
    struct audio_hw_device *a = &dev->d;
    a->common.tag = HARDWARE_DEVICE_TAG;
    /* 2.0: no audio patches, routing goes through set_parameters; the android.hardware.audio@2.0 impl reports
     * supportsAudioPatches() = false for it and AudioFlinger takes the legacy path, the least that can go wrong */
    a->common.version = AUDIO_DEVICE_API_VERSION_2_0;
    a->common.module = (struct hw_module_t *)module;
    a->common.close = dev_close;
    a->get_supported_devices = 0;                          /* deprecated since 3.0 */
    a->init_check = dev_init_check;
    a->set_voice_volume = dev_set_voice_volume;
    a->set_master_volume = dev_set_master_volume;
    a->get_master_volume = dev_get_master_volume;
    a->set_mode = dev_set_mode;
    a->set_mic_mute = dev_set_mic_mute;
    a->get_mic_mute = dev_get_mic_mute;
    a->set_parameters = dev_set_parameters;
    a->get_parameters = dev_get_parameters;
    a->get_input_buffer_size = dev_get_input_buffer_size;
    a->open_output_stream = dev_open_output_stream;
    a->close_output_stream = dev_close_output_stream;
    a->open_input_stream = dev_open_input_stream;
    a->close_input_stream = dev_close_input_stream;
    a->dump = dev_dump;
    a->set_master_mute = dev_set_master_mute;
    a->get_master_mute = dev_get_master_mute;
    /* create_audio_patch, release_audio_patch, get_audio_port, set_audio_port_config: NULL */
    *device = &a->common;
    return 0;
}

static hw_module_methods_t hal_methods = { .open = hal_open };

/* hw_get_module() dlsym()s "HMI" and writes module->dso into it: it must stay writable. */
EXPORT hw_module_t HAL_MODULE_INFO_SYM = {
    .tag = HARDWARE_MODULE_TAG,
    .module_api_version = AUDIO_MODULE_API_VERSION_0_1,
    .hal_api_version = 0,
    .id = AUDIO_HARDWARE_MODULE_ID,
    .name = "AEmulator audio HAL (/dev/eac)",
    .author = "AEmulator Plus",
    .methods = &hal_methods,
};
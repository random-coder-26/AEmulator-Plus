#include <stdlib.h>
#include <string.h>
#include "arc_internal.h"

struct arc_dec { const arc_ops *ops; void *st; int codec; int ended; };

__attribute__((constructor)) static void arc_ctor(void) { arc_global_init(); }

const char *arc_version(void) { return "aemuarc 1.0 (zlib 1.3.1, bzip2 1.0.8, lz4 1.10.0, zstd 1.5.7, brotli 1.1.0, 7-Zip 25.01)"; }

const char *arc_strerror(int s) {
    switch (s) {
    case ARC_OK: return "ok";
    case ARC_END: return "end of stream";
    case ARC_E_DATA: return "corrupt compressed data";
    case ARC_E_MEM: return "out of memory";
    case ARC_E_UNSUPPORTED: return "unsupported compression method";
    case ARC_E_TRUNCATED: return "unexpected end of compressed data";
    case ARC_E_ARG: return "bad argument";
    case ARC_E_IO: return "read error";
    default: return "unknown error";
    }
}

static const arc_ops *ops_for(int codec) {
    switch (codec) {
    case ARC_GZIP: case ARC_ZLIB: case ARC_DEFLATE: return &arc_ops_zlib;
    case ARC_XZ: case ARC_LZMA: return &arc_ops_lzma;
    case ARC_BZIP2: return &arc_ops_bz2;
    case ARC_LZ4F: return &arc_ops_lz4f;
    case ARC_ZSTD: return &arc_ops_zstd;
    case ARC_BROTLI: return &arc_ops_brotli;
    default: return NULL;
    }
}

arc_dec *arc_dec_new(int codec) {
    arc_dec *d = (arc_dec *)calloc(1, sizeof *d);
    if (!d) return NULL;
    d->codec = codec;
    if (codec != ARC_COPY) {
        d->ops = ops_for(codec);
        if (!d->ops) { free(d); return NULL; }
        d->st = d->ops->create(codec);
        if (!d->st) { free(d); return NULL; }
    }
    return d;
}

void arc_dec_free(arc_dec *d) {
    if (!d) return;
    if (d->ops) d->ops->destroy(d->st);
    free(d);
}

int arc_dec_step(arc_dec *d, const uint8_t *in, size_t in_len, size_t *in_used, uint8_t *out, size_t out_cap, size_t *out_made, int eof) {
    if (!d || !in_used || !out_made) return ARC_E_ARG;
    *in_used = 0; *out_made = 0;
    if (d->ended) return ARC_END;
    if (d->codec == ARC_COPY) {
        size_t n = ARC_MIN(in_len, out_cap);
        if (n) memcpy(out, in, n);
        *in_used = *out_made = n;
        if (eof && n == in_len) { d->ended = 1; return ARC_END; }
        return ARC_OK;
    }
    int r = d->ops->step(d->st, in, in_len, in_used, out, out_cap, out_made, eof);
    if (r == ARC_END) d->ended = 1;
    return r;
}

int arc_decode_all(int codec, const uint8_t *in, size_t in_len, uint64_t size_hint, uint8_t **out, size_t *out_len) {
    arc_dec *d = arc_dec_new(codec);
    if (!d) return ARC_E_MEM;
    size_t cap = size_hint ? (size_t)size_hint : (in_len * 4 < 65536 ? 65536 : in_len * 4);
    size_t len = 0, pos = 0;
    uint8_t *buf = (uint8_t *)malloc(cap);
    int rc = ARC_OK;
    if (!buf) { arc_dec_free(d); return ARC_E_MEM; }
    for (;;) {
        size_t used, made;
        if (len == cap) {
            size_t ncap = cap + (cap >> 1) + 65536;
            uint8_t *nb = (uint8_t *)realloc(buf, ncap);
            if (!nb) { rc = ARC_E_MEM; break; }
            buf = nb; cap = ncap;
        }
        rc = arc_dec_step(d, in + pos, in_len - pos, &used, buf + len, cap - len, &made, 1);
        pos += used; len += made;
        if (rc == ARC_END) { rc = ARC_OK; break; }
        if (rc < 0) break;
        if (used == 0 && made == 0 && len < cap) { rc = ARC_E_TRUNCATED; break; }
    }
    arc_dec_free(d);
    if (rc != ARC_OK) { free(buf); return rc; }
    *out = buf; *out_len = len;
    return ARC_OK;
}

void arc_free(void *p) { free(p); }

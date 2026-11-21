/* gzip / zlib / raw deflate through the vendored zlib. gzip members may be concatenated (like java.util.zip.GZIPInputStream). */
#include <stdlib.h>
#include <string.h>
#include <limits.h>
#include "zlib.h"
#include "arc_internal.h"

typedef struct { z_stream z; int kind; int between; } zst;

static void *zl_create(int codec) {
    zst *s = (zst *)calloc(1, sizeof *s);
    if (!s) return NULL;
    s->kind = codec;
    if (inflateInit2(&s->z, codec == ARC_GZIP ? 15 + 16 : codec == ARC_ZLIB ? 15 : -15) != Z_OK) { free(s); return NULL; }
    return s;
}

static void zl_destroy(void *p) { zst *s = (zst *)p; if (s) { inflateEnd(&s->z); free(s); } }

static int zl_step(void *p, const uint8_t *in, size_t in_len, size_t *used, uint8_t *out, size_t cap, size_t *made, int eof) {
    zst *s = (zst *)p;
    *used = 0; *made = 0;
    for (;;) {
        if (s->between) {
            size_t avail = in_len - *used;
            if (avail < 2) return eof ? ARC_END : ARC_OK;               /* wait for the next member's magic */
            if (in[*used] != 0x1f || in[*used + 1] != 0x8b) return ARC_END; /* trailing garbage: ignored, as the JDK does */
            inflateReset(&s->z);
            s->between = 0;
        }
        size_t ai = in_len - *used, ao = cap - *made;
        if (ao == 0) return ARC_OK;
        uInt ci = (uInt)ARC_MIN(ai, (size_t)UINT_MAX), co = (uInt)ARC_MIN(ao, (size_t)UINT_MAX);
        s->z.next_in = (Bytef *)(in + *used); s->z.avail_in = ci;
        s->z.next_out = out + *made; s->z.avail_out = co;
        int r = inflate(&s->z, Z_NO_FLUSH);
        *used += ci - s->z.avail_in;
        *made += co - s->z.avail_out;
        if (r == Z_STREAM_END) {
            if (s->kind == ARC_GZIP) { s->between = 1; continue; }
            return ARC_END;
        }
        if (r == Z_OK || r == Z_BUF_ERROR) {
            if (s->z.avail_out == 0) return ARC_OK;
            if (s->z.avail_in == 0 || r == Z_BUF_ERROR) {
                if (ci == s->z.avail_in && co == s->z.avail_out) return eof ? ARC_E_TRUNCATED : ARC_OK; /* no progress */
                if (*used == in_len) return eof && *made == 0 ? ARC_E_TRUNCATED : ARC_OK;
            }
            continue;
        }
        if (r == Z_MEM_ERROR) return ARC_E_MEM;
        return ARC_E_DATA; /* Z_DATA_ERROR, Z_NEED_DICT, Z_STREAM_ERROR */
    }
}

const arc_ops arc_ops_zlib = { zl_create, zl_step, zl_destroy };

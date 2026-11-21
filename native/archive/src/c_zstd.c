/* Zstandard frames (concatenated and skippable ones included), decoder only. */
#include <stdlib.h>
#include "zstd.h"
#include "arc_internal.h"

typedef struct { ZSTD_DCtx *d; int between; } zdst;

static void *zs_create(int codec) {
    (void)codec;
    zdst *s = (zdst *)calloc(1, sizeof *s);
    if (!s) return NULL;
    s->d = ZSTD_createDCtx();
    if (!s->d) { free(s); return NULL; }
    ZSTD_DCtx_setParameter(s->d, ZSTD_d_windowLogMax, 31); /* images packed with --long=31 */
    return s;
}
static void zs_destroy(void *p) { zdst *s = (zdst *)p; if (s) { ZSTD_freeDCtx(s->d); free(s); } }

static int zs_step(void *p, const uint8_t *in, size_t in_len, size_t *used, uint8_t *out, size_t cap, size_t *made, int eof) {
    zdst *s = (zdst *)p;
    ZSTD_inBuffer ib = { in, in_len, 0 };
    ZSTD_outBuffer ob = { out, cap, 0 };
    *used = 0; *made = 0;
    for (;;) {
        size_t pi = ib.pos, po = ob.pos;
        if (ob.pos == ob.size) break;
        size_t r = ZSTD_decompressStream(s->d, &ob, &ib);
        if (ZSTD_isError(r)) { *used = ib.pos; *made = ob.pos; return ARC_E_DATA; }
        if (ib.pos == pi && ob.pos == po) break; /* no progress: a probe at a frame boundary must not undo `between` */
        s->between = (r == 0);
    }
    *used = ib.pos; *made = ob.pos;
    if (eof && ib.pos == in_len && ob.pos < ob.size) return s->between ? ARC_END : ARC_E_TRUNCATED;
    return ARC_OK;
}

const arc_ops arc_ops_zstd = { zs_create, zs_step, zs_destroy };

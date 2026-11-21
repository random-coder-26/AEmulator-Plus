/* LZ4 frame format (what `lz4` writes), concatenated and skippable frames included. */
#include <stdlib.h>
#include "lz4frame.h"
#include "arc_internal.h"

typedef struct { LZ4F_dctx *ctx; int between; } lst;

static void *lz_create(int codec) {
    (void)codec;
    lst *s = (lst *)calloc(1, sizeof *s);
    if (!s) return NULL;
    if (LZ4F_isError(LZ4F_createDecompressionContext(&s->ctx, LZ4F_VERSION))) { free(s); return NULL; }
    return s;
}
static void lz_destroy(void *p) { lst *s = (lst *)p; if (s) { LZ4F_freeDecompressionContext(s->ctx); free(s); } }

static int lz_step(void *p, const uint8_t *in, size_t in_len, size_t *used, uint8_t *out, size_t cap, size_t *made, int eof) {
    lst *s = (lst *)p;
    *used = 0; *made = 0;
    for (;;) {
        size_t ss = in_len - *used, ds = cap - *made;
        if (s->between && ss == 0) return eof ? ARC_END : ARC_OK;
        if (ds == 0) return ARC_OK;
        size_t r = LZ4F_decompress(s->ctx, out + *made, &ds, in + *used, &ss, NULL);
        if (LZ4F_isError(r)) return ARC_E_DATA;
        *used += ss; *made += ds;
        if (r == 0) { s->between = 1; LZ4F_resetDecompressionContext(s->ctx); continue; }
        s->between = 0;
        if (ss == 0 && ds == 0) return eof ? ARC_E_TRUNCATED : ARC_OK; /* needs input */
        if (*made == cap) return ARC_OK;
    }
}

const arc_ops arc_ops_lz4f = { lz_create, lz_step, lz_destroy };

int64_t arc_lz4_block(const uint8_t *in, size_t in_len, uint8_t *out, size_t out_cap) {
    extern int LZ4_decompress_safe(const char *, char *, int, int);
    if (in_len > 0x7fffffffu || out_cap > 0x7fffffffu) return ARC_E_ARG;
    int r = LZ4_decompress_safe((const char *)in, (char *)out, (int)in_len, (int)out_cap);
    return r < 0 ? ARC_E_DATA : r;
}

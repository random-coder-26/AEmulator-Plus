/* bzip2 through the vendored libbz2; concatenated streams are decoded one after another. */
#include <stdlib.h>
#include <string.h>
#include <limits.h>
#include "bzlib.h"
#include "arc_internal.h"

typedef struct { bz_stream s; int between; } bst;

static void *bz_create(int codec) {
    (void)codec;
    bst *b = (bst *)calloc(1, sizeof *b);
    if (!b) return NULL;
    if (BZ2_bzDecompressInit(&b->s, 0, 0) != BZ_OK) { free(b); return NULL; }
    return b;
}
static void bz_destroy(void *p) { bst *b = (bst *)p; if (b) { BZ2_bzDecompressEnd(&b->s); free(b); } }

static int bz_step(void *p, const uint8_t *in, size_t in_len, size_t *used, uint8_t *out, size_t cap, size_t *made, int eof) {
    bst *b = (bst *)p;
    *used = 0; *made = 0;
    for (;;) {
        if (b->between) {
            size_t avail = in_len - *used;
            if (avail < 3) return eof ? ARC_END : ARC_OK;
            if (in[*used] != 'B' || in[*used + 1] != 'Z' || in[*used + 2] != 'h') return ARC_END;
            BZ2_bzDecompressEnd(&b->s);
            memset(&b->s, 0, sizeof b->s);
            if (BZ2_bzDecompressInit(&b->s, 0, 0) != BZ_OK) return ARC_E_MEM;
            b->between = 0;
        }
        size_t ai = in_len - *used, ao = cap - *made;
        if (ao == 0) return ARC_OK;
        unsigned ci = (unsigned)ARC_MIN(ai, (size_t)UINT_MAX), co = (unsigned)ARC_MIN(ao, (size_t)UINT_MAX);
        b->s.next_in = (char *)(in + *used); b->s.avail_in = ci;
        b->s.next_out = (char *)(out + *made); b->s.avail_out = co;
        int r = BZ2_bzDecompress(&b->s);
        *used += ci - b->s.avail_in;
        *made += co - b->s.avail_out;
        if (r == BZ_STREAM_END) { b->between = 1; continue; }
        if (r == BZ_OK) {
            if (b->s.avail_out == 0) return ARC_OK;
            if (ci == b->s.avail_in && co == b->s.avail_out) return eof ? ARC_E_TRUNCATED : ARC_OK; /* no progress */
            if (*used == in_len) return eof && *made == 0 ? ARC_E_TRUNCATED : ARC_OK;
            continue;
        }
        if (r == BZ_MEM_ERROR) return ARC_E_MEM;
        return ARC_E_DATA;
    }
}

const arc_ops arc_ops_bz2 = { bz_create, bz_step, bz_destroy };

/* libbz2 is built with BZ_NO_STDIO: this is where it reports a bug in itself (never reached on valid or corrupt input) */
void bz_internal_error(int errcode) { (void)errcode; __builtin_trap(); }

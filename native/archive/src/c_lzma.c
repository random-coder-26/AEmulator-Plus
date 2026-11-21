/* .xz (blocks with LZMA2 + BCJ/ARM/ARM64/RISCV/Delta filters, CRC32/CRC64/SHA-256, concatenated streams, stream padding)
 * and "lzma alone" (.lzma), both on the 7-Zip LZMA SDK. */
#include <stdlib.h>
#include <string.h>
#include "Alloc.h"
#include "LzmaDec.h"
#include "Xz.h"
#include "XzCrc64.h"
#include "7zCrc.h"
#include "arc_internal.h"

typedef struct {
    int codec;
    CXzUnpacker xz;
    CLzmaDec lz;
    int lz_ready;
    uint8_t hdr[13]; unsigned hlen;
    uint64_t left; int known;   /* lzma alone: bytes still to produce when the header gave a size */
    int finished;
} lzst;

void arc_global_init(void) {
    static int done;
    if (done) return;
    CrcGenerateTable();
    Crc64GenerateTable();
    done = 1;
}

static void *lzm_create(int codec) {
    lzst *s = (lzst *)calloc(1, sizeof *s);
    if (!s) return NULL;
    arc_global_init();
    s->codec = codec;
    if (codec == ARC_XZ) { XzUnpacker_Construct(&s->xz, &g_Alloc); XzUnpacker_Init(&s->xz); }
    else LzmaDec_CONSTRUCT(&s->lz);
    return s;
}
static void lzm_destroy(void *p) {
    lzst *s = (lzst *)p;
    if (!s) return;
    if (s->codec == ARC_XZ) XzUnpacker_Free(&s->xz);
    else if (s->lz_ready) LzmaDec_Free(&s->lz, &g_Alloc);
    free(s);
}

static int xz_step(lzst *s, const uint8_t *in, size_t in_len, size_t *used, uint8_t *out, size_t cap, size_t *made, int eof) {
    SizeT sl = in_len, dl = cap;
    ECoderStatus st;
    SRes r = XzUnpacker_Code(&s->xz, out, &dl, in, &sl, eof, CODER_FINISH_ANY, &st);
    *used = sl; *made = dl;
    if (r == SZ_OK) {
        if (eof && sl == in_len && dl < cap) return XzUnpacker_IsStreamWasFinished(&s->xz) ? ARC_END : ARC_E_TRUNCATED;
        return ARC_OK;
    }
    if (r == SZ_ERROR_NO_ARCHIVE && s->xz.numFinishedStreams > 0) return ARC_END; /* data after the last stream */
    if (r == SZ_ERROR_MEM) return ARC_E_MEM;
    if (r == SZ_ERROR_UNSUPPORTED) return ARC_E_UNSUPPORTED;
    return ARC_E_DATA;
}

static int lzma_alone_step(lzst *s, const uint8_t *in, size_t in_len, size_t *used, uint8_t *out, size_t cap, size_t *made, int eof) {
    size_t u = 0;
    *used = 0; *made = 0;
    if (s->finished) return ARC_END;
    while (s->hlen < 13 && u < in_len) s->hdr[s->hlen++] = in[u++];
    *used = u;
    if (s->hlen < 13) return eof ? ARC_E_TRUNCATED : ARC_OK;
    if (!s->lz_ready) {
        uint8_t props[5];
        uint64_t size = 0;
        uint32_t dict = 0;
        int i;
        for (i = 7; i >= 0; i--) size = (size << 8) | s->hdr[5 + i];
        for (i = 4; i >= 1; i--) dict = (dict << 8) | s->hdr[i];
        if (s->hdr[0] >= 9 * 5 * 5 || dict < 4096 || dict > 0x70000000u) return ARC_E_DATA;
        s->known = size != UINT64_MAX;
        s->left = size;
        if (s->known && size < dict) dict = (uint32_t)(size < 4096 ? 4096 : size); /* a small file needs no big window */
        memcpy(props, s->hdr, 5);
        props[1] = (uint8_t)dict; props[2] = (uint8_t)(dict >> 8); props[3] = (uint8_t)(dict >> 16); props[4] = (uint8_t)(dict >> 24);
        SRes r = LzmaDec_Allocate(&s->lz, props, 5, &g_Alloc);
        if (r != SZ_OK) return r == SZ_ERROR_MEM ? ARC_E_MEM : ARC_E_DATA;
        s->lz_ready = 1;
        LzmaDec_Init(&s->lz);
    }
    for (;;) {
        SizeT sl = in_len - u, dl = cap - *made;
        ELzmaStatus st;
        if (s->known && dl > s->left) dl = (SizeT)s->left;
        if (s->known && s->left == 0) { s->finished = 1; return ARC_END; }
        if (dl == 0) return ARC_OK;
        SRes r = LzmaDec_DecodeToBuf(&s->lz, out + *made, &dl, in + u, &sl, LZMA_FINISH_ANY, &st);
        u += sl; *used = u; *made += dl;
        if (s->known) s->left -= dl;
        if (r != SZ_OK) return ARC_E_DATA;
        if (st == LZMA_STATUS_FINISHED_WITH_MARK) { s->finished = 1; return ARC_END; }
        if (s->known && s->left == 0) { s->finished = 1; return ARC_END; }
        if (sl == 0 && dl == 0) return eof ? ARC_E_TRUNCATED : ARC_OK; /* needs input */
        if (*made == cap) return ARC_OK;
    }
}

static int lzm_step(void *p, const uint8_t *in, size_t in_len, size_t *used, uint8_t *out, size_t cap, size_t *made, int eof) {
    lzst *s = (lzst *)p;
    return s->codec == ARC_XZ ? xz_step(s, in, in_len, used, out, cap, made, eof)
                              : lzma_alone_step(s, in, in_len, used, out, cap, made, eof);
}

const arc_ops arc_ops_lzma = { lzm_create, lzm_step, lzm_destroy };

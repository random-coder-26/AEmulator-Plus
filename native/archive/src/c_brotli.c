/* Brotli (RFC 7932), decoder only. */
#include <stdlib.h>
#include "brotli/decode.h"
#include "arc_internal.h"

static void *br_create(int codec) {
    (void)codec;
    BrotliDecoderState *s = BrotliDecoderCreateInstance(NULL, NULL, NULL);
    if (s) BrotliDecoderSetParameter(s, BROTLI_DECODER_PARAM_LARGE_WINDOW, 1);
    return s;
}
static void br_destroy(void *p) { if (p) BrotliDecoderDestroyInstance((BrotliDecoderState *)p); }

static int br_step(void *p, const uint8_t *in, size_t in_len, size_t *used, uint8_t *out, size_t cap, size_t *made, int eof) {
    size_t ai = in_len, ao = cap;
    const uint8_t *ni = in;
    uint8_t *no = out;
    BrotliDecoderResult r = BrotliDecoderDecompressStream((BrotliDecoderState *)p, &ai, &ni, &ao, &no, NULL);
    *used = in_len - ai;
    *made = cap - ao;
    switch (r) {
    case BROTLI_DECODER_RESULT_SUCCESS: return ARC_END;
    case BROTLI_DECODER_RESULT_NEEDS_MORE_INPUT: return eof && ai == 0 ? ARC_E_TRUNCATED : ARC_OK;
    case BROTLI_DECODER_RESULT_NEEDS_MORE_OUTPUT: return ARC_OK;
    default: return ARC_E_DATA;
    }
}

const arc_ops arc_ops_brotli = { br_create, br_step, br_destroy };

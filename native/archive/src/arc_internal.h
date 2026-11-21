#ifndef ARC_INTERNAL_H
#define ARC_INTERNAL_H
#include "aemuarc.h"

/* A push decoder backend. Same contract as arc_dec_step. */
typedef struct arc_ops {
    void *(*create)(int codec);
    int (*step)(void *st, const uint8_t *in, size_t in_len, size_t *in_used,
                uint8_t *out, size_t out_cap, size_t *out_made, int eof);
    void (*destroy)(void *st);
} arc_ops;

extern const arc_ops arc_ops_zlib;   /* ARC_GZIP, ARC_ZLIB, ARC_DEFLATE */
extern const arc_ops arc_ops_bz2;    /* ARC_BZIP2 */
extern const arc_ops arc_ops_lz4f;   /* ARC_LZ4F */
extern const arc_ops arc_ops_zstd;   /* ARC_ZSTD */
extern const arc_ops arc_ops_brotli; /* ARC_BROTLI */
extern const arc_ops arc_ops_lzma;   /* ARC_XZ, ARC_LZMA (7-Zip LZMA SDK) */

void arc_global_init(void);

#define ARC_MIN(a, b) ((a) < (b) ? (a) : (b))
#endif

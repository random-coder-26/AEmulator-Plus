/*
 * aemuarc - one native library for every decompression format the firmware importer meets.
 *
 * Push decoders (arc_dec_*) for gzip / zlib / raw deflate / xz / lzma-alone / bzip2 / lz4 frame / zstd / brotli,
 * a streaming 7z reader (arc7z_*), and one-shot helpers. Pure C, no JNI in here (see src/arc_jni.c).
 * Third-party code is vendored in third_party/ (zlib, bzip2, lz4, zstd, brotli, 7-Zip LZMA SDK); see README.md.
 */
#ifndef AEMUARC_H
#define AEMUARC_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif
#pragma GCC visibility push(default)

enum arc_codec {
    ARC_COPY = 0,
    ARC_GZIP = 1,      /* RFC 1952, concatenated members */
    ARC_ZLIB = 2,      /* RFC 1950 */
    ARC_DEFLATE = 3,   /* RFC 1951 raw */
    ARC_XZ = 4,        /* .xz, concatenated streams and stream padding */
    ARC_LZMA = 5,      /* "lzma alone" (.lzma) */
    ARC_BZIP2 = 6,     /* concatenated streams */
    ARC_LZ4F = 7,      /* LZ4 frame format, concatenated frames, skippable frames */
    ARC_ZSTD = 8,      /* concatenated frames, skippable frames */
    ARC_BROTLI = 9
};

enum arc_status {
    ARC_OK = 0,             /* progress made, or more input needed */
    ARC_END = 1,            /* the compressed stream is complete, no more output will follow */
    ARC_E_DATA = -1,        /* corrupt input */
    ARC_E_MEM = -2,
    ARC_E_UNSUPPORTED = -3,
    ARC_E_TRUNCATED = -4,   /* input ended inside a stream */
    ARC_E_ARG = -5,
    ARC_E_IO = -6           /* the byte source failed */
};

const char *arc_strerror(int status);
const char *arc_version(void);

typedef struct arc_dec arc_dec;

arc_dec *arc_dec_new(int codec);
void arc_dec_free(arc_dec *d);

/*
 * One decoding step. Consumes some of in[0..in_len), writes some of out[0..out_cap). Input that was not consumed
 * (*in_used < in_len) must be handed in again, at the front of the next call, together with more. [eof] says that no
 * input beyond [in_len] will ever come. Call again while *in_used or *out_made is non-zero; with both zero the decoder
 * wants more input (or, if the output was full, more output room).
 */
int arc_dec_step(arc_dec *d, const uint8_t *in, size_t in_len, size_t *in_used,
                 uint8_t *out, size_t out_cap, size_t *out_made, int eof);

/* Decodes a whole buffer. *out is malloc'ed, release with arc_free. [size_hint] (0 = unknown) pre-sizes the output. */
int arc_decode_all(int codec, const uint8_t *in, size_t in_len, uint64_t size_hint, uint8_t **out, size_t *out_len);
/* LZ4 block (no frame): the decoded size must be known. Returns the decoded size or a negative status. */
int64_t arc_lz4_block(const uint8_t *in, size_t in_len, uint8_t *out, size_t out_cap);
void arc_free(void *p);

/* ---- 7z ------------------------------------------------------------------------------------------------------- */

/* Random access to the archive bytes. read_at fills up to len bytes at pos and returns the count (0 at end), <0 on error. */
typedef struct arc_src {
    int64_t (*read_at)(void *ctx, uint64_t pos, void *buf, size_t len);
    void *ctx;
    uint64_t size;
} arc_src;

typedef struct arc7z arc7z;

/*
 * Opens a 7z archive: parses the headers and checks that every folder can be decoded here (LZMA, LZMA2, PPMd, BZip2,
 * Deflate, Copy, plus BCJ/ARM/ARM64/ARMT/PPC/SPARC/IA64/RISCV/Delta filters stream; BCJ2 and other multi-coder folders
 * are unpacked in memory when no larger than [mem_cap] bytes). Otherwise *err is ARC_E_UNSUPPORTED and the caller
 * should use another reader. Encrypted archives are unsupported.
 */
arc7z *arc7z_open(const arc_src *src, uint64_t mem_cap, int *err);
void arc7z_close(arc7z *a);
uint32_t arc7z_count(const arc7z *a);
/* UTF-16 name; with buf == NULL only the length (in UTF-16 units, without the terminator) is returned */
size_t arc7z_name16(const arc7z *a, uint32_t i, uint16_t *buf);
int arc7z_info(const arc7z *a, uint32_t i, uint64_t *size, int *is_dir);
/* Makes entry i the current one; its bytes are then read with arc7z_read (forward only, skipping is lazy). */
int arc7z_select(arc7z *a, uint32_t i);
/* >0 bytes, 0 at the end of the entry (its CRC is checked there), <0 an arc_status */
int64_t arc7z_read(arc7z *a, uint8_t *buf, size_t cap);

#pragma GCC visibility pop
#ifdef __cplusplus
}
#endif
#endif

# Source lists shared by build.sh and tests/run.sh (sourced, POSIX sh).
TP=$ARC_DIR/third_party

ARC_CORE="src/arc_codec.c src/c_zlib.c src/c_bz2.c src/c_lz4f.c src/c_zstd.c src/c_brotli.c src/c_lzma.c src/arc_7z.c"
ARC_JNI="src/arc_jni.c"

# vendored libraries: "<group> <files...>"; every group is compiled with its own include path and defines
SRC_ZLIB="adler32.c crc32.c inffast.c inflate.c inftrees.c zutil.c"
SRC_BZIP2="blocksort.c bzlib.c compress.c crctable.c decompress.c huffman.c randtable.c"
SRC_LZ4="lz4.c lz4frame.c lz4hc.c xxhash.c"
SRC_ZSTD="common/debug.c common/entropy_common.c common/error_private.c common/fse_decompress.c common/zstd_common.c common/xxhash.c \
decompress/huf_decompress.c decompress/zstd_ddict.c decompress/zstd_decompress.c decompress/zstd_decompress_block.c"
SRC_BROTLI="common/constants.c common/context.c common/dictionary.c common/platform.c common/shared_dictionary.c common/transform.c \
dec/bit_reader.c dec/decode.c dec/huffman.c dec/state.c"
SRC_SDK="7zAlloc.c 7zArcIn.c 7zBuf.c 7zCrc.c 7zCrcOpt.c 7zDec.c 7zStream.c Alloc.c Bcj2.c Bra.c Bra86.c BraIA64.c CpuArch.c \
Delta.c LzmaDec.c Lzma2Dec.c Ppmd7.c Ppmd7Dec.c Sha256.c Sha256Opt.c Xz.c XzCrc64.c XzCrc64Opt.c XzDec.c"

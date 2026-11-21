# libaemuarc - one native library for every archive/compression format

The importer used to unpack with Java libraries (tukaani XZ, Commons Compress bzip2/lz4/7z, Brotli dec, `GZIPInputStream`).
They are several times slower than C on a phone. `libaemuarc.so` replaces all of them behind two Java entry points:

| Java (`app.aemu.archive`) | Formats |
|---|---|
| `ArcStreams.gzip/zlib/xz/lzma/bzip2/lz4/zstd/brotli(InputStream)`, `decodeAll`, `lz4Block`, `byName` | gzip (concatenated members), zlib, raw deflate, xz (concatenated, padding, all filters, CRC32/64/SHA-256), lzma-alone, bzip2 (concatenated), LZ4 frame (concatenated, skippable), Zstandard, Brotli, raw LZ4 block |
| `SevenZReaders.open(FileChannel)` | 7z: LZMA, LZMA2, PPMd, BZip2, Deflate, Copy, BCJ/ARM/ARM64/ARMT/PPC/SPARC/IA64/RISC-V/Delta filters, **streamed** (a multi-GB solid archive is never held in memory); BCJ2 folders are unpacked in memory up to 512 MiB |

If the library cannot be loaded (JVM unit tests) or a 7z uses something it does not know (AES, other coders), the same Java
call falls back to the old libraries, so nothing is lost.

## Layout
- `include/aemuarc.h` the C API (push decoders `arc_dec_*`, 7z `arc7z_*`), `src/` the implementation, `src/arc_jni.c` the JNI glue.
- `third_party/` vendored, unmodified upstream sources (decoders only): zlib 1.3.1, bzip2 1.0.8, lz4 1.10.0, zstd 1.5.7,
  brotli 1.1.0, 7-Zip 25.01 `C/` (LZMA SDK: LZMA, LZMA2, xz, PPMd, 7z headers, filters). Licenses are next to each.
- `jni/` a copy of `jni.h` / `jni_md.h` (OpenJDK, GPLv2+CE) for building without an NDK.

## Build
```sh
sh native/archive/build.sh android   # arm64-v8a with zig cc, no NDK; writes app/src/main/jniLibs/arm64-v8a/libaemuarc.so (checked in)
sh native/archive/tests/run.sh       # host build (plain + AddressSanitizer) and the test suite
```
`build.sh android` needs only `python3 -m pip install ziglang` (like `native/hwchal/build-zig.sh`): it compiles with musl headers and links
against a stand-in `libc.so`, so the result imports only plain libc functions (memcpy, malloc, free, memset, memmove, calloc, realloc,
getauxval, bcmp) that bionic provides. 16 KiB page alignment, only `JNI_OnLoad` and `arc_*` are exported.

## Tests
`tests/run.sh` compares against the reference tools (xz, gzip, bzip2, python lz4/zstandard/brotli, and 7-Zip 25 `7zz` when
`Z7=/path/to/7zz` or it is on PATH): every codec at awkward chunk sizes (1 byte in/out ... 1 MiB), concatenated and padded streams,
truncation, corruption (never crashes, checksums catch flips), and 7z in 18 layouts (solid / non-solid / multi-block, all coders and
filters, BCJ2, empty files, unicode names, forward / reverse / partial reading, encrypted and damaged archives).

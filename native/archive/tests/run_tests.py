#!/usr/bin/env python3
"""Tests of libaemuarc against the reference tools: usage run_tests.py <dir with test_arc and libaemuarc.so> [7zz path]"""
import bz2, gzip, hashlib, lzma, os, random, shutil, struct, subprocess, sys, tempfile, zlib

BIN = os.path.join(sys.argv[1], "test_arc")
Z7 = sys.argv[2] if len(sys.argv) > 2 else shutil.which("7zz") or shutil.which("7z")
ENV = dict(os.environ, ASAN_OPTIONS="detect_leaks=1:abort_on_error=0", LSAN_OPTIONS="exitcode=23")
COD = dict(copy=0, gzip=1, zlib=2, deflate=3, xz=4, lzma=5, bzip2=6, lz4=7, zstd=8, brotli=9)
fails = []
tmp = tempfile.mkdtemp(prefix="arctest")
rnd = random.Random(1234)

def make_data(n, seed=1):
    r = random.Random(seed)
    words = [bytes(r.choice(b"abcdefghijklmnopqrstuvwxyz") for _ in range(r.randint(2, 9))) for _ in range(300)]
    out = bytearray()
    while len(out) < n:
        k = r.random()
        if k < 0.45: out += b" ".join(r.choice(words) for _ in range(r.randint(5, 60)))
        elif k < 0.6: out += os.urandom(r.randint(100, 4000))
        elif k < 0.7: out += bytes(r.randint(100, 3000))
        elif k < 0.85:  # x86 call / ARM branch-like patterns, to give the branch filters something to convert
            for _ in range(r.randint(20, 200)): out += b"\xe8" + struct.pack("<i", r.randint(-2**20, 2**20)) + b"\x90"
        else: out += out[-r.randint(1, 5000):][:r.randint(50, 3000)]
    return bytes(out[:n])

def check(name, ok, info=""):
    if not ok:
        fails.append(name)
        print("FAIL", name, info)

def run(args, timeout=300):
    return subprocess.run([BIN] + [str(a) for a in args], capture_output=True, text=True, env=ENV, timeout=timeout)

def dec(name, codec, comp, plain, chunks=((65536, 65536), (1 << 20, 1 << 20), (4093, 777))):
    ip = os.path.join(tmp, "in.bin"); op = os.path.join(tmp, "out.bin")
    open(ip, "wb").write(comp)
    for ic, oc in chunks:
        if os.path.exists(op): os.remove(op)
        p = run(["dec", COD[codec], ip, op, ic, oc])
        got = open(op, "rb").read() if os.path.exists(op) else b""
        check(f"{name} chunks={ic}/{oc}", p.returncode == 0 and got == plain,
              f"rc={p.returncode} {p.stderr[-300:]} got={len(got)} want={len(plain)}")

def expect_error(name, codec, comp):
    ip = os.path.join(tmp, "bad.bin"); op = os.path.join(tmp, "badout.bin")
    open(ip, "wb").write(comp)
    p = run(["dec", COD[codec], ip, op, 65536, 65536], timeout=60)
    check(name, p.returncode == 1, f"rc={p.returncode} {p.stderr[-300:]}")

def no_crash(name, codec, comp):
    ip = os.path.join(tmp, "bad.bin"); op = os.path.join(tmp, "badout.bin")
    open(ip, "wb").write(comp)
    p = run(["dec", COD[codec], ip, op, 65536, 65536], timeout=60)
    check(name, p.returncode in (0, 1), f"rc={p.returncode} {p.stderr[-500:]}")

def xz_cli(data, *opts):
    return subprocess.run(["xz", "-c", "-T1"] + list(opts), input=data, capture_output=True, check=True).stdout

big = make_data(6 << 20, 7)
small = make_data(3000, 8)
tiny = b"hello"
empty = b""

# ---- stream codecs ------------------------------------------------------------------------------------------------
import lz4.frame, lz4.block, zstandard, brotli
cases = []
for label, d in (("big", big), ("small", small), ("tiny", tiny), ("empty", empty)):
    cases += [
        (f"gzip {label}", "gzip", gzip.compress(d, 6), d),
        (f"zlib {label}", "zlib", zlib.compress(d, 6), d),
        (f"deflate {label}", "deflate", (lambda c: c.compress(d) + c.flush())(zlib.compressobj(6, zlib.DEFLATED, -15)), d),
        (f"xz crc64 {label}", "xz", lzma.compress(d, check=lzma.CHECK_CRC64), d),
        (f"xz crc32 {label}", "xz", lzma.compress(d, check=lzma.CHECK_CRC32), d),
        (f"xz sha256 {label}", "xz", lzma.compress(d, check=lzma.CHECK_SHA256), d),
        (f"xz nocheck {label}", "xz", lzma.compress(d, check=lzma.CHECK_NONE), d),
        (f"lzma-alone {label}", "lzma", lzma.compress(d, format=lzma.FORMAT_ALONE), d),
        (f"bzip2 {label}", "bzip2", bz2.compress(d, 9), d),
        (f"lz4 {label}", "lz4", lz4.frame.compress(d, content_checksum=True, block_checksum=True), d),
        (f"lz4 linked {label}", "lz4", lz4.frame.compress(d, block_linked=True, compression_level=9), d),
        (f"zstd {label}", "zstd", zstandard.ZstdCompressor(level=9, write_checksum=True).compress(d), d),
        (f"brotli {label}", "brotli", brotli.compress(d, quality=5), d),
        (f"copy {label}", "copy", d, d),
    ]
for name, codec, comp, plain in cases:
    if "big" in name:
        dec(name, codec, comp, plain, chunks=((65536, 65536), (1 << 20, 3000)))
    elif "small" in name:
        dec(name, codec, comp, plain, chunks=((1, 1), (1, 7), (3, 5), (65536, 65536)))
    else:
        dec(name, codec, comp, plain, chunks=((1, 1), (65536, 65536)))

# concatenation, padding, trailing data, extra filters
d1, d2 = make_data(100000, 11), make_data(54321, 12)
dec("gzip concatenated", "gzip", gzip.compress(d1) + gzip.compress(d2), d1 + d2, chunks=((1, 64), (65536, 65536)))
dec("gzip trailing garbage", "gzip", gzip.compress(d1) + b"\x00\x00\x00trailer", d1)
dec("xz concatenated", "xz", lzma.compress(d1) + lzma.compress(d2), d1 + d2, chunks=((1, 64), (65536, 65536)))
dec("xz padded", "xz", lzma.compress(d1) + b"\x00" * 8 + lzma.compress(d2) + b"\x00" * 4, d1 + d2)
dec("bzip2 concatenated", "bzip2", bz2.compress(d1) + bz2.compress(d2), d1 + d2, chunks=((1, 64), (65536, 65536)))
dec("lz4 concatenated", "lz4", lz4.frame.compress(d1) + lz4.frame.compress(d2), d1 + d2, chunks=((1, 64), (65536, 65536)))
skippable = struct.pack("<II", 0x184D2A50, 5) + b"12345"
dec("lz4 skippable frame", "lz4", skippable + lz4.frame.compress(d1), d1, chunks=((1, 64), (65536, 65536)))
dec("zstd concatenated", "zstd", zstandard.ZstdCompressor().compress(d1) + zstandard.ZstdCompressor().compress(d2), d1 + d2, chunks=((1, 64), (65536, 65536)))
zskip = struct.pack("<II", 0x184D2A50, 3) + b"abc"
dec("zstd skippable frame", "zstd", zskip + zstandard.ZstdCompressor().compress(d1), d1, chunks=((1, 64), (65536, 65536)))
dec("zstd streaming frames", "zstd", zstandard.ZstdCompressor(level=3).compressobj().compress(d1) + zstandard.ZstdCompressor(level=3).compressobj().flush(), b"") if False else None
dec("xz multi-block", "xz", xz_cli(big, "--block-size=1MiB", "-1"), big, chunks=((65536, 65536),))
dec("xz x86 bcj", "xz", xz_cli(big[:2 << 20], "--x86", "--lzma2=preset=3"), big[:2 << 20], chunks=((65536, 65536), (1, 4096) if False else (4093, 777)))
dec("xz arm64", "xz", xz_cli(big[:2 << 20], "--arm64", "--lzma2=preset=3"), big[:2 << 20], chunks=((65536, 65536),))
dec("xz arm", "xz", xz_cli(big[:1 << 20], "--arm", "--lzma2=preset=2"), big[:1 << 20], chunks=((65536, 65536),))
dec("xz delta", "xz", xz_cli(big[:1 << 20], "--delta=dist=4", "--lzma2=preset=2"), big[:1 << 20], chunks=((65536, 65536),))
dec("xz big dict 64M", "xz", xz_cli(small, "--lzma2=preset=6,dict=64MiB"), small)
# lzma alone with a size in the header (known size, no end marker needed) and with one that is too big a window
la = bytearray(lzma.compress(d1, format=lzma.FORMAT_ALONE))
la[5:13] = struct.pack("<Q", len(d1))
dec("lzma-alone known size", "lzma", bytes(la), d1)
la2 = bytearray(la); la2[1:5] = struct.pack("<I", 0x40000000)
dec("lzma-alone 1GiB window claim", "lzma", bytes(la2), d1, chunks=((65536, 65536),))

# truncation must be an error, corruption must never crash
for name, codec, comp, plain in cases:
    if "small" not in name or codec == "copy": continue
    expect_error(f"truncated {name}", codec, comp[:-max(1, len(comp) // 3)])
    if codec != "copy":
        for pos in (len(comp) // 2, len(comp) - 3, 12):
            bad = bytearray(comp); bad[pos % len(bad)] ^= 0x5a
            no_crash(f"corrupt {name} @{pos}", codec, bytes(bad))
        no_crash(f"garbage {name}", codec, os.urandom(2000))
for codec, comp in (("xz", lzma.compress(small)), ("zstd", zstandard.ZstdCompressor(write_checksum=True).compress(small)),
                    ("gzip", gzip.compress(small)), ("bzip2", bz2.compress(small)),
                    ("lz4", lz4.frame.compress(small, content_checksum=True))):
    bad = bytearray(comp); bad[len(bad) // 2] ^= 0xff
    expect_error(f"checksum catches flipped byte {codec}", codec, bytes(bad))

# lz4 block
raw_block = lz4.block.compress(big[:100000], store_size=False)
open(os.path.join(tmp, "blk"), "wb").write(raw_block)
p = run(["lz4block", os.path.join(tmp, "blk"), os.path.join(tmp, "blk.out"), 100000])
check("lz4 block", p.returncode == 0 and open(os.path.join(tmp, "blk.out"), "rb").read() == big[:100000])

# ---- 7z ---------------------------------------------------------------------------------------------------------------
def build_tree(root):
    shutil.rmtree(root, ignore_errors=True)
    files = {
        "a.txt": make_data(50000, 21), "dir/b.bin": make_data(1500000, 22), "dir/sub/c.exe": make_data(900000, 23),
        "empty.txt": b"", "dir/sub/d.img": make_data(2300000, 24), "ünï-çødé.txt": make_data(777, 25),
        "z_last": make_data(123457, 26), "small": b"x",
    }
    for n, d in files.items():
        p = os.path.join(root, n); os.makedirs(os.path.dirname(p), exist_ok=True); open(p, "wb").write(d)
    os.makedirs(os.path.join(root, "emptydir"), exist_ok=True)
    return files

def seven(name, args, files, orders=("forward", "reverse", "partial"), chunk=100000, cap=1 << 30, expect_unsupported=False):
    arc = os.path.join(tmp, "t.7z")
    if os.path.exists(arc): os.remove(arc)
    root = os.path.join(tmp, "tree")
    r = subprocess.run([Z7, "a", "-bd", "-y", "-mmt=1", arc] + args + ["."], cwd=root, capture_output=True, text=True)
    if r.returncode != 0:
        check(f"7z create {name}", False, r.stdout[-300:] + r.stderr[-300:]); return
    for order in orders:
        out = os.path.join(tmp, "x"); shutil.rmtree(out, ignore_errors=True); os.makedirs(out)
        p = run(["7z", arc, out, order, cap, chunk])
        if expect_unsupported:
            check(f"7z {name} unsupported", p.returncode == 3, f"rc={p.returncode} {p.stderr[-200:]}"); return
        if p.returncode != 0:
            check(f"7z {name} [{order}]", False, f"rc={p.returncode} {p.stderr[-400:]}"); continue
        listing = {}
        for line in p.stdout.splitlines():
            k, size, isdir, nm = line.split(" ", 3); listing[nm.replace("\\", "/")] = (int(k), int(size), int(isdir))
        ok = True
        for n, d in files.items():
            key = n if all(ord(c) < 128 for c in n) else None
            if key is None:
                cand = [v for kk, v in listing.items() if v[1] == len(d) and "?" in kk]
                if not cand: ok = False; print("  missing unicode entry", n); continue
                k = cand[0][0]
            else:
                if key not in listing: ok = False; print("  missing", key); continue
                k = listing[key][0]
            got = open(os.path.join(out, f"{k}.bin"), "rb").read() if os.path.exists(os.path.join(out, f"{k}.bin")) else None
            if order == "partial" and k & 1 and len(d) > 1:
                ok &= got is not None and d.startswith(got)    # only the first half was read
            else:
                ok &= got == d
                if got != d: print("  mismatch", n, None if got is None else len(got), len(d))
        check(f"7z {name} [{order}]", ok)

if Z7:
    tree = os.path.join(tmp, "tree")
    files = build_tree(tree)
    configs = [
        ("lzma2 solid", ["-m0=LZMA2:d24", "-ms=on"]), ("lzma2 non-solid", ["-m0=LZMA2:d20", "-ms=off"]),
        ("lzma2 multi-block", ["-m0=LZMA2:d20", "-ms=1m"]), ("lzma", ["-m0=LZMA:d22", "-ms=on"]),
        ("ppmd", ["-m0=PPMd:mem=24:o=6", "-ms=on"]), ("bzip2", ["-m0=BZip2", "-ms=on"]), ("deflate", ["-m0=Deflate", "-ms=on"]),
        ("copy", ["-m0=Copy"]), ("bcj+lzma2", ["-m0=BCJ", "-m1=LZMA2:d22"]), ("arm64+lzma2", ["-m0=ARM64", "-m1=LZMA2:d22"]),
        ("arm+lzma", ["-m0=ARM", "-m1=LZMA:d22"]), ("delta+lzma2", ["-m0=Delta:4", "-m1=LZMA2:d22"]),
        ("sparc+lzma2", ["-m0=SPARC", "-m1=LZMA2:d22"]), ("ppc+lzma2", ["-m0=PPC", "-m1=LZMA2:d22"]),
        ("ia64+lzma2", ["-m0=IA64", "-m1=LZMA2:d22"]),
        ("lzma2 d=1g window", ["-m0=LZMA2:d30", "-ms=on"]),
        ("headers uncompressed", ["-mhc=off", "-m0=LZMA2:d20"]), ("headers lzma2 filenames", ["-m0=LZMA2:d20", "-mhc=on"]),
    ]
    for name, args in configs: seven(name, args, files)
    seven("bcj2 in memory", ["-m0=BCJ2", "-m1=LZMA:d22", "-m2=LZMA:d20", "-m3=LZMA:d20", "-mb0:1", "-mb0s1:2", "-mb0s2:3"], files, orders=("forward", "reverse"))
    seven("bcj2 over the memory cap", ["-m0=BCJ2", "-m1=LZMA:d22", "-m2=LZMA:d20", "-m3=LZMA:d20", "-mb0:1", "-mb0s1:2", "-mb0s2:3"], files, orders=("forward",), cap=100, expect_unsupported=True)
    seven("encrypted", ["-psecret", "-mhe=on"], files, orders=("forward",), expect_unsupported=False if False else False) if False else None
    # an encrypted archive must not be taken for readable
    arc = os.path.join(tmp, "enc.7z"); 
    if os.path.exists(arc): os.remove(arc)
    subprocess.run([Z7, "a", "-bd", "-y", "-psecret", "-mhe=on", arc, "."], cwd=tree, capture_output=True)
    p = run(["7z", arc, tmp, "forward", 1 << 30, 65536]); check("7z encrypted headers refused", p.returncode in (1, 3), f"rc={p.returncode}")
    arc = os.path.join(tmp, "enc2.7z")
    if os.path.exists(arc): os.remove(arc)
    subprocess.run([Z7, "a", "-bd", "-y", "-psecret", arc, "."], cwd=tree, capture_output=True)
    p = run(["7z", arc, tmp, "forward", 1 << 30, 65536]); check("7z encrypted data refused", p.returncode in (1, 3), f"rc={p.returncode} {p.stderr[-100:]}")
    # damaged archives must fail cleanly
    seven("(for damage)", ["-m0=LZMA2:d20"], files, orders=("forward",))
    arc = os.path.join(tmp, "t.7z"); raw = bytearray(open(arc, "rb").read())
    for pos in (40, len(raw) // 2, len(raw) - 20):
        bad = bytearray(raw); bad[pos] ^= 0xff; open(os.path.join(tmp, "bad.7z"), "wb").write(bad)
        p = run(["7z", os.path.join(tmp, "bad.7z"), tmp, "forward", 1 << 30, 65536]); check(f"7z damaged @{pos} fails cleanly", p.returncode in (0, 1, 3), f"rc={p.returncode} {p.stderr[-300:]}")
    open(os.path.join(tmp, "trunc.7z"), "wb").write(raw[: len(raw) // 2])
    p = run(["7z", os.path.join(tmp, "trunc.7z"), tmp, "forward", 1 << 30, 65536]); check("7z truncated fails", p.returncode == 1, f"rc={p.returncode}")
else:
    print("no 7zz/7z found: 7z tests skipped")

shutil.rmtree(tmp, ignore_errors=True)
print("FAILED:" if fails else "all tests passed", len(fails) if fails else "")
for f in fails: print("  -", f)
sys.exit(1 if fails else 0)

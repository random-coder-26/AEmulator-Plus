#!/usr/bin/env python3
"""AEmulator Plus 2026-11-17: makes the bionic-built x86 Eclair gralloc (engines/kk-x86/gralloc.default.so) loadable on a glibc
Google TV firmware (Logitech Revue, glibc 2.7).  Output: engines/glibc-x86/gralloc.default.so, used together with aegr.so
(native/revue-shims/aegr_shim.c, which says why each name is replaced).

Only strings of the dynamic string table are rewritten, always to a name of the same or a shorter length (the rest is zeroed), so
nothing in the file moves:
    DT_NEEDED   libc.so               -> aegr.so
    imports     pthread_mutex_init    -> aegr_mx_init
                pthread_mutex_lock    -> aegr_mx_lock
                pthread_mutex_unlock  -> aegr_mx_unlock
                pthread_mutex_destroy -> aegr_mx_destroy
(The imports are renamed, not just provided: a symbol is looked up in the process-wide scope first, where glibc's own
pthread_mutex_* with its 24-byte mutex would win over the 4-byte lock this gralloc was built with.)

The build of 2026-11-17 (single-page fb0 -> plain ashmem buffer, AOSP link line) also lists bionic's libstdc++.so, libm.so and
libdl.so as DT_NEEDED.  A glibc firmware has libstdc++.so.6 / libm.so.6 / libdl.so.2 and none of those names, and a longer name
does not fit in place, so these three are pointed at aegr.so too (a repeated DT_NEEDED is loaded once).  The module calls
nothing of libm / libdl; the only thing it takes from libstdc++ (operator new / delete) and bionic's __page_size are defined by
aegr.so.  A file without these three entries (the earlier build) is patched as before.

usage: patch_gralloc_glibc.py <in gralloc.default.so> <out gralloc.default.so>
Idempotent: an already patched file is written unchanged.  Every name must be found exactly once (otherwise: error).
"""
import struct, sys

RENAMES = [
    (b"libc.so", b"aegr.so"),
    (b"pthread_mutex_init", b"aegr_mx_init"),
    (b"pthread_mutex_lock", b"aegr_mx_lock"),
    (b"pthread_mutex_unlock", b"aegr_mx_unlock"),
    (b"pthread_mutex_destroy", b"aegr_mx_destroy"),
]
# DT_NEEDED names a glibc firmware cannot satisfy and the module needs nothing from; present in the 2026-11-17 build only
OPTIONAL_RENAMES = [
    (b"libstdc++.so", b"aegr.so"),
    (b"libm.so", b"aegr.so"),
    (b"libdl.so", b"aegr.so"),
]


def dynstr_range(d):
    if d[:4] != b"\x7fELF" or d[4] != 1 or d[5] != 1:
        raise SystemExit("not a little-endian ELF32 file")
    if struct.unpack_from("<H", d, 18)[0] != 3:
        raise SystemExit("not an i386 file")
    phoff, = struct.unpack_from("<I", d, 0x1c)
    phentsize, phnum = struct.unpack_from("<HH", d, 0x2a)
    loads, dyn = [], None
    for i in range(phnum):
        t, off, va, _pa, fsz, msz, _fl, _al = struct.unpack_from("<8I", d, phoff + i * phentsize)
        if t == 1:
            loads.append((va, off, fsz))
        elif t == 2:
            dyn = (off, fsz)
    if dyn is None:
        raise SystemExit("no PT_DYNAMIC")
    tags = {}
    for p in range(dyn[0], dyn[0] + dyn[1], 8):
        tag, val = struct.unpack_from("<II", d, p)
        if tag == 0:
            break
        tags.setdefault(tag, val)
    strtab, strsz = tags.get(5), tags.get(10)
    if strtab is None or strsz is None:
        raise SystemExit("no DT_STRTAB / DT_STRSZ")
    for va, off, fsz in loads:
        if va <= strtab < va + fsz:
            start = strtab - va + off
            return start, start + strsz
    raise SystemExit("DT_STRTAB is outside every PT_LOAD")


def patch(d):
    d = bytearray(d)
    lo, hi = dynstr_range(d)
    s = bytes(d[lo:hi])
    for old, new in RENAMES + OPTIONAL_RENAMES:
        assert len(new) <= len(old), (old, new)
        optional = (old, new) in OPTIONAL_RENAMES
        key = b"\0" + old + b"\0"
        n = s.count(key)
        if n == 0 and (optional or s.count(b"\0" + new + b"\0") >= 1):
            continue  # done already (or an optional name this build does not have)
        if n != 1:
            raise SystemExit("%s found %d times in the dynamic string table (expected 1)" % (old.decode(), n))
        at = lo + s.index(key) + 1
        d[at:at + len(old)] = new + b"\0" * (len(old) - len(new))
    return bytes(d)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    out = patch(open(sys.argv[1], "rb").read())
    open(sys.argv[2], "wb").write(out)
    print("%s: %d bytes written" % (sys.argv[2], len(out)))

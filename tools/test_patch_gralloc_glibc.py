#!/usr/bin/env python3
"""Checks that engines/glibc-x86/gralloc.default.so is what tools/patch_gralloc_glibc.py makes of engines/kk-x86/gralloc.default.so
(so the two copies cannot drift apart), that the patch is idempotent, that only dynamic-string-table bytes differ, and that aegr.so
defines every name the patched module expects from it.
    python3 tools/test_patch_gralloc_glibc.py        (from the checkout root)
"""
import os, struct, sys, unittest
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import patch_gralloc_glibc as P

A = "app/src/main/assets/engines/"
src = open(A + "kk-x86/gralloc.default.so", "rb").read()
out = open(A + "glibc-x86/gralloc.default.so", "rb").read()
aegr = open(A + "glibc-x86/aegr.so", "rb").read()


def dyn_symbols(d):
    """(defined, undefined) names of the .dynsym of an ELF32 file, read through DT_SYMTAB / DT_STRTAB / DT_HASH or DT_GNU_HASH-less nchain"""
    phoff, = struct.unpack_from("<I", d, 0x1c)
    phentsize, phnum = struct.unpack_from("<HH", d, 0x2a)
    loads, dyn = [], None
    for i in range(phnum):
        t, off, va, _pa, fsz, msz, _fl, _al = struct.unpack_from("<8I", d, phoff + i * phentsize)
        if t == 1:
            loads.append((va, off, fsz))
        elif t == 2:
            dyn = off
    tags, p = {}, dyn
    while True:
        tag, val = struct.unpack_from("<II", d, p)
        if tag == 0:
            break
        tags.setdefault(tag, val)
        p += 8
    def at(va):
        for lv, off, fsz in loads:
            if lv <= va < lv + fsz:
                return va - lv + off
        raise AssertionError(hex(va))
    symtab, strtab = at(tags[6]), at(tags[5])
    n = struct.unpack_from("<I", d, at(tags[4]) + 4)[0]   # DT_HASH nchain = number of symbols
    defined, undefined = set(), set()
    for i in range(1, n):
        name, _v, _sz, _info, _oth, shndx = struct.unpack_from("<IIIBBH", d, symtab + i * 16)
        end = d.index(b"\0", strtab + name)
        (undefined if shndx == 0 else defined).add(d[strtab + name:end])
    return defined, undefined


def needed(d):
    lo, hi = P.dynstr_range(d)
    phoff, = struct.unpack_from("<I", d, 0x1c)
    phentsize, phnum = struct.unpack_from("<HH", d, 0x2a)
    for i in range(phnum):
        t, off, *_ = struct.unpack_from("<8I", d, phoff + i * phentsize)
        if t == 2:
            res, p = [], off
            while True:
                tag, val = struct.unpack_from("<II", d, p)
                if tag == 0:
                    return res
                if tag == 1:
                    res.append(d[lo + val:d.index(b"\0", lo + val)])
                p += 8


# what a glibc 2.7 firmware's libc.so.6 / libcutils.so / liblog.so provide to this module besides aegr.so
GLIBC = {b"snprintf", b"open", b"ioctl", b"memcpy", b"dup", b"getpid", b"mmap", b"munmap", b"memset", b"strerror", b"strcmp",
         b"calloc", b"malloc", b"free", b"close", b"getpagesize"}
CUTILS_LOG = {b"__android_log_print", b"ashmem_create_region", b"android_atomic_cmpxchg", b"android_atomic_or"}


class T(unittest.TestCase):
    def test_in_sync(self):
        self.assertEqual(P.patch(src), out)

    def test_idempotent(self):
        self.assertEqual(P.patch(out), out)

    def test_only_dynstr_changes(self):
        lo, hi = P.dynstr_range(src)
        self.assertEqual(len(src), len(out))
        self.assertEqual(P.dynstr_range(out), (lo, hi))
        diff = [i for i in range(len(src)) if src[i] != out[i]]
        self.assertTrue(diff and all(lo <= i < hi for i in diff))

    def test_names(self):
        lo, hi = P.dynstr_range(out)
        s = out[lo:hi]
        for gone in (b"libc.so", b"libstdc++.so", b"libm.so", b"libdl.so", b"pthread_mutex_init", b"pthread_mutex_lock",
                     b"pthread_mutex_unlock", b"pthread_mutex_destroy"):
            self.assertNotIn(b"\0" + gone + b"\0", s)
        for new in (b"aegr_mx_init", b"aegr_mx_lock", b"aegr_mx_unlock", b"aegr_mx_destroy"):
            self.assertEqual(s.count(b"\0" + new + b"\0"), 1)
        self.assertGreaterEqual(s.count(b"\0aegr.so\0"), 1)

    def test_needed(self):
        # bionic build: its own libc / libstdc++ / libm / libdl; glibc build: only the firmware's libcutils / liblog and aegr.so
        self.assertEqual(sorted(set(needed(out))), [b"aegr.so", b"libcutils.so", b"liblog.so"])
        for n in needed(src):
            self.assertIn(n, (b"libcutils.so", b"liblog.so", b"libstdc++.so", b"libm.so", b"libdl.so", b"libc.so"))

    def test_every_import_is_provided(self):
        """nothing the patched module imports is left for a library a glibc firmware does not have"""
        _d, undef = dyn_symbols(out)
        a_def, a_undef = dyn_symbols(aegr)
        missing = {u for u in undef if u not in a_def and u not in GLIBC and u not in CUTILS_LOG}
        self.assertEqual(missing, set(), "imports nobody provides on a glibc firmware")
        # the redirected / defined names really are aegr's
        for n in (b"_Znwj", b"_ZdlPv", b"__page_size", b"__errno", b"gettid", b"atexit"):
            self.assertIn(n, undef); self.assertIn(n, a_def)
        # aegr itself only needs glibc
        self.assertEqual(a_undef, {b"__errno_location", b"__cxa_atexit", b"malloc", b"free"})

    def test_aegr_defines_what_the_module_imports(self):
        lo, hi = P.dynstr_range(aegr)
        s = aegr[lo:hi]
        for n in (b"aegr_mx_init", b"aegr_mx_lock", b"aegr_mx_unlock", b"aegr_mx_destroy", b"__errno", b"gettid", b"atexit",
                  b"_Znwj", b"_ZdlPv", b"__page_size"):
            self.assertIn(b"\0" + n + b"\0", s)
        self.assertIn(b"\0libc.so.6\0", s)   # DT_NEEDED of the glibc imports


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Patches libqemu_x86_64.so (stock QEMU 8.2.7, see tools/qemu-zig) so that a guest mmap() with MAP_32BIT gets an address below 4 GiB.

Why: Linux/x86-64 has MAP_32BIT (0x40): "put the mapping in the first 2 GiB". Android 10's x86-64 ART depends on it. Its large-object
space (and the other low_4gb maps) are made with mmap(NULL, len, ..., MAP_32BIT | ...), because heap references are 32 bit. QEMU's
linux-user ignores the flag: the 13-entry mmap_flags_tbl (0x1046ff8, walked by target_to_host_bitmask with the count 13 that do_mmap passes) has no
0x40 entry, so the request goes to mmap_find_vma() like any other and lands at a host address such as 0x72dde95000. ART keeps only
the low 32 bits of the reference, 0xdde95000, which is not mapped: the next read of the array length ([ref + 8]) is a SIGSEGV in zygote
(KXmlParser.setInput+198, system_server, VoldStub, ... all "page aligned reference, fault at +8").
libqemu_i386.so is not touched: a 32-bit guest has no MAP_32BIT and its mappings are below 4 GiB anyway (mmap_find_vma starts at
task_unmapped_base 0x40000000 and refuses what h2g_valid() does not accept). libqemu_kk_i386.so is not touched either.

What: three changes, all inside the file's existing headroom (no address of the program moves):
  * mmap_flags_tbl entry 12 (0x10470b8) is MAP_UNINITIALIZED, which maps to host 0, i.e. it does nothing. It becomes
        { 0x40, 0x40, 0x40, 0x40 }          guest MAP_32BIT -> host flag bit 0x40 (a bit nothing on aarch64 uses): a marker
    that target_to_host_bitmask() puts into the flags do_mmap hands to target_mmap(). (A guest 0x4000000 is now ignored like any other
    unknown flag, which is what it was doing before.)
  * do_mmap() (0x11ed13c) calls target_mmap() at 0x11ed1a4 (`bl 0x11d672c`). The call goes to a stub instead. The stub
      - without the marker: `b target_mmap`, nothing changes;
      - with the marker: clears it, and for a request with a hint (addr != 0), MAP_FIXED, MAP_FIXED_NOREPLACE or length 0 goes to
        target_mmap() as well (a hint was never a MAP_32BIT question, and length 0 gets its EINVAL there);
      - otherwise it looks for room in [0x40000000, 0xffff0000) (the kernel's own window for MAP_32BIT starts at 1 GiB, too; the top is 64 KiB short
        of 4 GiB so that addr + len stays below 2^32 and checks of the form (addr + len) >> 32 == 0 pass): target_mmap(hint, len, prot, flags | MAP_FIXED_NOREPLACE, fd, off)
        at hint = cursor, cursor + step, ... where step = len rounded up to 64 KiB (a multiple of every host page size, so the hints are
        always host-page aligned). target_mmap() answers EEXIST for a range that is taken (its own page_check_range_empty(), and the host kernel's
        MAP_FIXED_NOREPLACE since reserved_va is 0 on x86-64), then the stub goes on to the next hint. Past the end it wraps
        once to 0x40000000 and stops where it started: -1 / ENOMEM. Any other error from target_mmap() is passed on as it is (-1, its errno).
        On success the cursor becomes the end of the mapping. Addresses grow upwards, so 1..2 GiB, which is what the kernel hands out, is used first.
  * the cursor is one 8-byte word in .bss, at 0x138df40, right behind the program's bss (nothing in the text refers to that page,
    this script checks it). p_memsz of the data segment and the .bss section grow by 16 bytes so that it is really part of the image, in the
    same page as before. 0 (never written) means 0x40000000. Threads race on it at worst into the same hint twice, and the
    loser gets EEXIST and moves on; a fork copies it with the address space it belongs to.

Where the stub goes: behind the text segment, in the zero padding that patch_x86_translator_process_vm_readv.py leaves (64 KiB), after
the stubs of the other scripts, like patch_x86_translator_crash_regs.py does: run the process_vm_readv script first (idempotent), the
order of the others does not matter. The text segment (p_filesz / p_memsz, the .text section size) grows by the size of the stub.
Every site is checked against the expected original words first (a file that is neither original nor completely patched is refused). Idempotent.

Usage (from the checkout root):  python3 tools/patch_x86_translator_map_32bit.py
         or, for another place:  python3 patch_x86_translator_map_32bit.py libqemu_x86_64.so
Check with:  python3 tools/test_x86_translator_map_32bit.py      (runs do_mmap + the stub of the patched file in a small A64 interpreter)
"""
import os, struct, sys

D = "app/src/main/jniLibs/arm64-v8a/"
CURSOR_BYTES = 16                    # growth of the bss: the cursor (8) rounded up

# ---- A64 encoders / a label assembler (only what the stub needs; every form below was compared with words of the original file) ------------
def bitmask(value, size):
    """(N, immr, imms) of a logical immediate"""
    value &= (1 << size) - 1
    e = size
    while e > 2 and (value & ((1 << (e // 2)) - 1)) == (value >> (e // 2)) & ((1 << (e // 2)) - 1): e //= 2
    mask = (1 << e) - 1; v = value & mask
    for rot in range(e):
        r = ((v >> rot) | (v << (e - rot))) & mask
        ones = bin(r).count("1")
        if 0 < ones < e and r == (1 << ones) - 1:
            return (1 if e == 64 else 0), (e - rot) % e, ((~(e - 1) << 1) & 0x3f | (ones - 1)) & 0x3f
    raise ValueError("%#x is not a logical immediate" % value)
def logical_imm(op, sf, rd, rn, v):
    n, immr, imms = bitmask(v, 64 if sf else 32)
    return (sf << 31) | (op << 29) | (0b100100 << 23) | (n << 22) | (immr << 16) | (imms << 10) | (rn << 5) | rd
CC = {"eq": 0, "ne": 1, "hs": 2, "lo": 3, "hi": 8, "ls": 9}
def movz(sf, rd, imm, hw=0):    return (sf << 31) | 0x52800000 | (hw << 21) | (imm << 5) | rd
def movk(sf, rd, imm, hw=0):    return (sf << 31) | 0x72800000 | (hw << 21) | (imm << 5) | rd
def mov_r(sf, rd, rm):          return (sf << 31) | 0x2a0003e0 | (rm << 16) | rd
def add_i(sf, rd, rn, imm, sh=0): return (sf << 31) | 0x11000000 | (sh << 22) | (imm << 10) | (rn << 5) | rd
def subs_i(sf, rd, rn, imm):    return (sf << 31) | 0x71000000 | (imm << 10) | (rn << 5) | rd
def adds_i(sf, rd, rn, imm):    return (sf << 31) | 0x31000000 | (imm << 10) | (rn << 5) | rd
def add_r(sf, rd, rn, rm):      return (sf << 31) | 0x0b000000 | (rm << 16) | (rn << 5) | rd
def sub_r(sf, rd, rn, rm):      return (sf << 31) | 0x4b000000 | (rm << 16) | (rn << 5) | rd
def subs_r(sf, rd, rn, rm):     return (sf << 31) | 0x6b000000 | (rm << 16) | (rn << 5) | rd
def ands_r(sf, rd, rn, rm):     return (sf << 31) | 0x6a000000 | (rm << 16) | (rn << 5) | rd
def csel(sf, rd, rn, rm, cc):   return (sf << 31) | 0x1a800000 | (rm << 16) | (CC[cc] << 12) | (rn << 5) | rd
def ldr_x(rt, rn, off=0):       return 0xf9400000 | ((off // 8) << 10) | (rn << 5) | rt
def str_x(rt, rn, off=0):       return 0xf9000000 | ((off // 8) << 10) | (rn << 5) | rt
def ldr_w(rt, rn, off=0):       return 0xb9400000 | ((off // 4) << 10) | (rn << 5) | rt
def str_w(rt, rn, off=0):       return 0xb9000000 | ((off // 4) << 10) | (rn << 5) | rt
def stp_pre(rt, rt2, rn, off):  return 0xa9800000 | (((off // 8) & 0x7f) << 15) | (rt2 << 10) | (rn << 5) | rt
def stp_off(rt, rt2, rn, off):  return 0xa9000000 | (((off // 8) & 0x7f) << 15) | (rt2 << 10) | (rn << 5) | rt
def ldp_off(rt, rt2, rn, off):  return 0xa9400000 | (((off // 8) & 0x7f) << 15) | (rt2 << 10) | (rn << 5) | rt
def ldp_post(rt, rt2, rn, off): return 0xa8c00000 | (((off // 8) & 0x7f) << 15) | (rt2 << 10) | (rn << 5) | rt
def B(f, t):                    return 0x14000000 | (((t - f) >> 2) & 0x3ffffff)
def BL(f, t):                   return 0x94000000 | (((t - f) >> 2) & 0x3ffffff)
RET = 0xd65f03c0

class Asm:
    def __init__(self): self.items = []; self.labels = {}
    def label(self, name): self.labels[name] = len(self.items)
    def i(self, text, word): self.items.append((text, lambda pc, L, w=word: w))
    def tbz(self, wt, bit, lab):    self.items.append(("tbz w%d, #%d, %s" % (wt, bit, lab), lambda pc, L: 0x36000000 | (bit << 19) | ((((L[lab] - pc) >> 2) & 0x3fff) << 5) | wt))
    def bcond(self, cc, lab):       self.items.append(("b.%s %s" % (cc, lab), lambda pc, L: 0x54000000 | ((((L[lab] - pc) >> 2) & 0x7ffff) << 5) | CC[cc]))
    def cbz(self, xt, lab, nz=False): self.items.append(("%s x%d, %s" % ("cbnz" if nz else "cbz", xt, lab), lambda pc, L: (0xb5000000 if nz else 0xb4000000) | ((((L[lab] - pc) >> 2) & 0x7ffff) << 5) | xt))
    def b(self, tgt):               self.items.append(("b %s" % (tgt if isinstance(tgt, str) else hex(tgt)), lambda pc, L: B(pc, L.get(tgt, tgt))))
    def bl(self, tgt):              self.items.append(("bl %#x" % tgt, lambda pc, L: BL(pc, tgt)))
    def adrp(self, xd, target):
        def f(pc, L):
            imm = ((target & ~0xfff) - (pc & ~0xfff)) >> 12
            return 0x90000000 | ((imm & 3) << 29) | (((imm >> 2) & 0x7ffff) << 5) | xd
        self.items.append(("adrp x%d, %#x" % (xd, target & ~0xfff), f))
    def build(self, base):
        L = {k: base + 4 * v for k, v in self.labels.items()}
        return [(fn(base + 4 * n, L), text) for n, (text, fn) in enumerate(self.items)]

# ---- the stub ---------------------------------------------------------------------------------------------------------------------------
# Entered instead of target_mmap(abi_ulong start x0, abi_ulong len x1, int prot w2, int flags w3, int fd w4, off_t offset x5), returns what it returns
# (the guest address, or -1 with errno set). x19..x28 and x29/x30 are saved; x8..x10 are scratch.
#   x19 len, w20 prot, w21 flags | MAP_FIXED_NOREPLACE, w22 fd, x23 offset, x24 hint, x25 first hint, x26 &cursor, x27 step, x28 wrapped
def build_stub(base, target_mmap, errno_location, cursor):
    A = Asm()
    A.tbz(3, 6, "plain")                                                        # no marker: not for us
    A.i("and w3, w3, #0xffffffbf", logical_imm(0, 0, 3, 3, 0xffffffbf))          # marker off from here on
    A.i("mov w9, #0x10", movz(0, 9, 0x10))
    A.i("movk w9, #0x10, lsl #16", movk(0, 9, 0x10, 1))                        # MAP_FIXED | MAP_FIXED_NOREPLACE
    A.i("tst w3, w9", ands_r(0, 31, 3, 9))
    A.bcond("ne", "plain")
    A.cbz(0, "plain", nz=True)                                                  # a hint
    A.cbz(1, "plain")                                                           # length 0
    A.i("stp x29, x30, [sp, #-96]!", stp_pre(29, 30, 31, -96))
    A.i("mov x29, sp", add_i(1, 29, 31, 0))
    A.i("stp x19, x20, [sp, #16]", stp_off(19, 20, 31, 16))
    A.i("stp x21, x22, [sp, #32]", stp_off(21, 22, 31, 32))
    A.i("stp x23, x24, [sp, #48]", stp_off(23, 24, 31, 48))
    A.i("stp x25, x26, [sp, #64]", stp_off(25, 26, 31, 64))
    A.i("stp x27, x28, [sp, #80]", stp_off(27, 28, 31, 80))
    A.i("mov x19, x1", mov_r(1, 19, 1))
    A.i("mov w20, w2", mov_r(0, 20, 2))
    A.i("orr w21, w3, #0x100000", logical_imm(1, 0, 21, 3, 0x100000))            # | MAP_FIXED_NOREPLACE
    A.i("mov w22, w4", mov_r(0, 22, 4))
    A.i("mov x23, x5", mov_r(1, 23, 5))
    A.i("mov x9, #0xbfff0000", movz(1, 9, 0xbfff, 1))                           # size of the window [0x40000000, 0xffff0000)
    A.i("cmp x1, x9", subs_r(1, 31, 1, 9))
    A.bcond("hi", "nomem")                                                      # longer than the window
    A.i("add x27, x1, #0xf000", add_i(1, 27, 1, 0xf, 1))
    A.i("add x27, x27, #0xfff", add_i(1, 27, 27, 0xfff))
    A.i("and x27, x27, #0xffffffffffff0000", logical_imm(0, 1, 27, 27, 0xffffffffffff0000))   # step
    A.adrp(26, cursor)
    A.i("add x26, x26, #%#x" % (cursor & 0xfff), add_i(1, 26, 26, cursor & 0xfff))
    A.i("ldr x24, [x26]", ldr_x(24, 26))                                        # the hint to start with
    A.i("mov x8, #0x40000000", movz(1, 8, 0x4000, 1))
    A.i("mov x10, #0xffff0000", movz(1, 10, 0xffff, 1))
    A.i("sub x10, x10, x27", sub_r(1, 10, 10, 27))                              # the last start at which the mapping still fits
    A.i("cmp x24, x8", subs_r(1, 31, 24, 8))
    A.i("csel x24, x24, x8, hs", csel(1, 24, 24, 8, "hs"))                      # 0 (never written) or below the window: the start
    A.i("cmp x24, x10", subs_r(1, 31, 24, 10))
    A.i("csel x24, x24, x8, ls", csel(1, 24, 24, 8, "ls"))                      # no longer fits: the start
    A.i("mov x25, x24", mov_r(1, 25, 24))
    A.i("mov x28, xzr", mov_r(1, 28, 31))
    A.label("loop")
    A.i("mov x0, x24", mov_r(1, 0, 24))
    A.i("mov x1, x19", mov_r(1, 1, 19))
    A.i("mov w2, w20", mov_r(0, 2, 20))
    A.i("mov w3, w21", mov_r(0, 3, 21))
    A.i("mov w4, w22", mov_r(0, 4, 22))
    A.i("mov x5, x23", mov_r(1, 5, 23))
    A.bl(target_mmap)
    A.i("cmn x0, #1", adds_i(1, 31, 0, 1))
    A.bcond("ne", "ok")
    A.bl(errno_location)
    A.i("ldr w8, [x0]", ldr_w(8, 0))
    A.i("cmp w8, #17", subs_i(0, 31, 8, 17))                                    # EEXIST: taken, try the next hint
    A.bcond("ne", "fail")
    A.i("add x24, x24, x27", add_r(1, 24, 24, 27))
    A.i("mov x10, #0xffff0000", movz(1, 10, 0xffff, 1))
    A.i("sub x10, x10, x27", sub_r(1, 10, 10, 27))
    A.i("cmp x24, x10", subs_r(1, 31, 24, 10))
    A.bcond("ls", "chk")
    A.cbz(28, "nomem", nz=True)                                                 # past the end for the second time
    A.i("mov x28, #1", movz(1, 28, 1))
    A.i("mov x24, #0x40000000", movz(1, 24, 0x4000, 1))                         # wrap
    A.label("chk")
    A.cbz(28, "loop")
    A.i("cmp x24, x25", subs_r(1, 31, 24, 25))
    A.bcond("lo", "loop")                                                       # wrapped and not back at the first hint yet
    A.label("nomem")
    A.bl(errno_location)
    A.i("mov w8, #12", movz(0, 8, 12))
    A.i("str w8, [x0]", str_w(8, 0))                                            # ENOMEM
    A.label("fail")
    A.i("mov x0, #-1", 0x92800000)
    A.b("ret")
    A.label("ok")
    A.i("add x8, x24, x27", add_r(1, 8, 24, 27))
    A.i("str x8, [x26]", str_x(8, 26))                                          # next hint = end of this mapping
    A.label("ret")
    A.i("ldp x19, x20, [sp, #16]", ldp_off(19, 20, 31, 16))
    A.i("ldp x21, x22, [sp, #32]", ldp_off(21, 22, 31, 32))
    A.i("ldp x23, x24, [sp, #48]", ldp_off(23, 24, 31, 48))
    A.i("ldp x25, x26, [sp, #64]", ldp_off(25, 26, 31, 64))
    A.i("ldp x27, x28, [sp, #80]", ldp_off(27, 28, 31, 80))
    A.i("ldp x29, x30, [sp], #96", ldp_post(29, 30, 31, 96))
    A.i("ret", RET)
    A.label("plain")
    A.b(target_mmap)
    return A.build(base)

def stub_bytes(base, target_mmap, errno_location, cursor):
    return b"".join(struct.pack("<I", w) for w, _ in build_stub(base, target_mmap, errno_location, cursor))

# ---- the sites ---------------------------------------------------------------------------------------------------------------------------
# mmap_flags_tbl, 13 entries of { target_mask, target_bits, host_mask, host_bits } (do_mmap passes the count 13 to target_to_host_bitmask)
TABLE = 0x1046ff8
TABLE_ORIG = [(0x10,) * 4, (0x20,) * 4, (0x100,) * 4, (0x800,) * 4, (0x1000,) * 4, (0x2000,) * 4, (0x4000,) * 4, (0x40000,) * 4,
              (0x20000, 0x20000, 0, 0), (0x10000,) * 4, (0x8000,) * 4, (0x100000,) * 4,
              (0x4000000, 0x4000000, 0, 0)]                                           # MAP_UNINITIALIZED: no host bit
MARKER = (0x40, 0x40, 0x40, 0x40)                                                     # entry 12 after the patch
SITES = {
    # name: dict(call = the `bl target_mmap` in do_mmap, target_mmap, errno = __errno_location, cursor, expect = (va, word, what) of the surroundings)
    "libqemu_x86_64.so": dict(
        call=0x11ed1a4, target_mmap=0x11d672c, errno=0x12e94f0, cursor=0x138df40,
        expect=[(0x11ed13c, 0xa9bc7bfd, "stp x29, x30, [sp, #-64]!   (do_mmap)"),
                (0x11ed160, 0xb0fff2c8, "adrp x8, 0x1046000"), (0x11ed164, 0x913fe108, "add x8, x8, #0xff8     (mmap_flags_tbl)"),
                (0x11ed17c, 0x528001a2, "mov w2, #0xd                 (13 entries)"),
                (0x11ed188, 0x9400194a, "bl 0x11f36b0                 (target_to_host_bitmask)"),
                (0x11ed18c, 0x2a180003, "orr w3, w0, w24"),
                (0x11ed1a8, 0xb100041f, "cmn x0, #1"), (0x11ed1b0, 0x9403f0d0, "bl 0x12e94f0                 (__errno_location)"),
                (0x11f36dc, 0xb9400c29, "ldr w9, [x1, #0xc]           (host_bits of an entry)"),
                (0x11d672c, 0xd10483ff, "sub sp, sp, #0x120           (target_mmap)"),
                (0x11d6868, 0x720c8277, "ands w23, w19, #0x100010     (MAP_FIXED | MAP_FIXED_NOREPLACE)"),
                (0x11d6a68, 0x36a00193, "tbz w19, #20, ..             (MAP_FIXED_NOREPLACE)"),
                (0x11d6dc0, 0x52800228, "mov w8, #0x11                (EEXIST)"),
                (0x12e94f0, 0xd53bd048, "mrs x8, tpidr_el0            (__errno_location)"), (0x12e94f4, 0xd1029100, "sub x0, x8, #0xa4"),
                (0x12e94f8, 0xd65f03c0, "ret")]),
}

# ---- ELF plumbing ------------------------------------------------------------------------------------------------------------------------
def phdrs(d):
    phoff = struct.unpack_from("<Q", d, 32)[0]; phsz = struct.unpack_from("<H", d, 54)[0]; phn = struct.unpack_from("<H", d, 56)[0]
    return [(phoff + i * phsz,) + struct.unpack_from("<IIQQQQQQ", d, phoff + i * phsz) for i in range(phn)]   # (pos, type, flags, off, va, pa, fsz, msz, al)

def vaddr_to_off(d, va):
    for pos, t, fl, off, vad, _, fsz, msz, al in phdrs(d):
        if t == 1 and vad <= va < vad + fsz:
            return off + va - vad
    raise SystemExit("address not in a PT_LOAD segment: %#x" % va)

def word(d, va): return struct.unpack_from("<I", d, vaddr_to_off(d, va))[0]

def table_entry(d, i): return struct.unpack_from("<4I", d, vaddr_to_off(d, TABLE + 16 * i))

def sections(d):
    shoff = struct.unpack_from("<Q", d, 40)[0]; shsz = struct.unpack_from("<H", d, 58)[0]; shn = struct.unpack_from("<H", d, 60)[0]
    for i in range(shn if shoff else 0):
        sp = shoff + i * shsz
        sty, sfl, sad, sof, ssz = struct.unpack_from("<IQQQQ", d, sp + 4)
        yield sp, sty, sfl, sad, sof, ssz

def patch(path):
    name = os.path.basename(path)
    if name == "libqemu_i386.so":
        sys.exit("%s: not needed. A 32-bit guest has no MAP_32BIT and its mmap()s are below 4 GiB anyway; the 64-bit zygote is libqemu_x86_64.so" % path)
    site = SITES.get(name)
    if site is None:
        sys.exit("%s: not libqemu_x86_64.so" % path)
    d = bytearray(open(path, "rb").read())
    if d[:4] != b"\x7fELF" or struct.unpack_from("<H", d, 18)[0] != 183:
        sys.exit("%s: not an aarch64 ELF" % path)
    call, target_mmap, errno, cursor = site["call"], site["target_mmap"], site["errno"], site["cursor"]
    for va, w, what in site["expect"]:
        assert word(d, va) == w, "%s: %#x is not `%s` (%#010x, found %#010x); this is not the %s this patch was made for" % (name, va, what, w, word(d, va), name)
    ph = phdrs(d)
    loads = [p for p in ph if p[1] == 1]
    text = [p for p in loads if p[2] & 1]
    assert len(text) == 1, "%s: expected one executable PT_LOAD" % name
    tpos, _, _, toff, tva, _, tfsz, tmsz, _ = text[0]
    assert tfsz == tmsz
    data = [p for p in loads if p[4] + p[7] in (cursor, cursor + CURSOR_BYTES)]                          # the PT_LOAD whose memory ends at the cursor (bss last)
    assert len(data) == 1, "%s: cannot find the PT_LOAD with the bss" % name
    dpos, _, _, doff, dva, _, dfsz, dmsz, _ = data[0]

    # --- state of the three sites
    entries = [table_entry(d, i) for i in range(13)]
    assert entries[:12] == TABLE_ORIG[:12], "%s: mmap_flags_tbl is not the one this patch was made for" % name
    if entries[12] == TABLE_ORIG[12]:     t_state = "orig"
    elif entries[12] == MARKER:           t_state = "done"
    else: sys.exit("%s: unexpected mmap_flags_tbl entry 12 %r" % (name, entries[12]))
    orig_call = BL(call, target_mmap)
    cw = word(d, call)
    if cw == orig_call:                   c_state = "orig"
    elif (cw & 0xfc000000) == 0x94000000: c_state = "done"
    else: sys.exit("%s: unexpected instruction %#010x at %#x" % (name, cw, call))
    if dva + dmsz == cursor:              b_state = "orig"
    elif dva + dmsz == cursor + CURSOR_BYTES: b_state = "done"
    else: sys.exit("%s: the bss ends at %#x, not where this patch expects (%#x)" % (name, dva + dmsz, cursor))
    states = [t_state, c_state, b_state]
    if all(s == "done" for s in states):
        stub_va = call + 4 * ((cw & 0x3ffffff) | (-(cw & 0x2000000) & ~0x3ffffff))
        want = stub_bytes(stub_va, target_mmap, errno, cursor)
        assert bytes(d[vaddr_to_off(d, stub_va):vaddr_to_off(d, stub_va) + len(want)]) == want, "%s: the call at %#x goes to %#x, which is not our stub" % (name, call, stub_va)
        print("%s: already patched (do_mmap's call -> stub at %#x, %d bytes)" % (name, stub_va, len(want))); return
    if any(s == "done" for s in states):
        sys.exit("%s: partly patched (table %s, call %s, bss %s), refusing to touch it" % (name, *states))

    # --- the cursor: right behind the bss, in the page the loader maps anyway; nothing in the text may refer to that page
    assert cursor % 8 == 0 and (cursor & 0xfff) + CURSOR_BYTES <= 0x1000, "%s: the cursor would leave its page" % name
    for k, (w,) in enumerate(struct.iter_unpack("<I", bytes(d[toff:toff + tfsz]))):
        if (w & 0x9f000000) == 0x90000000:                                       # adrp
            va = tva + 4 * k
            imm = ((w >> 5) & 0x7ffff) << 2 | ((w >> 29) & 3)
            imm = imm - (1 << 21) if imm >> 20 else imm
            assert (va & ~0xfff) + (imm << 12) != (cursor & ~0xfff), "%s: %#x refers to the page of the cursor (%#x)" % (name, va, cursor)

    # --- the room for the stub: zero padding behind the text segment (see patch_x86_translator_process_vm_readv.py)
    nxt = min((p for p in loads if p[4] > tva), key=lambda p: p[4])             # the segment after the text in memory
    ins = nxt[3]                                                                # its file offset
    stub = build_stub(0, target_mmap, errno, cursor)
    n = 4 * len(stub)
    stub_va = (tva + tfsz + 15) & ~15
    stub_off = toff + (stub_va - tva)
    tls_end = max([p[4] + p[7] for p in ph if p[1] == 7 and p[4] >= tva] + [0])
    assert stub_va >= tls_end, "%s: the text ends inside the PT_TLS range (run patch_x86_translator_process_vm_readv.py first)" % name
    assert stub_off + n <= ins, "%s: no room behind the text segment (run patch_x86_translator_process_vm_readv.py first, it leaves 64 KiB)" % name
    assert stub_va + n <= (nxt[4] & ~0xffff), "%s: no room for the stub below the next segment in memory" % name
    assert not any(d[toff + tfsz:stub_off + n]), "%s: the bytes behind the text segment are not padding" % name
    assert abs(stub_va - call) < (1 << 27), "%s: the stub is out of range of a bl" % name
    new_end = stub_va + n

    out = bytearray(d)
    out[stub_off:stub_off + n] = stub_bytes(stub_va, target_mmap, errno, cursor)
    struct.pack_into("<I", out, vaddr_to_off(out, call), BL(call, stub_va))      # do_mmap: bl stub
    struct.pack_into("<4I", out, vaddr_to_off(out, TABLE + 16 * 12), *MARKER)    # guest MAP_32BIT -> marker bit
    struct.pack_into("<Q", out, tpos + 32, new_end - tva)                        # text: p_filesz
    struct.pack_into("<Q", out, tpos + 40, new_end - tva)                        #       p_memsz
    struct.pack_into("<Q", out, dpos + 40, dmsz + CURSOR_BYTES)                  # data: p_memsz (bss +16)
    for sp, sty, sfl, sad, sof, ssz in sections(out):
        if sad == tva and (sfl & 4):                                             # .text covers the stub too
            struct.pack_into("<Q", out, sp + 32, new_end - tva)
        if sty == 8 and sad + ssz == cursor:                                     # .bss (NOBITS) ends at the cursor
            struct.pack_into("<Q", out, sp + 32, ssz + CURSOR_BYTES)
    tmp = path + ".tmp"
    open(tmp, "wb").write(out)
    os.chmod(tmp, os.stat(path).st_mode & 0o7777)
    os.replace(tmp, path)
    print("%s: patched: do_mmap's call at %#x -> stub at %#x (%d bytes, text segment now %#x..%#x), flag table entry 12 -> MAP_32BIT marker, cursor at %#x (bss +%d)"
          % (name, call, stub_va, n, tva, new_end, cursor, CURSOR_BYTES))

if __name__ == "__main__":
    args = sys.argv[1:] or [D + n for n in SITES]
    for a in args:
        patch(a)

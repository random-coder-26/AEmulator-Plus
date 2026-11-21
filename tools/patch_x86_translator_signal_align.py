#!/usr/bin/env python3
"""Patches libqemu_x86_64.so and libqemu_i386.so (stock QEMU 8.2.7, see tools/qemu-zig) so that guest signal handlers are entered with the
stack alignment the ABI promises.

Why: the translators build the signal frame at a 16-byte aligned address, so a handler starts with
    x86-64:  rsp % 16 == 0   (the SysV ABI wants rsp % 16 == 8 at function entry: the return address has just been pushed)
    i386:    esp % 16 == 0   (the i386 ABI wants esp % 16 == 12)
Android is built with aligned SSE stores (movaps against rbp/rsp-relative slots), so the first one in a handler (vsnprintf in bionic's
crash-signal handler, for one) takes a #GP, which QEMU reports as SIGSEGV inside the handler. The signal is blocked there, so the guest dies
with "qemu: uncaught target signal 11". libqemu_kk_i386.so is not affected (older, SENSES-patched translator) and is not touched.

What: the frame moves 8 bytes (x86-64) / 4 bytes (i386) *down*, so that frame % 16 == 8 / 12, and the FP/XSAVE area stays at the very same
absolute address (it needs 16 / 64 byte alignment itself, which is why it can't just move with the frame). Nothing is added to the stack
above the old frame; the frame simply starts 8 / 4 bytes lower and has an unused gap in front of the FP area.

  x86-64, setup_rt_frame (0x1149e40). The frame address x23 is computed three times (no FXSR / FXSR / XSAVE) and ends up as the guest RSP.
    * the three `sub x23, x8, #imm` take 8 more (0x3c0 -> 0x3c8, 0x1c0 -> 0x1c8). The no-FXSR mask goes from -8 to -16 (that path can't be
      taken on an x86-64 CPU, it is kept consistent anyway).
    * everything that is frame-relative stays as it is (pretcode, uc at +8, siginfo at +0x138, the saved registers).
    * everything that is FP-area-relative moves with +8: the fpstate address (x23+0x1c0 -> +0x1c8), the fx/xsave save call, the
      _fpx_sw_bytes at host x24+0x390.. (magic1/extended_size/xfeatures/xstate_size, -> +0x398..), the XSAVE header at x24+0x3c0 (-> +0x3c8)
      and the end-of-area magic (x9 = x24+0x3c0 -> 0x3c8).
    * `tst x23,#0xf` / `tst x23,#0x3f` (the alignment checks that send the function to its error path) test x25, the fpstate address, now.
    * `stp q0,q0,[x24,#0x3c0]` can't take 0x3c8 (q-pair offsets are multiples of 16), so x10 = x24+0x3c8 is the base: the instruction
      that kept the XSAVE size in w25 (and clobbered the fpstate address in x25) becomes `add x10, x24, #0x3c8`, the size is rebuilt
      from w28 (size + 4) right before it is stored, and x25 keeps the fpstate address for the store into uc_mcontext.fpstate (+0xe8).
    * lock_user() of the new frame gets 8 more bytes (0x3c8) so the locked range ends where it did before; the same for the lock in
      rt_sigreturn (0x114a248..), which finds the frame from rsp - 8 and the FP area from the pointer in the frame, so nothing else changes.

  i386, setup_frame (0x114b038) and setup_rt_frame (0x114b494). Same idea; there the FP area is filled by setup_sigcontext() (0x114b2a4),
  which gets the fpstate host/guest addresses as arguments (x1 / w4) and works relative to them, so only the callers change:
    * frame subtractions 0x550 -> 0x554 / 0x350 -> 0x354 (setup_frame), 0x380 -> 0x384 / 0x180 -> 0x184 (setup_rt_frame); the
      -8 masks of the no-FXSR path become -16;
    * the fpstate arguments (frame + 0x2e0 / + 0x110) -> + 0x2e4 / + 0x114, the same absolute addresses as before;
    * lock_user() sizes of the frame and of both sigreturns grow by 4.

No size, no segment and no section changes, no address of the program moves: this does not depend on the order of the other
tools/patch_*translator*.py scripts. Every site is checked against the expected original instruction first; a file that is neither
original nor already patched is refused. Idempotent.

Usage (from the checkout root):  python3 tools/patch_x86_translator_signal_align.py
         or, for one file / other place:  python3 patch_x86_translator_signal_align.py libqemu_x86_64.so|libqemu_i386.so [...]
(the file name decides which site list is used.)
"""
import os, struct, sys

D = "app/src/main/jniLibs/arm64-v8a/"

# ---- A64 encoders (only what the sites need) -------------------------------------------------------------------------------------------
def addsub(sf, sub, rd, rn, imm):   return (sf << 31) | (sub << 30) | (0b100010 << 23) | (imm << 10) | (rn << 5) | rd
def add_x(rd, rn, imm):  return addsub(1, 0, rd, rn, imm)
def sub_x(rd, rn, imm):  return addsub(1, 1, rd, rn, imm)
def add_w(rd, rn, imm):  return addsub(0, 0, rd, rn, imm)
def sub_w(rd, rn, imm):  return addsub(0, 1, rd, rn, imm)
def movz_w(rd, imm):     return 0x52800000 | (imm << 5) | rd
def str_w(rt, rn, off):  return 0xb9000000 | ((off // 4) << 10) | (rn << 5) | rt
def str_x(rt, rn, off):  return 0xf9000000 | ((off // 8) << 10) | (rn << 5) | rt
def stp_q(rt, rt2, rn, off): return 0xad000000 | ((off // 16) << 15) | (rt2 << 10) | (rn << 5) | rt
def set_rn(w, rn):       return (w & ~(0x1f << 5)) | (rn << 5)
def mask_16(w):          return w - (1 << 16) - (1 << 10)       # logical immediate of N trailing zeros -> N+1 (immr and imms drop by one)

XZR = 31

# ---- sites: (vaddr, original word, new word, original asm, new asm) -----------------------------------------------------------------------
def S(va, old, new, a_old, a_new): return (va, old, new, a_old, a_new)

X86_64 = [
    S(0x1149e94, 0x927df008,                mask_16(0x927df008),         "and x8, x0, #-8",              "and x8, x0, #-16"),          # no FXSR (unreachable)
    S(0x1149e98, sub_x(23, 8, 0x3c0),       sub_x(23, 8, 0x3c8),         "sub x23, x8, #0x3c0",          "sub x23, x8, #0x3c8"),
    S(0x1149eb0, sub_x(23, 8, 0x3c0),       sub_x(23, 8, 0x3c8),         "sub x23, x8, #0x3c0",          "sub x23, x8, #0x3c8"),       # FXSR
    S(0x1149ed8, sub_x(23, 8, 0x1c0),       sub_x(23, 8, 0x1c8),         "sub x23, x8, #0x1c0",          "sub x23, x8, #0x1c8"),       # XSAVE
    S(0x1149ef0, movz_w(2, 0x3c0),          movz_w(2, 0x3c8),            "mov w2, #0x3c0",               "mov w2, #0x3c8"),            # lock_user size
    S(0x1149ff8, add_x(25, 23, 0x1c0),      add_x(25, 23, 0x1c8),        "add x25, x23, #0x1c0",         "add x25, x23, #0x1c8"),      # x25 = fpstate
    S(0x114a000, 0xf2400eff,                set_rn(0xf2400eff, 25), "tst x23, #0xf", "tst x25, #0xf"),
    S(0x114a008, add_x(1, 23, 0x1c0),       add_x(1, 23, 0x1c8),         "add x1, x23, #0x1c0",          "add x1, x23, #0x1c8"),
    S(0x114a014, str_w(XZR, 24, 0x390),     str_w(XZR, 24, 0x398),       "str wzr, [x24, #0x390]",       "str wzr, [x24, #0x398]"),
    S(0x114a028, 0xf24016ff,                set_rn(0xf24016ff, 25),      "tst x23, #0x3f",               "tst x25, #0x3f"),
    S(0x114a034, 0x2a0003f9,                add_x(10, 24, 0x3c8),        "mov w25, w0",                  "add x10, x24, #0x3c8"),
    S(0x114a040, add_x(1, 23, 0x1c0),       add_x(1, 23, 0x1c8),         "add x1, x23, #0x1c0",          "add x1, x23, #0x1c8"),
    S(0x114a048, stp_q(0, 0, 24, 0x3c0),    stp_q(0, 0, 10, 0),          "stp q0, q0, [x24, #0x3c0]",    "stp q0, q0, [x10]"),
    S(0x114a04c, stp_q(0, 0, 24, 0x3e0),    stp_q(0, 0, 10, 0x20),       "stp q0, q0, [x24, #0x3e0]",    "stp q0, q0, [x10, #0x20]"),
    S(0x114a058, str_w(28, 24, 0x394),      str_w(28, 24, 0x39c),        "str w28, [x24, #0x394]",       "str w28, [x24, #0x39c]"),
    S(0x114a064, str_w(9, 24, 0x390),       str_w(9, 24, 0x398),         "str w9, [x24, #0x390]",        "str w9, [x24, #0x398]"),
    S(0x114a06c, str_w(25, 24, 0x3a0),      sub_w(10, 28, 4),            "str w25, [x24, #0x3a0]",       "sub w10, w28, #4"),          # w10 = XSAVE size
    S(0x114a070, add_x(25, 23, 0x1c0),      str_w(10, 24, 0x3a8),        "add x25, x23, #0x1c0",         "str w10, [x24, #0x3a8]"),    # x25 stays the fpstate address
    S(0x114a074, str_x(9, 24, 0x398),       str_x(9, 24, 0x3a0),         "str x9, [x24, #0x398]",        "str x9, [x24, #0x3a0]"),
    S(0x114a078, add_x(9, 24, 0x3c0),       add_x(9, 24, 0x3c8),         "add x9, x24, #0x3c0",          "add x9, x24, #0x3c8"),
    S(0x114a278, movz_w(2, 0x3c0),          movz_w(2, 0x3c8),            "mov w2, #0x3c0",               "mov w2, #0x3c8"),            # rt_sigreturn lock size
]

I386 = [
    # setup_frame
    S(0x114b09c, 0x121d72e8,                mask_16(0x121d72e8),         "and w8, w23, #-8",             "and w8, w23, #-16"),
    S(0x114b0a0, sub_w(23, 8, 0x550),       sub_w(23, 8, 0x554),         "sub w23, w8, #0x550",          "sub w23, w8, #0x554"),
    S(0x114b0cc, sub_w(23, 8, 0x550),       sub_w(23, 8, 0x554),         "sub w23, w8, #0x550",          "sub w23, w8, #0x554"),
    S(0x114b0ec, sub_w(23, 8, 0x350),       sub_w(23, 8, 0x354),         "sub w23, w8, #0x350",          "sub w23, w8, #0x354"),
    S(0x114b104, movz_w(2, 0x550),          movz_w(2, 0x554),            "mov w2, #0x550",               "mov w2, #0x554"),
    S(0x114b124, add_x(1, 24, 0x2e0),       add_x(1, 24, 0x2e4),         "add x1, x24, #0x2e0",          "add x1, x24, #0x2e4"),
    S(0x114b128, add_w(4, 23, 0x2e0),       add_w(4, 23, 0x2e4),         "add w4, w23, #0x2e0",          "add w4, w23, #0x2e4"),
    S(0x114b79c, movz_w(2, 0x550),          movz_w(2, 0x554),            "mov w2, #0x550",               "mov w2, #0x554"),            # sigreturn
    # setup_rt_frame
    S(0x114b504, 0x121d7308,                mask_16(0x121d7308),         "and w8, w24, #-8",             "and w8, w24, #-16"),
    S(0x114b508, sub_w(24, 8, 0x380),       sub_w(24, 8, 0x384),         "sub w24, w8, #0x380",          "sub w24, w8, #0x384"),
    S(0x114b534, sub_w(24, 8, 0x380),       sub_w(24, 8, 0x384),         "sub w24, w8, #0x380",          "sub w24, w8, #0x384"),
    S(0x114b554, sub_w(24, 8, 0x180),       sub_w(24, 8, 0x184),         "sub w24, w8, #0x180",          "sub w24, w8, #0x184"),
    S(0x114b56c, movz_w(2, 0x380),          movz_w(2, 0x384),            "mov w2, #0x380",               "mov w2, #0x384"),
    S(0x114b5c4, add_x(1, 25, 0x110),       add_x(1, 25, 0x114),         "add x1, x25, #0x110",          "add x1, x25, #0x114"),
    S(0x114b5c8, add_w(4, 24, 0x110),       add_w(4, 24, 0x114),         "add w4, w24, #0x110",          "add w4, w24, #0x114"),
    S(0x114bac4, movz_w(2, 0x380),          movz_w(2, 0x384),            "mov w2, #0x380",               "mov w2, #0x384"),            # rt_sigreturn
]

SITES = {"libqemu_x86_64.so": X86_64, "libqemu_i386.so": I386}

# ---- ELF plumbing ----------------------------------------------------------------------------------------------------------------------
def phdrs(d):
    phoff = struct.unpack_from("<Q", d, 32)[0]; phsz = struct.unpack_from("<H", d, 54)[0]; phn = struct.unpack_from("<H", d, 56)[0]
    return [(phoff + i * phsz,) + struct.unpack_from("<IIQQQQQQ", d, phoff + i * phsz) for i in range(phn)]   # (pos, type, flags, off, va, pa, fsz, msz, al)

def vaddr_to_off(d, va):
    for pos, t, fl, off, vad, _, fsz, msz, al in phdrs(d):
        if t == 1 and fl & 1 and vad <= va < vad + fsz:
            return off + va - vad
    sys.exit("address not in an executable PT_LOAD segment: %#x" % va)

def patch(path):
    name = os.path.basename(path)
    sites = SITES.get(name)
    if sites is None:
        sys.exit("%s: not libqemu_x86_64.so or libqemu_i386.so" % path)
    d = bytearray(open(path, "rb").read())
    if d[:4] != b"\x7fELF" or struct.unpack_from("<H", d, 18)[0] != 183:
        sys.exit("%s: not an aarch64 ELF" % path)
    state = []
    for va, old, new, a_old, a_new in sites:
        off = vaddr_to_off(d, va)
        cur = struct.unpack_from("<I", d, off)[0]
        if cur == old:       state.append("orig")
        elif cur == new:     state.append("done")
        else:
            sys.exit("%s: unexpected instruction %#010x at %#x (expected `%s` = %#010x or the patched `%s` = %#010x);\n"
                     "this is not the %s this patch was made for" % (path, cur, va, a_old, old, a_new, new, name))
    if all(s == "done" for s in state):
        print("%s: already patched" % path); return
    if any(s == "done" for s in state):
        sys.exit("%s: partly patched (%d of %d sites), refusing to touch it" % (path, state.count("done"), len(state)))
    for va, old, new, a_old, a_new in sites:
        struct.pack_into("<I", d, vaddr_to_off(d, va), new)
    tmp = path + ".tmp"
    open(tmp, "wb").write(d)
    os.chmod(tmp, os.stat(path).st_mode & 0o7777)
    os.replace(tmp, path)
    print("%s: patched (%d instructions)" % (path, len(sites)))

if __name__ == "__main__":
    args = sys.argv[1:] or [D + n for n in SITES]
    for a in args:
        patch(a)

"""Patches libqemu_x86_64.so (stock QEMU 8.2.7) so the guest-memory walk at 0x11eaf58 stops instead of dereferencing NULL.

Why: system_server of the Android 10 x86-64 guest killed the translator ("QEMU internal SIGSEGV {code=MAPERR, addr=0x20}", then
"fatal signal 11 in app_process64 [system_server]", zygote exit 137). The fault is at 0x11eafe4: the loop body calls
0x1257f9c(root = [x20,#8], start, end) (an interval-tree lookup of the guest's page-flag nodes), gets NULL because no region covers the
range and loads node->last ([x0,#0x20]). The function is the one with the x86-64 vsyscall page special case (start == 0xffffffffff600000).
With no node the walk is finished: it now takes the function's own "return 0" exit at 0x11eafa0.

What: 0x11eafe4 `ldr x26,[x0,#0x20]` becomes `b T`; at T (zero padding left by patch_x86_translator_process_vm_readv.py, in the text segment):
    T+0   cbnz x0, T+8              ; (cbz reaches only +-1 MiB, the exit is further away)
    T+4   b    0x11eafa0            ; no node: done
    T+8   ldr  x26,[x0,#0x20]       ; the original instruction
    T+12  b    0x11eafe8
Run from the checkout root, after the other tools/patch_*translator*.py scripts; every site is checked first. Idempotent.
"""
import struct
P = "app/src/main/jniLibs/arm64-v8a/libqemu_x86_64.so"
SITE, CALL, DONE, T = 0x11eafe4, 0x11eafe0, 0x11eafa0, 0x132c840
LDR = 0xf9401000 | 26                       # ldr x26, [x0, #0x20]

def vaddr_to_off(d, va):
    phoff = struct.unpack_from("<Q", d, 32)[0]; phsz = struct.unpack_from("<H", d, 54)[0]; phn = struct.unpack_from("<H", d, 56)[0]
    for i in range(phn):
        t, fl, off, vad, _, filesz = struct.unpack_from("<IIQQQQ", d, phoff + i * phsz)
        if t == 1 and (fl & 1) and vad <= va < vad + filesz:      # PT_LOAD, executable
            return off + va - vad
    raise SystemExit("address not in an executable PT_LOAD segment: %#x" % va)

def B(f, t):   return 0x14000000 | (((t - f) // 4) & 0x3ffffff)
def CBNZ(f, t):
    assert abs(t - f) < (1 << 20)
    return 0xb5000000 | ((((t - f) // 4) & 0x7ffff) << 5)       # cbnz x0, t

d = bytearray(open(P, "rb").read())
o, ot = vaddr_to_off(d, SITE), vaddr_to_off(d, T)
cur = struct.unpack_from("<I", d, o)[0]
if cur == B(SITE, T):
    print("already patched"); raise SystemExit
bl = struct.unpack_from("<I", d, vaddr_to_off(d, CALL))[0]
assert (bl & 0xfc000000) == 0x94000000, "no bl at %#x" % CALL
assert CALL + (((bl & 0x3ffffff) ^ 0x2000000) - 0x2000000) * 4 == 0x1257f9c, "bl at %#x does not call 0x1257f9c" % CALL
assert cur == LDR, "expected ldr x26,[x0,#0x20] at %#x, found %#x" % (SITE, cur)
assert struct.unpack_from("<4I", d, ot) == (0, 0, 0, 0), "trampoline space at %#x is not free" % T
struct.pack_into("<4I", d, ot, CBNZ(T, T + 8), B(T + 4, DONE), LDR, B(T + 12, SITE + 4))
struct.pack_into("<I", d, o, B(SITE, T))
open(P, "wb").write(d)
print("patched: NULL node at %#x now ends the walk (trampoline at %#x)" % (SITE, T))

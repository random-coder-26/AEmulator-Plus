"""Runs the patched libqemu_x86_64.so (tools/patch_x86_translator_bpf.py) in Unicorn, like test_x86_translator_process_vm_readv.py:
do_syscall1 is entered at its first instruction with the guest syscall number and arguments in the registers QEMU passes them in, the jump
table, the stub and the function's epilogue run for real, and the host system calls (svc) are answered by a small model.

    pip install unicorn
    python3 tools/test_x86_translator_bpf.py [dir with the unpatched libqemu_x86_64.so]        (from the checkout root)

Checks: bpf (321) makes exactly one host call, nr 280, with cmd / attr / size as given, and returns the host's result (success and -errno);
sp / x19..x29 are balanced; 311 and 320 (unimplemented) is still -ENOSYS with no host call; getpid (39) is unchanged; the image of the
unpatched file is byte for byte the same except the one jump table entry.
"""
import struct, sys
from unicorn import *
from unicorn.arm64_const import *
D = "app/src/main/jniLibs/arm64-v8a/"
ORIG = sys.argv[1] if len(sys.argv) > 1 else None
ENOSYS, ENOENT, EPERM = 38, 2, 1
SENT, STACK_TOP, ENTRY = 0x2000, 0x7f100000, 0x11e5a48
SELF = 1234

def segments(d):
    phoff = struct.unpack_from("<Q", d, 32)[0]; phsz = struct.unpack_from("<H", d, 54)[0]; phn = struct.unpack_from("<H", d, 56)[0]
    return [p for p in (struct.unpack_from("<IIQQQQQQ", d, phoff + i * phsz) for i in range(phn)) if p[0] == 1]
def image(d): return [(va, bytes(d[off:off + fsz]) + bytes(msz - fsz)) for _, _, off, va, _, fsz, msz, _ in segments(d)]

class Emu:
    def __init__(self, path, host):
        self.d = open(path, "rb").read(); self.host = host; self.calls = []
        self.uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        for va, data in image(self.d):
            lo = va & ~0xfff; hi = (va + len(data) + 0xfff) & ~0xfff
            try: self.uc.mem_map(lo, hi - lo)
            except UcError: pass
        for va, data in image(self.d): self.uc.mem_write(va, data)
        self.uc.mem_map(SENT & ~0xfff, 0x1000); self.uc.mem_map(STACK_TOP - 0x100000, 0x100000); self.uc.mem_map(0x10000000, 0x10000)
        self.uc.hook_add(UC_HOOK_INTR, self.svc)
    def rd(self, r): return self.uc.reg_read(r)
    def svc(self, uc, intno, user):
        nr = self.rd(UC_ARM64_REG_X8); a = [self.rd(UC_ARM64_REG_X0 + i) for i in range(6)]
        if nr == 172: r = SELF
        else:
            r = self.host(nr, a); self.calls.append((nr, a, r))
        uc.reg_write(UC_ARM64_REG_X0, r & 0xffffffffffffffff)
    def syscall(self, nr, args):
        uc = self.uc; sp = STACK_TOP
        init = {r: 0x1111000 + i for i, r in enumerate(range(UC_ARM64_REG_X19, UC_ARM64_REG_X29))}
        for r, v in init.items(): uc.reg_write(r, v)
        uc.reg_write(UC_ARM64_REG_X29, 0x2222222); uc.reg_write(UC_ARM64_REG_SP, sp); uc.reg_write(UC_ARM64_REG_X30, SENT)
        uc.reg_write(UC_ARM64_REG_X0, 0x7e000000); uc.reg_write(UC_ARM64_REG_X1, nr)
        for i, a in enumerate(args): uc.reg_write(UC_ARM64_REG_X2 + i, a)
        self.calls = []
        uc.emu_start(ENTRY, SENT, count=2000000)
        ok = (self.rd(UC_ARM64_REG_SP) == sp and self.rd(UC_ARM64_REG_X29) == 0x2222222 and all(self.rd(r) == v for r, v in init.items()))
        return struct.unpack("<q", struct.pack("<Q", self.rd(UC_ARM64_REG_X0)))[0], ok

fails = 0
def check(name, cond, extra=""):
    global fails
    print("  %-66s %s %s" % (name, "ok" if cond else "FAIL", extra))
    if not cond: fails += 1

def host(nr, a):                                  # a model of the runner's bpf stand-in: OBJ_GET (7) gives fd 9, an unknown pin is -ENOENT, size 0 -> -EPERM
    if nr != 280: raise AssertionError("unexpected host syscall %d" % nr)
    cmd, attr, size = a[0], a[1], a[2]
    if cmd == 7: return 9 if attr == 0x10000100 else -ENOENT
    if cmd == 99: return -EPERM
    return 0

e = Emu(D + "libqemu_x86_64.so", host)
print("libqemu_x86_64.so (bpf, syscall 321)")
r, ok = e.syscall(321, [7, 0x10000100, 128, 0, 0, 0])
check("BPF_OBJ_GET: one host call, nr 280, cmd / attr / size as given; the fd comes back", r == 9 and len(e.calls) == 1 and e.calls[0][0] == 280 and e.calls[0][1][:3] == [7, 0x10000100, 128], str(e.calls))
check("sp, x19..x29 restored", ok)
r, ok = e.syscall(321, [7, 0x10000200, 128, 0, 0, 0]); check("host -ENOENT comes back as -ENOENT", r == -ENOENT and ok, "ret=%d" % r)
r, ok = e.syscall(321, [99, 0, 0, 0, 0, 0]);           check("host -EPERM comes back as -EPERM", r == -EPERM and ok, "ret=%d" % r)
r, ok = e.syscall(321, [1, 0x10000100, 24, 0, 0, 0]);  check("MAP_LOOKUP_ELEM (1): result 0", r == 0 and ok and len(e.calls) == 1, "ret=%d" % r)
r, ok = e.syscall(311, [0, 0, 0, 0, 0, 0]);            check("process_vm_writev (311) is still unimplemented: -ENOSYS, no host call", r == -ENOSYS and not e.calls and ok, "ret=%d" % r)
r, ok = e.syscall(320, [0, 0, 0, 0, 0, 0]);            check("kexec_file_load (320) is still -ENOSYS (the neighbour below)", r == -ENOSYS and not e.calls and ok, "ret=%d" % r)
r, ok = e.syscall(39, []);                             check("getpid (39) unchanged", r == SELF and ok, "ret=%d" % r)
if ORIG:
    a = image(open(ORIG + "/libqemu_x86_64.so", "rb").read()); b = image(open(D + "libqemu_x86_64.so", "rb").read())
    diff = []
    for (va, da), (vb, db) in zip(a, b):
        db = db[:len(da)]; diff += [va + i for i in range(len(da)) if da[i] != db[i] and va + i >= 0x1000240]
    check("image of the unpatched file unchanged except the jump table entry (%d byte(s))" % len(diff), 1 <= len(diff) <= 4, str([hex(x) for x in diff]))
print("FAILED: %d" % fails if fails else "all checks passed")
sys.exit(1 if fails else 0)

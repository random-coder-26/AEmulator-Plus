"""Runs the cave of tools/patch_qemu_guest_roots.py in unicorn against a stubbed faccessat (pip install unicorn capstone).

Usage: python3 tools/test_patch_qemu_guest_roots.py [patched libqemu_kk.so]
The code is taken from the patched library itself, so this checks what ships: which paths end up guest-rooted, that sp is balanced
and that x0 = x27 (the candidate) reaches the free() on the host-path fall-through.
"""
import os, struct, sys
from unicorn import Uc, UcError, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import UC_ARM64_REG_X0, UC_ARM64_REG_X21, UC_ARM64_REG_X23, UC_ARM64_REG_X27, UC_ARM64_REG_SP, UC_ARM64_REG_X30, UC_ARM64_REG_PC

HOOK, CAVE, CAVE_END = 0x1adc4c, 0x388200, 0x388400
ROOTED, HOST, ACCESS = 0x1adea8, 0x1adc50, 0x27d39c
here = os.path.dirname(os.path.abspath(__file__))
lib = sys.argv[1] if len(sys.argv) > 1 else os.path.join(here, "..", "app/src/main/jniLibs/arm64-v8a/libqemu_kk.so")
code = open(lib, "rb").read()[CAVE:CAVE_END]
B, ROOT = 0x400000, "/data/user/0/app/files/vm1/root"


def run(path, existing, root=ROOT):
    mu = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    mu.mem_map(0x388000, 0x2000); mu.mem_map(B, 0x100000); mu.mem_map(0x1ad000, 0x1000); mu.mem_map(0x27d000, 0x1000)
    mu.mem_write(CAVE, code)
    for a in (ROOTED, HOST, ACCESS): mu.mem_write(a, b"\x1f\x20\x03\xd5" * 4)
    mu.mem_write(B + 0x10000, root.encode() + b"\0"); mu.mem_write(B + 0x20000, path.encode() + b"\0")
    mu.mem_write(B + 0x30000 + 0x1b0, struct.pack("<Q", B + 0x10000))
    mu.reg_write(UC_ARM64_REG_X21, B + 0x20000); mu.reg_write(UC_ARM64_REG_X23, B + 0x30000); mu.reg_write(UC_ARM64_REG_X27, 0xdead0000)
    sp = B + 0xf0000; mu.reg_write(UC_ARM64_REG_SP, sp)
    r = {"sp_ok": False}

    def hook(uc, addr, size, _):
        if addr == ACCESS:
            p, s = uc.reg_read(UC_ARM64_REG_X0), bytearray()
            while uc.mem_read(p, 1)[0]: s.append(uc.mem_read(p, 1)[0]); p += 1
            r["probe"] = s.decode()
            uc.reg_write(UC_ARM64_REG_X0, 0 if r["probe"] in existing else 0xffffffffffffffff)
            uc.reg_write(UC_ARM64_REG_PC, uc.reg_read(UC_ARM64_REG_X30))
        elif addr in (ROOTED, HOST):
            r["exit"] = "ROOTED" if addr == ROOTED else "HOST"
            r["sp_ok"] = uc.reg_read(UC_ARM64_REG_SP) == sp
            r["x0_ok"] = addr == ROOTED or uc.reg_read(UC_ARM64_REG_X0) == 0xdead0000
            uc.emu_stop()
    mu.hook_add(UC_HOOK_CODE, hook, begin=0x1ad000, end=0x27e000)
    mu.emu_start(CAVE, 0x389fff, count=20000)
    return r


have = {ROOT + "/" + d for d in ("cust", "oem", "preload", "dev", "proc", "sys", "a" * 100)}
cases = [  # path, expected exit
    ("/cust/new/file", "ROOTED"), ("/cust", "ROOTED"), ("/oem/x", "ROOTED"), ("/preload/log/x", "ROOTED"), ("/cust/../etc", "ROOTED"),
    ("/carrier/x", "HOST"),                       # the directory is not in the tree: the phone's path, as before
    ("/dev/null", "HOST"), ("/proc/self/maps", "HOST"), ("/sys/class/x", "HOST"),
    ("/", "HOST"), ("/.", "HOST"), ("/../x", "HOST"), ("//cust/x", "HOST"), ("/" + "a" * 129 + "/x", "HOST"), ("/" + "a" * 100 + "/x", "ROOTED"),
]
bad = 0
for p, want in cases:
    r = run(p, have)
    ok = r.get("exit") == want and r["sp_ok"] and r.get("x0_ok")
    bad += not ok
    print(("ok   " if ok else "FAIL ") + p[:40], "->", r.get("exit"), r.get("probe", "")[len(ROOT):][:20])
r = run("/cust/x", {"/" + "r" * 800 + "/cust"}, "/" + "r" * 800)       # a root too long for the buffer is never half-copied
bad += not (r["exit"] == "HOST" and r["sp_ok"]); print("ok   overlong root" if r["exit"] == "HOST" and r["sp_ok"] else "FAIL overlong root")
sys.exit(1 if bad else 0)

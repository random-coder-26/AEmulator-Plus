#!/usr/bin/env python3
"""
Make the undefined C++ typeinfo references (_ZTI*) of a 32-bit ARM shared object weak.

Why (AEmulator Plus, 2026-11-14): the 32-bit audio HAL 2.0 set in app/src/main/assets/engines/common/audio32 was built with RTTI,
so its derived classes' typeinfo point at the typeinfo of the HIDL interfaces (android::hardware::audio::V2_0::IDevice, ...).
The HIDL libraries of an Android 10+ firmware (android.hardware.audio@2.0.so in the VNDK) are built without RTTI: they export
the vtables but no _ZTI symbols. The linker then refuses the whole library:
    dlopen failed: cannot locate symbol "_ZTIN7android8hardware5audio4V2_07IDeviceE" referenced by ".../android.hardware.audio@2.0-impl.so"
    audiohalservice: Could not register Audio Core API   (SIGABRT, exit 134)
Nothing in the HAL does a dynamic_cast / typeid / throw on these types, so a weak reference (resolved to 0 when no library
defines it, and to the real typeinfo when one does) is all it needs. Only st_info of the dynamic symbols changes: no size, offset
or hash changes. Idempotent.

usage: weaken_typeinfo.py FILE.so [FILE.so ...]
"""
import struct, sys

STB_GLOBAL, STB_WEAK = 1, 2
SHT_SYMTAB, SHT_DYNSYM = 2, 11


def patch(path):
    data = bytearray(open(path, "rb").read())
    if data[:4] != b"\x7fELF" or data[4] != 1 or data[5] != 1:
        raise SystemExit(f"{path}: not a little-endian ELF32")
    shoff, = struct.unpack_from("<I", data, 0x20)
    shentsize, shnum = struct.unpack_from("<HH", data, 0x2E)
    sections = [struct.unpack_from("<IIIIIIIIII", data, shoff + i * shentsize) for i in range(shnum)]
    changed = []
    for (_, typ, _, _, off, size, link, _, _, entsize) in sections:
        if typ not in (SHT_DYNSYM, SHT_SYMTAB):
            continue
        stroff = sections[link][4]
        for i in range(size // entsize):
            at = off + i * entsize
            name_off, _, _, info, _, shndx = struct.unpack_from("<IIIBBH", data, at)
            if shndx != 0 or (info >> 4) != STB_GLOBAL:
                continue
            end = data.index(0, stroff + name_off)
            name = bytes(data[stroff + name_off:end]).decode()
            if name.startswith("_ZTI"):
                data[at + 12] = (STB_WEAK << 4) | (info & 0xF)
                changed.append(name)
    open(path, "wb").write(data)
    print(f"{path}: {len(changed)} undefined typeinfo reference(s) made weak")
    for n in changed:
        print("   ", n)


if __name__ == "__main__":
    for p in sys.argv[1:]:
        patch(p)

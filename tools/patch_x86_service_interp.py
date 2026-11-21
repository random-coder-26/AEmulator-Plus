"""Gives the engine's x86-64 HAL services (engines/n64-30/x86_64: allocator@2.0-service, composer@2.1-service) the PT_INTERP they lack.

Why: both were linked without a program interpreter. QEMU then loads the PIE and jumps to its entry with no dynamic linker having run: no
relocation is applied, no DT_NEEDED library is loaded, and the first call through the PLT (_start_main -> __libc_init) goes to the
unrelocated lazy stub, an address that is not mapped:
  hwcomposer-2-1.log   aemu-guest: 000000000000000b ... rip=0000000000002486   (the composer service's `push $4` of the __libc_init PLT slot)
  gralloc-2-0.log      aemu-guest: 000000000000000b ... rip=0000000000001936   (the allocator service's `push $0` of the __libc_init PLT slot)
Both die with SIGSEGV (exit 139) a few ms after they start, restarted until the restart limit; nothing registers IComposer / IAllocator,
SurfaceFlinger aborts ("failed to get hwcomposer service") and fb0 stays empty. The audio service (same method, same crt) has its
PT_INTERP and starts.

What: the file has no spare room for one more program header (the table ends where the first section starts), so a header the service does
not need to run is given up for the PT_INTERP: PT_GNU_RELRO if there is one (it only makes the linker mprotect the GOT read-only after
relocation; the composer service has it), else PT_GNU_EH_FRAME (the unwind index for crash backtraces; the allocator service, linked
-z norelro, has it). "/system/bin/linker64\\0" is appended to the end
of the file (the loader reads PT_INTERP by file offset; the address fields point at the same bytes and are never mapped), and the entry
goes right behind PT_PHDR, ahead of the PT_LOADs, where a linker puts it.

Run from the checkout root: python3 tools/patch_x86_service_interp.py [file ...]  (default: the two services above). Idempotent: a file that
has a PT_INTERP is left alone. The build scripts (native/x86_64/composer/build-composer.sh, gralloc2/build-gralloc2.sh) check for the
header after linking, so a build that loses it fails instead of shipping.
"""
import struct
import sys

PT_PHDR, PT_INTERP, PT_GNU_EH_FRAME, PT_GNU_RELRO = 6, 3, 0x6474e550, 0x6474e552
INTERP = b"/system/bin/linker64\0"
D = "app/src/main/assets/engines/n64-30/x86_64/"
DEFAULT = [D + "vendor#bin#hw#android.hardware.graphics.allocator@2.0-service",
           D + "vendor#bin#hw#android.hardware.graphics.composer@2.1-service"]


def patch(path):
    d = bytearray(open(path, "rb").read())
    if d[:4] != b"\x7fELF" or d[4] != 2 or d[5] != 1 or struct.unpack_from("<H", d, 18)[0] != 62:
        raise SystemExit("%s: not an x86-64 ELF" % path)
    phoff = struct.unpack_from("<Q", d, 32)[0]
    phsz, phn = struct.unpack_from("<HH", d, 54)
    if phsz != 56:
        raise SystemExit("%s: unexpected program header size %d" % (path, phsz))
    ph = [bytes(d[phoff + i * phsz: phoff + (i + 1) * phsz]) for i in range(phn)]
    types = [struct.unpack_from("<I", p, 0)[0] for p in ph]
    if PT_INTERP in types:
        print("%s: has PT_INTERP, unchanged" % path)
        return False
    give = next((t for t in (PT_GNU_RELRO, PT_GNU_EH_FRAME) if t in types), None)
    if give is None:
        raise SystemExit("%s: no PT_GNU_RELRO / PT_GNU_EH_FRAME to give up for the PT_INTERP" % path)
    off = len(d)
    d += INTERP
    # p_type, p_flags(R), p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_align
    interp = struct.pack("<IIQQQQQQ", PT_INTERP, 4, off, off, off, len(INTERP), len(INTERP), 1)
    rest = [p for p, t in zip(ph, types) if t != give]
    new = []
    placed = False
    for p in rest:
        new.append(p)
        if not placed and struct.unpack_from("<I", p, 0)[0] == PT_PHDR:
            new.append(interp)
            placed = True
    if not placed:
        new.insert(0, interp)
    assert len(new) == phn
    for i, p in enumerate(new):
        d[phoff + i * phsz: phoff + (i + 1) * phsz] = p
    open(path, "wb").write(d)
    print("%s: PT_INTERP added (%d -> %d bytes), %s dropped" % (path, off, len(d), "PT_GNU_RELRO" if give == PT_GNU_RELRO else "PT_GNU_EH_FRAME"))
    return True


if __name__ == "__main__":
    for f in (sys.argv[1:] or DEFAULT):
        patch(f)

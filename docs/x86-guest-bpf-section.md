
## x86-64 firmware: bpf() (2026-11-15)

netd of Android 12+ opens its pinned eBPF maps with `bpf(BPF_OBJ_GET)` and exits (crash loop, 60 s sleeps) when it cannot:
`Failed to start trafficcontroller: Pinned map not accessible ... (/sys/fs/bpf/map_netd_cookie_tag_map): Function not implemented`,
`ClatdController: getClatEgress4MapFd() failure: Function not implemented`. User-mode QEMU has no case for bpf (321 on x86-64), so the
guest got `-ENOSYS` without any host call and the runner's eBPF stand-in (`native/guest64/<api>/run64.c`, `case 280`) never saw it.
`libqemu_x86_64.so` now forwards it to the host (aarch64 syscall 280), where the runner answers it like it does for an arm64 guest.

`tools/patch_x86_translator_bpf.py` (run from the checkout root, idempotent, every site checked first; run after
`patch_x86_translator_process_vm_readv.py`) points the jump-table entry of 321 at a 24-byte stub that makes the host call and leaves
through the function's own epilogue. `tools/test_x86_translator_bpf.py` runs the patched `do_syscall1` in Unicorn (host call and
arguments, results and errors, balanced registers, the neighbouring unimplemented syscalls still -ENOSYS, image unchanged but for the
table entry). `libqemu_i386.so` is not touched.

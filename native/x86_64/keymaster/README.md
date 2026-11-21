# keymaster@4.1-service.remote, unpatched, answered on /dev/hvc1

The firmware service is a client: with ro.boot.vsock_keymaster_port == 0 it opens /dev/hvc1 O_RDWR, tcgetattr +
cfmakeraw, then speaks kiwi's KeymasterChannel (12-byte header: u32 cmd, u8 is_response, 3 pad, u32 payload_size; payload =
AOSP keymaster message). Its first frame is CONFIGURE (18). Failure to open = LOG(FATAL), exit 134 (your log).

`aemu-keymaster-answerer` creates a pty, makes /dev/hvc1 a symlink to its slave, and answers every frame with AOSP's software
keymaster from the firmware's own libkeymaster_portable / _messages / libpuresoftkeymasterdevice. No firmware file is patched,
no property is set. Stateless (PureSoft blobs use a fixed software master key).

Integration (the parts I could not compile here, they live in your Kotlin/TreeFixer):
1. Stop parking the remote keymaster service for this tree (TreeFixer.parkRemoteKeymasterOfForeignTree).
2. Install `vendor#bin#hw#aemu-keymaster-answerer` (prebuilt in ../prebuilt/) to /vendor/bin/hw/, mode 0755.
3. Start it before vendor.keymaster-4-1, e.g. in the same rc:
     service aemu-keymaster-answerer /vendor/bin/hw/aemu-keymaster-answerer
         class early_hal
         user system
   and make sure /dev/hvc1 can be created in the guest's dev (the answerer mkdir/unlink/symlinks it).
   If the service still starts first it aborts once and is restarted; harmless.
4. The one thing to verify on the phone: the guest must be able to posix_openpt() (/dev/ptmx, /dev/pts reachable through the
   emulator's path layer). The answerer logs `/dev/hvc1 -> /dev/pts/N` on success, or the exact errno on failure.

Build: sh build-keymaster-answerer.sh <dir with the 3 firmware libs> [aosp dir] (fails if any keymaster import isn't exported
by the firmware's libs). Host test: sh tests/run-host-test.sh <aosp dir>.

## Wired in (TreeFixer.kt, 2026-11-14)
x86KeymasterAnswererWanted(): x86-64 tree + ro.product.system.device contains "vsoc_kiwi_" + the ".remote" keymaster in the tree.
Then assets/engines/common-x86_64/vendor#bin#hw#aemu-keymaster-answerer and ...#etc#init#aemu-keymaster-answerer.rc are installed,
the keymaster service is no longer set aside (and put back if an earlier start parked it). Any other firmware is unchanged.

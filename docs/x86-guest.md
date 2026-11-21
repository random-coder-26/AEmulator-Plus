# x86-32 guest firmware

AEmulator Plus runs an x86-32 Android firmware on the **same universal engine** as ARM firmware. The only
architecture-specific host binary is the translator: `libqemu_kk_i386.so` (QEMU 8.2.4 `i386-linux-user` with the SENSES
patches) instead of `libqemu_kk.so`. x86-64 and the native engine are not covered.

## Importing Android-x86 ISO files

An Android-x86 live CD (`android-x86-*.iso`) is imported directly: `ramdisk.img` becomes the ramdisk and the `system.img` inside
`system.sfs` the system partition, see import-format-support.md. The result is an ordinary x86-32 image (detected from the ELF files as
below), so everything in this document applies to it. Android-x86 5.1 (API 22) is covered by the bundled software GLES renderer.

## App side

| What | Where |
| --- | --- |
| Guest architecture (`GuestArch`: `ARM`, `X86`), stored in the image as `arch` | `core/GuestArch.kt`, `core/GuestImage.kt` |
| Detection from the ELF machine type (`EM_386` = x86-32) of the firmware's own files; `ro.product.cpu.abi` is only the fallback | `GuestArch.detect`, `importer/Analyzer.kt` (profile version 17: older images are re-analysed once) |
| "Not ARM" import warning dropped for x86-32 (still shown for x86-64, MIPS, ...) | `GuestArch.isUnsupported` |
| Translator choice, no `native32`, no `DHD_NO_SMC` / `GUEST_NO_SMC`, no preload of libraries of another architecture | `core/GuestRunner.kt` |
| Translator checks with a clear message when `libqemu_kk_i386.so` is not in the APK | `core/GuestVm.kt` |
| Engine helper files per architecture, missing files skipped | `core/EngineAssets.kt` (`forArch`), `core/TreeFixer.kt` |
| GPU/hwui off for an x86 guest until the x86 GL library exists | `core/GuestVm.kt` (`settings`) |

`ElfPatch` only touches EM_ARM files, so the firmware's own x86 libraries are left alone.

### Guest-side helper libraries (to be built for x86)

`assets/engines/common` and `assets/engines/kk` hold **ARM** binaries (their sources use ARM inline assembly in places).
For an x86 firmware the installer looks in `assets/engines/common-x86` and `assets/engines/kk-x86` under the same file
name. A file that is not there is skipped (the log says how many) and the firmware boots without it. Dex jars
(`aemu-setup.jar`, `aemu-stubs.jar`, ...) are machine independent and are taken from the ARM directories.

Build these for x86 (Android NDK, `x86`/i686) and drop them in the `-x86` directories:

* `common-x86`: `libaemushim.so` (**built**, reduced: `native/guestshim/aemushim_x86.c`, see below), `aemu_true.so` (**built**: `native/guestshim/true_x86.S`, the exit(0) stand-in for iptables/ip6tables), `libaemurlimit.so`, `libaemuxattr.so`, `libshmshim.so`, `lights.default.so`, `camera.default.so`,
  `camera.aemu_host.so`, `sensors.aemu_host.so`, `sensors.default.so`, `libcamera.none.so`
* `kk-x86`: `libashmemshim.so`, `libGLES.so`, `libGLES_split.so`, `libGLES_route.so`, `libGLES_aemu.so`,
  `gralloc.default.so` (**built**, i386, bionic: the AOSP gralloc for x86 **Eclair** (API 5..7) with a bionic userland; it
  handles a single-page fb0 with a plain ashmem buffer, so the firmware gralloc's module-mutex deadlock ("page flipping not
  supported", FUTEX_WAIT right after the mmap of fb0) is gone. The 2026-11-17 rebuild also clears `HW_2D` / `HW_TEXTURE` for that
  buffer (the pmem path took the module mutex a second time), see `native/gralloc-eclair`. Needs from the firmware: libcutils,
  liblog, libstdc++ (operator new / delete), libc (incl. `__page_size`, `__errno`, `gettid`, `atexit`). `TreeFixer.installEngineFiles` parks the firmware's gralloc
  (`system/.aemu-parked/system#lib#hw#gralloc.default.so.eclair`) and installs it; not for other API levels and not for a glibc
  firmware (Google TV), which could not load it as it is; see `glibc-x86`), `audio.primary.default.so` (and the audio variants), `fbpaint`
* `glibc-x86` (glibc Google TV firmware, Logitech Revue / Intel CE4100, glibc 2.7): the `libgdl`/`libgma`/`libismd_*`/`libclock_control`/`libGLES_CM`
  stand-ins (`native/revue-shims`), and the Eclair gralloc for that userland: `gralloc.default.so` is `kk-x86/gralloc.default.so` with its
  dynamic string table patched by `tools/patch_gralloc_glibc.py` (DT_NEEDED `libc.so`, `libstdc++.so`, `libm.so`, `libdl.so` -> `aegr.so`
  (glibc has none of these names; a repeated DT_NEEDED is loaded once; the module calls nothing of libm / libdl), `pthread_mutex_*` ->
  `aegr_mx_*`; `python3 tools/patch_gralloc_glibc.py <kk-x86 file> <glibc-x86 file>`, idempotent, nothing moves), `aegr.so` (**built**:
  `native/revue-shims/build-aegr.sh`, freestanding i386: bionic's `__errno`, `gettid`, `atexit`, `__page_size`, operator new / delete
  over glibc's malloc / free, the four mutex functions). `python3 tools/test_patch_gralloc_glibc.py` checks that both files are in
  sync, that every import of the patched module is provided by aegr.so, glibc or the firmware's libcutils / liblog, and that
  DT_NEEDED is down to `libcutils.so`, `liblog.so`, `aegr.so`.
  `TreeFixer.glibcGralloc` installs both (aegr.so in `/lib`; the gralloc over every `gralloc.*.so` of the firmware's `system/lib/hw` and
  `vendor/lib/hw`, the originals kept as `system/.aemu-parked/system#lib#hw#gralloc.<name>.so.glibc`).

Until then an x86 firmware runs without the other shims: no ashmem/rlimit/xattr preload, no host camera and sensors, no GPU bridge.

## QEMU side (`qemu-senses/qemu`)

Tested against a QEMU 8.2.4 tree: all patches apply, `i386-linux-user` builds. Runtime fixes for an x86 guest:

* `guest_no_smc()` (`src/guest_pagelog.c`) returns 0 on non-ARM targets. GUEST_NO_SMC stops QEMU invalidating translated
  code on writes and relies on ARM's `cacheflush`; x86 has no such call, an x86 JIT would run stale code.
* Patch 0052: the ARM-only register reads (`env->regs[15]`, the `blx ip` signature) in `accel/tcg/user-exec.c` are under
  `#ifdef TARGET_ARM`.
* Patch 0056: the `sigsuspend` mask-by-value branch covers i386 as well (x86-32 takes the mask by value in the third argument).
* `src/guest_tls.c`: whole body under `#ifdef TARGET_ARM`, stub returning false elsewhere (meson.build is untouched).
* `/proc/cpuinfo`: QEMU 8.2 has none for i386, the guest would read the phone's ARM one. `src-target/i386/target_proc.h`
  replaces the stock stub when building qemu-i386 (`GUEST_CPUS`, `GUEST_X86_FLAGS`, `GUEST_X86_MODEL` tune it).
* Patch 0102 (new): crash report and stack trace for x86-32 (EIP bytes/mapping, return address from EBP, stack scan with a
  CALL-before-address filter). Patches 0035/0037 stay ARM-only.
* `build-android.sh` builds `arm-linux-user` and `i386-linux-user` in one run and writes `qemu/build/qemu-i386-android`
  (and `-dbg`) next to the ARM binary.

## Building and packaging

```
sudo ./qemu-senses/qemu/build-android.sh      # on aarch64 Linux
cp qemu-senses/qemu/build/qemu-i386-android app/src/main/jniLibs/arm64-v8a/libqemu_kk_i386.so
```

The ARM binary goes to `libqemu_kk.so` as before. Without `libqemu_kk_i386.so` the app still builds; an x86 image then stops
at start with "the x86 translator (libqemu_kk_i386.so) is not in this build of the app".

## Software GL renderer for x86 firmware

x86 firmware from real devices often ships no `system/lib/egl/libGLES_android.so`; without it libEGL aborts SurfaceFlinger
("couldn't find an OpenGL ES implementation", exit 132). `core/X86SoftwareGles.kt` installs the AOSP software renderer built for
the firmware's own API level (`assets/engines/x86-gles/libGLES_android-<api>.so`, i386; 20, 21, 22, 23, 24 and 25 are bundled)
into `system/lib/egl` when the tree has none: at import (`Importer`, after analysis) and before each start
(`TreeFixer.eglConfig`, for images imported earlier). x86-32 trees only; a firmware that has its own copy is left untouched,
and an API level without a bundled file is skipped with a log line. To support another level, drop `libGLES_android-<api>.so` there.

### Android 7.x (API 24, 25): libGLES_android.so is present but ignored

7.0+ libEGL never takes `libGLES_android.so` from its directory scan. With `ro.kernel.qemu=1` it still picks that file by name,
but only when `/vendor/lib/egl` does not exist; if the directory exists (even empty) it sets `qemu.gles=2`
("Emulator has vendor provided software renderer"), scans `libGLES_*.so` skipping `libGLES_android.so`, finds nothing and
aborts (SIGABRT, exit 134). `X86SoftwareGles.ensure` therefore also installs the renderer as `system/lib/egl/libGLES_aemusw.so`
(a copy of the firmware's own `libGLES_android.so`, or the bundled one), and `TreeFixer` does not set that name aside.

## ashmem on x86 Nougat (API 24+): "Not a typewriter"

7.0+ libcutils opens `/dev/ashmem`, `fstat`s it and rejects anything that is not a character device (`errno = ENOTTY`). Under the
translator `/dev/ashmem` is a memfd, so `ashmem_create_region()` fails for ART ("ashmem_create_region failed for 'zygote space'",
then a null dereference in ART's abort handler, which looks like a zygote SIGSEGV), gralloc and MemoryHeapBase. ARM guests get
`libashmemshim.so` (LD_PRELOAD) that replaces the libcutils entry points; the x86 build is `native/guestshim/ashmem_x86.c`
(`build-x86.sh`, no libc, raw `int 0x80`), shipped as `assets/engines/kk-x86/libashmemshim.so` and installed/preloaded like the ARM one.

## x86 Nougat: what `aemushim_x86.c` fixes

`assets/engines/common-x86/libaemushim.so` is a small x86-32 port of the parts of `libaemushim.so` that 7.x needs on the phone's
kernel (no libc, raw `int 0x80`, only bionic's `__errno` is imported; the rest of `aemushim.c` is ARM code and is not ported).
Each item below is a failure seen in the logs of an API 25 x86 image (Wear, Tag Heuer) without it, in boot order:

| Symptom | Hook |
|---|---|
| zygote aborts, exit 134: `Not whitelisted : /dhd.owners`, `Unable to construct file descriptor table` (the `SIGSEGV` in `libart.so` after it is only the crash handler failing while it dumps the abort) | `readlink`, `readlinkat`, `getsockname`: the owners table is shown as `/system/framework/aemu-owners.jar` (`TreeFixer.parkNfc` links it, API 24+), pipes, the host log file and `/dev/__properties__` as `/dev/null`, the host image root is cut from paths and unix socket names. 7.0+ zygote checks every open descriptor against a whitelist of guest paths before it forks |
| `mediaextractor`/`mediacodec` exit 134: `prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER): Invalid argument` | `prctl`: the guest's seccomp filter is accepted and dropped |
| installd fails every `create_app_data`, system_server dies in `SettingsProvider` (`SQLiteCantOpenDatabaseException`), zygote restarts itself (exit 137) | `get/lget/fgetxattr`, `set/lset/fsetxattr`: ENOSYS from the phone's kernel is answered with a fixed SELinux label, "not set" and successful writes; `user.default` reads as present, `user.serial` as ENODATA |
| no TCP/DNS (5.0+ netd sets `SO_MARK`) | `setsockopt`: `SO_MARK` accepted and dropped |
| AlarmManager falls back to a handler that kills system_server | `timerfd_create`: `*_ALARM` clocks become the plain ones |

Build: `native/guestshim/build-x86.sh` (needs `gcc -m32`). Test: `native/guestshim/tests/aemushim-x86-smoke.c` (header says how to run it).
An existing x86 image picks the file up on the next start (the installer compares size and CRC). `libaemuxattr.so` is not needed
on x86: its job (installd's xattr calls) is done here, in the library every guest process preloads.

## Android 10 x86-64: mediaextractor / media.swcodec abort in Scudo, system_server dies (2026-11-13)

Log of an `Android SDK built for x86_64` (API 29) image on a 39-bit phone (OnePlus Pad 3, Android 17): `mediaextractor` and
`media.swcodec` abort on every start,

```
==N==ERROR: Scudo failed to allocate 0x3000 (12288) bytes of memory at address 0x3fffffff000 (error code: 12)
Scudo ERROR: CHECK failed at .../sanitizer_common.cc:54 ((0 && "unable to mmap")) != (0) (0, 0)
```

(guest backtrace: `MmapFixedImpl <- SizeClassAllocator64::Init <- initOnce <- scudoAllocate <- libc_scudo.so malloc+26`). The two programs
link `libc_scudo.so`, which puts Scudo's hardened allocator (`libscudo_wrapper.so`) in front of bionic's malloc. On x86-64 that Scudo
reserves a 4 TiB primary region (only AArch64 gets the 256 GiB one); the phone's address space is 512 GiB, the reservation fails
unnoticed and the next fixed mapping, at the end of the region, fails with ENOMEM. With no `media.extractor` the framework's media
users wait (`Waiting for service 'media.extractor'`, `MediaExtractorFactory: extractor service not running`, SoundPool in
AudioService), the permission controller times out and system_server is killed:
`FATAL EXCEPTION IN SYSTEM PROCESS: android.fg: Error granting/upgrading runtime permissions`, then `Exit zygote because system server has terminated`.

`TreeFixer.scudoStub` (`core/ScudoStub.kt`) replaces every 64-bit x86 `libc_scudo.so` of the tree (system/vendor `lib64`, the `lib64` of each
APEX module: swcodec has its own copy) with an empty library of the same soname, `assets/engines/common-x86_64/libc_scudo_stub.so`
(`native/x86_64/build-scudo-stub.sh`). Nothing in it defines malloc, so the programs keep bionic's allocator (jemalloc on 10), as every other guest
process does. The firmware's file is kept in `system/.aemu-parked/`. x86-32 copies (small 32-bit region) and arm64 ones are left alone.

## Stock translators for the native engine

`libqemu_i386.so` and `libqemu_x86_64.so` are now **unpatched QEMU 8.2.7** (`i386-linux-user` / `x86_64-linux-user`), built with Zig as static aarch64
executables, see `tools/qemu-zig/README.md`. They have no SENSES patches (no `DHD_*` env, no pagelog). The patched x86-32 build for the KK engine is
`libqemu_kk_i386.so`.

## x86-64 on the native engine: engine files built with zig (2026-11-04)

`assets/engines/common-x86_64` (release independent) and `assets/engines/n64-<api>/x86_64` (one release; today `n64-30/x86_64`, the audio HAL stack) hold the engine's own x86-64 builds (sources and scripts in `native/x86_64`). The arm/arm64
files of the other layers are still not installed for a translated firmware; these are. Today: `hwcomposer.default.so`,
the HWC2 module that stops `composer@2.4-service` exiting with `failed to get hwcomposer2 function 54`. It is skipped when
the firmware has a hwcomposer HAL of its own. The graphics allocator (minigbm needs a DRM node) and an OpenGL ES driver are
still missing for x86-64 guests, see `native/x86_64/README.md`.

## 32-bit programs of an x86 / x86-64 firmware: `-B 0` and the 4 GiB reservation (2026-11-04)

`libqemu_i386.so` (stock QEMU 8.2.7, a non-PIE static executable linked at 0x1000000) reserves a 32-bit guest's whole address
space by default. With the runner's `-B 0` that is [0, 4 GiB) of the host, which contains the translator's own image, so every
32-bit program (zygote_secondary, the audio HAL, ...) exited with code 1:
`libqemu_i386.so: /system/bin/app_process32: requires virtual address space that is in use (omit the -B option ...)`.
`tools/patch_i386_translator_reserved_va.py` removes the default reservation (one instruction); `libqemu_x86_64.so` needs nothing,
a 64-bit target reserves nothing. Run the script again after rebuilding the translator (the address is checked). A clean fix is
to link the translator as a PIE / at a high base; then the patch is not needed.

## x86 / x86-64 firmware: audio, gatekeeper and camera provider (2026-11-04)

Boot log of an Android 10 x86-64 image (`Android SDK built for x86_64`) with the three services dying on every start:

| Service | Log | Cause | Fix (`TreeFixer`) |
|---|---|---|---|
| `vendor.audio-hal-2-0` (32-bit program, `libqemu_i386.so`) | `Could not get passthrough implementation for android.hardware.audio@4.0::IDevicesFactory/default`, `Could not register audio core API 2, 4 nor 5`, exit 134; audioserver waits for `IDevicesFactory/default` | `sanitize()` (import) and the per-start vendor-HAL pass set the firmware's own `android.hardware.audio@4.0-impl.so`, `audio.effect@4.0-impl.so` and `audio.primary.*.so` aside, assuming the engine's ARM HALs take their place; for an x86 guest nothing does | not set aside for a foreign-architecture guest; `restoreForeignAudioHal()` puts back what an earlier import parked |
| `vendor.gatekeeper-1-0` | `Unable to open GateKeeper HAL`, exit 139 | passthrough over `gatekeeper.<board>.so`, which the image does not have | `parkHalsWithoutModuleOfPure32Treble()` now judges foreign-architecture trees too: service and rc go aside, declaration dropped, gatekeeperd uses its software GateKeeper |
| `vendor.camera-provider-2-4` | `Could not get passthrough implementation for android.hardware.camera.provider@2.4::ICameraProvider/legacy/0`, exit 1; cameraserver "Waited one second for ... ICameraProvider/legacy/0" | the goldfish camera module (`camera.ranchu.so`) is set aside and needs the emulator host anyway | same rule: provider set aside, declaration dropped, cameraserver sees no provider |

`diag.txt` lists the HAL files set aside (`HAL files set aside`), so the next log shows what is still parked.

## x86 / x86-64 firmware: process_vm_readv (2026-11-13)

Android on x86 / x86-64 needs `process_vm_readv` to boot (syscall 310 on x86-64, 347 on i386). User-mode QEMU has no case for it, so the
guest got `-ENOSYS`. `libqemu_x86_64.so` and `libqemu_i386.so` (the translators of the native engine) now forward it to the host
(aarch64 syscall 270). The ARM translators, `libqemu_kk_i386.so` and with it the Android 1-7 x86 engine are not touched; the ARM
guests do not make the call. `process_vm_writev` (311 / 348) stays unimplemented.

`tools/patch_x86_translator_process_vm_readv.py` (run from the checkout root, idempotent, addresses are checked first) points the
jump-table entry of the syscall in `do_syscall1` at a stub that makes the host call and leaves through the function's own epilogue:

| Translator | Stub | Notes |
| --- | --- | --- |
| x86-64 | 9 instructions: `x2..x7` (guest arguments) to `x0..x5`, `x8 = 270`, `svc #0` | the guest `struct iovec` is the host's own; guest and host addresses are the same under the runner's `-B 0`; the kernel does the checks, the result is already `-errno` |
| i386 | ~70 instructions | `iovec` is `{u32, u32}` here, so both vectors are copied with a `process_vm_readv` on the translator's own process (an unreadable vector gives `-EFAULT`, not a SIGSEGV in the translator) and widened in place to the host layout; flags != 0 and counts above 1024 give `-EINVAL` like the kernel; at most 32 KiB of stack, released by the epilogue |

There was no room for the stub: the text segment is packed against the data segment in the file. The patch inserts 0x10000 bytes
(keeps the 64 KiB offset/vaddr congruence of the data segments) behind the text, puts the stub there and makes the text segment (and
the `.text` section) that much longer. No virtual address moves, so the other `tools/patch_*translator*.py` scripts still match, in any order.

`tools/test_x86_translator_process_vm_readv.py <dir with the unpatched .so files>` (needs `pip install unicorn`) maps the patched files like the kernel,
runs `do_syscall1` for real from its first instruction, answers the host calls with a model of the kernel's `process_vm_readv` and checks
arguments, results, errors (`ESRCH`, `EINVAL`, `EFAULT`), the i386 widening, balanced sp / callee-saved registers and that the neighbouring
syscalls and `process_vm_writev` behave as before. The pointer arguments rely on `-B 0` like the ioctl passthrough does.

## x86-64 Android 10/11 system-only image: GL bridge, keystore and audioserver (2026-11-14)

Log of an `Android SDK built for x86_64` (API 30) image whose `/vendor` has no HAL at all (only the engine's `libGLES_aemu.so`, hwcomposer and sensors):

| Service | Log | Cause | Fix |
| --- | --- | --- | --- |
| `surfaceflinger` | `SwiftShader is not there`, `eglQueryStringImplementationANDROID(EGL_VERSION) failed`, abort | the GPU bridge took EGL displays and configs from SwiftShader, which exists for the phone's architecture only | `native/glbridge/guest.c` "solo" mode (no SwiftShader found): the bridge has its own display, nine configs, EGL strings, images and fences, and every context goes to the host GPU. Host test: `native/glbridge/tests/run-solo.sh` |
| 32-bit programs of the same image | no bridge for them | only `system/lib64/egl` had one | `assets/engines/common-x86_64/system#lib#egl#libGLES_aemu.so` (i386, `native/glbridge/build32-x86.sh`; TreeFixer moves it to `vendor/lib/egl`); x86-64 build: `build64-x86_64.sh`; both `build-x86-zig.sh` |
| `keystore` | `Check failed: kmDevices[TRUSTED_ENVIRONMENT] Error no viable keymaster device found`, exit 134, 12 restarts | no keymaster HAL | `GuestVm`: no keymaster service in the plan, so the daemon is not started; the `aemu-keystore` stand-in serves `android.security.keystore` (no real keys) |
| `system_server` / `zygote` | `Cannot find entry android.hardware.health@2.0::IHealth/default`, `Failed to start service com.android.server.BatteryService`, `No IHealth service instance among [default, backup] is available`, zygote `exited due to signal 9` | no health HAL, BatteryService.onStart throws | `native/x86_64/health`: a stand-in IHealth@2.0 service that registers `default` and serves `/sys/class/power_supply` (the nodes HostBatteryBridge writes), built with zig from AOSP android11-release (`audio/fetch-aosp.sh`, `health/build-health.sh`); `TreeFixer.x86HealthWanted` installs it for API 29/30 when the tree has no health HAL of its own |
| `audioserver` | `Cannot find entry android.hardware.audio@{6,5,4,2}.0::IDevicesFactory/default`, SIGSEGV in `AudioFlinger::AudioFlinger` | no audio HAL, libaudiohal returns null | `native/x86_64/audio`: audio 2.0 core + effect HAL impls, service and the engine's `/dev/eac` module built with zig from AOSP android11-release (`fetch-aosp.sh`, `build-audio.sh`); `TreeFixer.x86AudioWanted` installs them for API 29/30 when the tree has no audio HAL of its own |

The vendor namespace does not see `/system/lib64` libraries that are not VNDK: `android.hardware.audio.common-util.so` and `android.hardware.audio.common@2.0-util.so` are shipped in `vendor/lib64`, `libaudiofoundation` (AudioContainers) and `libeffectsconfig` + tinyxml2 are linked statically. None of this has run in a guest yet.


## System-only Android 11 x86-64 image: manifest, composer, gralloc (2026-11-14)

Log of the Android 11 SDK image on the native engine: `Cannot read /vendor/manifest.xml` (no device manifest, so every HAL registration
was refused: audio HAL abort 134, audioserver 139), `failed to get hwcomposer service` (SurfaceFlinger abort 134, fb0 empty), keystore
abort 134. What changed:

* `TreeFixer.ensureDeviceManifest()` writes an empty device manifest (target-level by API) when the tree has none.
* `engines/n64-30/x86_64` also holds the composer@2.1 stack (`native/x86_64/composer`) and the Android 11 build of allocator/mapper 2.0
  (installed from API 30, `x86Gralloc2Wanted`; API 31+ keeps the 12L build of `common-x86_64`). The composer stack is installed for API 29/30
  when the firmware has no composer HAL (`x86ComposerWanted`).
* The keystore daemon is no longer started for such a firmware: `wait_for_keymaster` (a program of every tree) was taken for a keymaster HAL,
  so the check in `GuestVm` never fired; the `aemu-keystore` stand-in serves `android.security.keystore`.

Not run in a guest: the binaries are compiled, linked and checked (DT_NEEDED, imports, exported `HIDL_FETCH_IComposer`) only.

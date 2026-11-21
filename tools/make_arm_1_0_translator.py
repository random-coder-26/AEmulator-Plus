"""Builds app/src/main/jniLibs/arm64-v8a/libqemu_arm_1.0.so from libqemu_kk.so (run from the checkout root).

The translator for Android 1.0 / 1.1 (API 1 and 2) images that are not pre-1.0 builds: the stock universal translator plus the
framebuffer answer of tools/patch_qemu_fb_yres.py, and nothing else. The pre-1.0 changes (read-implies-exec, binder EINTR) are not in it.
"""
import subprocess, sys
d = "app/src/main/jniLibs/arm64-v8a/"
open(d + "libqemu_arm_1.0.so", "wb").write(open(d + "libqemu_kk.so", "rb").read())
subprocess.check_call([sys.executable, "tools/patch_qemu_fb_yres.py", d + "libqemu_arm_1.0.so"])

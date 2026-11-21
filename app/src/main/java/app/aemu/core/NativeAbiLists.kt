/* AEmulator Plus addition, 2026-11-14: guest ABI lists on the native arm64 engine. GPL-3.0; see NOTICE.md. */
package app.aemu.core

/**
 * The ro.product.cpu.abilist* values the native arm64 engine gives the guest.
 *
 * They have to name the instruction set of the FIRMWARE's programs, not the phone's. An x86-64 firmware
 * (an emulator image, Android-x86) is translated by qemu-x86_64 / qemu-i386, so its zygote, its system apps and
 * their native libraries are all x86. Telling the guest "arm64-v8a" made PackageManagerService reject every
 * system APK that ships native code only for x86 / x86_64 (ExtServices, NetworkStack) with
 * "Error unpackaging native libs for app, errorCode=-113" (INSTALL_FAILED_NO_MATCHING_ABIS). system_server then
 * died in PackageManagerService with "Required services extension package is missing, check
 * config_servicesExtensionPackage" and zygote followed with exit code 137.
 *
 * ARM firmware keeps the lists it always had. For the foreign architectures only the architecture's own ABIs
 * are listed: the guest has no native bridge, so a list that offered arm ABIs would only let PackageManagerService
 * pick an ABI whose libraries cannot be loaded.
 */
internal object NativeAbiLists {
    data class Lists(val abilist: String, val abilist64: String, val abilist32: String)

    /** [has32]: the tree has a 32-bit side the engine can start (zygote64_32 instead of zygote64). */
    fun forArch(arch: GuestArch, has32: Boolean): Lists = when (arch) {
        GuestArch.ARM ->
            if (has32) Lists("arm64-v8a,armeabi-v7a,armeabi", "arm64-v8a", "armeabi-v7a,armeabi")
            else Lists("arm64-v8a", "arm64-v8a", "")
        GuestArch.X86_64 ->
            if (has32) Lists("x86_64,x86", "x86_64", "x86")
            else Lists("x86_64", "x86_64", "")
        // an x86-32 firmware has no 64-bit side at all (the pure 32-bit tree path sets its own 32-bit list)
        GuestArch.X86 -> Lists("x86", "", "x86")
    }

    /** The 32-bit list of a pure 32-bit tree when the firmware names no ABI it could be taken from. */
    fun pure32Fallback(arch: GuestArch): String = if (arch == GuestArch.X86) "x86" else "armeabi-v7a,armeabi"

    /** What the 32-bit list gets when only ro.product.cpu.abi is known: ARM adds the legacy armeabi, x86 has no equivalent. */
    fun pure32FromAbi(arch: GuestArch, abi: String): String =
        if (arch == GuestArch.ARM && abi != "armeabi") "$abi,armeabi" else if (arch == GuestArch.ARM) "armeabi-v7a,armeabi" else abi
}

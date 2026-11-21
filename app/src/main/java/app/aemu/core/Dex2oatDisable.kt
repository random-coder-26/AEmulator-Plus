/* Added for AEmulator Plus (2026-11-17). GPL-3.0; see LICENSE. */
package app.aemu.core

import java.io.File

/**
 * The optional VM setting "Disable dex2oat" (Android 5.0+, off by default).
 *
 * Every dex2oat binary the firmware has is replaced by a tiny shell script that prints one line and exits with status 1,
 * so nothing is ever compiled in the guest: installd's per-app compiles, the first-boot compiles and the background
 * dexopt all fail at once. ART then runs the app's dex code from the apk (interpreted), which is what it does for any
 * app whose compile failed. The original binary is kept next to it as `<name>.aemu-orig` and put back as soon as the
 * option is switched off.
 *
 * What goes wrong is the user's risk, and the setting says so: a firmware with no usable boot image cannot start zygote
 * at all, system code that expects compiled code can crash, apps run slower, and other glitches are possible.
 *
 * This object coexists with [Dex2oatArgSplit], [Dex2oatSkip] and [Lpw39Dex2oat]: whichever file sits at the dex2oat path
 * (the real binary or one of their wrappers) is moved aside unchanged and restored unchanged by [uninstall]. Their own
 * install functions leave a foreign script alone, so call [uninstall] before them and [install] after them.
 */
object Dex2oatDisable {
    const val MARKER = "# aemu-dex2oat-disabled-wrapper"
    const val SUFFIX = ".aemu-orig"

    /** Every directory a dex2oat lives in, from 5.0 (system/bin) to the ART apex of 12+. */
    private val DIRS = listOf("system/bin", "apex/com.android.runtime/bin", "apex/com.android.art/bin")
    private val NAMES = listOf("dex2oat", "dex2oatd", "dex2oat32", "dex2oat64", "dex2oatd32", "dex2oatd64")

    fun script(): String = listOf(
        "#!/system/bin/sh",
        MARKER,
        "echo \"aemu: dex2oat is disabled in the VM settings\" >&2",
        "exit 1",
        ""
    ).joinToString("\n")

    fun isWrapper(f: File): Boolean =
        f.isFile && f.length() < 4096 && runCatching { f.readText(Charsets.ISO_8859_1).contains(MARKER) }.getOrDefault(false)

    private fun targets(root: File): List<File> =
        DIRS.flatMap { d -> NAMES.map { File(root, "$d/$it") } }

    /** Puts the disabling script in place under [root]. True when something changed; safe to call on every boot. */
    fun install(root: File): Boolean {
        var changed = false
        for (f in targets(root)) {
            if (isWrapper(f)) {
                // already ours: refresh it only when the text differs
                if (f.readText(Charsets.ISO_8859_1) != script()) { f.writeText(script(), Charsets.ISO_8859_1); changed = true }
                f.setReadable(true, false); f.setExecutable(true, false)
                continue
            }
            // a symlink (e.g. dex2oat -> dex2oat32) is renamed as the link it is, which keeps its target
            if (!f.exists() && !java.nio.file.Files.isSymbolicLink(f.toPath())) continue
            val orig = File(f.path + SUFFIX)
            if (orig.exists()) orig.delete()
            if (!f.renameTo(orig)) continue
            f.writeText(script(), Charsets.ISO_8859_1)
            f.setReadable(true, false); f.setExecutable(true, false)
            changed = true
        }
        return changed
    }

    /** Puts the original binaries (or the other wrappers that stood there) back. True when something changed. */
    fun uninstall(root: File): Boolean {
        var changed = false
        for (f in targets(root)) {
            val orig = File(f.path + SUFFIX)
            val origThere = orig.exists() || java.nio.file.Files.isSymbolicLink(orig.toPath())
            if (!origThere) continue
            if (f.exists() && !isWrapper(f)) continue // something else took the place: leave both alone
            f.delete()
            if (orig.renameTo(f)) changed = true
        }
        return changed
    }
}

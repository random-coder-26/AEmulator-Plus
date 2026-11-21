/* Added for AEmulator Plus (2026-11-13). GPL-3.0; see LICENSE. */
package app.aemu.core

import java.io.File

/**
 * Keeps dex2oat from recompiling a patched /system/framework/services.jar (Android 10-13).
 *
 * [WatchdogPatch] edits the jar's dex, so its checksum no longer matches the firmware's services.odex/.vdex
 * ("Dex checksum does not match for dex: /system/framework/services.jar"). zygote's performSystemServerDexOpt then asks
 * installd to compile the jar, and dex2oat under the translator runs out of memory / crashes (the 1 GB heap does not fit,
 * then SEGV), costs a lot of time and leaves system_server to die soon after. The compile is optional: zygote only logs
 * "Failed compiling classpath element for system server" and the runtime loads the dex from the jar.
 *
 * dex2oat becomes a small shell wrapper around the original (dex2oat.real, same directory) that fails at once, with exit
 * status 1, when it is asked to compile services.jar (or to write its services.odex / dalvik-cache output) and execs the
 * real binary for everything else.
 *
 * installd does not pass the jar as a full path: it hands dex2oat an open fd with `--zip-fd=N --zip-location=services.jar`
 * (the basename; the directory only appears as `--classpath-dir=/system/framework`) and `--oat-location=` for the output
 * (.../services.odex or /data/dalvik-cache/<isa>/system@framework@services.jar@classes.dex). A match on the full path
 * /system/framework/services.jar therefore never fired and dex2oat ran out of memory. The wrapper matches the basename in
 * any argument form instead.
 */
object Dex2oatSkip {
    const val MARKER = "# aemu-dex2oat-skip-services-wrapper"
    /**
     * Arguments that mean "compile the patched services.jar": the input as `--zip-location=services.jar`,
     * `--dex-file=...`, a bare path, or the output as services.odex / system@framework@services.jar@classes.*.
     * Anchored on the exact basename, so wifi-service.jar and the like are not caught.
     */
    private const val PATTERNS = "*=services.jar|*/services.jar|services.jar|*=services.odex|*/services.odex|*system@framework@services.jar@*"
    private val NAMES = listOf("dex2oat", "dex2oatd")
    private const val DIR = "apex/com.android.runtime/bin"

    fun script(dir: String, name: String): String = listOf(
        "#!/system/bin/sh",
        MARKER,
        "for a in \"\$@\"; do",
        "  case \"\$a\" in",
        "    $PATTERNS) echo \"aemu: dex2oat skipped for the patched services.jar\" >&2; exit 1 ;;",
        "  esac",
        "done",
        "exec /$dir/$name.real \"\$@\"",
        ""
    ).joinToString("\n")

    fun isWrapper(f: File): Boolean =
        f.isFile && f.length() < 4096 && runCatching { f.readText(Charsets.ISO_8859_1).contains(MARKER) }.getOrDefault(false)

    /** Puts the wrappers in place under [root]. True when something changed; safe to call on every boot. */
    fun install(root: File): Boolean {
        var changed = false
        for (n in NAMES) {
            val d2o = File(root, "$DIR/$n")
            val real = File(root, "$DIR/$n.real")
            if (!d2o.exists() && !real.isFile) continue
            if (d2o.exists() && !isWrapper(d2o)) {
                // a foreign script (not ours) is left alone
                if (d2o.length() < 4096 && runCatching { d2o.readText(Charsets.ISO_8859_1).startsWith("#!") }.getOrDefault(false)) continue
                real.delete()
                if (!d2o.renameTo(real)) continue
                changed = true
            }
            if (!real.isFile) continue
            val want = script(DIR, n)
            if (!isWrapper(d2o) || d2o.readText(Charsets.ISO_8859_1) != want) {
                d2o.delete()
                d2o.writeText(want, Charsets.ISO_8859_1)
                changed = true
            }
            for (f in listOf(d2o, real)) { f.setReadable(true, false); f.setExecutable(true, false) }
        }
        return changed
    }

    /** Puts the original binaries back. True when something changed. */
    fun uninstall(root: File): Boolean {
        var changed = false
        for (n in NAMES) {
            val d2o = File(root, "$DIR/$n")
            val real = File(root, "$DIR/$n.real")
            if (!isWrapper(d2o) || !real.isFile) continue
            d2o.delete()
            if (real.renameTo(d2o)) changed = true
        }
        return changed
    }
}

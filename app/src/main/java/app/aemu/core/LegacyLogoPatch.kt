package app.aemu.core

import java.io.File
import java.nio.file.Files

/**
 * Samsung 2.x `playlogos1` first calls a helper that opens /dev/tty0 and runs ioctl(KDSETMODE, KD_GRAPHICS), and its
 * caller prints "============> 1" and tries again until the helper returns 0. Under qemu-user the host refuses the
 * console ioctl (EACCES) and there is no tty0 at all, so it spun forever, leaked descriptors and crashed in fclose(NULL).
 * The animation draws straight into fb0 and does not need a real console, so the helper is made to report success:
 *
 *   cmp r0,#0 ; mov r4,r0 ; bge L ; bl close ; movs r5,#100 ; b end ; L: movw r1,#0x4b3a ; movs r2,#1 ; bl ioctl ;
 *   mov r5,r0 ; mov r0,r4 ...
 *                                         ^^^^^^^^^                    ^^^^^^^^^^
 *   both result moves become `movs r5,#0`  (open failure: movs r5,#100 -> #0, ioctl result: mov r5,r0 -> movs r5,#0)
 */
internal object LegacyLogoPatch {
    private const val ANY = -1
    private val SIG = intArrayOf(
        0x00, 0x28, 0x04, 0x46, 0x03, 0xda, ANY, ANY, ANY, ANY,
        0x64, 0x25, 0x08, 0xe0, 0x44, 0xf6, 0x3a, 0x31, 0x01, 0x22, ANY, ANY, ANY, ANY,
        0x05, 0x46, 0x20, 0x46,
    )
    private const val OPEN_FAIL_AT = 10 // 0x64 (movs r5,#100)
    private const val IOCTL_RESULT_AT = 24 // 0x05 0x46 (mov r5,r0)

    /** Patches [data] in place; returns how many helpers were changed (0 = unknown build or already patched). */
    fun patch(data: ByteArray): Int {
        var n = 0
        var i = 0
        while (i <= data.size - SIG.size) {
            if (matches(data, i)) {
                data[i + OPEN_FAIL_AT] = 0x00
                data[i + IOCTL_RESULT_AT] = 0x00; data[i + IOCTL_RESULT_AT + 1] = 0x25
                n++
                i += SIG.size
            } else i++
        }
        return n
    }

    private fun matches(d: ByteArray, at: Int): Boolean {
        for (k in SIG.indices) if (SIG[k] != ANY && (d[at + k].toInt() and 0xff) != SIG[k]) return false
        return true
    }

    fun apply(root: File, log: (String) -> Unit) {
        runCatching {
            val f = File(root, "system/bin/${BootMediaServices.LEGACY_LOGO}")
            if (!f.isFile || Files.isSymbolicLink(f.toPath())) return
            if (!f.canonicalPath.startsWith(root.canonicalPath + File.separator)) return
            val data = f.readBytes()
            if (patch(data) > 0) {
                f.setWritable(true)
                f.writeBytes(data)
                log("boot logo: playlogos1 patched (console graphics-mode check skipped)")
            }
        }.onFailure { log("boot logo: playlogos1 patch failed: ${it.message}") }
    }

    /**
     * The boot-sound thread reads /sys/class/switch/h2w/state (headset plugged?) with fopen and, when that fails, still
     * calls fclose(NULL) a few seconds in, which kills the whole process (and the animation with it). The host has no
     * such switch; qemu maps a guest path into the tree only when the file exists there, so provide "0" (no headset).
     */
    fun provideHeadsetState(root: File, log: (String) -> Unit) {
        runCatching {
            val dir = File(root, "sys/class/switch/h2w")
            dir.mkdirs()
            val state = File(dir, "state")
            if (!state.isFile || state.readText() != "0\n") state.writeText("0\n")
            state.setReadable(true, false)
        }.onFailure { log("boot logo: cannot create the headset switch state (${it.message})") }
    }

    /**
     * Samsung's `powersnd` service (samsungpowersoundplay, Lollipop and later) reads the headset state before it plays PowerOn.ogg and,
     * when that fails, only logs "SamsungPowerSound: earphone state reading is failed" - in a loop, with no sound. The path is the one
     * the firmware's own program or its sound library names, so every /sys/... string in them that looks like a jack/headset switch is
     * given a "0" (no headset), next to the usual switch nodes. qemu maps a guest path into the tree only when the file exists there.
     */
    fun provideEarphoneState(root: File, binary: File?, log: (String) -> Unit) {
        runCatching {
            val paths = LinkedHashSet<String>(listOf(
                "sys/class/switch/h2w/state", "sys/devices/virtual/switch/h2w/state",
                "sys/class/switch/earjack/state", "sys/devices/virtual/switch/earjack/state"))
            val sources = ArrayList<File>()
            if (binary != null && binary.isFile) sources += binary
            for (d in listOf("system/lib64", "system/lib")) File(root, d).listFiles()
                ?.filter { it.isFile && it.name.endsWith(".so") && Regex("(?i)(powersound|soundalive|audiopolicy)").containsMatchIn(it.name) && it.length() < 8_000_000 }
                ?.let { sources += it }
            val rx = Regex("/sys/[A-Za-z0-9_./-]+")
            val keep = Regex("(?i)(switch|jack|earphone|earjack|headset|h2w|headphone)")
            for (f in sources) {
                val text = runCatching { String(f.readBytes(), Charsets.ISO_8859_1) }.getOrNull() ?: continue
                for (m in rx.findAll(text)) {
                    val p = m.value.trimStart('/')
                    if (".." in p || !keep.containsMatchIn(p) || p.endsWith('/') || p.count { it == '/' } > 8) continue
                    paths += p
                }
            }
            var made = 0
            for (rel in paths) {
                val state = File(root, rel)
                val parent = state.parentFile ?: continue
                if (!parent.canonicalPath.startsWith(root.canonicalPath + File.separator)) continue
                parent.mkdirs()
                if (!state.isFile || state.readText() != "0\n") state.writeText("0\n")
                state.setReadable(true, false); made++
            }
            log("boot sound: headset state \"0\" provided at $made paths")
        }.onFailure { log("boot sound: cannot create the headset state (${it.message})") }
    }
}

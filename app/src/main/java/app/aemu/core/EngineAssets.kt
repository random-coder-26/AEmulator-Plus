package app.aemu.core

import android.content.Context
import java.io.InputStream

/**
 * The engines' guest files (assets/engines/...) as the APK carries them: every kernel has its own copy of most of
 * them, 473 MB stored uncompressed, 110 MB of it different. The build (app/build.gradle.kts, packEngines) keeps each
 * content once, compressed, under assets/blobs/<sha1>, and writes assets/engines.idx: "path<TAB>sha1<TAB>size" per
 * file. Paths here are the old ones ("engines/kk/libGLES.so").
 *
 * A layer directory (engines/n64-<api>, -own, -gsi, n64-gl) may be split by architecture: next to its plain files it
 * can have "arm32/" and "arm64/" subdirectories with files named the same way ("a#b#c" for a/b/c). Files with
 * different names are all installed; where both carry the same name (the same path in the guest) the guest's
 * architecture decides, see [layerFiles].
 */
object EngineAssets {
    private class Entry(val blob: String, val size: Long)

    @Volatile private var index: Map<String, Entry>? = null
    @Volatile private var dirs: Map<String, List<String>>? = null

    private fun load(ctx: Context): Map<String, Entry> {
        index?.let { return it }
        synchronized(this) {
            index?.let { return it }
            val m = HashMap<String, Entry>(4096)
            val d = HashMap<String, MutableList<String>>()
            ctx.assets.open("engines.idx").bufferedReader().useLines { lines ->
                for (l in lines) {
                    val p = l.split('\t')
                    if (p.size < 3) continue
                    m[p[0]] = Entry(p[1], p[2].toLong())
                    d.getOrPut(p[0].substringBeforeLast('/')) { ArrayList() }.add(p[0].substringAfterLast('/'))
                }
            }
            dirs = d
            index = m
            return m
        }
    }

    /** names in a directory, like AssetManager.list (empty when there is none) */
    fun list(ctx: Context, dir: String): Array<String> {
        load(ctx)
        return dirs?.get(dir.trimEnd('/'))?.toTypedArray() ?: emptyArray()
    }

    const val ARM32 = "arm32"
    const val ARM64 = "arm64"

    /**
     * File name to asset path for one layer directory, resolved for a guest of the given architecture. Later wins:
     * the other architecture's subdirectory, then the plain files of the directory, then the guest's own subdirectory.
     * The guest's own architecture therefore always wins (arm32/ over arm64/ on a 32-bit guest, arm64/ over arm32/ on a
     * 64-bit one), and the plain files, which are the 64-bit programs of a layer with no arm64/ directory, are not
     * covered by the other architecture's files of the same name (modified for AEmulator Plus, 2026-11-01: the arm32
     * allocator and keymaster services of engines/n64-30 were laid over the plain 64-bit ones on a 64-bit tree and
     * could not link).
     */
    fun layerFiles(ctx: Context, dir: String, guest64: Boolean): Map<String, String> {
        load(ctx)
        val d = dir.trimEnd('/')
        val own = if (guest64) ARM64 else ARM32
        val other = if (guest64) ARM32 else ARM64
        val out = LinkedHashMap<String, String>()
        for (sub in listOf("$d/$other", d, "$d/$own")) for (n in dirs?.get(sub).orEmpty()) out[n] = "$sub/$n"
        return out
    }

    /** every file name a layer directory ships, whatever the architecture */
    fun layerNames(ctx: Context, dir: String): Set<String> {
        load(ctx)
        val d = dir.trimEnd('/')
        return listOf(d, "$d/$ARM32", "$d/$ARM64").flatMap { dirs?.get(it).orEmpty() }.toSet()
    }

    /** the asset path of one file of a layer directory: [prefer]'s subdirectory first, then the other one, then the plain files; null if none */
    fun layerAsset(ctx: Context, dir: String, name: String, prefer: String): String? {
        val d = dir.trimEnd('/')
        val other = if (prefer == ARM64) ARM32 else ARM64
        return listOf("$d/$prefer/$name", "$d/$other/$name", "$d/$name").firstOrNull { exists(ctx, it) }
    }

    fun exists(ctx: Context, path: String): Boolean = load(ctx).containsKey(path)

    /** Added for AEmulator Plus (2026-11-14): the x86-64 build of the GPU bridge (native/glbridge/build64-x86_64.sh); installed with the other common-x86_64 files */
    const val X86_64_GPU_BRIDGE = "engines/common-x86_64/system#lib64#egl#libGLES_aemu.so"

    /**
     * The engine's own x86-64 builds, split like the native engine's layers (modified for AEmulator Plus, 2026-11-14; they
     * all used to sit in engines/n64-x86_64): engines/common-x86_64 holds what does not depend on the Android release, and
     * engines/n64-<api>/x86_64 what is built for one release only (n64-30/x86_64: the audio HAL stack of android11-release),
     * in analogy to engines/n64-<api>/arm32. Later wins. The audio stack is also what an Android 10 guest (API 29) gets, so
     * n64-30/x86_64 is a layer of the API 29 kernel as well.
     */
    fun x86_64Dirs(tag: String, api: Int): List<String> =
        listOf("engines/common-x86_64", "engines/n64-$tag/x86_64") + (if (api == 29 && tag != "30") listOf("engines/n64-30/x86_64") else emptyList())

    /** file name to asset path of the x86-64 engine files for a guest of this kernel; only the "a#b#c" guest files (common-x86_64 also holds libc_scudo_stub.so, which core/ScudoStub.kt installs) */
    fun x86_64Files(ctx: Context, tag: String, api: Int): Map<String, String> {
        load(ctx)
        val out = LinkedHashMap<String, String>()
        for (d in x86_64Dirs(tag, api)) for (n in dirs?.get(d).orEmpty()) if ('#' in n) out[n] = "$d/$n"
        return out
    }

    /**
     * Guest-side helper files per guest architecture. The directories above hold ARM builds. For an x86 guest the same
     * file lives in a sibling directory with the architecture's suffix (engines/kk -> engines/kk-x86, engines/common ->
     * engines/common-x86). Where the x86 build does not exist (yet) the answer is a path that is not in the index, so
     * [size] is -1 and [exists] false for it and the installers skip the file; a machine-independent file (a dex jar,
     * a text file) falls back to the plain path because it runs the same everywhere.
     */
    fun forArch(ctx: Context, path: String, arch: GuestArch): String {
        if (arch == GuestArch.ARM) return path
        val m = Regex("^engines/(common|kk|gb)/(.+)$").find(path) ?: return path
        val alt = "engines/${m.groupValues[1]}${arch.assetSuffix}/${m.groupValues[2]}"
        if (exists(ctx, alt)) return alt
        return if (isMachineIndependent(path) && exists(ctx, path)) path else "engines/.missing-${arch.id}/${m.groupValues[2]}"
    }

    /** Dex jars and text/property files carry no machine code. */
    fun isMachineIndependent(path: String): Boolean =
        path.endsWith(".jar") || path.endsWith(".props") || path.endsWith(".xml") || path.endsWith(".txt")

    /** size in bytes, -1 when there is no such file */
    fun size(ctx: Context, path: String): Long = load(ctx)[path]?.size ?: -1L

    fun open(ctx: Context, path: String): InputStream {
        val e = load(ctx)[path] ?: throw java.io.FileNotFoundException(path)
        return ctx.assets.open("blobs/" + e.blob)
    }
}

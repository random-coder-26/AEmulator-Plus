package app.aemu.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Added for AEmulator Plus, 2026-11-17. Google TV 1.x (Logitech Revue, i386, glibc userland): system_server died with SIGSEGV
 * a few seconds after "boot completion", at the first window of the first app, every start.
 *
 * Measured on the crash dump of image 6tmuxv against the Revue firmware (b54202): EIP = libopengl_bridge.so + 0x120a, the
 * prologue of its glDrawTexiOES wrapper (EAX = 0x2d0 = the 720 it loads from its last argument; ESP - EBP = 0x18 is that
 * wrapper's frame); ESP = 0x4ec34000, CR2 = ESP - 4, and the thread's TLS sits exactly 8 MB above: the stack was used up by the
 * wrapper calling itself.
 *
 * Why: libopengl_bridge.so (SurfaceFlinger's GLES 1.x front end) exports every OES extension entry point, and in
 * opengl_bridge_init it stores eglGetProcAddress("glDrawTexiOES") and friends in a table that its wrappers jump through, with
 * no NULL or self check. The software renderer that replaces the PowerVR EGL (libGLES_android.so, installed as lib/libEGL.so) keeps
 * the answers of eglGetProcAddress in a table of R_386_32 relocations against its own global functions (glDrawTex*OES,
 * glQueryMatrixxOES, glClipPlane*, glBindBuffer.., eglCreateImageKHR..). On a glibc loader a global symbol is searched in the
 * global scope first, and libopengl_bridge.so is in it (libsurfaceflinger.so needs it) ahead of the renderer: the table is filled
 * with the bridge's own wrappers, eglGetProcAddress("glDrawTexiOES") returns the bridge's glDrawTexiOES, and the first
 * glDrawTexiOES from SurfaceFlinger (Layer::drawWithOpenGL) recurses until the stack is gone. SurfaceFlinger initialises fine
 * and draws nothing before that, which is why EGL, gralloc and fb0 all looked healthy.
 *
 * This binds those table entries to the library's own functions, the effect of -Bsymbolic-functions: every R_386_32 relocation
 * against a function the library itself defines becomes R_386_RELATIVE, with the symbol's address written into the word (REL
 * relocations keep their addend in place). Undefined symbols (the __cxa_pure_virtual vtable slots) and data symbols are left
 * alone, and so are calls through the PLT, which keep their interposition (libgdl's eglCreateWindowSurface hook is one).
 * Idempotent: once converted there is nothing left to convert.
 */
object LocalExtensionBinding {
    private const val PT_LOAD = 1
    private const val PT_DYNAMIC = 2
    private const val DT_NULL = 0
    private const val DT_SYMTAB = 6
    private const val DT_REL = 17
    private const val DT_RELSZ = 18
    private const val R_386_32 = 1
    private const val R_386_RELATIVE = 8
    private const val STT_FUNC = 2

    /** The patched copy of an i386 shared object, or null when it is not one or has nothing to bind (also: already patched). */
    fun apply(elf: ByteArray): ByteArray? = patch(elf)?.first

    /** How many relocations [apply] would convert in [elf] (0 for anything it cannot read). */
    fun count(elf: ByteArray): Int = patch(elf.copyOf())?.second ?: 0

    /** Patches [f] in place; true when the file was changed. */
    fun apply(f: File): Boolean = runCatching {
        if (!f.isFile || java.nio.file.Files.isSymbolicLink(f.toPath())) return false
        val out = apply(f.readBytes()) ?: return false
        f.outputStream().use { it.write(out) }
        true
    }.getOrDefault(false)

    private fun patch(src: ByteArray): Pair<ByteArray, Int>? = runCatching {
        if (src.size < 52 || src[0] != 0x7f.toByte() || src[1] != 'E'.code.toByte() || src[2] != 'L'.code.toByte() ||
            src[3] != 'F'.code.toByte() || src[4].toInt() != 1 || src[5].toInt() != 1) return null
        val d = src.copyOf()
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        if ((b.getShort(18).toInt() and 0xffff) != 3) return null                  // EM_386
        val phoff = b.getInt(0x1c); val phent = b.getShort(0x2a).toInt() and 0xffff; val phnum = b.getShort(0x2c).toInt() and 0xffff
        val loads = ArrayList<IntArray>()                                           // vaddr, file offset, filesz
        var dynOff = -1; var dynSize = 0
        for (i in 0 until phnum) {
            val p = phoff + i * phent
            when (b.getInt(p)) {
                PT_LOAD -> loads.add(intArrayOf(b.getInt(p + 8), b.getInt(p + 4), b.getInt(p + 16)))
                PT_DYNAMIC -> { dynOff = b.getInt(p + 4); dynSize = b.getInt(p + 16) }
            }
        }
        if (dynOff < 0) return null
        fun fo(va: Int): Int? { for (l in loads) if (va >= l[0] && va - l[0] < l[2]) return va - l[0] + l[1]; return null }
        var rel = -1; var relSz = 0; var sym = -1
        var o = dynOff
        while (o + 8 <= dynOff + dynSize) {
            val tag = b.getInt(o); val v = b.getInt(o + 4); o += 8
            if (tag == DT_NULL) break
            when (tag) { DT_REL -> if (rel < 0) rel = fo(v) ?: -1; DT_RELSZ -> relSz = v; DT_SYMTAB -> if (sym < 0) sym = fo(v) ?: -1 }
        }
        if (rel < 0 || sym < 0 || relSz <= 0) return null
        var changed = 0
        for (i in 0 until relSz / 8) {
            val r = rel + i * 8
            val off = b.getInt(r); val info = b.getInt(r + 4)
            val symIdx = info ushr 8
            if ((info and 0xff) != R_386_32 || symIdx == 0) continue
            val s = sym + symIdx * 16
            val value = b.getInt(s + 4)
            val stInfo = d[s + 12].toInt() and 0xff
            val shndx = b.getShort(s + 14).toInt() and 0xffff
            if (shndx == 0 || (stInfo and 0xf) != STT_FUNC) continue                   // undefined, or not a function
            val w = fo(off) ?: continue
            b.putInt(w, value + b.getInt(w))
            b.putInt(r + 4, R_386_RELATIVE)
            changed++
        }
        if (changed == 0) null else d to changed
    }.getOrNull()
}

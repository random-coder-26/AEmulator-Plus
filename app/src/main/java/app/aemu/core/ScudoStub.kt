/* Added for AEmulator Plus (2026-11-13). GPL-3.0; see LICENSE. */
package app.aemu.core

import java.io.File
import java.nio.file.Files

/**
 * Android 10 x86-64: mediaextractor and media.swcodec (and with them system_server) die at their first malloc().
 *
 * Why: both programs link libc_scudo.so, which puts Scudo's hardened allocator (libscudo_wrapper.so) in front of bionic's
 * malloc. The x86-64 build of that Scudo reserves one 4 TiB region for its primary allocator (AllocatorSize 0x40000000000;
 * only AArch64 gets the smaller 256 GiB one). The translator hands guest mmap()s to the phone's kernel, and the phone's
 * address space is 39 bits (512 GiB: every pointer in the logs is 0x7f_xxxx_xxxx at most), so the reservation fails:
 *     Scudo ERROR: failed to allocate 0x3000 bytes of memory at address 0x3fffffff000 (error code: 12)
 *     Scudo ERROR: CHECK failed at .../sanitizer_common.cc:54 ((0 && "unable to mmap")) != (0)
 *     (guest backtrace: MmapFixedImpl <- SizeClassAllocator64::Init <- initOnce <- scudoAllocate <- libc_scudo.so malloc+26)
 * (the reservation itself is not checked by that Scudo, it only notices when it maps the region table at the end of it).
 * mediaextractor aborts on every start, "media.extractor" never registers, the media stack the framework starts with
 * (SoundPool in AudioService, then PermissionController) waits for it for 60 s and system_server is killed:
 *     E MediaExtractorFactory: extractor service not running
 *     E AndroidRuntime: *** FATAL EXCEPTION IN SYSTEM PROCESS: android.fg
 *     java.lang.IllegalStateException: Error granting/upgrading runtime permissions
 *     E Zygote: Exit zygote because system server (pid ...) has terminated
 *
 * What: libc_scudo.so becomes an empty library of the same name (assets/engines/common-x86_64/libc_scudo_stub.so, built by
 * native/x86_64/build-scudo-stub.sh): nothing in it defines malloc, so the programs that name it keep the allocator of their
 * bionic (jemalloc on 10), which every other guest process uses already. libscudo_wrapper.so is no longer loaded.
 * The firmware's own file is kept in system/.aemu-parked. Only 64-bit x86 copies are touched: an x86-32 Scudo has a small
 * 32-bit region, an arm64 one is the 256 GiB build.
 */
internal object ScudoStub {
    const val LIB = "libc_scudo.so"

    /** where a tree keeps its libc_scudo.so: the system partitions' lib64 and the lib64 of every APEX module (swcodec has its own copy) */
    fun candidates(root: File): List<File> {
        val dirs = ArrayList<File>()
        for (d in listOf("system/lib64", "vendor/lib64", "system/vendor/lib64")) dirs += File(root, d)
        File(root, "apex").listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { dirs += File(it, "lib64") }
        return dirs.map { File(it, LIB) }.filter { it.isFile }
    }

    /** a 64-bit x86 shared object: ELF64, little endian, ET_DYN, EM_X86_64 */
    fun isX8664SharedObject(f: File): Boolean = runCatching {
        f.inputStream().use { i ->
            val h = ByteArray(20)
            if (i.read(h) != 20) return@use false
            h[0] == 0x7f.toByte() && h[1] == 'E'.code.toByte() && h[2] == 'L'.code.toByte() && h[3] == 'F'.code.toByte() &&
                h[4] == 2.toByte() && h[5] == 1.toByte() &&
                (h[16].toInt() and 0xff) == 3 && (h[17].toInt() and 0xff) == 0 &&       // e_type ET_DYN
                (h[18].toInt() and 0xff) == 62 && (h[19].toInt() and 0xff) == 0         // e_machine EM_X86_64
        }
    }.getOrDefault(false)

    /** True for a libc_scudo.so that is to be replaced: an x86-64 library that is not the stub already */
    fun needsStub(f: File, stub: ByteArray): Boolean {
        if (!f.isFile) return false
        if (f.length() == stub.size.toLong() && runCatching { f.readBytes().contentEquals(stub) }.getOrDefault(false)) return false
        return isX8664SharedObject(f)
    }

    /** Puts [stub] in place of every libc_scudo.so of [root] that needs it; returns the guest paths that were replaced. */
    fun apply(root: File, stub: ByteArray, log: (String) -> Unit): List<String> {
        val done = ArrayList<String>()
        for (f in candidates(root)) {
            if (!needsStub(f, stub)) continue
            val rel = f.relativeTo(root).path
            runCatching {
                val parked = File(root, "system/.aemu-parked/" + rel.replace('/', '#'))
                parked.parentFile?.mkdirs()
                // an earlier start has parked the firmware's file already: that one is the original, never overwrite it
                if (!parked.exists()) f.copyTo(parked)
                if (Files.isSymbolicLink(f.toPath())) Files.delete(f.toPath())
                f.writeBytes(stub)
                f.setReadable(true, false)
                f.setExecutable(true, false)
                done += "/$rel"
            }.onFailure { log("scudo: /$rel not replaced: ${it.message}") }
        }
        if (done.isNotEmpty()) log("scudo: ${done.joinToString()} replaced by an empty library; mediaextractor and media.swcodec use bionic's malloc (the 4 TiB region of the x86-64 Scudo does not fit the phone's 39-bit address space)")
        return done
    }
}

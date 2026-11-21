package app.aemu.core

import java.io.File
import java.nio.file.Files
import org.junit.Test
import org.junit.Assert.*

class ScudoStubTest {
    /** the stub the app ships (unit tests run in app/) */
    private val stub = File("src/main/assets/engines/common-x86_64/libc_scudo_stub.so").readBytes()

    /** the first 64 bytes of an ELF64 shared object for the given machine, followed by a tail that tells the files apart */
    private fun elf(machine: Int, cls: Int = 2, type: Int = 3, tail: String = "firmware"): ByteArray {
        val b = ByteArray(64)
        b[0] = 0x7f; b[1] = 'E'.code.toByte(); b[2] = 'L'.code.toByte(); b[3] = 'F'.code.toByte()
        b[4] = cls.toByte(); b[5] = 1
        b[16] = type.toByte(); b[18] = (machine and 0xff).toByte(); b[19] = (machine shr 8).toByte()
        return b + tail.toByteArray()
    }

    private fun fixture(test: (File) -> Unit) {
        val root = Files.createTempDirectory("aess-scudo").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }

    private fun put(root: File, rel: String, bytes: ByteArray) = File(root, rel).apply { parentFile!!.mkdirs(); writeBytes(bytes) }

    @Test fun theShippedStubIsAnEmptyX8664LibraryNamedLikeScudoShim() {
        assertTrue(stub.size in 1000..16000)
        assertEquals(0x7f, stub[0].toInt()); assertEquals('E'.code, stub[1].toInt())
        assertEquals(2, stub[4].toInt())                         // ELF64
        assertEquals(3, stub[16].toInt())                        // ET_DYN
        assertEquals(62, stub[18].toInt() and 0xff)              // EM_X86_64
        val text = String(stub, Charsets.ISO_8859_1)
        assertTrue(text.contains("libc_scudo.so"))               // DT_SONAME
        assertTrue(text.contains("__aemu_scudo_stub"))           // the one marker symbol
        for (sym in listOf("malloc", "free", "calloc", "realloc", "memalign", "posix_memalign", "libc.so"))
            assertFalse("the stub must not define or import $sym", text.replace("libc_scudo.so", "").contains(sym))
    }

    @Test fun systemAndApexCopiesAreReplacedAndTheOriginalsParked() = fixture { root ->
        val own = elf(62)
        put(root, "system/lib64/libc_scudo.so", own)
        put(root, "apex/com.android.media.swcodec/lib64/libc_scudo.so", own)
        val done = ScudoStub.apply(root, stub) { }
        assertEquals(listOf("/system/lib64/libc_scudo.so", "/apex/com.android.media.swcodec/lib64/libc_scudo.so").sorted(), done.sorted())
        assertArrayEquals(stub, File(root, "system/lib64/libc_scudo.so").readBytes())
        assertArrayEquals(stub, File(root, "apex/com.android.media.swcodec/lib64/libc_scudo.so").readBytes())
        assertArrayEquals(own, File(root, "system/.aemu-parked/system#lib64#libc_scudo.so").readBytes())
        assertArrayEquals(own, File(root, "system/.aemu-parked/apex#com.android.media.swcodec#lib64#libc_scudo.so").readBytes())
    }

    @Test fun secondStartChangesNothing() = fixture { root ->
        put(root, "system/lib64/libc_scudo.so", elf(62))
        assertEquals(1, ScudoStub.apply(root, stub) { }.size)
        assertTrue(ScudoStub.apply(root, stub) { }.isEmpty())
        assertArrayEquals(stub, File(root, "system/lib64/libc_scudo.so").readBytes())
    }

    @Test fun anOlderStubDoesNotOverwriteTheParkedFirmwareFile() = fixture { root ->
        val own = elf(62)
        put(root, "system/lib64/libc_scudo.so", own)
        ScudoStub.apply(root, stub) { }
        // a later build of the app ships a different stub: the file in place is replaced, the parked original stays the firmware's
        val older = elf(62, tail = "old stub")
        File(root, "system/lib64/libc_scudo.so").writeBytes(older)
        ScudoStub.apply(root, stub) { }
        assertArrayEquals(stub, File(root, "system/lib64/libc_scudo.so").readBytes())
        assertArrayEquals(own, File(root, "system/.aemu-parked/system#lib64#libc_scudo.so").readBytes())
    }

    @Test fun otherArchitecturesAndClassesAreLeftAlone() = fixture { root ->
        val arm64 = elf(183)
        val x86 = elf(3, cls = 1)
        put(root, "system/lib64/libc_scudo.so", arm64)       // the arm64 Scudo is the 256 GiB build and works
        put(root, "system/lib/libc_scudo.so", x86)           // x86-32: small 32-bit region, and the 32-bit directory is not looked at
        put(root, "vendor/lib64/libc_scudo.so", elf(62, cls = 1))
        assertTrue(ScudoStub.apply(root, stub) { }.isEmpty())
        assertArrayEquals(arm64, File(root, "system/lib64/libc_scudo.so").readBytes())
        assertArrayEquals(x86, File(root, "system/lib/libc_scudo.so").readBytes())
        assertFalse(File(root, "system/.aemu-parked").exists())
    }

    @Test fun treeWithoutScudoIsUntouched() = fixture { root ->
        put(root, "system/lib64/libc.so", elf(62))
        assertTrue(ScudoStub.candidates(root).isEmpty())
        assertTrue(ScudoStub.apply(root, stub) { }.isEmpty())
    }

    @Test fun aLinkedCopyIsReplacedByAFileAndTheLinkTargetStaysTheFirmwares() = fixture { root ->
        val own = elf(62)
        val real = put(root, "apex/com.android.media.swcodec/lib64/libc_scudo.so", own)
        File(root, "system/lib64").mkdirs()
        Files.createSymbolicLink(File(root, "system/lib64/libc_scudo.so").toPath(), real.toPath())
        val done = ScudoStub.apply(root, stub) { }
        assertEquals(2, done.size)
        assertFalse(Files.isSymbolicLink(File(root, "system/lib64/libc_scudo.so").toPath()))
        assertArrayEquals(stub, File(root, "system/lib64/libc_scudo.so").readBytes())
        assertArrayEquals(stub, real.readBytes())
    }

    @Test fun truncatedFilesAreNotElf() = fixture { root ->
        put(root, "system/lib64/libc_scudo.so", ByteArray(7))
        assertFalse(ScudoStub.needsStub(File(root, "system/lib64/libc_scudo.so"), stub))
    }
}

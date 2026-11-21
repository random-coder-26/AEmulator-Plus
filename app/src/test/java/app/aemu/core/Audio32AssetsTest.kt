package app.aemu.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 32-bit audio HAL 2.0 set (engines/common/audio32) that TreeFixer.installAudio32Hal puts next to the 32-bit audio service
 * of an Android 11 board: all four must be 32-bit ARM shared objects whose SONAME is the name the service and the linker ask for.
 */
class Audio32AssetsTest {
    private val dir = File("src/main/assets/engines/common/audio32")
    private val names = listOf(
        "android.hardware.audio@2.0-impl.so",
        "android.hardware.audio.effect@2.0-impl.so",
        "android.hardware.audio.common@2.0-util.so",
        "audio.primary.default.so",
    )

    @Test fun allFourFilesAreThere() {
        for (n in names) assertTrue("$n missing", File(dir, n).isFile)
    }

    @Test fun eachIsA32BitArmElf() {
        for (n in names) {
            val h = File(dir, n).inputStream().use { i -> ByteArray(20).also { i.read(it) } }
            assertEquals("$n: ELF magic", 0x7f, h[0].toInt())
            assertEquals("$n: ELF magic", 'E'.code, h[1].toInt())
            assertEquals("$n: ELFCLASS32", 1, h[4].toInt())
            assertEquals("$n: EM_ARM (40)", 40, (h[18].toInt() and 0xff) or ((h[19].toInt() and 0xff) shl 8))
        }
    }

    @Test fun eachCarriesItsOwnSoname() {
        for (n in names) assertTrue("$n: soname", String(File(dir, n).readBytes(), Charsets.ISO_8859_1).contains(n))
    }

    /**
     * 2026-11-14: the HIDL libraries of an Android 10+ firmware are built without RTTI and export no typeinfo (_ZTI*), while these
     * HALs were built with it. A strong undefined _ZTI reference makes dlopen() fail ("cannot locate symbol
     * _ZTIN7android8hardware5audio4V2_07IDeviceE"), the audio service aborts with "Could not register Audio Core API" (exit 134)
     * and AudioFlinger never gets its factory. tools/weaken_typeinfo.py makes them weak; this keeps a rebuilt asset honest.
     */
    @Test fun noStrongUndefinedTypeinfoReferences() {
        for (n in names) {
            val strong = strongUndefinedTypeinfo(File(dir, n).readBytes())
            assertTrue("$n: strong undefined typeinfo (run tools/weaken_typeinfo.py): $strong", strong.isEmpty())
        }
    }

    private fun strongUndefinedTypeinfo(elf: ByteArray): List<String> {
        val b = ByteBuffer.wrap(elf).order(ByteOrder.LITTLE_ENDIAN)
        val shoff = b.getInt(0x20); val shentsize = b.getShort(0x2E).toInt() and 0xffff; val shnum = b.getShort(0x30).toInt() and 0xffff
        fun sh(i: Int, field: Int) = b.getInt(shoff + i * shentsize + field)
        val out = ArrayList<String>()
        for (i in 0 until shnum) {
            val type = sh(i, 4)
            if (type != 2 && type != 11) continue // SHT_SYMTAB, SHT_DYNSYM
            val off = sh(i, 16); val size = sh(i, 20); val strOff = sh(sh(i, 24), 16); val ent = sh(i, 36)
            for (k in 0 until size / ent) {
                val at = off + k * ent
                val info = b.get(at + 12).toInt() and 0xff
                val shndx = b.getShort(at + 14).toInt() and 0xffff
                if (shndx != 0 || (info shr 4) != 1) continue // defined, or not STB_GLOBAL
                var e = strOff + b.getInt(at); val start = e
                while (elf[e] != 0.toByte()) e++
                val name = String(elf, start, e - start, Charsets.ISO_8859_1)
                if (name.startsWith("_ZTI")) out += name
            }
        }
        return out
    }
}

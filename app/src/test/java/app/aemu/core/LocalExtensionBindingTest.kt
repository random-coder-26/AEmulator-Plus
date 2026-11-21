package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The Revue crash this guards against: the software renderer's eglGetProcAddress table (R_386_32 against its own glDrawTex*OES ..)
 * resolved to libopengl_bridge.so's exports, whose wrappers call the same table: an endless recursion in system_server.
 * A synthetic i386 ELF with the same shape: one local function (relocated, address 0x1234 in the word), one undefined symbol
 * (a __cxa_pure_virtual slot: left alone) and one non-function symbol.
 */
class LocalExtensionBindingTest {
    private val symtab = 0x100; private val rel = 0x150; private val words = 0x180; private val dyn = 0x1a0; private val end = 0x1e0

    private fun elf(): ByteArray {
        val b = ByteBuffer.allocate(end).order(ByteOrder.LITTLE_ENDIAN)
        b.put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 1, 1, 1))
        b.putShort(16, 3); b.putShort(18, 3)                          // ET_DYN, EM_386
        b.putInt(0x1c, 52); b.putShort(0x2a, 32); b.putShort(0x2c, 2)
        // PT_LOAD: the whole file at vaddr 0
        b.putInt(52, 1); b.putInt(52 + 4, 0); b.putInt(52 + 8, 0); b.putInt(52 + 16, end); b.putInt(52 + 20, end)
        // PT_DYNAMIC
        b.putInt(84, 2); b.putInt(84 + 4, dyn); b.putInt(84 + 8, dyn); b.putInt(84 + 16, 4 * 8); b.putInt(84 + 20, 4 * 8)
        // symbols: 0 null, 1 local FUNC (shndx 10), 2 undefined FUNC, 3 local OBJECT
        fun sym(i: Int, value: Int, info: Int, shndx: Int) { b.putInt(symtab + i * 16 + 4, value); b.put(symtab + i * 16 + 12, info.toByte()); b.putShort(symtab + i * 16 + 14, shndx.toShort()) }
        sym(1, 0x1234, 0x12, 10); sym(2, 0, 0x12, 0); sym(3, 0x2000, 0x11, 11)
        // relocations: word0 -> sym1, word1 -> sym2, word2 -> sym3 (all R_386_32)
        for (i in 0..2) { b.putInt(rel + i * 8, words + i * 4); b.putInt(rel + i * 8 + 4, ((i + 1) shl 8) or 1) }
        b.putInt(words, 0x10)                                          // an addend kept in place is added to the symbol value
        for ((i, tv) in listOf(17 to rel, 18 to 24, 6 to symtab, 0 to 0).withIndex()) { b.putInt(dyn + i * 8, tv.first); b.putInt(dyn + i * 8 + 4, tv.second) }
        return b.array()
    }

    @Test fun localFunctionBecomesRelative() {
        val out = LocalExtensionBinding.apply(elf())
        assertNotNull(out)
        val b = ByteBuffer.wrap(out!!).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(8, b.getInt(rel + 4))                             // R_386_RELATIVE, no symbol
        assertEquals(0x1234 + 0x10, b.getInt(words))
    }

    @Test fun undefinedAndDataSymbolsAreLeftAlone() {
        val b = ByteBuffer.wrap(LocalExtensionBinding.apply(elf())!!).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals((2 shl 8) or 1, b.getInt(rel + 8 + 4))
        assertEquals((3 shl 8) or 1, b.getInt(rel + 16 + 4))
    }

    @Test fun countsAndIsIdempotent() {
        assertEquals(1, LocalExtensionBinding.count(elf()))
        val once = LocalExtensionBinding.apply(elf())!!
        assertNull(LocalExtensionBinding.apply(once))
        assertEquals(0, LocalExtensionBinding.count(once))
    }

    @Test fun otherFilesAreNotTouched() {
        assertNull(LocalExtensionBinding.apply(ByteArray(10)))
        assertNull(LocalExtensionBinding.apply(elf().also { it[18] = 40 }))   // not i386
    }
}

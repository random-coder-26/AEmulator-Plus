package app.aemu.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcomGpuTest {
    @Test fun parkedGrallocIsPutBack() {
        val root = File.createTempFile("tree", "").apply { delete(); mkdirs() }
        File(root, "system/lib/hw").mkdirs()
        val parked = File(root, "system/.aemu-parked/system#lib#hw#gralloc.default.so.gememalloc")
        parked.parentFile.mkdirs(); parked.writeText("fw")
        assertEquals(1, BroadcomGpu.restoreParkedGralloc(root))
        assertTrue(File(root, "system/lib/hw/gralloc.default.so").isFile)
        assertEquals(0, BroadcomGpu.restoreParkedGralloc(root))
    }
}

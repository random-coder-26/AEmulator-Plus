package app.aemu.core

import java.io.File
<<<<<<< HEAD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
=======
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
>>>>>>> efb4f53f81abf064295e4304362fbc4a225f7e1d

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

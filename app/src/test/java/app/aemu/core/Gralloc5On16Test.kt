package app.aemu.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-11-15: a Google Pixel 6 Pro image of Android 16 (API 36) died in SurfaceFlinger on its first start ("Gralloc4: mapper 4.x is not
 * supported", "gralloc-mapper is missing", exit code 134): its libui takes a mapper 4 or 5 only, as the
 * one of Android 17 does. The engine's gralloc5 (AIDL IAllocator/default + mapper.aemu.so) of n64-37 goes in for such a build from
 * engines/n64-36-gralloc5, and has to be the same binaries.
 */
class Gralloc5On16Test {
    private val e = File("src/main/assets/engines")

    @Test fun layerOf16ShipsTheFilesOf17() {
        assertEquals("engines/n64-36-gralloc5", Kernel64.A16.gralloc5Assets)
        for (n in listOf("vendor#lib64#hw#mapper.aemu.so", "vendor#bin#hw#android.hardware.graphics.allocator-service.aemu",
                "vendor#etc#init#android.hardware.graphics.allocator-service.aemu.rc")) {
            val a = File(e, "n64-36-gralloc5/$n"); val b = File(e, "n64-37/$n")
            assertTrue("$n missing", a.isFile && b.isFile)
            assertArrayEquals(n, b.readBytes(), a.readBytes())
        }
    }

    @Test fun layerDeclaresTheAidlAllocatorAndTheNativeMapper() {
        val x = File(e, "n64-36-gralloc5/vendor#etc#vintf#manifest#aemu-gralloc5.xml").readText()
        assertTrue(x.contains("<name>android.hardware.graphics.allocator</name>") && x.contains("<fqname>IAllocator/default</fqname>"))
        assertTrue(x.contains("<name>mapper</name>") && x.contains("<instance>aemu</instance>"))
    }

    @Test fun gralloc16AndGralloc17ModulesAreTheSame() {
        for (n in listOf("vendor#lib64#hw#gralloc.default.so", "vendor#lib64#libgralloc_aosp.so"))
            assertArrayEquals(n, File(e, "n64-37/$n").readBytes(), File(e, "n64-36/$n").readBytes())
    }
}

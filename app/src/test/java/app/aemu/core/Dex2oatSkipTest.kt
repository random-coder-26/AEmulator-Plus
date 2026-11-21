/* Added for AEmulator Plus (2026-11-13). GPL-3.0; see LICENSE. */
package app.aemu.core

import java.io.File
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class Dex2oatSkipTest {
    /** Runs the wrapper's loop with `exec` replaced by `echo real`, so the answer is 1 (refused) or 0 (passed on). */
    private fun run(vararg args: String): Int {
        val body = Dex2oatSkip.script("apex/com.android.runtime/bin", "dex2oat")
            .lines().filterNot { it.startsWith("#!") }
            .joinToString("\n") { if (it.startsWith("exec ")) "echo real" else it }
        val f = File.createTempFile("d2o", ".sh").apply { writeText(body); deleteOnExit() }
        val p = ProcessBuilder(listOf("sh", f.path) + args).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        return p.waitFor()
    }

    @Test fun refusesTheArgumentFormsInstalldUses() {
        // installd passes the jar as an fd plus its basename, never the full path
        assertEquals(1, run("--zip-fd=7", "--zip-location=services.jar", "--classpath-dir=/system/framework"))
        assertEquals(1, run("--zip-fd=7", "--zip-location=services.jar", "--oat-location=/system/framework/oat/x86_64/services.odex"))
        assertEquals(1, run("--oat-location=/data/dalvik-cache/x86_64/system@framework@services.jar@classes.dex"))
        assertEquals(1, run("--dex-file=/system/framework/services.jar"))
        assertEquals(1, run("/system/framework/services.jar"))
    }

    @Test fun letsEverythingElseThrough() {
        assertEquals(0, run("--zip-fd=7", "--zip-location=wifi-service.jar", "--classpath-dir=/system/framework"))
        assertEquals(0, run("--zip-location=framework.jar", "--oat-location=/system/framework/oat/x86_64/framework.odex"))
        assertEquals(0, run("--dex-file=/data/app/x/base.apk"))
        assertEquals(0, run())
    }

    @Test fun scriptIsAWrapperAndKeepsTheMarker() {
        assertTrue(Dex2oatSkip.script("d", "dex2oat").contains(Dex2oatSkip.MARKER))
    }
}

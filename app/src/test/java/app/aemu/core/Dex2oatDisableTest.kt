/* Added for AEmulator Plus (2026-11-17). GPL-3.0; see LICENSE. */
package app.aemu.core

import java.io.File
import java.nio.file.Files
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class Dex2oatDisableTest {
    private fun tree(): File = Files.createTempDirectory("d2od").toFile().apply { deleteOnExit() }

    private fun put(root: File, path: String, text: String): File =
        File(root, path).apply { parentFile.mkdirs(); writeText(text) }

    @Test fun offByDefaultAndSurvivesJson() {
        assertFalse(VmSettings().disableDex2oat)
        assertFalse(VmSettings.fromJson(VmSettings().toJson()).disableDex2oat)
        assertTrue(VmSettings.fromJson(VmSettings(disableDex2oat = true).toJson()).disableDex2oat)
        assertFalse(VmSettings.fromJson(org.json.JSONObject()).disableDex2oat)
    }

    @Test fun installReplacesAndUninstallRestoresTheOriginal() {
        val root = tree()
        val bin = put(root, "system/bin/dex2oat", "ELF-original")
        assertTrue(Dex2oatDisable.install(root))
        assertTrue(Dex2oatDisable.isWrapper(bin))
        assertEquals("ELF-original", File(root, "system/bin/dex2oat" + Dex2oatDisable.SUFFIX).readText())
        assertFalse("second call changes nothing", Dex2oatDisable.install(root))
        assertTrue(Dex2oatDisable.uninstall(root))
        assertEquals("ELF-original", bin.readText())
        assertFalse(File(root, "system/bin/dex2oat" + Dex2oatDisable.SUFFIX).exists())
        assertFalse(Dex2oatDisable.uninstall(root))
    }

    @Test fun anotherWrapperIsKeptAndComesBack() {
        val root = tree()
        val other = Dex2oatArgSplit.script()
        put(root, "system/bin/dex2oat", other)
        put(root, "system/bin/dex2oat.real", "ELF")
        assertTrue(Dex2oatDisable.install(root))
        assertTrue(Dex2oatDisable.uninstall(root))
        assertEquals(other, File(root, "system/bin/dex2oat").readText())
    }

    @Test fun coversTheArtApexAndTheBitnessNames() {
        val root = tree()
        val a = put(root, "apex/com.android.art/bin/dex2oat64", "x")
        val b = put(root, "apex/com.android.runtime/bin/dex2oat", "y")
        Dex2oatDisable.install(root)
        assertTrue(Dex2oatDisable.isWrapper(a)); assertTrue(Dex2oatDisable.isWrapper(b))
    }

    @Test fun missingBinariesAreNotCreated() {
        val root = tree()
        assertFalse(Dex2oatDisable.install(root))
        assertFalse(File(root, "system/bin/dex2oat").exists())
    }

    @Test fun scriptFailsWithStatusOne() {
        val f = File.createTempFile("d2o", ".sh").apply { writeText(Dex2oatDisable.script().lines().filterNot { it.startsWith("#!") }.joinToString("\n")); deleteOnExit() }
        val p = ProcessBuilder("sh", f.path).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        assertEquals(1, p.waitFor())
    }
}

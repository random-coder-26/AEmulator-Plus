/* AEmulator Plus, 2026-11-14: software-only decoder list for ExoPlayer. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MediaCodecsPolicyTest {
    @get:Rule val tmp = TemporaryFolder()

    private val xml = """
        <MediaCodecs>
          <Include href="media_codecs_google_video.xml"/>
          <Decoders>
            <MediaCodec name="OMX.qcom.video.decoder.avc" type="video/avc" >
              <Quirk name="requires-allocate-on-input-ports" />
              <Limit name="size" min="64x64" max="1920x1088" />
            </MediaCodec>
            <MediaCodec name="OMX.SEC.avc.dec" type="video/avc"/>
            <MediaCodec name="OMX.google.h264.decoder" type="video/avc"/>
          </Decoders>
          <Encoders>
            <MediaCodec name="OMX.qcom.video.encoder.avc" type="video/avc"/>
          </Encoders>
        </MediaCodecs>
    """.trimIndent()

    @Test fun hardwareDecodersGoEncodersStay() {
        val p = MediaCodecsPolicy.filter(xml, softwareIncluded = true)
        assertFalse(p.contains("OMX.qcom.video.decoder.avc"))
        assertFalse(p.contains("OMX.SEC.avc.dec"))
        assertTrue(p.contains("OMX.google.h264.decoder"))
        assertTrue(p.contains("OMX.qcom.video.encoder.avc"))
        assertEquals(p, MediaCodecsPolicy.filter(p, softwareIncluded = true))
    }

    @Test fun neverLeavesTheListWithoutADecoder() {
        val hwOnly = """<MediaCodecs><Decoders><MediaCodec name="OMX.SEC.avc.dec" type="video/avc"/></Decoders></MediaCodecs>"""
        assertEquals(hwOnly, MediaCodecsPolicy.filter(hwOnly, softwareIncluded = false))
        // the AOSP list is in the tree: the board's decoders can go
        assertFalse(MediaCodecsPolicy.filter(hwOnly, softwareIncluded = true).contains("OMX.SEC"))
    }

    @Test fun parkedAndRestored() {
        val root = tmp.newFolder("root")
        File(root, "system/etc").mkdirs()
        File(root, "system/etc/media_codecs_google_video.xml").writeText("<MediaCodecs/>")
        val f = File(root, "system/etc/media_codecs.xml").apply { writeText(xml) }
        assertEquals(listOf("system/etc/media_codecs.xml"), MediaCodecsPolicy.configure(root, true))
        assertFalse(f.readText().contains("OMX.SEC.avc.dec"))
        assertTrue(MediaCodecsPolicy.configure(root, true).isEmpty())      // already done
        assertEquals(listOf("system/etc/media_codecs.xml"), MediaCodecsPolicy.configure(root, false))
        assertEquals(xml, f.readText())
        assertTrue(MediaCodecsPolicy.configure(root, false).isEmpty())
    }

    @Test fun firmwareUpdateBecomesTheNewOriginal() {
        val root = tmp.newFolder("root2")
        File(root, "system/etc").mkdirs()
        File(root, "system/etc/media_codecs_google_video.xml").writeText("<MediaCodecs/>")
        val f = File(root, "system/etc/media_codecs.xml").apply { writeText(xml) }
        MediaCodecsPolicy.configure(root, true)
        val updated = xml.replace("OMX.SEC.avc.dec", "OMX.Exynos.avc.dec")
        f.writeText(updated)                                                // e.g. an OTA rewrote the file
        assertEquals(1, MediaCodecsPolicy.configure(root, true).size)
        assertFalse(f.readText().contains("OMX.Exynos.avc.dec"))
        MediaCodecsPolicy.configure(root, false)
        assertEquals(updated, f.readText())
    }

    @Test fun googleAndPerformanceListsAreLeftAlone() {
        val root = tmp.newFolder("root3")
        File(root, "vendor/etc").mkdirs()
        val g = File(root, "vendor/etc/media_codecs_google_video.xml").apply { writeText(xml) }
        val p = File(root, "vendor/etc/media_codecs_performance.xml").apply { writeText(xml) }
        assertTrue(MediaCodecsPolicy.configure(root, true).isEmpty())
        assertEquals(xml, g.readText()); assertEquals(xml, p.readText())
    }
}

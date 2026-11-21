package app.aemu.archive

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the Java fallback on a plain JVM; with -Daemu.nativearc.lib=<libaemuarc.so built by native/archive/build.sh host> it runs the
 * native decoders instead (same assertions).
 */
class ArcStreamsTest {
    private val data = ByteArray(300_000) { ((it * 31) xor (it shr 7)).toByte() }

    private fun gz(d: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(d) } }.toByteArray()

    @Test fun gzipRoundTripAndConcatenation() {
        assertArrayEquals(data, ArcStreams.gzip(ByteArrayInputStream(gz(data))).use { it.readBytes() })
        assertArrayEquals(data + data, ArcStreams.gzip(ByteArrayInputStream(gz(data) + gz(data))).use { it.readBytes() })
    }

    @Test fun zlibRoundTrip() {
        val z = ByteArrayOutputStream().also { o -> DeflaterOutputStream(o).use { it.write(data) } }.toByteArray()
        assertArrayEquals(data, ArcStreams.zlib(ByteArrayInputStream(z)).use { it.readBytes() })
        assertArrayEquals(data, ArcStreams.decodeAll(NativeArc.ZLIB, z))
    }

    @Test fun truncatedGzipFails() {
        val c = gz(data)
        val failed = runCatching { ArcStreams.gzip(ByteArrayInputStream(c.copyOf(c.size / 2))).use { it.readBytes() } }.isFailure
        assertTrue(failed)
    }

    @Test fun smallReadsWork() {
        ArcStreams.gzip(ByteArrayInputStream(gz(data))).use { s ->
            val first = ByteArray(10)
            assertTrue(s.read(first) > 0)
            assertTrue(s.read() >= 0)
        }
    }
}

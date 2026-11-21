/* Added for AEmulator Plus, 2026-11-15: the HTTP debugger. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.netdebug

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult

/** An established TLS session as two plain streams. */
interface SecureStream {
    val input: InputStream
    val output: OutputStream
    val protocol: String
    val cipher: String
    fun close()
}

/** What a guest's first TLS record says about it. */
class ClientHello(
    val raw: ByteArray,            // the record(s) as read from the wire
    val maxVersion: Int,           // 0x0301 = TLS 1.0 ...
    val sni: String?,
    val suites: List<Int>,
    val hasTls13: Boolean,
    val hasRenegotiationInfo: Boolean,
    val sslv2: Boolean,
    val handshake: ByteArray,      // the ClientHello handshake message (what the Finished hashes cover)
    val random: ByteArray,
)

object Tls {
    /** Reads the first record of a connection. Returns null if the bytes are not the start of a TLS handshake. */
    fun readClientHello(inp: InputStream): ClientHello? {
        val h = ByteArray(5)
        readFully(inp, h, 0, 5)
        if ((h[0].toInt() and 0xff) == 0x80 && (h[2].toInt() == 1)) return readV2(inp, h)
        if (h[0].toInt() != 0x16 || h[1].toInt() != 3) return null
        val len = ((h[3].toInt() and 0xff) shl 8) or (h[4].toInt() and 0xff)
        if (len < 4 || len > 16384 + 2048) return null
        val body = ByteArray(len); readFully(inp, body, 0, len)
        if (body[0].toInt() != 1) return null
        var hsLen = ((body[1].toInt() and 0xff) shl 16) or ((body[2].toInt() and 0xff) shl 8) or (body[3].toInt() and 0xff)
        if (hsLen > 65536) return null
        val raw = ByteArrayOutputStream().apply { write(h); write(body) }
        val hs = ByteArrayOutputStream().apply { write(body) }
        // a ClientHello can span records (rare: huge extension lists)
        while (hs.size() < hsLen + 4) {
            val h2 = ByteArray(5); readFully(inp, h2, 0, 5)
            val l2 = ((h2[3].toInt() and 0xff) shl 8) or (h2[4].toInt() and 0xff)
            if (h2[0].toInt() != 0x16 || l2 > 16384 + 2048) return null
            val b2 = ByteArray(l2); readFully(inp, b2, 0, l2)
            raw.write(h2); raw.write(b2); hs.write(b2)
        }
        val msg = hs.toByteArray().copyOf(hsLen + 4)
        return parseV3(raw.toByteArray(), msg)
    }

    private fun parseV3(raw: ByteArray, msg: ByteArray): ClientHello? = runCatching {
        var p = 4
        val ver = u16(msg, p); p += 2
        val random = msg.copyOfRange(p, p + 32); p += 32
        p += 1 + (msg[p].toInt() and 0xff)
        val nSuites = u16(msg, p); p += 2
        val suites = ArrayList<Int>()
        for (i in 0 until nSuites / 2) suites += u16(msg, p + i * 2)
        p += nSuites
        p += 1 + (msg[p].toInt() and 0xff)
        var sni: String? = null; var tls13 = false; var reneg = 0x00ff in suites
        if (p + 2 <= msg.size) {
            val extEnd = p + 2 + u16(msg, p); p += 2
            while (p + 4 <= extEnd) {
                val type = u16(msg, p); val l = u16(msg, p + 2); val b = p + 4
                when (type) {
                    0 -> if (l >= 5) {                                       // server_name
                        var q = b + 2
                        while (q + 3 <= b + l) {
                            val nt = msg[q].toInt(); val nl = u16(msg, q + 1)
                            if (nt == 0) { sni = String(msg, q + 3, nl, Charsets.US_ASCII); break }
                            q += 3 + nl
                        }
                    }
                    43 -> {                                                 // supported_versions
                        var q = b + 1
                        while (q + 2 <= b + l) { if (u16(msg, q) == 0x0304) tls13 = true; q += 2 }
                    }
                    0xff01 -> reneg = true
                }
                p = b + l
            }
        }
        ClientHello(raw, ver, sni?.lowercase(), suites, tls13, reneg, false, msg, random)
    }.getOrNull()

    /** An SSLv2-format ClientHello (what a few very old stacks send first): 0x80 len | 1 | version | n | m | c | specs | id | challenge */
    private fun readV2(inp: InputStream, h: ByteArray): ClientHello? = runCatching {
        val len = h[1].toInt() and 0xff
        if (len < 9) return null
        val body = ByteArray(len)
        System.arraycopy(h, 2, body, 0, 3)
        readFully(inp, body, 3, len - 3)
        val ver = u16(body, 1)
        val nSpec = u16(body, 3); val nId = u16(body, 5); val nCh = u16(body, 7)
        if (9 + nSpec + nId + nCh > len) return null
        val suites = ArrayList<Int>()
        var p = 9
        for (i in 0 until nSpec / 3) { if (body[p + i * 3].toInt() == 0) suites += u16(body, p + i * 3 + 1) }
        p += nSpec + nId
        val ch = body.copyOfRange(p, p + nCh)
        val random = ByteArray(32); System.arraycopy(ch, maxOf(0, nCh - 32), random, 32 - minOf(32, nCh), minOf(32, nCh))
        ClientHello(byteArrayOf(h[0], h[1]) + body, ver, null, suites, false, 0x00ff in suites, true, body, random)
    }.getOrNull()

    /** The record layer is not needed by the JSSE path: [ClientHello.raw] is fed to the engine as its first network bytes. */
    fun serverEngine(leaf: CertAuthority.Leaf): SSLEngine {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("k", leaf.key, CharArray(0), leaf.chain)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, CharArray(0))
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, null)
        val e = ctx.createSSLEngine()
        e.useClientMode = false
        // everything the phone's TLS stack still speaks (older versions are usually disabled by default)
        runCatching { e.enabledProtocols = e.supportedProtocols.filter { it != "SSLv2Hello" && it != "SSLv3" }.toTypedArray() }
        runCatching { e.enabledCipherSuites = e.supportedCipherSuites.filter { !it.contains("_anon_") && !it.contains("_NULL_") && !it.contains("EXPORT") }.toTypedArray() }
        return e
    }

    fun u16(b: ByteArray, p: Int) = ((b[p].toInt() and 0xff) shl 8) or (b[p + 1].toInt() and 0xff)

    fun readFully(inp: InputStream, b: ByteArray, off: Int, len: Int) {
        var n = 0
        while (n < len) {
            val r = inp.read(b, off + n, len - n)
            if (r < 0) throw EOFException("connection closed")
            n += r
        }
    }
}

/** A server-side TLS session driven through an [SSLEngine] over blocking streams. */
class EngineStream(
    private val engine: SSLEngine,
    private val rawIn: InputStream,
    private val rawOut: OutputStream,
    firstBytes: ByteArray,
) : SecureStream {
    private val net = engine.session.packetBufferSize.coerceAtLeast(16 * 1024 + 2048)
    private var netIn: ByteBuffer = ByteBuffer.allocate(net + firstBytes.size).also { it.put(firstBytes); it.flip() }
    private var appIn: ByteBuffer = ByteBuffer.allocate(engine.session.applicationBufferSize.coerceAtLeast(16 * 1024 + 2048)).also { it.flip() }
    private var closed = false

    override val protocol: String get() = engine.session.protocol
    override val cipher: String get() = engine.session.cipherSuite

    init { handshake() }

    private fun fill(): Boolean {
        netIn.compact()
        val n = try { rawIn.read(netIn.array(), netIn.position(), netIn.remaining()) } catch (e: IOException) { -1 }
        if (n > 0) netIn.position(netIn.position() + n)
        netIn.flip()
        return n > 0
    }

    private fun flushWrap(src: ByteBuffer) {
        while (true) {
            val out = ByteBuffer.allocate(net)
            val r = engine.wrap(src, out)
            out.flip()
            if (out.hasRemaining()) { rawOut.write(out.array(), 0, out.limit()); rawOut.flush() }
            if (r.status == SSLEngineResult.Status.CLOSED) return
            if (r.status == SSLEngineResult.Status.BUFFER_OVERFLOW) continue
            if (!src.hasRemaining() && r.handshakeStatus != SSLEngineResult.HandshakeStatus.NEED_WRAP) return
            if (r.bytesConsumed() == 0 && r.bytesProduced() == 0) return
        }
    }

    private fun handshake() {
        engine.beginHandshake()
        val empty = ByteBuffer.allocate(0)
        var guard = 0
        while (guard++ < 200) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_TASK -> { var t = engine.delegatedTask; while (t != null) { t.run(); t = engine.delegatedTask } }
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> flushWrap(empty)
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    if (!netIn.hasRemaining() && !fill()) throw EOFException("closed during handshake")
                    appIn.clear()
                    val r = engine.unwrap(netIn, appIn)
                    appIn.flip()
                    if (r.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) { if (!fill()) throw EOFException("closed during handshake") }
                    if (r.status == SSLEngineResult.Status.CLOSED) throw EOFException("closed during handshake")
                }
                else -> return
            }
        }
        throw IOException("TLS handshake did not finish")
    }

    override val input: InputStream = object : InputStream() {
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (true) {
                if (appIn.hasRemaining()) { val n = minOf(len, appIn.remaining()); appIn.get(b, off, n); return n }
                if (closed) return -1
                if (!netIn.hasRemaining() && !fill()) { closed = true; return -1 }
                appIn.clear()
                val r = try { engine.unwrap(netIn, appIn) } catch (e: javax.net.ssl.SSLException) { closed = true; throw e }
                appIn.flip()
                when (r.status) {
                    SSLEngineResult.Status.BUFFER_UNDERFLOW -> if (!fill()) { closed = true; return -1 }
                    SSLEngineResult.Status.CLOSED -> { closed = true; if (!appIn.hasRemaining()) return -1 }
                    else -> {}
                }
                // a post-handshake message (a session ticket, a key update) may need a reply
                while (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) engine.delegatedTask?.run()
                if (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) flushWrap(ByteBuffer.allocate(0))
            }
        }
    }

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            synchronized(this@EngineStream) { flushWrap(ByteBuffer.wrap(b, off, len)) }
        }
        override fun flush() = rawOut.flush()
    }

    override fun close() {
        runCatching {
            engine.closeOutbound()
            synchronized(this) { while (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) flushWrap(ByteBuffer.allocate(0)) }
        }
    }
}

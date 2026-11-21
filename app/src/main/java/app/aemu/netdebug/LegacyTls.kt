/* Added for AEmulator Plus, 2026-11-15: the HTTP debugger. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.netdebug

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A TLS 1.0 / 1.1 / 1.2 server with RSA key exchange, written out because the phone's own TLS stack cannot do what an
 * old guest needs: Android 1.0 - 2.x speak TLS 1.0 (or SSLv3-compatible hellos) with AES-CBC, 3DES or RC4, and current
 * Android builds have TLS 1.0 and RC4 switched off or gone. The guest decides about trust (it has the debugger's CA
 * in its store, see [GuestTrust]); nothing here is for the phone's own security, only for the guest's lack of it.
 *
 * Suites: TLS_RSA_WITH_{AES_128_CBC_SHA, AES_256_CBC_SHA, 3DES_EDE_CBC_SHA, RC4_128_SHA, RC4_128_MD5}, and for TLS 1.2
 * AES_{128,256}_CBC_SHA256. No resumption, no client certificates, no compression. SSL 3.0 is not served.
 */
class LegacyTlsServer(private val leaf: CertAuthority.Leaf) {
    private class Suite(val id: Int, val name: String, val macAlg: String, val macLen: Int, val keyLen: Int, val ivLen: Int, val cipher: String, val tls12Only: Boolean)

    private val suites = listOf(
        Suite(0x002F, "TLS_RSA_WITH_AES_128_CBC_SHA", "HmacSHA1", 20, 16, 16, "AES", false),
        Suite(0x0035, "TLS_RSA_WITH_AES_256_CBC_SHA", "HmacSHA1", 20, 32, 16, "AES", false),
        Suite(0x000A, "TLS_RSA_WITH_3DES_EDE_CBC_SHA", "HmacSHA1", 20, 24, 8, "DESede", false),
        Suite(0x0005, "TLS_RSA_WITH_RC4_128_SHA", "HmacSHA1", 20, 16, 0, "RC4", false),
        Suite(0x0004, "TLS_RSA_WITH_RC4_128_MD5", "HmacMD5", 16, 16, 0, "RC4", false),
        Suite(0x003C, "TLS_RSA_WITH_AES_128_CBC_SHA256", "HmacSHA256", 32, 16, 16, "AES", true),
        Suite(0x003D, "TLS_RSA_WITH_AES_256_CBC_SHA256", "HmacSHA256", 32, 32, 16, "AES", true),
    )

    fun canServe(ch: ClientHello): Boolean = ch.maxVersion >= 0x0301 && suites.any { it.id in ch.suites }

    fun accept(ch: ClientHello, rawIn: InputStream, rawOut: OutputStream): SecureStream {
        val rnd = SecureRandom()
        val version = minOf(ch.maxVersion, 0x0303)
        if (version < 0x0301) { alert(rawOut, 0x0301, 70); throw IOException("client offers SSL 3.0 only") }
        val suite = ch.suites.firstNotNullOfOrNull { id -> suites.firstOrNull { it.id == id && (!it.tls12Only || version >= 0x0303) } }
        if (suite == null) { alert(rawOut, version, 40); throw IOException("no common cipher suite") }
        val tls12 = version >= 0x0303
        val hs = ByteArrayOutputStream()                       // every handshake message so far, for the Finished checks
        hs.write(ch.handshake)

        // ---- ServerHello, Certificate, ServerHelloDone
        val serverRandom = ByteArray(32).also { rnd.nextBytes(it) }
        val ext = if (ch.hasRenegotiationInfo) byteArrayOf(0xff.toByte(), 0x01, 0x00, 0x01, 0x00) else ByteArray(0)
        val sh = ByteArrayOutputStream().apply {
            write(version shr 8); write(version and 0xff)
            write(serverRandom)
            write(0)                                          // no session id: no resumption
            write(suite.id shr 8); write(suite.id and 0xff)
            write(0)                                          // no compression
            if (ext.isNotEmpty()) { write(ext.size shr 8); write(ext.size and 0xff); write(ext) }
        }.toByteArray()
        val certs = ByteArrayOutputStream()
        for (c in leaf.chain) { val d = c.encoded; certs.write(d.size shr 16); certs.write(d.size shr 8); certs.write(d.size); certs.write(d) }
        val certBody = certs.toByteArray()
        val certMsg = hsMsg(11, byteArrayOf((certBody.size shr 16).toByte(), (certBody.size shr 8).toByte(), certBody.size.toByte()) + certBody)
        val flight = ByteArrayOutputStream().apply { write(hsMsg(2, sh)); write(certMsg); write(hsMsg(14, ByteArray(0))) }.toByteArray()
        hs.write(flight)
        record(rawOut, 22, version, flight)
        rawOut.flush()

        // ---- ClientKeyExchange, ChangeCipherSpec, Finished
        val rd = RecordReader(rawIn)
        val cke = rd.handshakeMessage()
        if (cke.type != 16) throw IOException("expected ClientKeyExchange, got ${cke.type}")
        hs.write(cke.whole)
        val body = cke.body
        val encLen = Tls.u16(body, 0)
        if (encLen + 2 > body.size) throw IOException("short ClientKeyExchange")
        val pre = decryptPremaster(body.copyOfRange(2, 2 + encLen), ch.maxVersion, rnd)

        val prf = Prf(tls12)
        val master = prf.compute(pre, "master secret", ch.random + serverRandom, 48)
        val blockCipher = suite.ivLen > 0
        val ivBytes = if (blockCipher && !tls12 && version == 0x0301) suite.ivLen else 0     // TLS 1.0: implicit CBC IVs from the key block
        val kb = prf.compute(master, "key expansion", serverRandom + ch.random, 2 * (suite.macLen + suite.keyLen) + 2 * ivBytes)
        var o = 0
        fun take(n: Int) = kb.copyOfRange(o, o + n).also { o += n }
        val cMac = take(suite.macLen); val sMac = take(suite.macLen)
        val cKey = take(suite.keyLen); val sKey = take(suite.keyLen)
        val cIv = if (ivBytes > 0) take(ivBytes) else ByteArray(0)
        val sIv = if (ivBytes > 0) take(ivBytes) else ByteArray(0)

        val inState = Protection(suite, cMac, cKey, cIv, version, false)
        val outState = Protection(suite, sMac, sKey, sIv, version, true)

        rd.expectChangeCipherSpec()
        rd.protection = inState
        val fin = rd.handshakeMessage()
        if (fin.type != 20) throw IOException("expected Finished, got ${fin.type}")
        val expected = prf.compute(master, "client finished", prf.handshakeHash(hs.toByteArray()), 12)
        if (!MessageDigest.isEqual(expected, fin.body)) { alert(rawOut, version, 51); throw IOException("bad Finished from the client") }
        hs.write(fin.whole)

        record(rawOut, 20, version, byteArrayOf(1))
        val myFin = hsMsg(20, prf.compute(master, "server finished", prf.handshakeHash(hs.toByteArray()), 12))
        record(rawOut, 22, version, myFin, outState)
        rawOut.flush()
        return LegacySession(rawIn, rawOut, rd, outState, version, suite.name, rnd)
    }

    private fun decryptPremaster(enc: ByteArray, clientVersion: Int, rnd: SecureRandom): ByteArray {
        // a premaster that cannot be read is replaced by a random one, so that the failure shows only at the Finished check
        val fallback = ByteArray(48).also { rnd.nextBytes(it); it[0] = (clientVersion shr 8).toByte(); it[1] = clientVersion.toByte() }
        return runCatching {
            val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            c.init(Cipher.DECRYPT_MODE, leaf.key)
            val p = c.doFinal(enc)
            if (p.size == 48) p else fallback
        }.getOrDefault(fallback)
    }

    private fun alert(out: OutputStream, version: Int, desc: Int) {
        runCatching { record(out, 21, version, byteArrayOf(2, desc.toByte())); out.flush() }
    }

    // ---------------------------------------------------------------------------------------------- messages and records

    private class HsMessage(val type: Int, val body: ByteArray, val whole: ByteArray)

    private fun hsMsg(type: Int, body: ByteArray) =
        byteArrayOf(type.toByte(), (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()) + body

    private fun record(out: OutputStream, type: Int, version: Int, data: ByteArray, prot: Protection? = null) {
        var off = 0
        do {
            val n = minOf(16384, data.size - off)
            val frag = data.copyOfRange(off, off + n)
            val body = prot?.seal(type, frag) ?: frag
            out.write(byteArrayOf(type.toByte(), (version shr 8).toByte(), version.toByte(), (body.size shr 8).toByte(), body.size.toByte()))
            out.write(body)
            off += n
        } while (off < data.size)
    }

    /** reads records, undoing the protection once it is switched on, and hands out whole handshake messages */
    private class RecordReader(val inp: InputStream) {
        var protection: Protection? = null
        private val pending = ByteArrayOutputStream()
        private var buf = ByteArray(0)

        fun nextRecord(): Pair<Int, ByteArray> {
            val h = ByteArray(5); Tls.readFully(inp, h, 0, 5)
            val len = Tls.u16(h, 3)
            if (len > 16384 + 2048) throw IOException("record too long")
            val body = ByteArray(len); Tls.readFully(inp, body, 0, len)
            val type = h[0].toInt() and 0xff
            return type to (protection?.open(type, body) ?: body)
        }

        fun handshakeMessage(): HsMessage {
            while (true) {
                if (buf.size >= 4) {
                    val l = ((buf[1].toInt() and 0xff) shl 16) or ((buf[2].toInt() and 0xff) shl 8) or (buf[3].toInt() and 0xff)
                    if (buf.size >= 4 + l) {
                        val whole = buf.copyOf(4 + l)
                        buf = buf.copyOfRange(4 + l, buf.size)
                        return HsMessage(buf0(whole), whole.copyOfRange(4, 4 + l), whole)
                    }
                }
                val (t, d) = nextRecord()
                when (t) {
                    22 -> buf += d
                    21 -> throw IOException("alert from the client: ${d.getOrNull(1)}")
                    else -> throw IOException("unexpected record type $t during the handshake")
                }
            }
        }

        private fun buf0(b: ByteArray) = b[0].toInt() and 0xff

        fun expectChangeCipherSpec() {
            val (t, d) = nextRecord()
            if (t != 20 || d.size != 1 || d[0].toInt() != 1) throw IOException("expected ChangeCipherSpec")
        }
    }

    // ---------------------------------------------------------------------------------------------- crypto

    private class Prf(val tls12: Boolean) {
        fun compute(secret: ByteArray, label: String, seed: ByteArray, len: Int): ByteArray {
            val ls = label.toByteArray(Charsets.US_ASCII) + seed
            if (tls12) return pHash("HmacSHA256", secret, ls, len)
            val half = (secret.size + 1) / 2
            val s1 = secret.copyOfRange(0, half); val s2 = secret.copyOfRange(secret.size - half, secret.size)
            val a = pHash("HmacMD5", s1, ls, len); val b = pHash("HmacSHA1", s2, ls, len)
            return ByteArray(len) { (a[it].toInt() xor b[it].toInt()).toByte() }
        }

        fun handshakeHash(all: ByteArray): ByteArray =
            if (tls12) MessageDigest.getInstance("SHA-256").digest(all)
            else MessageDigest.getInstance("MD5").digest(all) + MessageDigest.getInstance("SHA-1").digest(all)

        private fun pHash(alg: String, secret: ByteArray, seed: ByteArray, len: Int): ByteArray {
            val mac = Mac.getInstance(alg)
            mac.init(SecretKeySpec(secret, alg))
            val out = ByteArrayOutputStream()
            var a = seed
            while (out.size() < len) {
                a = mac.doFinal(a)
                out.write(mac.doFinal(a + seed))
            }
            return out.toByteArray().copyOf(len)
        }
    }

    /** one direction of the record protection (MAC, then CBC or RC4) */
    private class Protection(val suite: Suite, macKey: ByteArray, key: ByteArray, iv: ByteArray, val version: Int, val encrypt: Boolean) {
        private val mac = Mac.getInstance(suite.macAlg).also { it.init(SecretKeySpec(macKey, suite.macAlg)) }
        private val keySpec = SecretKeySpec(key, suite.cipher)
        private val block = suite.ivLen
        private val rc4 = if (suite.cipher == "RC4") Rc4(key) else null
        private val chain: Cipher? = if (block > 0 && version == 0x0301) cbc(iv) else null     // TLS 1.0: one cipher whose state carries from record to record
        private var seq = 0L
        private val rnd = SecureRandom()

        private fun cbc(iv: ByteArray): Cipher =
            Cipher.getInstance(suite.cipher + "/CBC/NoPadding").also { it.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv)) }

        private fun macOf(type: Int, data: ByteArray): ByteArray {
            val s = seq++
            val hdr = ByteArray(13)
            for (i in 0 until 8) hdr[i] = (s shr (56 - 8 * i)).toByte()
            hdr[8] = type.toByte(); hdr[9] = (version shr 8).toByte(); hdr[10] = version.toByte()
            hdr[11] = (data.size shr 8).toByte(); hdr[12] = data.size.toByte()
            mac.update(hdr); return mac.doFinal(data)
        }

        fun seal(type: Int, frag: ByteArray): ByteArray {
            val withMac = frag + macOf(type, frag)
            rc4?.let { return it.crypt(withMac) }
            val pad = block - (withMac.size % block)
            val plain = withMac + ByteArray(pad) { (pad - 1).toByte() }
            if (version == 0x0301) return chain!!.update(plain)
            val iv = ByteArray(block).also { rnd.nextBytes(it) }
            return iv + cbc(iv).doFinal(plain)
        }

        fun open(type: Int, body: ByteArray): ByteArray {
            val plain: ByteArray
            if (rc4 != null) plain = rc4.crypt(body)
            else if (version == 0x0301) {
                if (body.isEmpty() || body.size % block != 0) throw IOException("bad record length")
                plain = chain!!.update(body)
            } else {
                if (body.size < 2 * block || body.size % block != 0) throw IOException("bad record length")
                plain = cbc(body.copyOfRange(0, block)).doFinal(body, block, body.size - block)
            }
            var end = plain.size
            if (rc4 == null) {
                val pad = (plain[plain.size - 1].toInt() and 0xff) + 1
                if (pad > plain.size - suite.macLen) throw IOException("bad record padding")
                end = plain.size - pad
            }
            if (end < suite.macLen) throw IOException("record too short")
            val data = plain.copyOfRange(0, end - suite.macLen)
            val got = plain.copyOfRange(end - suite.macLen, end)
            if (!MessageDigest.isEqual(macOf(type, data), got)) throw IOException("bad record MAC")
            return data
        }
    }

    private class Rc4(key: ByteArray) {
        private val s = IntArray(256) { it }
        private var i = 0; private var j = 0
        init {
            var k = 0
            for (n in 0 until 256) { k = (k + s[n] + (key[n % key.size].toInt() and 0xff)) and 0xff; val t = s[n]; s[n] = s[k]; s[k] = t }
        }
        fun crypt(d: ByteArray): ByteArray = ByteArray(d.size) {
            i = (i + 1) and 0xff; j = (j + s[i]) and 0xff
            val t = s[i]; s[i] = s[j]; s[j] = t
            (d[it].toInt() xor s[(s[i] + s[j]) and 0xff]).toByte()
        }
    }

    private class LegacySession(
        private val rawIn: InputStream, private val rawOut: OutputStream, private val rd: RecordReader,
        private val out: Protection, private val version: Int, override val cipher: String, private val rnd: SecureRandom,
    ) : SecureStream {
        override val protocol: String = when (version) { 0x0301 -> "TLSv1"; 0x0302 -> "TLSv1.1"; else -> "TLSv1.2" }
        private var cur: ByteArray = ByteArray(0)
        private var pos = 0
        private var eof = false
        private val self = this

        override val input: InputStream = object : InputStream() {
            override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (pos >= cur.size) {
                    if (eof) return -1
                    val (t, d) = try { rd.nextRecord() } catch (e: EOFException) { eof = true; return -1 }
                    when (t) {
                        23 -> { cur = d; pos = 0 }
                        21 -> { eof = true; return -1 }                       // close_notify or a fatal alert: the end either way
                        else -> {}                                            // a renegotiation attempt is ignored
                    }
                }
                val n = minOf(len, cur.size - pos)
                System.arraycopy(cur, pos, b, off, n); pos += n
                return n
            }
        }

        override val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                synchronized(self) {
                    var o = off
                    while (o < off + len) {
                        val n = minOf(16384, off + len - o)
                        val body = out.seal(23, b.copyOfRange(o, o + n))
                        rawOut.write(byteArrayOf(23, (version shr 8).toByte(), version.toByte(), (body.size shr 8).toByte(), body.size.toByte()))
                        rawOut.write(body)
                        o += n
                    }
                }
            }
            override fun flush() = rawOut.flush()
        }

        override fun close() {
            runCatching {
                synchronized(self) {
                    val body = out.seal(21, byteArrayOf(1, 0))              // close_notify
                    rawOut.write(byteArrayOf(21, (version shr 8).toByte(), version.toByte(), (body.size shr 8).toByte(), body.size.toByte()))
                    rawOut.write(body); rawOut.flush()
                }
            }
        }
    }
}

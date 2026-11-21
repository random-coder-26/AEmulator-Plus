/* Added for AEmulator Plus, 2026-11-15: the HTTP debugger. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.netdebug

import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap

/**
 * The debugger's own certificate authority. HTTPS from the guest is terminated here, so the guest has to trust it: the CA
 * certificate is written into the firmware's trust store ([GuestTrust]) when the VM starts. Every host name gets a
 * leaf certificate signed on the fly; all leaves share one RSA key (signing is cheap, generating a key on a phone is not).
 *
 * Certificates are built by hand (DER) so that nothing but the JDK/Android runtime is needed. Validity runs from 1970 to
 * 2049 (the last year a UTCTime can hold, which every old X.509 parser reads): a guest's clock is often wrong.
 */
class CertAuthority private constructor(
    val caKey: PrivateKey,
    val caCert: X509Certificate,
    private val leafKeys: KeyPair,
    private val sigAlg: String,
) {
    class Leaf(val chain: Array<X509Certificate>, val key: PrivateKey)

    private val cache = ConcurrentHashMap<String, Leaf>()

    val caDer: ByteArray get() = caCert.encoded
    val caPem: String get() = pem(caDer)

    /** OpenSSL's old-style subject hash (what the file names of /system/etc/security/cacerts are made of): "<hash>.0" */
    val trustFileName: String get() = subjectHashOld(caCert) + ".0"

    fun leaf(host: String): Leaf {
        val key = host.lowercase()
        cache[key]?.let { return it }
        if (cache.size > 512) cache.clear()
        val l = Leaf(arrayOf(mint(key), caCert), leafKeys.private)
        cache[key] = l
        return l
    }

    private fun mint(host: String): X509Certificate {
        val isIp = isIpLiteral(host)
        val cn = if (host.length <= 64) host else host.takeLast(64).trimStart('.')
        val subject = Der.name(cn)
        val san = if (isIp) Der.ctx(7, false, ipBytes(host)) else Der.ctx(2, false, host.toByteArray(Charsets.US_ASCII))
        val exts = Der.seq(
            Der.ext("2.5.29.19", false, Der.seq(), ),                                   // basicConstraints: CA false
            Der.ext("2.5.29.15", true, Der.bitString(byteArrayOf(0xA0.toByte()), 5)),     // digitalSignature, keyEncipherment
            Der.ext("2.5.29.37", false, Der.seq(Der.oid("1.3.6.1.5.5.7.3.1"))),         // serverAuth
            Der.ext("2.5.29.17", false, Der.seq(san)),
            Der.ext("2.5.29.14", false, Der.octets(sha1(leafKeys.public.encoded))),      // subjectKeyIdentifier
            Der.ext("2.5.29.35", false, Der.seq(Der.ctx(0, false, sha1(caCert.publicKey.encoded)))),   // authorityKeyIdentifier
        )
        return build(subject, caCert.subjectX500Principal.encoded, leafKeys.public.encoded, exts, caKey, sigAlg)
    }

    companion object {
        const val CA_COMMON_NAME = "AEmulator HTTP Debugger CA"

        fun loadOrCreate(dir: File, sigAlg: String = "SHA256withRSA"): CertAuthority {
            dir.mkdirs()
            val kf = File(dir, "ca.key"); val cf = File(dir, "ca.crt"); val lf = File(dir, "leaf.key")
            if (kf.isFile && cf.isFile && lf.isFile) runCatching {
                val kfac = KeyFactory.getInstance("RSA")
                val key = kfac.generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
                val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(cf.readBytes())) as X509Certificate
                val leafPriv = kfac.generatePrivate(PKCS8EncodedKeySpec(lf.readBytes()))
                // the leaf public key is not stored: it is in the private key (CRT form) once more
                val leafPub = kfac.generatePublic(java.security.spec.RSAPublicKeySpec(
                    (leafPriv as java.security.interfaces.RSAPrivateCrtKey).modulus, leafPriv.publicExponent))
                return CertAuthority(key, cert, KeyPair(leafPub, leafPriv), sigAlg)
            }
            val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
            val caPair = gen.generateKeyPair()
            val leafPair = gen.generateKeyPair()
            val name = Der.name(CA_COMMON_NAME, "AEmulator Plus")
            val spki = caPair.public.encoded
            val exts = Der.seq(
                Der.ext("2.5.29.19", true, Der.seq(Der.bool(true))),                      // basicConstraints: CA
                Der.ext("2.5.29.15", true, Der.bitString(byteArrayOf(0x06), 1)),            // keyCertSign, cRLSign
                Der.ext("2.5.29.14", false, Der.octets(sha1(spki))),
            )
            val cert = build(name, name, spki, exts, caPair.private, sigAlg)
            // key files first, the certificate last: the certificate's presence marks a complete set
            File(dir, "ca.key.tmp").writeBytes(caPair.private.encoded); File(dir, "ca.key.tmp").renameTo(kf)
            File(dir, "leaf.key.tmp").writeBytes(leafPair.private.encoded); File(dir, "leaf.key.tmp").renameTo(lf)
            File(dir, "ca.crt.tmp").writeBytes(cert.encoded); File(dir, "ca.crt.tmp").renameTo(cf)
            return CertAuthority(caPair.private, cert, leafPair, sigAlg)
        }

        fun pem(der: ByteArray): String {
            val b64 = java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
            return "-----BEGIN CERTIFICATE-----\n$b64\n-----END CERTIFICATE-----\n"
        }

        /** X509_NAME_hash_old: the first four bytes of the MD5 of the DER-encoded subject, little-endian, as 8 hex digits */
        fun subjectHashOld(cert: X509Certificate): String {
            val h = MessageDigest.getInstance("MD5").digest(cert.subjectX500Principal.encoded)
            val v = (h[0].toLong() and 0xff) or ((h[1].toLong() and 0xff) shl 8) or ((h[2].toLong() and 0xff) shl 16) or ((h[3].toLong() and 0xff) shl 24)
            return "%08x".format(v)
        }

        private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b)

        fun isIpLiteral(h: String) = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(h) || (h.contains(':') && Regex("^[0-9a-fA-F:.]+$").matches(h))

        private fun ipBytes(h: String): ByteArray = java.net.InetAddress.getByName(h).address

                private fun build(subject: ByteArray, issuer: ByteArray, spki: ByteArray, exts: ByteArray, signer: PrivateKey, alg: String): X509Certificate {
            val sigOid = if (alg.startsWith("SHA1")) "1.2.840.113549.1.1.5" else "1.2.840.113549.1.1.11"
            val sigAlgId = Der.seq(Der.oid(sigOid), Der.nul())
            val serial = BigInteger(127, SecureRandom()).add(BigInteger.ONE)
            val tbs = Der.seq(
                Der.ctx(0, true, Der.integer(2)),
                Der.integer(serial),
                sigAlgId,
                issuer,
                Der.seq(Der.utcTime("700101000000Z"), Der.utcTime("491231235959Z")),
                subject,
                spki,
                Der.ctx(3, true, exts),
            )
            val sig = Signature.getInstance(alg).run { initSign(signer); update(tbs); sign() }
            val der = Der.seq(tbs, sigAlgId, Der.bitString(sig, 0))
            return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }
    }
}

/** Just enough DER to write certificates. */
internal object Der {
    private fun tlv(tag: Int, body: ByteArray): ByteArray {
        val n = body.size
        val len = when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
            n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
            else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
        }
        return byteArrayOf(tag.toByte()) + len + body
    }

    fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })
    fun integer(v: Int) = integer(BigInteger.valueOf(v.toLong()))
    fun integer(v: BigInteger) = tlv(0x02, v.toByteArray())
    fun bool(v: Boolean) = tlv(0x01, byteArrayOf(if (v) 0xff.toByte() else 0))
    fun nul() = byteArrayOf(0x05, 0x00)
    fun octets(b: ByteArray) = tlv(0x04, b)
    fun bitString(b: ByteArray, unused: Int) = tlv(0x03, byteArrayOf(unused.toByte()) + b)
    fun utcTime(s: String) = tlv(0x17, s.toByteArray(Charsets.US_ASCII))
    /** context-specific [n], constructed (explicit) or primitive (implicit) */
    fun ctx(n: Int, constructed: Boolean, body: ByteArray) = tlv(0x80 or (if (constructed) 0x20 else 0) or n, body)

    fun oid(s: String): ByteArray {
        val p = s.split('.').map { it.toLong() }
        val out = java.io.ByteArrayOutputStream()
        out.write((p[0] * 40 + p[1]).toInt())
        for (x in p.drop(2)) {
            var v = x
            val tmp = ArrayList<Int>()
            tmp.add((v and 0x7f).toInt()); v = v shr 7
            while (v > 0) { tmp.add(((v and 0x7f) or 0x80).toInt()); v = v shr 7 }
            for (b in tmp.reversed()) out.write(b)
        }
        return tlv(0x06, out.toByteArray())
    }

    /** one extension: extnID, critical (only written when true), extnValue as an OCTET STRING around [value] */
    fun ext(oid: String, critical: Boolean, value: ByteArray): ByteArray =
        if (critical) seq(oid(oid), bool(true), octets(value)) else seq(oid(oid), octets(value))

    private fun printable(s: String) = s.all { it.isLetterOrDigit() && it.code < 128 || it in " '()+,-./:=?" }

    private fun rdn(oid: String, v: String): ByteArray =
        set(seq(oid(oid), tlv(if (printable(v)) 0x13 else 0x0c, v.toByteArray(Charsets.UTF_8))))

    fun name(cn: String, org: String? = null): ByteArray =
        if (org != null) seq(rdn("2.5.4.10", org), rdn("2.5.4.3", cn)) else seq(rdn("2.5.4.3", cn))
}

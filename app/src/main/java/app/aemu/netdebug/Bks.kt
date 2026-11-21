/* Added for AEmulator Plus, 2026-11-15: the HTTP debugger. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.netdebug

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Bouncy Castle's "BKS" key store, the format of /system/etc/security/cacerts.bks (Android 1.x - 3.x keep their trusted
 * certificates there; 4.0 moved to one file per certificate). Only what a trust store needs: certificate entries.
 * A store with anything else in it, or one whose MAC does not check, is not touched ([addCertificate] returns null):
 * a broken trust store would take every HTTPS connection of the guest down.
 */
object Bks {
    private val PASSWORDS = listOf("changeit", "")

    class Entry(val alias: String, val date: Long, val type: String, val cert: ByteArray)

    class Store(val version: Int, val password: String, val entries: MutableList<Entry>)

    /** Reads a certificate-only store; null if it is another kind, or a password that is not one of the usual ones. */
    fun read(data: ByteArray): Store? = runCatching {
        val inp = DataInputStream(ByteArrayInputStream(data))
        val version = inp.readInt()
        if (version != 1 && version != 2) return null
        val salt = ByteArray(inp.readInt().also { if (it !in 0..1024) return null }); inp.readFully(salt)
        val iter = inp.readInt().also { if (it !in 1..1_000_000) return null }
        val entries = ArrayList<Entry>()
        while (true) {
            val t = inp.readUnsignedByte()
            if (t == 0) break
            if (t != 1) return null                                       // key / secret / sealed entries: not a trust store
            val alias = inp.readUTF(); val date = inp.readLong(); val type = inp.readUTF()
            val len = inp.readInt().also { if (it !in 1..1_000_000) return null }
            val cert = ByteArray(len); inp.readFully(cert)
            entries += Entry(alias, date, type, cert)
        }
        val macPos = data.size - inp.available()
        val mac = ByteArray(20); inp.readFully(mac)
        if (inp.available() != 0) return null
        for (pw in PASSWORDS) {
            if (MessageDigest.isEqual(mac(data.copyOfRange(0, macPos), pw, salt, iter, version), mac)) return Store(version, pw, entries)
        }
        null
    }.getOrNull()

    fun write(store: Store): ByteArray {
        val salt = ByteArray(20).also { java.security.SecureRandom().nextBytes(it) }
        val iter = 1024
        val body = ByteArrayOutputStream()
        val out = DataOutputStream(body)
        out.writeInt(2)
        out.writeInt(salt.size); out.write(salt)
        out.writeInt(iter)
        for (e in store.entries) {
            out.writeByte(1); out.writeUTF(e.alias); out.writeLong(e.date); out.writeUTF(e.type)
            out.writeInt(e.cert.size); out.write(e.cert)
        }
        out.writeByte(0)
        val bytes = body.toByteArray()
        return bytes + mac(bytes, store.password, salt, iter, 2)
    }

    /** [existing] plus [certDer] under [alias] (an entry of that alias is replaced); null when [existing] is not safe to rewrite */
    fun addCertificate(existing: ByteArray, alias: String, certDer: ByteArray): ByteArray? {
        val s = read(existing) ?: return null
        s.entries.removeAll { it.alias == alias }
        s.entries += Entry(alias, System.currentTimeMillis(), "X.509", certDer)
        return write(s)
    }

    fun removeAlias(existing: ByteArray, alias: String): ByteArray? {
        val s = read(existing) ?: return null
        if (s.entries.none { it.alias == alias }) return existing
        s.entries.removeAll { it.alias == alias }
        return write(s)
    }

    private fun mac(data: ByteArray, password: String, salt: ByteArray, iter: Int, version: Int): ByteArray {
        // BKS version 1 derives a mac key of 20 *bits* (a long-standing quirk); version 2 derives 160 bits
        val keyLen = if (version == 1) 3 else 20
        val key = pkcs12Key(password, salt, iter, 3, keyLen)
        return Mac.getInstance("HmacSHA1").run { init(SecretKeySpec(key, "HmacSHA1")); doFinal(data) }
    }

    /** PKCS#12 key derivation (RFC 7292, appendix B) with SHA-1; [id] 3 = MAC key */
    internal fun pkcs12Key(password: String, salt: ByteArray, iter: Int, id: Int, len: Int): ByteArray {
        val u = 20; val v = 64
        val pw = if (password.isEmpty()) ByteArray(0) else ByteArray(password.length * 2 + 2).also {
            for ((i, c) in password.withIndex()) { it[i * 2] = (c.code shr 8).toByte(); it[i * 2 + 1] = c.code.toByte() }
        }
        fun stretch(b: ByteArray): ByteArray = if (b.isEmpty()) b else ByteArray(v * ((b.size + v - 1) / v)) { b[it % b.size] }
        val d = ByteArray(v) { id.toByte() }
        var i = stretch(salt) + stretch(pw)
        val out = ByteArrayOutputStream()
        val sha = MessageDigest.getInstance("SHA-1")
        while (out.size() < len) {
            var a = sha.digest(d + i)
            for (r in 1 until iter) a = sha.digest(a)
            out.write(a)
            if (out.size() >= len) break
            val b = ByteArray(v) { a[it % u] }
            val blocks = i.size / v
            val ni = ByteArray(i.size)
            for (j in 0 until blocks) {
                var carry = 1
                for (k in v - 1 downTo 0) {
                    val s = (i[j * v + k].toInt() and 0xff) + (b[k].toInt() and 0xff) + carry
                    ni[j * v + k] = s.toByte(); carry = s shr 8
                }
            }
            i = ni
        }
        return out.toByteArray().copyOf(len)
    }
}

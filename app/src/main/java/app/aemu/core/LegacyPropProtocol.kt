/* Added for AEmulator Plus, 2026-11-15: property_service protocol of the pre-1.0 libcutils (JamVM / Dalvik selection).
 * GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The wire protocol of the property service as spoken by the libcutils of pre-1.0 builds (HTC "Sooner", build 20645 and
 * friends). Every later release talks the fixed 128-byte `prop_msg` (cmd 1 = setprop, see [PropService]) and reads values
 * through a shared memory area; the 2007 library has no shared area at all: `property_get`, `property_set` and the
 * `property_pfd_*` family each send a request over the stream socket `property_service` and wait for the answer.
 *
 * Recovered from libcutils.so of build 20645 (property_pfd_get / property_pfd_set / property_pfd_list, ARM Thumb):
 *
 *     request   u32 cmd | u32 keyLen | u32 valueLen | key bytes (keyLen, with the NUL) | value bytes (valueLen, with the NUL)
 *     reply     u32 status (0 = ok) | u32 count | u32 valueLen | value bytes (valueLen, with the NUL)
 *
 *     cmd 2 GET   valueLen 0; the reply carries the value. The client takes any reply of less than 12 bytes, a non-zero
 *                 status, or valueLen 0 as "no such property" and uses the default it was given.
 *     cmd 3 SET   the reply is exactly 12 bytes, status 0 (the client checks the length).
 *     cmd 6 LIST  the reply is a run of records until one whose count is 0; this service lists nothing.
 *
 * All integers are little endian. The client keeps a key to 127 characters and a value to 255 (limits checked in the library).
 * One connection may carry any number of requests (`property_pfd_open` hands out the descriptor, the caller closes it).
 *
 * Why it matters: the Android runtime of these builds chooses its virtual machine with `property_get("runtime.vm", .., "dalvik")`
 * ("jam" = JamVM 1.4.1 running the class-file jars of /javalib, "dalvik" = the dex VM). With the later protocol only, the old
 * client's request sat unanswered (the server waited for a 128-byte message that never came) or was refused, so the runtime
 * could not learn the image's `runtime.vm=jam` and went on as Dalvik, which cannot load a firmware with no classes.dex at all.
 *
 * Plain JVM code (streams in, streams out), covered by LegacyPropProtocolTest.
 */
object LegacyPropProtocol {
    const val CMD_GET = 2
    const val CMD_SET = 3
    const val CMD_LIST = 6

    /** u32 cmd, u32 keyLen, u32 valueLen. */
    const val HEADER = 12

    /** The library refuses anything longer, key and value counted with their terminating NUL. */
    const val MAX_KEY = 128
    const val MAX_VALUE = 256

    /** Status words of a reply. */
    const val OK = 0
    const val NOT_FOUND = 1
    const val BAD_REQUEST = 2

    /** True for the commands of this protocol: they cannot be mistaken for 1 (setprop of 1.0+) or 0x00020001 (protocol 2). */
    fun isLegacyCmd(cmd: Int): Boolean = cmd == CMD_GET || cmd == CMD_SET || cmd == CMD_LIST

    class Request(val cmd: Int, val key: String, val value: String)

    private fun le(i: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(i).array()

    private fun readInt(inp: InputStream): Int {
        val b = ByteArray(4)
        var n = 0
        while (n < 4) {
            val r = inp.read(b, n, 4 - n)
            if (r < 0) { if (n == 0) throw EOFException(); throw IOException("property_service: request cut short") }
            n += r
        }
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt()
    }

    private fun readBytes(inp: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var at = 0
        while (at < n) {
            val r = inp.read(b, at, n - at)
            if (r < 0) throw IOException("property_service: request cut short")
            at += r
        }
        return b
    }

    private fun cstr(b: ByteArray, from: Int, len: Int): String {
        var n = 0
        while (n < len && b[from + n] != 0.toByte()) n++
        return String(b, from, n, Charsets.UTF_8)
    }

    /**
     * Reads the rest of one request whose command word [cmd] was already read (the service reads it to tell the protocols
     * apart). Sizes outside the library's own limits are an error: nothing in the stream can be trusted after them.
     */
    fun readRequest(inp: InputStream, cmd: Int): Request {
        val keyLen = readInt(inp)
        val valueLen = readInt(inp)
        if (keyLen < 0 || keyLen > MAX_KEY || valueLen < 0 || valueLen > MAX_VALUE)
            throw IOException("property_service: bad lengths $keyLen/$valueLen for cmd $cmd")
        val data = readBytes(inp, keyLen + valueLen)
        return Request(cmd, cstr(data, 0, keyLen), cstr(data, keyLen, valueLen))
    }

    /** A reply of [status], with [value] when there is one (its NUL is counted, which is what the client's copy expects). */
    fun reply(status: Int, value: String? = null): ByteArray {
        val v = value?.let { it.toByteArray(Charsets.UTF_8).let { b -> if (b.size > MAX_VALUE - 1) b.copyOf(MAX_VALUE - 1) else b } }
        val out = ByteBuffer.allocate(HEADER + (if (v == null) 0 else v.size + 1)).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(status).putInt(0).putInt(if (v == null) 0 else v.size + 1)
        if (v != null) { out.put(v); out.put(0) }
        return out.array()
    }

    /** What the service answers to one request. [get] looks a property up, [set] applies a write. */
    fun answer(req: Request, get: (String) -> String?, set: (String, String) -> Unit): ByteArray = when (req.cmd) {
        CMD_GET -> get(req.key)?.let { reply(OK, it) } ?: reply(NOT_FOUND)
        CMD_SET -> if (req.key.isEmpty()) reply(BAD_REQUEST) else { set(req.key, req.value); reply(OK) }
        CMD_LIST -> reply(OK)   // count 0: the end of the list, at once
        else -> reply(BAD_REQUEST)
    }

    /**
     * Serves one connection until the client closes it. [firstCmd] is the command word the caller has read already.
     * A cut-off request ends the connection with an exception, a clean close after a reply does not.
     */
    fun serve(inp: InputStream, out: OutputStream, firstCmd: Int, get: (String) -> String?, set: (String, String) -> Unit,
              log: (String) -> Unit = {}) {
        var cmd = firstCmd
        while (true) {
            if (!isLegacyCmd(cmd)) { log("props: unknown pre-1.0 command $cmd"); return }
            val req = readRequest(inp, cmd)
            out.write(answer(req, get, set))
            out.flush()
            cmd = try { readInt(inp) } catch (_: EOFException) { return }
        }
    }
}

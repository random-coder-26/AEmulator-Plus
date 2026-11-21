/* Added for AEmulator Plus (2026-11-17): pre-1.0 "m3" builds (m3-rc20 / m3-rc22 / m3-rc37), GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import java.io.File
import java.security.MessageDigest
import java.util.zip.Adler32

/**
 * m3 builds boot to the animation and never start the launcher. Why:
 *
 *  - The m3 ActivityManagerService ignores systemReady() until startRunning(pkg, cls, action, data) has been called
 *    (`if (!mStartRunning) return;`, the "Start running!" log line is what follows). Nothing in Java calls it: the native
 *    /system/bin/runtime does, in main(), after AndroidRuntime::start / zygote_run_oneshot has returned and it has waited
 *    for SurfaceFlinger and the "activity" service (the `-j` / `-v` / `-d` options are its arguments).
 *  - m3's libutils speaks binder protocol 5 (ProcessState::open_driver compares the version with 5); the translator's
 *    /dev/binder answers 7. ProcessState then logs "Binder driver protocol does not match user space protocol!" and the
 *    runtime says "Binder driver not found. Processes not supported.". In that single-process mode runtime hosts the system
 *    server in its own VM: AndroidRuntime::start() ends in DestroyJavaVM, which waits for the (non-daemon) ServerThread
 *    forever, so runtime never gets to startRunning. The home activity is never resumed, there is no "Start running!" and
 *    SurfaceFlinger never leaves the boot animation. (ACTION_BOOT_COMPLETED is still delivered by other paths, which is why
 *    the host's boot watch reports completion.)
 *
 * Three edits of framework.jar's classes.dex (format "009", the pre-release dex; the code units are the same size, nothing
 * moves) make AMS do what startRunning(null, null, null, null) would, in the order the stock boot had it:
 *
 *  1. android.server.ServerThread.run: the three-instruction `ActivityManagerNative.getDefault().systemReady()` (7 code
 *     units, in a try block of its own) is moved behind the WindowManagerService.systemReady() and
 *     PowerManagerService.systemReady() calls that follow it. The stock boot ran AMS's systemReady() after those two (runtime
 *     waited for SurfaceFlinger first), and the home activity needs the window manager to be ready. The try range is moved
 *     with it, and the one `goto` of its catch handler that continues after the try is retargeted to the new end.
 *  2. ActivityManagerService.<init>: `mStartRunning = false` becomes `= true` (the literal of one const/4).
 *  3. ActivityManagerService.resumeTopActivityLocked: `iget-object mTopAction` (what startRunning stores, "MAIN" when null)
 *     becomes `const-string "android.intent.action.MAIN"` (22c and 21c are both two code units). mTopComponent and mTopData
 *     are null by default, which is what startRunning(null, null, null, null) leaves them as.
 *
 * Applied to a copy of the dex and written back only when all three succeeded; the dex's adler32 and SHA-1 (they validate
 * on 009 files the same way as on 035) are recomputed. WatchdogPatch.patchJar rewrites the zip.
 *
 * When binder works for m3 (runtime then reaches startRunning itself), this patch must be removed: systemReady() would run twice.
 *
 * No Android classes here, so it runs on a plain JVM.
 */
object AmsStartRunningPatch {
    const val AMS = "android/server/am/ActivityManagerService"
    const val SERVER_THREAD = "android/server/ServerThread"
    const val AM_NATIVE = "android/app/ActivityManagerNative"
    const val I_AM = "android/app/IActivityManager"
    const val MAIN_ACTION = "android.intent.action.MAIN"

    /** Builds this applies to: the SDK / internal "m3-rcNN" builds, whose libutils needs binder protocol 5. */
    fun appliesTo(buildId: String): Boolean = buildId.startsWith("m3-")

    private const val OP_MOVE_RESULT_OBJECT = 0x0c
    private const val OP_CONST4 = 0x12
    private const val OP_CONST_STRING = 0x18
    private const val OP_GOTO = 0x33
    private const val OP_GOTO24 = 0x34
    private const val OP_PACKED_SWITCH = 0x35
    private const val OP_SPARSE_SWITCH = 0x36
    private const val OP_IF_FIRST = 0x37
    private const val OP_IF_LAST = 0x42
    private const val OP_IGET_OBJECT = 0x53
    private const val OP_IPUT_BOOLEAN = 0x5b
    private const val OP_INVOKE_FIRST = 0x6d
    private const val OP_INVOKE_LAST = 0x76

    /** Length in code units of every physical opcode of dex 009 (-2: not an opcode). From the fork's DexInsnInfo009. */
    private val LEN = intArrayOf(
        1, 1, 2, 3, 1, 2, 3, 1, 2, 3, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 2, 3, 2, 3, 5, 2, 2, 1, 1, 1, 1, 2, 2,
        1, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 3, 3, 2, 2, 2,
        2, 2, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
        2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
        2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
        2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3,
        3, 3, 3, 3, 3, 3, 3, -2, -2, -2, -2, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
        2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
        2, 2, 2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, 3, -2,
        3, -2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, -2, -2, -2, -2,
    )

    private class Bail(msg: String) : RuntimeException(msg)

    private fun isDex009(d: ByteArray): Boolean = d.size >= 112 &&
        d[0] == 'd'.code.toByte() && d[1] == 'e'.code.toByte() && d[2] == 'x'.code.toByte() && d[3] == '\n'.code.toByte() &&
        d[4] == '0'.code.toByte() && d[5] == '0'.code.toByte() && d[6] == '9'.code.toByte() && d[7] == 0.toByte()

    private class Insn(val pos: Int, val op: Int, val len: Int, val payload: Boolean)

    private class MethodRef(val cls: String, val name: String)

    /** The parts of a dex 009 file this patch reads; every access is bounds-checked and a bad one is a [Bail]. */
    private class Dex(val d: ByteArray) {
        fun u16(o: Int): Int {
            if (o < 0 || o + 2 > d.size) throw Bail("read past the end at $o")
            return (d[o].toInt() and 0xff) or ((d[o + 1].toInt() and 0xff) shl 8)
        }
        fun u32(o: Int): Int = u16(o) or (u16(o + 2) shl 16)
        fun put16(o: Int, v: Int) { d[o] = v.toByte(); d[o + 1] = (v shr 8).toByte() }
        fun put32(o: Int, v: Int) { put16(o, v and 0xffff); put16(o + 2, (v ushr 16) and 0xffff) }

        val stringIds = u32(48); val stringIdsOff = u32(52)
        val typeIdsOff = u32(64)
        val fieldIds = u32(68); val fieldIdsOff = u32(72)
        val methodIds = u32(76); val methodIdsOff = u32(80)
        val classDefs = u32(84); val classDefsOff = u32(88)

        private val strCache = HashMap<Int, String>()
        fun string(i: Int): String {
            if (i < 0 || i >= stringIds) throw Bail("string index $i")
            return strCache.getOrPut(i) {
                val off = u32(stringIdsOff + 8 * i)
                var e = off
                while (e < d.size && d[e].toInt() != 0) e++
                if (e >= d.size || off < 0) throw Bail("string $i is not terminated")
                String(d, off, e - off, Charsets.ISO_8859_1)
            }
        }
        /** 009 keeps type names in internal form: android/server/am/ActivityManagerService, no L and ;. */
        fun type(i: Int): String = string(u32(typeIdsOff + 4 * i))
        fun fieldRef(i: Int): MethodRef {
            if (i < 0 || i >= fieldIds) throw Bail("field index $i")
            val o = fieldIdsOff + 12 * i; return MethodRef(type(u32(o)), string(u32(o + 4)))
        }
        fun methodRef(i: Int): MethodRef {
            if (i < 0 || i >= methodIds) throw Bail("method index $i")
            val o = methodIdsOff + 12 * i; return MethodRef(type(u32(o)), string(u32(o + 4)))
        }
        fun findString(s: String): Int { for (i in 0 until stringIds) if (string(i) == s) return i; return -1 }
        fun findField(cls: String, name: String): Int {
            for (i in 0 until fieldIds) { val f = fieldRef(i); if (f.cls == cls && f.name == name) return i }
            return -1
        }
        fun classDef(name: String): Int {
            for (i in 0 until classDefs) { val o = classDefsOff + 32 * i; if (type(u32(o)) == name) return o }
            return -1
        }
        /** (method index, code item offset) of the direct and virtual methods of the class def at [cd]. */
        fun methods(cd: Int): List<Pair<Int, Int>> {
            val r = ArrayList<Pair<Int, Int>>()
            for (k in intArrayOf(24, 28)) {
                val off = u32(cd + k); if (off == 0) continue
                val n = u32(off)
                if (n < 0 || n > 100000) throw Bail("method count $n")
                for (j in 0 until n) { val e = off + 4 + 16 * j; r += u32(e) to u32(e + 12) }
            }
            return r
        }
        fun unitsBase(code: Int): Int = u32(code + 12) + 4
        fun unitCount(code: Int): Int = u32(u32(code + 12))
        fun walk(code: Int): List<Insn> {
            val base = unitsBase(code); val n = unitCount(code)
            if (n < 0 || base + 2 * n > d.size) throw Bail("bad code item")
            val out = ArrayList<Insn>(); var pos = 0
            while (pos < n) {
                val u = u16(base + 2 * pos)
                if (u == 0x0100 || u == 0x0200) {
                    val size = u16(base + 2 * pos + 2)
                    val len = if (u == 0x0100) 4 + size else 2 + 3 * size
                    out += Insn(pos, u, len, true); pos += len; continue
                }
                val len = LEN[u and 0xff]
                if (len < 0) throw Bail("unknown opcode ${(u and 0xff).toString(16)} at ${pos.toString(16)}")
                out += Insn(pos, u and 0xff, len, false); pos += len
            }
            if (pos != n) throw Bail("instructions do not end at the code size")
            return out
        }
        /** (start, end, handler, offset of the entry) of every try block. */
        fun tries(code: Int): List<IntArray> {
            val off = u32(code + 16); if (off == 0) return emptyList()
            val n = u32(off); if (n < 0 || n > 10000) throw Bail("try count $n")
            return (0 until n).map { i -> val e = off + 4 + 16 * i; intArrayOf(u32(e), u32(e + 4), u32(e + 8), e) }
        }
        fun fixHeader() {
            val sha = MessageDigest.getInstance("SHA-1").digest(d.copyOfRange(32, d.size))
            System.arraycopy(sha, 0, d, 12, 20)
            val a = Adler32().apply { update(d, 12, d.size - 12) }
            put32(8, a.value.toInt())
        }
    }

    private enum class Step { DONE, ALREADY }

    /** Patches [d] (a dex) in place. [WatchdogPatch.Outcome.ABSENT] when it is not a 009 dex with the m3 ActivityManagerService. */
    fun patchDex(d: ByteArray): WatchdogPatch.Result {
        if (!isDex009(d)) return WatchdogPatch.Result(WatchdogPatch.Outcome.ABSENT, "not a dex 009")
        val w = Dex(d.copyOf())
        return try {
            val amsDef = w.classDef(AMS)
            if (amsDef < 0) return WatchdogPatch.Result(WatchdogPatch.Outcome.ABSENT)
            val stDef = w.classDef(SERVER_THREAD)
            if (stDef < 0) return WatchdogPatch.Result(WatchdogPatch.Outcome.ABSENT)
            val reorder = reorderSystemReady(w, stDef)
            val ctor = ctorStartRunning(w, amsDef)
            val action = topActionRead(w, amsDef, alreadyOthers = reorder == Step.ALREADY && ctor == Step.ALREADY)
            if (reorder == Step.ALREADY && ctor == Step.ALREADY && action == Step.ALREADY)
                return WatchdogPatch.Result(WatchdogPatch.Outcome.ALREADY)
            if (reorder == Step.ALREADY || ctor == Step.ALREADY || action == Step.ALREADY)
                throw Bail("only some of the three edits are in place (reorder=$reorder ctor=$ctor action=$action)")
            w.fixHeader()
            System.arraycopy(w.d, 0, d, 0, d.size)
            WatchdogPatch.Result(WatchdogPatch.Outcome.PATCHED)
        } catch (e: Bail) {
            WatchdogPatch.Result(WatchdogPatch.Outcome.UNEXPECTED, e.message ?: "")
        } catch (e: RuntimeException) {
            WatchdogPatch.Result(WatchdogPatch.Outcome.UNEXPECTED, "unreadable: ${e.message}")
        }
    }

    fun patchJar(jar: File, backup: File?): WatchdogPatch.Result = WatchdogPatch.patchJar(jar, backup) { patchDex(it) }

    /** Whether [jar] is exactly the stock jar [stock] with this patch applied (see [WatchdogPatch.isStockPlusPatch]). */
    fun isStockPlusPatch(jar: File, stock: File): Boolean {
        val tmp = File(jar.parentFile, jar.name + ".aemu-chk")
        return try {
            stock.copyTo(tmp, overwrite = true)
            patchJar(tmp, null)
            tmp.readBytes().contentEquals(jar.readBytes())
        } catch (e: Exception) {
            true
        } finally {
            tmp.delete()
        }
    }

    // ---- 1. ServerThread.run: AM.systemReady() after the WindowManager / PowerManager ones --------------------------------

    private fun reorderSystemReady(w: Dex, stDef: Int): Step {
        val code = w.methods(stDef).firstOrNull { w.methodRef(it.first).name == "run" && it.second != 0 }?.second
            ?: throw Bail("ServerThread.run not found")
        val insns = w.walk(code); val base = w.unitsBase(code)
        fun ref(k: Int): MethodRef? {
            val i = insns[k]
            return if (!i.payload && i.op in OP_INVOKE_FIRST..OP_INVOKE_LAST) w.methodRef(w.u16(base + 2 * i.pos + 2)) else null
        }
        fun isSystemReady(r: MethodRef?, am: Boolean) = r != null && r.name == "systemReady" && (r.cls == I_AM) == am
        var k = -1
        for (j in 0 until insns.size - 2) {
            val r0 = ref(j); val r2 = ref(j + 2)
            if (r0 != null && r0.cls == AM_NATIVE && r0.name == "getDefault" && !insns[j + 1].payload &&
                insns[j + 1].op == OP_MOVE_RESULT_OBJECT && isSystemReady(r2, true)) { k = j; break }
        }
        if (k < 0) throw Bail("ActivityManagerNative.getDefault().systemReady() not found in ServerThread.run")
        val followers = ArrayList<Int>()
        var j = k + 3
        while (j < insns.size && followers.size < 4 && isSystemReady(ref(j), false)) { followers += j; j++ }
        if (followers.isEmpty()) {
            if (k > 0 && isSystemReady(ref(k - 1), false)) return Step.ALREADY
            throw Bail("no WindowManager/PowerManager systemReady() call after the ActivityManager one")
        }
        val rs = insns[k].pos
        val re = insns[followers.last()].pos + 3
        val f = re - rs - 7
        if (insns[k + 2].pos + 3 != rs + 7 || f <= 0) throw Bail("unexpected chain size")

        // nothing else may point into (rs, re) and no try may start or end there, except the chain's own try and its goto
        val tries = w.tries(code)
        var chainTry: IntArray? = null
        for (t in tries) {
            if (t[0] == rs && t[1] == rs + 7) { chainTry = t; continue }
            for (x in intArrayOf(t[0], t[1], t[2])) if (x > rs && x < re) throw Bail("try boundary/handler ${x.toString(16)} inside the region")
        }
        val remap = ArrayList<Insn>()
        for (i in insns) {
            if (i.payload) continue
            val u = w.u16(base + 2 * i.pos)
            val target = when (i.op) {
                OP_GOTO -> i.pos + (u shr 8).toByte().toInt()
                OP_GOTO24 -> i.pos + ((((u shr 8) shl 16) or w.u16(base + 2 * i.pos + 2)) shl 8 shr 8)
                OP_PACKED_SWITCH, OP_SPARSE_SWITCH -> throw Bail("switch in ServerThread.run")
                in OP_IF_FIRST..OP_IF_LAST -> i.pos + w.u16(base + 2 * i.pos + 2).toShort().toInt()
                else -> continue
            }
            if (target > rs && target < re) {
                val h = chainTry?.get(2)
                if (target == rs + 7 && h != null && i.pos >= h && i.pos < h + 6 && (i.op == OP_GOTO || i.op == OP_GOTO24)) remap += i
                else throw Bail("branch to ${target.toString(16)} from ${i.pos.toString(16)} lands inside the region")
            }
        }

        val chain = ByteArray(14); System.arraycopy(w.d, base + 2 * rs, chain, 0, 14)
        val rest = ByteArray(2 * f); System.arraycopy(w.d, base + 2 * (rs + 7), rest, 0, 2 * f)
        System.arraycopy(rest, 0, w.d, base + 2 * rs, rest.size)
        System.arraycopy(chain, 0, w.d, base + 2 * (rs + f), chain.size)
        if (chainTry != null) { w.put32(chainTry[3], rs + f); w.put32(chainTry[3] + 4, re) }
        for (i in remap) {
            val delta = re - i.pos
            if (i.op == OP_GOTO) {
                if (delta < -128 || delta > 127) throw Bail("goto out of range")
                w.put16(base + 2 * i.pos, ((delta and 0xff) shl 8) or OP_GOTO)
            } else {
                w.put16(base + 2 * i.pos, (((delta shr 16) and 0xff) shl 8) or OP_GOTO24)
                w.put16(base + 2 * i.pos + 2, delta and 0xffff)
            }
        }
        return Step.DONE
    }

    // ---- 2. AMS.<init>: mStartRunning = true --------------------------------------------------------------------------------

    private fun ctorStartRunning(w: Dex, amsDef: Int): Step {
        val field = w.findField(AMS, "mStartRunning")
        if (field < 0) throw Bail("ActivityManagerService.mStartRunning not found")
        var result: Step? = null
        for ((mi, code) in w.methods(amsDef)) {
            if (code == 0 || w.methodRef(mi).name != "<init>") continue
            val insns = w.walk(code); val base = w.unitsBase(code)
            for (k in insns.indices) {
                val i = insns[k]
                if (i.payload || i.op != OP_IPUT_BOOLEAN || w.u16(base + 2 * i.pos + 2) != field) continue
                val reg = (w.u16(base + 2 * i.pos) shr 8) and 0xf
                val p = insns.getOrNull(k - 1)
                if (p == null || p.payload || p.op != OP_CONST4) throw Bail("no const/4 before the mStartRunning store")
                val pu = w.u16(base + 2 * p.pos)
                if (((pu shr 8) and 0xf) != reg) throw Bail("the mStartRunning store uses another register")
                when (pu shr 12) {
                    1 -> result = Step.ALREADY
                    0 -> { w.put16(base + 2 * p.pos, (1 shl 12) or (reg shl 8) or OP_CONST4); result = Step.DONE }
                    else -> throw Bail("mStartRunning starts as ${pu shr 12}")
                }
            }
        }
        return result ?: throw Bail("no store to mStartRunning in ActivityManagerService.<init>")
    }

    // ---- 3. AMS.resumeTopActivityLocked: mTopAction -> "android.intent.action.MAIN" ----------------------------------------

    private fun topActionRead(w: Dex, amsDef: Int, alreadyOthers: Boolean): Step {
        val field = w.findField(AMS, "mTopAction")
        if (field < 0) throw Bail("ActivityManagerService.mTopAction not found")
        val str = w.findString(MAIN_ACTION)
        if (str < 0 || str > 0xffff) throw Bail("\"$MAIN_ACTION\" is not a usable string index")
        var n = 0
        for ((_, code) in w.methods(amsDef)) {
            if (code == 0) continue
            val insns = w.walk(code); val base = w.unitsBase(code)
            for (i in insns) {
                if (i.payload || i.op != OP_IGET_OBJECT || w.u16(base + 2 * i.pos + 2) != field) continue
                val reg = (w.u16(base + 2 * i.pos) shr 8) and 0xf
                w.put16(base + 2 * i.pos, (reg shl 8) or OP_CONST_STRING)
                w.put16(base + 2 * i.pos + 2, str)
                n++
            }
        }
        if (n > 0) return Step.DONE
        if (alreadyOthers) return Step.ALREADY
        throw Bail("no read of mTopAction in ActivityManagerService")
    }
}

package app.aemu.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Added for AEmulator Plus, 2026-11-17. Does this firmware's SurfaceFlinger speak the composer ABI of the engine's stand-in
 * hwcomposer (native/hwc1fb, TreeFixer.framebufferComposer)?
 *
 * The stand-in is a plain AOSP HWC 1.1 module: it reads and writes the layer array of hwc_display_contents_1_t with the
 * stride of AOSP's hwc_layer_1_t (96 bytes). A vendor SurfaceFlinger (MediaTek's JB2 for one) is built against a longer,
 * vendor-extended hwc_layer_1_t and a longer HWComposer; with the stand-in it does not always crash (see [HwcFallback]),
 * it draws garbage and dies later. Measured on two images:
 *
 *   Nexus 4 JVP15Q (Android 4.2, AOSP SurfaceFlinger):
 *       HWComposer::createWorkList(int, unsigned):  movs r0,#0x60 ; muls r0,r6,r0 ; adds r0,#0x14 ; blx malloc
 *   BLU Life One (MT6589, Android 4.2.1, "[MTK HWC part]" in libsurfaceflinger.so):
 *       the same function:                          movs r0,#0xe0 ; muls r0,r6,r0 ; adds r0,#0x14 ; blx malloc
 *
 * The allocation of the work list is what tells: "sizeof(hwc_layer_1_t) * numLayers + sizeof(hwc_display_contents_1_t header)".
 * It is read from the firmware's own libsurfaceflinger.so (no firmware names, no property, nothing to keep up to date): when
 * the stride is not the stand-in's, the stand-in does not go in. If the function cannot be read (stripped, inlined, another
 * code shape) the library is searched for the extensions vendors add to SurfaceFlinger and its neighbours; with none found the
 * stand-in is used as before, and the crash fallback ([HwcFallback]) remains the last resort.
 */
object HwcAbi {
    /** sizeof(hwc_layer_1_t) in a 32-bit program, as native/hwc1fb has it (static-asserted there) */
    const val STAND_IN_LAYER_SIZE = 96

    private const val CREATE_WORK_LIST = "_ZN7android10HWComposer14createWorkListEij"
    /** sizeof of the header of hwc_display_contents_1_t that comes before the layers (retire fence, outbuf, flags, numHwLayers...) */
    private const val CONTENTS_HEADER = 0x14

    data class Verdict(val fits: Boolean, val why: String)

    /** what an AOSP SurfaceFlinger (4.2 - 4.4) links against; anything else in DT_NEEDED is the vendor's addition */
    private val AOSP_NEEDED = setOf("liblog.so", "libcutils.so", "libdl.so", "libhardware.so", "libutils.so", "libEGL.so",
        "libGLESv1_CM.so", "libGLESv2.so", "libbinder.so", "libui.so", "libgui.so", "libc.so", "libstdc++.so", "libm.so",
        "libcorkscrew.so", "libpixelflinger.so", "libhardware_legacy.so", "libsync.so", "libnativehelper.so")
    private val VENDOR_SYMBOL = Regex("MTK|Mtk|xlog_buf|Mva|GEMConvert|AuxSlot|(Flags|Usage)Ex")

    /** the verdict for the SurfaceFlinger of the tree under [root]; fits = true when there is nothing to hold it against */
    fun standInFits(root: File): Verdict {
        val sf = File(root, "system/lib/libsurfaceflinger.so")
        if (!sf.isFile) return Verdict(true, "no libsurfaceflinger.so")
        val stride = layerSize(sf)
        if (stride != null) return if (stride == STAND_IN_LAYER_SIZE)
            Verdict(true, "libsurfaceflinger.so lays out hwc_layer_1_t as $stride bytes, as the stand-in does")
        else Verdict(false, "libsurfaceflinger.so lays out hwc_layer_1_t as $stride bytes, the stand-in's is $STAND_IN_LAYER_SIZE: " +
            "a vendor-extended composer interface" + vendorEvidence(sf).let { if (it.isEmpty()) "" else " (${it.joinToString()})" })
        val evidence = vendorEvidence(sf)
        return if (evidence.isNotEmpty()) Verdict(false, "libsurfaceflinger.so is vendor-extended (${evidence.joinToString()}) and its composer layout could not be read")
        else Verdict(true, "composer layout not readable, no vendor extension found in libsurfaceflinger.so")
    }

    /** Names that only a vendor-extended SurfaceFlinger has (at most four, for the log); empty when it looks like AOSP's. */
    fun vendorEvidence(sf: File): List<String> {
        val out = ArrayList<String>()
        runCatching {
            ElfPatch.dynSyms32(sf)?.let { s ->
                (s.defined + s.undefined).filter { VENDOR_SYMBOL.containsMatchIn(it) }.sorted().take(3).forEach { out.add(it) }
            }
            ElfPatch.needed32(sf)?.filter { it !in AOSP_NEEDED }?.take(2)?.forEach { out.add("needs $it") }
        }
        return out.take(4)
    }

    /**
     * sizeof(hwc_layer_1_t) as the SurfaceFlinger library in [sf] uses it: the multiplier in HWComposer::createWorkList's
     * allocation (count * stride + 0x14). Null when the function is not there or has another shape.
     */
    fun layerSize(sf: File): Int? {
        if (sf.length() > 64L shl 20) return null
        return runCatching {
            val d = sf.readBytes()
            val at = ElfPatch.symbols(sf, setOf(CREATE_WORK_LIST))?.get(CREATE_WORK_LIST)
            if (at == null) null else {
                val thumb = ((at shr 40) and 1L) == 1L
                val off = (at and 0xffffffffffL).toInt()
                val size = (ElfPatch.symbolSize(d, CREATE_WORK_LIST) ?: 400).coerceIn(32, 1024)
                layerSizeIn(d, off, size, thumb)
            }
        }.getOrNull()
    }

    /** [layerSize] on the bytes of the function: [off] and [size] in [d], [thumb] for Thumb-2 code, else ARM */
    fun layerSizeIn(d: ByteArray, off: Int, size: Int, thumb: Boolean): Int? {
        val end = minOf(off + size, d.size)
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        if (thumb) {
            var i = off
            while (i + 6 <= end) {
                val a = b.getShort(i).toInt() and 0xffff; val m = b.getShort(i + 2).toInt() and 0xffff; val c = b.getShort(i + 4).toInt() and 0xffff
                // movs Rd, #imm8 ; muls Rd, Rm, Rd ; adds Rd, #0x14
                if (a and 0xf800 == 0x2000 && m and 0xffc0 == 0x4340 && c and 0xf8ff == (0x3000 or CONTENTS_HEADER)) {
                    val rd = (a shr 8) and 7
                    if ((m and 7) == rd && ((c shr 8) and 7) == rd && (a and 0xff) >= 0x20) return a and 0xff
                }
                i += 2
            }
        } else {
            var i = off
            while (i + 4 <= end) {
                val w = b.getInt(i)
                // mov Rd, #imm8 (no rotation)
                if (w and 0xfff00f00.toInt() == 0xe3a00000.toInt()) {
                    val rd = (w shr 12) and 0xf; val imm = w and 0xff
                    var mul = false
                    var j = i + 4
                    while (j + 4 <= minOf(i + 4 * 6, end)) {
                        val x = b.getInt(j)
                        if (x and 0x0fe000f0 == 0x00000090 && (((x shr 8) and 0xf) == rd || (x and 0xf) == rd)) mul = true
                        // add Rx, Rx, #0x14
                        if (mul && x and 0x0ff00fff == 0x02800000 or CONTENTS_HEADER && ((x shr 16) and 0xf) == ((x shr 12) and 0xf) && imm >= 0x20) return imm
                        j += 4
                    }
                }
                i += 4
            }
        }
        return null
    }
}

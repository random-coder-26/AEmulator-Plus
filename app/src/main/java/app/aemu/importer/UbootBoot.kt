package app.aemu.importer

import java.io.ByteArrayInputStream
import app.aemu.archive.ArcStreams
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The boot partition of a development board card (PandaBoard, BeagleBone, ODROID, Allwinner ...): a small FAT volume, not
 * an Android boot image. It holds the boot loader (MLO, u-boot.bin) and, for U-Boot to load, the kernel (`uImage`, `zImage`)
 * and the ramdisk (`uInitrd`: a gzip cpio with a 64 byte U-Boot header, "Android Ramdisk Image").
 *
 * [fromFat] reads those two files and packs them as the standard "ANDROID!" image (header version 0, 2048 byte pages)
 * that mkbootimg would have made, so the rest of the importer (ramdisk, kernel version, boot.img of the VM) treats the card
 * like any other firmware.
 */
internal object UbootBoot {
    private const val UIMAGE_MAGIC = 0x27051956
    private const val UIMAGE_HEADER = 64
    private const val PAGE = 2048
    private const val MAX_KERNEL = 48L shl 20
    private const val MAX_RAMDISK = 64L shl 20
    private const val MAX_DECODED = 192 shl 20
    private const val DEFAULT_KERNEL_ADDR = 0x80008000L
    private const val DEFAULT_RAMDISK_ADDR = 0x81000000L
    private const val DEFAULT_TAGS_ADDR = 0x80000100L

    /** File names (any case, in any folder, the one nearest to the root wins) in the order they are tried. */
    private val BOOT_IMAGES = listOf("boot.img", "boot_android.img")
    private val KERNELS = listOf("uimage", "zimage", "kernel", "kernel.img", "image", "vmlinuz")
    private val RAMDISKS = listOf(
        "uinitrd", "uramdisk.img", "ramdisk-uboot.img", "ramdisk.img", "ramdisk.gz", "initrd.img", "initrd.gz", "initrd",
        "initramfs.img", "initramfs.gz", "rootfs.cpio.gz",
    )
    private val CMDLINES = listOf("cmdline", "cmdline.txt")

    /** A U-Boot image taken apart: its payload, the compression and the load address the header names. */
    private class Payload(val data: ByteArray, val comp: Int, val load: Long)

    private fun unwrap(raw: ByteArray): Payload {
        if (raw.size > UIMAGE_HEADER && ByteBuffer.wrap(raw, 0, 4).order(ByteOrder.BIG_ENDIAN).int == UIMAGE_MAGIC) {
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
            val size = bb.getInt(12).toLong() and 0xffffffffL
            val load = bb.getInt(16).toLong() and 0xffffffffL
            val comp = raw[31].toInt() and 0xff
            val end = minOf(raw.size.toLong(), UIMAGE_HEADER + size).toInt()
            return Payload(raw.copyOfRange(UIMAGE_HEADER, end), comp, load)
        }
        return Payload(raw, 0, 0)
    }

    /** The ramdisk as BootImage.ramdisk reads it (gzip / lzma / xz / raw cpio), or null if it is something else. */
    private fun ramdiskBytes(p: Payload): ByteArray? {
        val d = p.data
        if (d.size < 6) return null
        if (BootImage.decompress(d) != null) return d
        // U-Boot's "bzip2" compression type: the boot image format has no place for it, so it is stored unpacked
        if (p.comp == 2 || (d[0] == 'B'.code.toByte() && d[1] == 'Z'.code.toByte() && d[2] == 'h'.code.toByte())) {
            val raw = runCatching {
                ArcStreams.bzip2(ByteArrayInputStream(d)).use { z ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = z.read(buf)
                        if (n < 0) break
                        require(out.size().toLong() + n <= MAX_DECODED)
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
            }.getOrNull()
            if (raw != null && raw.size >= 6 && String(raw, 0, 6, Charsets.ISO_8859_1).startsWith("07070")) return raw
        }
        return null
    }

    /**
     * An Android boot image made of the files in the FAT volume [volume], or null if it holds none (neither a ready boot.img
     * nor a ramdisk). A boot.img in the volume is returned as it is.
     */
    fun fromFat(volume: File, log: (String) -> Unit): ByteArray? {
        val fat = FatReader.open(volume) ?: return null
        return fat.use { f ->
            val all = f.files()
            fun find(names: List<String>): FatReader.Entry? {
                for (n in names) {
                    val hit = all.filter { it.path.substringAfterLast('/').equals(n, true) }
                        .minByOrNull { e -> e.path.count { c -> c == '/' } }
                    if (hit != null) return hit
                }
                return null
            }
            fun read(e: FatReader.Entry, cap: Long): ByteArray? {
                if (e.size < 1 || e.size > cap) { log("boot: ${e.path} is ${e.size} bytes, skipped"); return null }
                val o = ByteArrayOutputStream(e.size.toInt())
                f.copy(e, o, cap)
                return o.toByteArray()
            }

            find(BOOT_IMAGES)?.let { e ->
                val b = read(e, MAX_RAMDISK)
                if (b != null && b.size > 64 && String(b, 0, 8, Charsets.ISO_8859_1) == "ANDROID!") {
                    log("boot: ${e.path} (Android boot image) on the FAT boot partition")
                    return@use b
                }
            }
            val rdEntry = find(RAMDISKS)
            if (rdEntry == null) { log("boot: no ramdisk (uInitrd, ramdisk.img, initrd.gz ...) on the FAT boot partition"); return@use null }
            val rdPayload = unwrap(read(rdEntry, MAX_RAMDISK) ?: return@use null)
            val ramdisk = ramdiskBytes(rdPayload)
            if (ramdisk == null) { log("boot: ${rdEntry.path} is not a gzip / lzma / xz / bzip2 cpio ramdisk"); return@use null }

            val kEntry = find(KERNELS)
            val kPayload = kEntry?.let { e -> read(e, MAX_KERNEL)?.let { unwrap(it) } }
            if (kEntry == null || kPayload == null) log("boot: no kernel (uImage, zImage) on the FAT boot partition; the ramdisk alone is used")
            val kernel = kPayload?.data ?: ByteArray(0)
            val kernelAddr = kPayload?.load?.takeIf { it != 0L } ?: DEFAULT_KERNEL_ADDR
            val ramdiskAddr = rdPayload.load.takeIf { it != 0L } ?: DEFAULT_RAMDISK_ADDR

            val cmdline = find(CMDLINES)?.let { e -> read(e, 4096)?.let { String(it, Charsets.ISO_8859_1) } }
                ?.replace('\n', ' ')?.replace('\r', ' ')?.replace('\u0000', ' ')?.trim().orEmpty()
            log("boot: ${rdEntry.path}${if (kEntry != null) " + ${kEntry.path}" else ""} from the FAT boot partition, packed as a boot image")
            androidImage(kernel, kernelAddr, ramdisk, ramdiskAddr, cmdline)
        }
    }

    /** "ANDROID!" boot image, header version 0, page size 2048: header page, kernel, ramdisk, each padded to a page. */
    fun androidImage(kernel: ByteArray, kernelAddr: Long, ramdisk: ByteArray, ramdiskAddr: Long, cmdline: String): ByteArray {
        fun pad(n: Int) = (n + PAGE - 1) / PAGE * PAGE
        val out = ByteArray(PAGE + pad(kernel.size) + pad(ramdisk.size))
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        "ANDROID!".toByteArray(Charsets.US_ASCII).copyInto(out, 0)
        bb.putInt(8, kernel.size)
        bb.putInt(12, kernelAddr.toInt())
        bb.putInt(16, ramdisk.size)
        bb.putInt(20, ramdiskAddr.toInt())
        bb.putInt(24, 0)
        bb.putInt(28, 0)
        bb.putInt(32, DEFAULT_TAGS_ADDR.toInt())
        bb.putInt(36, PAGE)
        val cl = cmdline.toByteArray(Charsets.ISO_8859_1)
        cl.copyInto(out, 64, 0, minOf(cl.size, 511))
        kernel.copyInto(out, PAGE)
        ramdisk.copyInto(out, PAGE + pad(kernel.size))
        return out
    }
}

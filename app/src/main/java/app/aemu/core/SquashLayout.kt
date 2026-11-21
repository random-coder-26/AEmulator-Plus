/* AEmulator Plus addition: SquashFS system layout, the counterpart of SystemLayout for ext4. GPL-3.0; see LICENSE. */
package app.aemu.core

import app.aemu.importer.tools.FirmwareToolset
import app.aemu.importer.tools.SquashLayoutTool
import java.io.File

/**
 * What a VM keeps of the stock system partition when /system is SquashFS, instead of a second copy of the image
 * ([StockImage]): the metadata of the image as it was, the list of its files (path, size, mode, SHA-1), and for every
 * data block a note that it is rebuilt by compressing the file's bytes again, which is checked while the layout is
 * made. A block that does not come out identical (another compressor or version of it) is kept as it was, so the
 * rebuilt image is always exact. See [SquashLayoutTool]. Like an ext4 layout ([SystemLayout]) it is used by block-based
 * OTAs: the stock image is rebuilt from the layout and the (unmodified) files, the OTA runs on it, and the files of the
 * VM are brought in line with the result.
 */
object SquashLayout {
    const val NAME = "system.squash.layout"

    /** Where the root of a SquashFS system partition is in the guest, relative to the VM's root. */
    const val PREFIX = "system/"

    fun file(vmDir: File) = File(vmDir, NAME)

    /** The tables only (files, links, folders); the stored bytes are not read. */
    fun load(f: File): SquashLayoutTool.Tables = SquashLayoutTool.load(f)

    /**
     * Writes the layout of the SquashFS image [image] to [out]. [visitor] (optional) also receives the content of the
     * image, so that a caller that has to read it anyway does it in the same pass.
     */
    fun capture(
        image: File,
        out: File,
        log: (String) -> Unit,
        cancelled: () -> Boolean,
        visitor: FirmwareToolset.SquashVisitor? = null,
    ): SquashLayoutTool.Stats = SquashLayoutTool.capture(image, out, { log(it) }, { cancelled() }, visitor)

    /**
     * Rebuilds the stock image described by [layout] into [out]; [content] gives the stock bytes of a file of the image
     * (null = the file in the VM is not the stock one). Returns the paths it could not provide: the image is only
     * complete if there are none.
     */
    fun rebuild(
        layout: File,
        out: File,
        content: (String) -> ByteArray?,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<String> = SquashLayoutTool.rebuild(layout, out, { content(it) }, { d, t -> progress(d, t) })
}

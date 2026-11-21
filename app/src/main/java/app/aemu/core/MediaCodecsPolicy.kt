/* AEmulator Plus, 2026-11-14: software-only decoder list for ExoPlayer / MediaCodec clients. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import java.io.File

/**
 * ExoPlayer (and every other MediaCodec client: YouTube, Chrome, gallery apps) asks the framework's codec list for the
 * decoders of a MIME type and takes the first one. A firmware's media_codecs.xml lists its board's hardware decoders first
 * (OMX.qcom.video.decoder.avc, OMX.SEC.avc.dec, OMX.TI.DUCATI1.VIDEO.DECODER, ...). The hardware behind them is not here
 * (TreeFixer sets the vendor OMX plugin libstagefrighthw.so and the board's Codec2 HAL aside), so creating that decoder
 * fails and ExoPlayer reports "Decoder init failed" or a playback error: it does not try the next decoder unless the app
 * turned decoder fallback on, and most apps did not.
 *
 * Here every <MediaCodec> of a <Decoders> block that is not one of AOSP's software codecs (OMX.google.*, c2.android.*) is
 * taken out of the firmware's media_codecs*.xml files, so the first decoder the list offers is one that works. Encoders and
 * everything else in the files stay as they are. A file is only touched when a software decoder stays reachable (it names
 * one itself, or it includes a media_codecs_google_*.xml that is in the tree), so the list is never left without a decoder.
 * The original of each file is kept and put back by [configure] with enabled = false; a firmware update that replaces a
 * file makes the new file the original.
 */
object MediaCodecsPolicy {
    /** names of the codecs that run in software here */
    private val SOFTWARE_PREFIXES = listOf("OMX.google.", "c2.android.")

    /** folders a firmware keeps its codec lists in */
    private val ETC_DIRS = listOf("system/etc", "vendor/etc", "system/vendor/etc", "odm/etc", "product/etc")

    /** where the original of a changed file is kept; the file's path with '#' for '/' is its name */
    private const val BACKUP_DIR = "system/.aemu-codecs-orig"

    private val DECODERS = Regex("<Decoders\\b[^>]*>.*?</Decoders>", RegexOption.DOT_MATCHES_ALL)

    // an opening tag (attributes may hold '>' inside quotes) that is either self-closing or closed by </MediaCodec>
    private val MEDIA_CODEC = Regex(
        "<MediaCodec\\b(?:[^>\"']|\"[^\"]*\"|'[^']*')*?(?:/>|>.*?</MediaCodec>)", RegexOption.DOT_MATCHES_ALL)
    private val NAME = Regex("\\bname\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
    private val INCLUDE_GOOGLE = Regex("<Include\\b[^>]*\\bhref\\s*=\\s*[\"'](media_codecs_google[^\"']*)[\"']")

    fun isSoftware(codecName: String) = SOFTWARE_PREFIXES.any { codecName.startsWith(it) }

    /** the lists this policy looks at: the firmware's own, not AOSP's software ones and not the performance/profiling data */
    private fun isBoardList(name: String) = name.startsWith("media_codecs") && name.endsWith(".xml") &&
        !name.startsWith("media_codecs_google") && !name.startsWith("media_codecs_performance") &&
        !name.startsWith("media_codecs_profiling")

    /**
     * [xml] without the decoders that are not software ones, or [xml] itself when nothing would be left to decode with
     * ([softwareIncluded]: a media_codecs_google_*.xml the list includes exists in the tree).
     */
    fun filter(xml: String, softwareIncluded: Boolean): String {
        var keepsSoftware = softwareIncluded
        val out = DECODERS.replace(xml) { block ->
            MEDIA_CODEC.replace(block.value) { m ->
                val tag = m.value.substringBefore('>')
                val name = NAME.find(tag)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } } ?: ""
                if (isSoftware(name)) { keepsSoftware = true; m.value } else ""
            }
        }
        return if (keepsSoftware) out else xml
    }

    /**
     * Applies or undoes the policy for the tree at [root]. Returns the files (relative paths) that were changed or put back.
     */
    fun configure(root: File, enabled: Boolean = true): List<String> {
        val changed = ArrayList<String>()
        for (dir in ETC_DIRS) {
            val etc = File(root, dir)
            for (f in etc.listFiles().orEmpty().sortedBy { it.name }) {
                if (!f.isFile || !isBoardList(f.name)) continue
                val rel = "$dir/${f.name}"
                if (process(root, f, rel, enabled)) changed += rel
            }
        }
        // a file that a firmware update removed leaves its kept original behind: nothing to put it back to, drop it
        for (b in File(root, BACKUP_DIR).listFiles().orEmpty()) {
            if (!File(root, b.name.replace('#', '/')).isFile) b.delete()
        }
        return changed
    }

    /** AOSP's software audio decoders: component name, MIME type, the library SoftOMXPlugin loads for it */
    private val SOFT_AUDIO = listOf(
        Triple("OMX.google.mp3.decoder", "audio/mpeg", "libstagefright_soft_mp3dec.so"),
        Triple("OMX.google.aac.decoder", "audio/mp4a-latm", "libstagefright_soft_aacdec.so"),
        Triple("OMX.google.vorbis.decoder", "audio/vorbis", "libstagefright_soft_vorbisdec.so"),
        Triple("OMX.google.amrnb.decoder", "audio/3gpp", "libstagefright_soft_amrdec.so"),
        Triple("OMX.google.amrwb.decoder", "audio/amr-wb", "libstagefright_soft_amrdec.so"),
        Triple("OMX.google.g711.alaw.decoder", "audio/g711-alaw", "libstagefright_soft_g711dec.so"),
        Triple("OMX.google.g711.mlaw.decoder", "audio/g711-mlaw", "libstagefright_soft_g711dec.so"),
        Triple("OMX.google.raw.decoder", "audio/raw", "libstagefright_soft_rawdec.so"),
    )

    /**
     * Modified for AEmulator Plus, 2026-11-17: a board's media_codecs.xml that lists only its own audio decoders (MediaTek:
     * OMX.MTK.AUDIO.DECODER.*, served by libstagefrighthw.so, which TreeFixer sets aside) leaves the guest with no
     * audio decoder at all: "AwesomePlayer: unsupportted audio detected", MediaPlayer error 262 for every sound, SoundPool
     * "Unable to load sample". The software decoders whose library is in the tree are put at the top of the <Decoders> block.
     * Returns the codec names that were added; a file that already names a codec, or includes media_codecs_google_*.xml, is left as is.
     */
    fun ensureSoftwareAudio(root: File): List<String> {
        val added = ArrayList<String>()
        for (dir in ETC_DIRS) {
            val f = File(root, "$dir/media_codecs.xml")
            if (!f.isFile) continue
            val xml = f.readText()
            if (included(root, xml)) continue
            val libs = listOf("system/lib", "vendor/lib", "system/vendor/lib").map { File(root, it) }
            val missing = SOFT_AUDIO.filter { (name, _, lib) ->
                !xml.contains("\"$name\"") && libs.any { File(it, lib).isFile }
            }
            if (missing.isEmpty()) continue
            val open = Regex("<Decoders\\b[^>]*>").find(xml) ?: continue
            val block = missing.joinToString("") { (n, t, _) -> "\n        <MediaCodec name=\"$n\" type=\"$t\" />" }
            val backup = File(root, "$BACKUP_DIR/${("$dir/media_codecs.xml").replace('/', '#')}")
            if (!backup.isFile) { backup.parentFile?.mkdirs(); backup.writeText(xml) }
            f.setWritable(true)
            f.writeText(xml.substring(0, open.range.last + 1) + block + xml.substring(open.range.last + 1))
            f.setReadable(true, false)
            added += missing.map { it.first }
        }
        return added
    }

    private fun process(root: File, f: File, rel: String, enabled: Boolean): Boolean {
        val backup = File(root, "$BACKUP_DIR/${rel.replace('/', '#')}")
        if (!enabled) {
            if (!backup.isFile) return false
            f.setWritable(true)
            backup.copyTo(f, overwrite = true)
            backup.delete()
            return true
        }
        val current = f.readText()
        // already ours and unchanged since: nothing to do; anything else (an update replaced it) is the new original
        if (backup.isFile) {
            val kept = backup.readText()
            if (filter(kept, included(root, kept)) == current) return false
        }
        val original = current
        val patched = filter(original, included(root, original))
        if (patched == current) {
            // nothing to hide; a stale backup of an older file is of no use
            if (backup.isFile) backup.delete()
            return false
        }
        backup.parentFile?.mkdirs()
        backup.writeText(original)
        f.setWritable(true)
        f.writeText(patched)
        f.setReadable(true, false)
        return true
    }

    /** true when [xml] includes a media_codecs_google_*.xml of AOSP's software codecs that is in the tree */
    private fun included(root: File, xml: String): Boolean = INCLUDE_GOOGLE.findAll(xml).any { m ->
        ETC_DIRS.any { d -> File(root, "$d/${m.groupValues[1]}").isFile }
    }
}

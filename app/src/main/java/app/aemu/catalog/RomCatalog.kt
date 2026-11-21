package app.aemu.catalog

import app.aemu.R
import app.aemu.core.LocalizedIllegalArgumentException
import org.json.JSONException
import org.json.JSONObject
import java.net.URI

/** Extra fields that only the JSON catalog (the submission worker's `/api/roms`) carries. */
data class RomDetails(val name: String, val romVersion: String, val versionCategory: String, val submittedBy: String,
    val fileSizeBytes: Long?, val emulatorVersion: String?, val notes: String?)
data class CatalogRom(val device: String, val android: String, val skin: String,
    val status: Int, val comment: String, val url: String?, val details: RomDetails? = null)
/** A folder inside a section (the server's "version category"). Its ROMs no longer carry the category themselves. */
data class CatalogSubsection(val name: String, val roms: List<CatalogRom>)
/**
 * One variant. [roms] are the ROMs sitting directly in it (no sub category); [subsections] are its sub category
 * folders, in the order the server sent them.
 */
data class CatalogSection(val name: String, val comment: String, val source: String?, val roms: List<CatalogRom>,
    val subsections: List<CatalogSubsection> = emptyList()) {
    /** Every ROM in the section, folders included. */
    val romCount: Int get() = roms.size + subsections.sumOf { it.roms.size }
}
data class RomCatalog(val motd: String, val sections: List<CatalogSection>, val warningLines: List<Int>)

object CatalogUrls {
    const val DEFAULT = "https://rom-submissions.and-rew-mc7291.workers.dev/api/roms"
    /**
     * Base URL of the ROM-submission worker (no trailing slash). Used for the submit button when the catalog
     * source is not itself the worker's `<base>/api/roms` (e.g. a custom rom.list); blank hides the button then.
     */
    const val SUBMIT_API_BASE = "https://rom-submissions.and-rew-mc7291.workers.dev"
    /** The worker base for [source]: derived from `<base>/api/roms`, else [SUBMIT_API_BASE], else null. */
    fun apiBase(source: String): String? {
        val s = valid(source)?.substringBefore('#')?.substringBefore('?')?.trimEnd('/')
        val derived = s?.takeIf { it.endsWith("/api/roms") }?.removeSuffix("/api/roms")?.let(::valid)
        return derived ?: SUBMIT_API_BASE.takeIf { it.isNotBlank() }?.let(::valid)
    }
    fun valid(value: String): String? = runCatching {
        val s = value.trim()
        require(s.length in 1..8192)
        val uri = URI(s)
        require(uri.scheme.equals("https", true) || uri.scheme.equals("http", true))
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null)
        s
    }.getOrNull()
}

/** Semicolon-separated records, with the final URL allowed to contain semicolons. */
object RomCatalogParser {
    fun parse(text: String): RomCatalog {
        data class Section(val name: String, val comment: String, val source: String?, val roms: MutableList<CatalogRom>)
        val sections = mutableListOf<Section>()
        val motd = mutableListOf<String>()
        val warnings = linkedSetOf<Int>()
        var started = false
        text.removePrefix("\uFEFF").lineSequence().forEachIndexed { index, raw ->
            if (index >= 10_000) throw app.aemu.core.LocalizedIllegalArgumentException("Catalog exceeds 10000 lines", app.aemu.R.string.catalog_err_too_many_lines)
            val line = raw.trim()
            if (line.isEmpty()) return@forEachIndexed
            if (line.startsWith('#')) {
                if (!started) motd += line.removePrefix("#").trim()
                return@forEachIndexed
            }
            started = true
            fun url(value: String): String? {
                val result = CatalogUrls.valid(value)
                if (value.isNotBlank() && result == null) warnings += index + 1
                return result
            }
            if (line.startsWith('[')) {
                val f = line.split(';', limit = 3).map(String::trim)
                if (f.size != 3 || !f[0].endsWith(']') || f[0].length < 3) {
                    warnings += index + 1
                    return@forEachIndexed
                }
                sections += Section(f[0].substring(1, f[0].length - 1), f[1], url(f[2]), mutableListOf())
            } else {
                val f = line.split(';', limit = 6).map(String::trim)
                if (f.size != 6 || f[0].isEmpty()) {
                    warnings += index + 1
                    return@forEachIndexed
                }
                val status = f[3].toIntOrNull()?.takeIf { it in -1..2 } ?: run { warnings += index + 1; -1 }
                if (sections.isEmpty()) sections += Section("", "", null, mutableListOf())
                sections.last().roms += CatalogRom(f[0], f[1], f[2], status, f[4], url(f[5]))
            }
        }
        return RomCatalog(motd.joinToString("\n"), sections.map { CatalogSection(it.name, it.comment, it.source, it.roms.toList()) }, warnings.toList())
    }
}

/**
 * The worker's `GET /api/roms` payload: `{"roms":[{variant, version_category, name, rom_version, device,
 * submitted_by, download_url, file_size_bytes, emulator_version, notes, ...}]}`. One section per variant, in the
 * order the server sent them (it sorts by variant, then version number). Each variant's `version_category` values become
 * sub sections (folders) of that variant; a ROM without a category stays directly in the variant.
 * Warning "lines" are 1-based entry numbers.
 */
object RomJsonParser {
    private const val MAX_ROMS = 10_000
    private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key).trim()

    fun parse(text: String): RomCatalog {
        val roms = (try { JSONObject(text.removePrefix("\uFEFF")).optJSONArray("roms") } catch (e: JSONException) { null })
            ?: throw LocalizedIllegalArgumentException("Catalog JSON has no \"roms\" array", R.string.catalog_err_bad_json)
        if (roms.length() > MAX_ROMS) throw LocalizedIllegalArgumentException("Catalog exceeds $MAX_ROMS ROMs", R.string.catalog_err_too_many_roms)
        val warnings = linkedSetOf<Int>()
        // variant -> version category ("" = none) -> ROMs, everything in the order first seen
        val bySection = LinkedHashMap<String, LinkedHashMap<String, MutableList<CatalogRom>>>()
        for (i in 0 until roms.length()) {
            val o = roms.optJSONObject(i)
            val name = o?.text("name").orEmpty()
            val device = o?.text("device").orEmpty()
            if (o == null || name.isEmpty() || device.isEmpty()) { warnings += i + 1; continue }
            val rawUrl = o.text("download_url")
            val url = CatalogUrls.valid(rawUrl)
            if (url == null) warnings += i + 1
            val size = if (o.isNull("file_size_bytes")) null else o.optLong("file_size_bytes", -1).takeIf { it >= 0 }
            val details = RomDetails(name, o.text("rom_version"), o.text("version_category"), o.text("submitted_by"),
                size, o.text("emulator_version").ifEmpty { null }, o.text("notes").ifEmpty { null })
            bySection.getOrPut(o.text("variant")) { LinkedHashMap() }
                .getOrPut(details.versionCategory) { mutableListOf() } += CatalogRom(device, "", "", -1, "", url, details)
        }
        val sections = bySection.map { (variant, categories) ->
            CatalogSection(variant, "", null, categories[""].orEmpty(),
                categories.filterKeys { it.isNotEmpty() }.map { (category, list) -> CatalogSubsection(category, list) })
        }
        return RomCatalog("", sections, warnings.toList())
    }
}

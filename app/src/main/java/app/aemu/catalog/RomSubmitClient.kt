package app.aemu.catalog

import app.aemu.R
import app.aemu.core.localizedError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import kotlin.coroutines.coroutineContext

data class RomVersionOption(val id: Int, val name: String)
data class RomVariantOption(val id: Int, val name: String, val versions: List<RomVersionOption>)

/** One ROM to send to `POST /api/submit`; limits mirror the CHECK constraints in the worker's schema.sql. */
data class RomSubmission(
    val variantId: Int, val versionId: Int, val name: String, val romVersion: String, val device: String,
    val submittedBy: String, val downloadUrl: String, val fileSizeBytes: Long?, val emulatorVersion: String?, val notes: String?,
) {
    companion object {
        const val MAX_NAME = 120
        const val MAX_ROM_VERSION = 60
        const val MAX_DEVICE = 120
        const val MAX_SUBMITTED_BY = 60
        const val MAX_URL = 2048
        const val MAX_EMULATOR = 40
        const val MAX_NOTES = 1000
    }
}

/** Talks to the ROM-submission worker (`GET /api/categories`, `POST /api/submit`). */
object RomSubmitClient {
    private const val MAX_RESPONSE_BYTES = 256 * 1024
    private const val CHECK_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    suspend fun loadCategories(apiBase: String): List<RomVariantOption> = withContext(Dispatchers.IO) {
        val (code, body) = request("$apiBase/api/categories", null)
        if (code != 200) localizedError("HTTP $code", R.string.submit_err_server, code)
        try {
            val variants = JSONObject(body).getJSONArray("variants")
            List(variants.length()) { i ->
                val v = variants.getJSONObject(i)
                val versions = v.getJSONArray("versions")
                RomVariantOption(v.getInt("id"), v.getString("name"), List(versions.length()) { j ->
                    versions.getJSONObject(j).let { RomVersionOption(it.getInt("id"), it.getString("name")) }
                })
            }
        } catch (e: JSONException) {
            localizedError("Unexpected categories response", R.string.submit_err_bad_response)
        }
    }

    /** Returns normally when the worker accepted the submission (HTTP 201); throws a localized error otherwise. */
    suspend fun submit(apiBase: String, rom: RomSubmission) = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("variant_id", rom.variantId)
            put("version_id", rom.versionId)
            put("name", rom.name)
            put("rom_version", rom.romVersion)
            put("device", rom.device)
            put("submitted_by", rom.submittedBy)
            put("download_url", rom.downloadUrl)
            rom.fileSizeBytes?.let { put("file_size_bytes", it) }
            rom.emulatorVersion?.let { put("emulator_version", it) }
            rom.notes?.let { put("notes", it) }
        }.toString()
        val (code, body) = request("$apiBase/api/submit", payload)
        val json = runCatching { JSONObject(body) }.getOrNull()
        when (code) {
            201 -> Unit
            409 -> localizedError("This download URL has already been submitted", R.string.submit_err_duplicate)
            429 -> {
                val minutes = ((json?.optInt("retry_after_seconds", 0) ?: 0) + 59) / 60
                localizedError("Rate limit reached", R.string.submit_err_rate_limited, minutes.coerceAtLeast(1))
            }
            400 -> {
                val details = json?.optJSONArray("details")?.let { a: JSONArray -> List(a.length()) { a.optString(it) } }
                    ?.filter { it.isNotBlank() }?.joinToString("; ").orEmpty()
                    .ifEmpty { json?.optString("error").orEmpty() }
                localizedError("Rejected: $details", R.string.submit_err_rejected, details.ifEmpty { "HTTP 400" })
            }
            else -> localizedError("HTTP $code", R.string.submit_err_server, code)
        }
    }

    /** archive.org and every subdomain of it (web.archive.org, ia800000.us.archive.org ...). */
    fun isArchiveOrg(url: String): Boolean {
        val host = runCatching { URI(url.trim()).host }.getOrNull()?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        return host == "archive.org" || host.endsWith(".archive.org")
    }

    /**
     * True when [url] may be submitted: an archive.org / web.archive.org link, or a link whose server answers with a file
     * (binary, archive, attachment) rather than a page - the same test the catalog's download button uses. Redirects are
     * followed (a share link that redirects to the file passes); only headers are read, the body is never downloaded.
     * Throws when the server cannot be reached.
     */
    suspend fun isDirectFileLink(url: String): Boolean = withContext(Dispatchers.IO) {
        var current = CatalogUrls.valid(url) ?: return@withContext false
        if (isArchiveOrg(current)) return@withContext true
        var hop = 0
        while (true) {
            coroutineContext.ensureActive()
            val conn = URL(current).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", CHECK_UA)
                conn.setRequestProperty("Accept", "*/*")
                conn.setRequestProperty("Accept-Encoding", "identity")
                val code = conn.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val location = conn.getHeaderField("Location") ?: return@withContext false
                    if (++hop > 6) return@withContext false
                    val next = CatalogUrls.valid(URL(URL(current), location).toExternalForm()) ?: return@withContext false
                    if (current.startsWith("https:", true) && !next.startsWith("https:", true)) return@withContext false
                    current = next
                    if (isArchiveOrg(current)) return@withContext true
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) return@withContext false
                return@withContext RomDownloader.isFile(conn.contentType, conn.getHeaderField("Content-Disposition"), current)
            } finally { conn.disconnect() }
        }
        false
    }

    /** GET when [json] is null, otherwise POST of that JSON body. Redirects are not followed (a POST would lose its body). */
    private suspend fun request(url: String, json: String?): Pair<Int, String> {
        val safe = CatalogUrls.valid(url) ?: localizedError("Invalid catalog URL", R.string.catalog_err_invalid_url)
        coroutineContext.ensureActive()
        val connection = URL(safe).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "AEmulator-Sunset-RomCatalog")
            if (json != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            return code to (stream?.use { readLimited(it) } ?: "")
        } finally { connection.disconnect() }
    }

    private suspend fun readLimited(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            if (bytes.size() + n > MAX_RESPONSE_BYTES) localizedError("Response too large", R.string.catalog_err_too_large)
            bytes.write(buffer, 0, n)
        }
        return bytes.toString("UTF-8")
    }
}

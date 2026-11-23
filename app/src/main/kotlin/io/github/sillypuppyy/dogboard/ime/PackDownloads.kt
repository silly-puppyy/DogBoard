package io.github.sillypuppyy.dogboard.ime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A pack the download host offers (tools/i18n/dist_pack.py's index.json). */
@Serializable
data class PackIndexEntry(
    val tag: String,
    val name: String,
    val script: String = "",
    val version: String,
    /** Bytes to download. */
    val size: Long = 0,
)

@Serializable
data class PackIndex(val format: Int = 1, val packs: List<PackIndexEntry> = emptyList())

/**
 * Packs from a download host laid out as dist_pack.py writes it: <base>/index.json, <base>/<tag>/manifest.json and the
 * pack's files beside it. The host is not chosen yet (checklist: release gap), so [BASE_URL] is empty and nothing is
 * offered; [PackInstaller] checks every file, so the host is trusted for availability, not for content.
 */
class PackDownloads(private val baseUrl: String, private val packsDir: File) {
    val enabled: Boolean get() = baseUrl.isNotEmpty()

    /** The host's packs, or null when it can't be reached. Network: call off the main thread. */
    fun index(): PackIndex? = if (!enabled) null else runCatching {
        open("index.json").use { json.decodeFromString<PackIndex>(it.readBytes().decodeToString()) }
    }.getOrNull()

    /** Downloads and installs [tag] (replacing an installed one only when the whole pack arrived intact). */
    fun install(tag: String, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): PackInstaller.Result =
        if (!enabled) PackInstaller.Result.Failed("no download host")
        else PackInstaller(packsDir).install({ path -> open("$tag/$path") }, onProgress)

    /** Whether the host has a newer version of an installed pack (versions are dates, so text order is time order). */
    fun updateFor(installed: LanguagePack, index: PackIndex): PackIndexEntry? =
        index.packs.firstOrNull { it.tag == installed.tag && it.version > installed.manifest.version }

    private fun open(path: String): java.io.InputStream {
        val c = URL("${baseUrl.trimEnd('/')}/$path").openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true // release hosts redirect to their file servers
        if (c.responseCode !in 200..299) {
            c.disconnect()
            throw java.io.IOException("HTTP ${c.responseCode} for $path")
        }
        return c.inputStream
    }

    companion object {
        /** The download host's base URL; empty until one is chosen (then packs show in Languages). */
        const val BASE_URL = ""
        private val json = Json { ignoreUnknownKeys = true }
    }
}

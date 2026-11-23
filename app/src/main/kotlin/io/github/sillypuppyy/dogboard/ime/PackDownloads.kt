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
    /** The pack as one .tar.xz (dist_pack.py): its download link and checksum; null: files in <base>/<tag>/. */
    val archive: PackArchive? = null,
)

@Serializable
data class PackArchive(val file: String = "", val size: Long = 0, val sha256: String = "", val url: String = "")

@Serializable
data class PackIndex(val format: Int = 1, val packs: List<PackIndexEntry> = emptyList())

/**
 * Packs from a download host laid out as dist_pack.py writes it: <base>/index.json, <base>/<tag>/manifest.json and the
 * pack's files beside it. The host is not chosen yet (checklist: release gap), so [BASE_URL] is empty and nothing is
 * offered; [PackInstaller] checks every file, so the host is trusted for availability, not for content.
 */
class PackDownloads(private val baseUrl: String, private val packsDir: File, private val publicKey: String = PUBLIC_KEY) {
    val enabled: Boolean get() = baseUrl.isNotEmpty()

    /**
     * The host's packs, or null when it can't be reached or its signature doesn't verify (index.json.sig, made by
     * tools/i18n/sign_index.py with the maintainers' key; [publicKey] is built into the app, so a host or account taken
     * over can't hand out other packs). Network: call off the main thread.
     */
    fun index(): PackIndex? = if (!enabled) null else runCatching {
        val bytes = open("index.json").use { it.readBytes() }
        if (publicKey.isNotEmpty()) {
            val sig = open("index.json.sig").use { java.util.Base64.getDecoder().decode(it.readBytes().decodeToString().trim()) }
            if (!verify(bytes, sig, publicKey)) { android.util.Log.w("DogBoardPerf", "pack index: signature does not verify"); return null }
        }
        json.decodeFromString<PackIndex>(bytes.decodeToString())
    }.getOrNull()

    /** Downloads and installs [tag] (replacing an installed one only when the whole pack arrived intact). */
    fun install(tag: String, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }, entry: PackIndexEntry? = null): PackInstaller.Result {
        if (!enabled) return PackInstaller.Result.Failed("no download host")
        val archive = entry?.archive?.takeIf { it.url.isNotEmpty() || it.file.isNotEmpty() }
        if (archive != null) {
            // one archive: its link (any release), else beside the index; its checksum comes from the signed index
            val stream = runCatching { if (archive.url.isNotEmpty()) openUrl(archive.url) else open(archive.file) }.getOrElse {
                return PackInstaller.Result.Failed("${archive.file}: ${it.message}")
            }
            return stream.use { PackInstaller(packsDir).installArchive(it, archive.sha256, onProgress) }
        }
        return PackInstaller(packsDir).install({ path -> open("$tag/$path") }, onProgress)
    }

    /** Whether the host has a newer version of an installed pack (versions are dates, so text order is time order). */
    fun updateFor(installed: LanguagePack, index: PackIndex): PackIndexEntry? =
        index.packs.firstOrNull { it.tag == installed.tag && it.version > installed.manifest.version }

    private fun open(path: String): java.io.InputStream = openUrl("${baseUrl.trimEnd('/')}/$path")

    private fun openUrl(url: String): java.io.InputStream {
        val c = URL(url).openConnection()
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        // a file:// host (a debug build's test folder) has no status code
        if (c is HttpURLConnection) {
            c.instanceFollowRedirects = true // release hosts redirect to their file servers
            if (c.responseCode !in 200..299) {
                c.disconnect()
                throw java.io.IOException("HTTP ${c.responseCode} for $url")
            }
        }
        return c.getInputStream()
    }

    companion object {
        /**
         * The download host: the DogBoard-packs repository's fixed `index` release (index.json + index.json.sig; each
         * pack's archive link is in the index, in whichever packs-<format>.<content>.<fix> release holds it), 2026-11-23.
         */
        const val BASE_URL = "https://github.com/silly-puppyy/DogBoard-packs/releases/download/index/"

        /**
         * The host to use: [BASE_URL], or in a debug build the URL in files/dogboard/pack_host.txt (a test host, or a
         * file:// folder pushed to the phone: tools/i18n/dist_pack.py's output).
         */
        fun baseUrl(context: android.content.Context): String {
            if (dev.patrickgold.florisboard.BuildConfig.DEBUG) {
                File(context.filesDir, "dogboard/pack_host.txt").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            return BASE_URL
        }

        fun of(context: android.content.Context) = baseUrl(context).let { url ->
            // a debug build's file:// test folder may be unsigned; every network host must sign its index
            PackDownloads(url, File(context.filesDir, "packs"), if (dev.patrickgold.florisboard.BuildConfig.DEBUG && url.startsWith("file:")) "" else PUBLIC_KEY)
        }

        /** The maintainers' pack-signing key (ECDSA P-256, X.509 DER base64; tools/i18n/sign_index.py keygen, 2026-11-23). */
        const val PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEcn75QUDhYinebmitUu5jGs++5nb7A2aIVmgFVMcwBaBRGiyuAjnC30/gU+d9GePGRSGE7yCudi3p2SRPnDhfiQ=="

        /** Whether [sig] (DER) signs [data] under [publicKeyB64] (SHA256withECDSA). */
        fun verify(data: ByteArray, sig: ByteArray, publicKeyB64: String): Boolean = runCatching {
            val key = java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(java.util.Base64.getDecoder().decode(publicKeyB64)))
            java.security.Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(data); verify(sig) }
        }.getOrDefault(false)
        private val json = Json { ignoreUnknownKeys = true }
    }
}

package io.github.sillypuppyy.dogboard.ime

import android.content.res.AssetManager
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A language pack (notes/design/i18n-pipeline.md): everything the engine and keyboard need for one
 * language, described by `manifest.json` in `assets/dogboard/packs/<tag>/`. The loader asks the pack
 * for each component's file instead of naming English files; a component the pack lacks (a language
 * without a neural model yet) is simply absent. The manifest is written by tools/i18n/write_manifest.py
 * with each file's size, which is checked here, so a stale or truncated file is not read.
 */
@Serializable
data class PackManifest(
    val format: Int,
    val tag: String,
    val name: String,
    val script: String,
    val version: String,
    /** component -> file (paths relative to the assets root, so packs can share files while they move). */
    val files: Map<String, PackFile>,
    val sources: List<PackSource> = emptyList(),
)

@Serializable
data class PackFile(
    val path: String,
    val size: Long = -1,
    val sha256: String = "",
    /** Optional components may be missing from a build (the neural model is built privately). */
    val optional: Boolean = false,
)

@Serializable
data class PackSource(
    val name: String,
    val licence: String,
    @SerialName("for") val forComponents: List<String> = emptyList(),
    val url: String = "",
)

/**
 * A language's files: built into the app ([assets], paths under assets/) or downloaded ([dir], paths relative to the
 * pack's folder, files/packs/<tag>/). Read through [open] and [map] so the engine doesn't care which.
 */
class LanguagePack private constructor(val manifest: PackManifest, private val assets: AssetManager?, private val dir: java.io.File? = null) {
    val tag: String get() = manifest.tag

    /** Whether the app ships this pack (its snapshots and index can come with the app). */
    val bundled: Boolean get() = dir == null

    /** [component]'s file as a stream, or null when the pack has none. */
    fun open(component: String): java.io.InputStream? {
        val p = path(component) ?: return null
        return if (dir != null) java.io.File(dir, p).inputStream() else assets!!.open(p)
    }

    /** [component]'s file memory-mapped (stored uncompressed in the app, or a downloaded file), or null. */
    fun map(component: String): java.nio.ByteBuffer? {
        val p = path(component) ?: return null
        if (dir != null) return java.io.RandomAccessFile(java.io.File(dir, p), "r").channel.use { c ->
            c.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, c.size())
        }
        return assets!!.openFd(p).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).channel.use { ch ->
                ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length)
            }
        }
    }

    /** The path of [component] (asset path, or relative to the pack's folder), or null when the pack has none (or the file is missing or the wrong size). */
    fun path(component: String): String? {
        val f = manifest.files[component] ?: return null
        if (dir != null) {
            val file = java.io.File(dir, f.path)
            if (!file.isFile || (f.size >= 0 && file.length() != f.size)) return null
            return f.path
        }
        val assets = assets!!
        if (f.size < 0) return f.path
        // stored (uncompressed, memory-mapped) files have a descriptor with their length; compressed text
        // files don't, and counting them would inflate them: for those, only that they exist
        val actual = runCatching { assets.openFd(f.path).use { it.length } }.getOrElse {
            return if (runCatching { assets.open(f.path).close() }.isSuccess) f.path else null
        }
        if (actual != f.size) {
            android.util.Log.w("DogBoardPack", "${manifest.tag}: $component is $actual bytes, the manifest says ${f.size}; not used")
            return null
        }
        return f.path
    }

    fun has(component: String): Boolean = path(component) != null

    /**
     * Whether the pack has its words: the word list, or (a downloaded pack) only its snapshot: the text lists are read
     * only when there is no snapshot, so a pack can leave them out (Spanish: 13 MB less to download and store).
     */
    val hasWords: Boolean get() = has("lexicon") || (!bundled && has("snapshot_lexicon"))

    companion object {
        const val FORMAT = 1
        private val json = Json { ignoreUnknownKeys = true }

        /** The pack for [tag] from the app's assets, or null when there is none (or its format is newer). */
        fun open(assets: AssetManager, tag: String): LanguagePack? = runCatching {
            val m = assets.open("dogboard/packs/$tag/manifest.json").bufferedReader().use { json.decodeFromString<PackManifest>(it.readText()) }
            if (m.format > FORMAT) null else LanguagePack(m, assets)
        }.onFailure { android.util.Log.w("DogBoardPack", "no pack $tag: ${it.javaClass.simpleName} ${it.message}") }.getOrNull()

        /** A downloaded pack in [dir] (its manifest.json and files), or null. */
        fun openDir(dir: java.io.File): LanguagePack? = runCatching {
            val m = java.io.File(dir, "manifest.json").readText().let { json.decodeFromString<PackManifest>(it) }
            if (m.format > FORMAT) null else LanguagePack(m, null, dir)
        }.getOrNull()

        /**
         * The pack for a keyboard language ([tag] such as "es-ES"): a downloaded one first (files/packs/), then one
         * built into the app; the exact tag, then the language alone ("es"). Null when there is none.
         */
        fun find(context: android.content.Context, tag: String): LanguagePack? {
            val candidates = listOf(tag, tag.substringBefore('-')).distinct()
            for (t in candidates) {
                openDir(java.io.File(context.filesDir, "packs/$t"))?.let { return it }
                if (runCatching { context.assets.list("dogboard/packs/$t")?.contains("manifest.json") == true }.getOrDefault(false))
                    open(context.assets, t)?.let { return it }
            }
            return null
        }

        /** Every pack: built into the app and downloaded (a downloaded one replaces a built-in one of the same tag), by tag. */
        fun all(context: android.content.Context): List<LanguagePack> {
            val byTag = LinkedHashMap<String, LanguagePack>()
            (context.assets.list("dogboard/packs") ?: emptyArray()).sorted().forEach { t -> open(context.assets, t)?.let { byTag[it.tag] = it } }
            java.io.File(context.filesDir, "packs").listFiles()?.sortedBy { it.name }?.forEach { d -> openDir(d)?.let { byTag[it.tag] = it } }
            return byTag.values.sortedBy { it.tag }
        }

        /** The language bundled with the app, used when the keyboard's language has no pack. */
        const val DEFAULT_TAG = "en-US"
    }
}

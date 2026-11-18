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

class LanguagePack private constructor(val manifest: PackManifest, private val assets: AssetManager) {
    val tag: String get() = manifest.tag

    /** The asset path of [component], or null when the pack has none (or the file is missing or the wrong size). */
    fun path(component: String): String? {
        val f = manifest.files[component] ?: return null
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

    companion object {
        const val FORMAT = 1
        private val json = Json { ignoreUnknownKeys = true }

        /** The pack for [tag] from the app's assets, or null when there is none (or its format is newer). */
        fun open(assets: AssetManager, tag: String): LanguagePack? = runCatching {
            val m = assets.open("dogboard/packs/$tag/manifest.json").bufferedReader().use { json.decodeFromString<PackManifest>(it.readText()) }
            if (m.format > FORMAT) null else LanguagePack(m, assets)
        }.onFailure { android.util.Log.w("DogBoardPack", "no pack $tag: ${it.javaClass.simpleName} ${it.message}") }.getOrNull()

        /** The language bundled with the app, used until the keyboard picks packs by language (i18n step 5). */
        const val DEFAULT_TAG = "en-US"
    }
}

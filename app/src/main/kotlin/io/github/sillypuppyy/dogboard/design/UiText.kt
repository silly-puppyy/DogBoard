package io.github.sillypuppyy.dogboard.design

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * DogBoard's own screens in the phone's language: the English text is the key, assets/i18n/<language>.json holds its
 * translation (German, Spanish, French, Portuguese, Russian; machine-translated with OPUS-MT, tools/i18n/ui_translate.py).
 * Looked up where text is drawn (DText), so the code keeps passing English (section titles stay keys) and a string
 * without a translation shows as written.
 */
object UiText {
    @Volatile private var map: Map<String, String> = emptyMap()
    @Volatile private var language: String? = null

    /** Reads the phone language's table (once per language; the app restarts when the language changes). */
    fun load(context: Context) {
        val lang = context.resources.configuration.locales[0].language
        if (lang == language) return
        language = lang
        map = runCatching {
            context.assets.open("i18n/$lang.json").bufferedReader().use { r ->
                (Json.parseToJsonElement(r.readText()) as JsonObject).mapValues { (_, v) -> (v as JsonPrimitive).content }
            }
        }.getOrDefault(emptyMap())
    }

    fun tr(s: String): String = map[s] ?: s
}

package io.github.sillypuppyy.dogboard.design

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Whether [DText] translates (UiText): on in the settings app, off by default, so the keyboard's suggestion bar and
 * clipboard show words as typed (a German phone turned the suggestion "Send" into "Senden", 2026-11-26).
 */
val LocalTranslate = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * DogBoard's own screens in the phone's language: the English text is the key, assets/i18n/<language>.json holds its
 * translation (German, Spanish, French: short labels by hand, sentences machine-translated and checked by translating
 * back, tools/i18n/ui_merge.py). Looked up where text is drawn (DText), so the code keeps passing English (section
 * titles stay keys) and a string without a translation shows as written.
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

    /** A table set directly (tests). */
    internal fun use(table: Map<String, String>) { map = table }

    private val NUMBER = Regex("""\d+""")

    /**
     * [s] translated; a text with numbers ("Your pack, 3 themes", "Undone 2 times") by its template with {n} for each
     * number ("Your pack, {n} themes"), the numbers put back in order.
     */
    fun tr(s: String): String {
        map[s]?.let { return it }
        if (map.isEmpty() || !s.any { it.isDigit() }) return s
        val t = map[s.replace(NUMBER, "{n}")] ?: return s
        val nums = NUMBER.findAll(s).map { it.value }.iterator()
        return Regex.fromLiteral("{n}").replace(t) { if (nums.hasNext()) nums.next() else "" }
    }
}

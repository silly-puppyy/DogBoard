package io.github.sillypuppyy.dogboard.ime

import android.content.res.AssetManager
import io.github.sillypuppyy.dogboard.engine.LanguageRules
import java.util.concurrent.ConcurrentHashMap

/**
 * The rules of the layout's language (notes/design/i18n-pipeline.md, step 3), from the pack for its exact
 * tag (`fr-FR`, `fr`): a pack may hold only rules.json, for a language with no word data yet. A tag with no
 * pack gets no special rules (fr-CA and fr-CH have none, so no French spacing there, as on iOS).
 */
object LanguageRulesRegistry {
    private val cache = ConcurrentHashMap<String, LanguageRules>()

    /** "fr_fr" / "fr-fr" -> "fr-FR"; "fr" stays. */
    fun normalize(tag: String): String {
        val parts = tag.replace('_', '-').split('-').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        return (listOf(parts[0].lowercase()) + parts.drop(1).take(1).map { it.uppercase() }).joinToString("-")
    }

    fun rulesFor(assets: AssetManager, tag: String): LanguageRules {
        val t = normalize(tag)
        if (t.isEmpty()) return NONE
        return cache.getOrPut(t) {
            val pack = LanguagePack.open(assets, t)
            pack?.path("rules")?.let { r -> runCatching { LanguageRules.parse(assets.open(r).bufferedReader().use { it.readText() }) }.getOrNull() } ?: NONE
        }
    }

    private val NONE = LanguageRules()
}

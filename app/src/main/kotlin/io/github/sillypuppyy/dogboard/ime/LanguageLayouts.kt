package io.github.sillypuppyy.dogboard.ime

/**
 * Which letter layout goes with which language (Gboard keeps a layout per language with a default; iOS input modes
 * list theirs, notes/design/data/layouts). A language's pack is built for its default layout's own-key letters
 * (ñ, å ä ö, й: Lexicon.distinctLetters), so switching language moves to its default layout unless the layout in
 * use already belongs to that language (a chosen Dvorak stays for English).
 */
object LanguageLayouts {
    /** Each language's default layout (GeneratedLayouts: tools/layouts/gen_layouts.py). */
    val default: Map<String, String> = GeneratedLayouts.all.filter { it.default }.associate { it.language to it.id }

    /** The languages each layout types (one keyboard file serves every language with the same letters and long-press). */
    private val languagesOf: Map<String, Set<String>> = GeneratedLayouts.all.groupBy({ it.id }, { it.language }).mapValues { it.value.toSet() }

    /** Whether [layoutId] types the language of the word engine's loaded pack (English when none is loaded yet). */
    fun matchesLoaded(layoutId: String): Boolean {
        val loaded = (EngineLoader.pack?.tag ?: LanguagePack.DEFAULT_TAG).substringBefore('-').lowercase()
        return loaded in (languagesOf[layoutId] ?: setOf(layoutId.substringAfterLast('-')))
    }

    /** The layout to use for [tag] ("es-ES") when [current] is in use: [current] if it types that language, else the default. */
    fun forLanguage(tag: String, current: String): String? {
        val lang = tag.substringBefore('-').lowercase()
        if (lang in languagesOf[current].orEmpty()) return null
        return default[lang]
    }
}

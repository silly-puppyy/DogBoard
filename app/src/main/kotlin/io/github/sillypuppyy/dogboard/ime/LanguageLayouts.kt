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

    /** [memory] ("en=dvorak-en,cs=qwertz-cs") as language -> layout id. */
    fun chosen(memory: String): Map<String, String> =
        memory.split(',').mapNotNull { e -> e.split('=').takeIf { it.size == 2 && it[0].isNotEmpty() }?.let { it[0] to it[1] } }.toMap()

    /** [memory] with [layoutId] as [lang]'s layout. */
    fun remember(memory: String, lang: String, layoutId: String): String =
        (chosen(memory) + (lang to layoutId)).entries.joinToString(",") { "${it.key}=${it.value}" }

    /** [lang]'s layout: the one chosen for it, else its default (null: a language without layouts). */
    fun layoutOf(lang: String, memory: String): String? =
        chosen(memory)[lang]?.takeIf { lang in languagesOf[it].orEmpty() } ?: default[lang]

    /**
     * The layout to switch to for [tag] ("es-ES") when [current] is in use: the language's chosen layout, else [current]
     * if it types that language, else the default; null when [current] is already it.
     */
    fun forLanguage(tag: String, current: String, memory: String = ""): String? {
        val lang = tag.substringBefore('-').lowercase()
        val want = chosen(memory)[lang]?.takeIf { lang in languagesOf[it].orEmpty() }
            ?: current.takeIf { lang in languagesOf[it].orEmpty() } ?: default[lang]
        return want?.takeIf { it != current }
    }
}

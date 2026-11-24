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

    /**
     * Whether [layoutId] types a language the word engine has loaded: the first (English when none is loaded yet) or
     * one typed together with it. Only the first was checked: an English + French keyboard on French AZERTY (its ' key
     * makes it French's own) got no suggestions and no autocorrect at all (2026-11-24).
     */
    fun matchesLoaded(layoutId: String): Boolean =
        types(layoutId, listOf(EngineLoader.pack?.tag ?: LanguagePack.DEFAULT_TAG) + EngineLoader.secondaryPacks.map { it.tag })

    /** Whether [layoutId] types any of [tags] ("en-US", "fr"): the decision behind [matchesLoaded]. */
    fun types(layoutId: String, tags: List<String>): Boolean {
        val types = languagesOf[layoutId] ?: setOf(layoutId.substringAfterLast('-'))
        return tags.any { it.substringBefore('-').lowercase() in types }
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

package io.github.sillypuppyy.dogboard.ime

import java.text.Normalizer

/**
 * Letters a language typed together with the keyboard's own (Subtype.secondaryLocales) needs and the layout lacks,
 * put on the long-press of a key (notes/design/multilingual.md, M7): Russian ы э ё on the Bulgarian phonetic layout,
 * ñ on English QWERTY. A letter goes on the key of its base letter (ё on е, ñ on n), else on the key [HOSTS] names
 * (letters with no base: э on е, as Gboard's Russian keys group them). Lowercase both ways; capitals follow the key.
 */
object ExtraLetters {
    /** Letters without a base letter, and the key each goes on (Cyrillic: lookalike or neighbour in sound). */
    private val HOSTS = mapOf(
        'э' to "еє", 'ы' to "иі", 'ъ' to "ь", 'ь' to "ъ", 'і' to "иы", 'є' to "еэ", 'ґ' to "г", 'ў' to "у",
        'ђ' to "дџ", 'ћ' to "чц", 'џ' to "дч", 'љ' to "л", 'њ' to "н", 'ј' to "й", 'ѕ' to "з",
        'ß' to "s", 'æ' to "a", 'ø' to "o", 'œ' to "o", 'þ' to "t", 'ð' to "d", 'ı' to "i",
    )

    /** The key's letter for [c]: its base letter when it has one (é -> e, ё -> е, й stays), else itself. */
    private fun base(c: Char): Char =
        Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull()?.takeIf { it != c && it.isLetter() } ?: c

    /**
     * For the layout [layoutId] typing [second] too: host letter -> the letters to add to its long-press. Letters the
     * layout has, or with no key to go on, are left out. Empty for no second language or no layout of its own.
     */
    fun forEntry(layoutId: String, second: String?): Map<String, List<String>> {
        if (second == null) return emptyMap()
        val own = GeneratedLayouts.all.firstOrNull { it.id == layoutId } ?: return emptyMap()
        val theirs = GeneratedLayouts.all.filter { it.language == second }.let { ls -> ls.firstOrNull { it.default } ?: ls.firstOrNull() } ?: return emptyMap()
        val have = own.rows.joinToString("").toSet()
        val out = LinkedHashMap<String, MutableList<String>>()
        for (c in theirs.rows.joinToString("").toList().distinct()) {
            if (!c.isLetter() || c in have) continue
            val host = base(c).takeIf { it != c && it in have } ?: HOSTS[c]?.firstOrNull { it in have } ?: continue
            out.getOrPut(host.toString()) { mutableListOf() }.add(c.toString())
        }
        return out
    }
}

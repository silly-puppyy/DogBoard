package io.github.sillypuppyy.dogboard.engine

import java.text.Normalizer

/**
 * A language's typed spellings (rules.json "spellings", and "digraphs" as letter rules): letters people type as other
 * letters, one table for every such method ("generalise all these transliteration methods as simple
 * replacement rules"). German ä as "ae" (fuer -> für); Vietnamese Telex: â as "aa", đ as "dd", the tone mark as a
 * letter at the syllable's end (vieetj -> việt). TypingEngine.withSpellings decodes the typed taps without the extra
 * letters ([extraLetters]) and accepts a word whose [spelled] form is exactly what was typed.
 */
class TypedSpellings(rules: List<LanguageRules.Spelling>) {
    // longer letters first, so a rule never replaces part of another's letter
    private val letters = rules.filter { it.letter.isNotEmpty() && it.typed.isNotEmpty() }.sortedByDescending { it.letter.length }
    private val endMarks = rules.filter { it.mark.isNotEmpty() && it.typed.isNotEmpty() && it.at == LanguageRules.Spelling.AT_END }

    val isEmpty: Boolean get() = letters.isEmpty() && endMarks.isEmpty()

    /** How [word] is typed under the rules, lowercase: "für" -> "fuer", "việt" -> "vieetj", "Đà" -> "ddaf". */
    fun spelled(word: String): String {
        var w = Normalizer.normalize(word.lowercase(), Normalizer.Form.NFD)
        val suffix = StringBuilder()
        // a mark typed at the end (Telex tones): taken off the letter, its key appended
        for (m in endMarks) if (m.mark in w) { w = w.replace(m.mark, ""); suffix.append(m.typed) }
        w = Normalizer.normalize(w, Normalizer.Form.NFC)
        for (l in letters) w = w.replace(l.letter, l.typed)
        return w + suffix
    }

    /**
     * The typed letters that belong to a rule's typed form rather than to a letter of their own: the letters after the
     * first of each typed form ("fuer": the e of "ue"; "vieetj": the second e of "ee") and an end mark's key ("j").
     * Left out, the rest is what the decoder searches ("fur", "viet").
     */
    fun extraLetters(typed: String): BooleanArray {
        val lower = typed.lowercase()
        val extra = BooleanArray(lower.length)
        for (m in endMarks) if (lower.length > m.typed.length && lower.endsWith(m.typed)) {
            for (k in lower.length - m.typed.length until lower.length) extra[k] = true
            break
        }
        for (l in letters) {
            var i = lower.indexOf(l.typed)
            while (i >= 0) {
                // as many typed letters stay as the rule's letter has (ươ typed "uow": u and o stay, the w is extra)
                if ((i until i + l.typed.length).none { extra[it] }) for (k in l.letter.length until l.typed.length) extra[i + k] = true
                i = lower.indexOf(l.typed, i + l.typed.length)
            }
        }
        return extra
    }
}

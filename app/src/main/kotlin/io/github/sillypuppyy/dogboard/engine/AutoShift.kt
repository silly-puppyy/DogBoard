package io.github.sillypuppyy.dogboard.engine

import java.util.regex.Pattern

/**
 * When the keyboard turns shift on by itself, following the iOS rules
 * (notes/design/settings-model.md §3; matches iOS behaviour).
 *
 * Sentences: the text before the cursor must end in a sentence boundary, then spaces, then
 * opening punctuation. A boundary is the start of the text, a new line, three spaces, or a word
 * (or nothing) followed by . ? or ! plus closing quotes and a space. After a ".", the word before it
 * decides: ".." never starts a sentence, and a word whose dictionary forms all keep their period
 * ("e.g.", "U.S.") does not either. When that word is capitalised only because it starts a
 * sentence itself, its capitalisation is ignored in the lookup.
 */
object AutoShift {
    /** The field's capitalisation (Android EditorInfo caps mode). */
    enum class Type { NONE, WORDS, SENTENCES, ALL }

    /** The forms a word is stored in: the lexicon's own and any abbreviation with its period. */
    fun interface WordForms {
        fun formsOf(word: String): List<String>
    }

    private const val SPACE = "[\\u0020\\u00A0]"
    private const val TRAILING = "['\"\\u2019\\u201D]"
    private const val PREFIX = "[\\u00A1\\u00BF'\"\\u2018\\u201C(]"

    /**
     * A word character in Unicode (ICU's \w), spelled out: Android's regex engine rejects the
     * UNICODE_CHARACTER_CLASS flag and the desktop JVM's plain \w is ASCII only.
     */
    private const val WORD = "[\\p{L}\\p{M}\\p{N}\\p{Pc}]"

    /**
     * The sentence-boundary pattern; group 1 is the word plus its delimiter when that is the boundary. [quotes]:
     * the language's five quote characters (LanguageRules.quotes); its closing quotes may follow the sentence end
     * and its opening ones start the next sentence, besides the fixed sets (German „…“, French «…»).
     */
    internal fun boundary(sentenceEnd: String, quotes: String = ""): Pattern = boundaries.getOrPut(sentenceEnd + "\u0000" + quotes) {
        fun cls(chars: String) = chars.map { "\\u%04X".format(it.code) }.joinToString("")
        // the sentence-ending characters (LanguageRules.sentenceEnd) as a character class
        val delimiter = "[" + cls(sentenceEnd) + "]"
        val q = quotes.takeIf { it.length == 5 }
        val trailing = if (q == null) TRAILING else TRAILING.dropLast(1) + cls("${q[1]}${q[3]}") + "]"
        val prefix = if (q == null) PREFIX else PREFIX.dropLast(1) + cls("${q[0]}${q[2]}") + "]"
        Pattern.compile(
            "(?:\\A|\\n|(?:$SPACE){3}|((?:(?<!$WORD)$WORD++(?:\\.$WORD++)*)?(?:$delimiter))(?:$trailing)*(?:$SPACE))(?:$SPACE)*(?:$prefix)*\\z",
            Pattern.MULTILINE,
        )
    }
    private val boundaries = java.util.concurrent.ConcurrentHashMap<String, Pattern>()

    /** The pattern for Latin-script sentences (".!?"). */
    internal val BOUNDARY: Pattern get() = boundary(LanguageRules().sentenceEnd)

    /** How far back a capitalised word before a period is checked for being a sentence start. */
    private const val MAX_RECURSION = 2

    /** Whether the next letter typed after [before] should be a capital in a field of [type]. */
    fun shouldShift(
        before: CharSequence, type: Type, words: WordForms?, sentenceEnd: String = LanguageRules().sentenceEnd, quotes: String = "",
    ): Boolean = when (type) {
        Type.NONE -> false
        Type.ALL -> true
        Type.WORDS -> before.isEmpty() || before.last().let { it == '\n' || it == ' ' || it == ' ' }
        Type.SENTENCES -> before.isEmpty() || canStartSentenceAfter(before.toString(), words, MAX_RECURSION, sentenceEnd, quotes)
    }

    internal fun canStartSentenceAfter(
        text: String, words: WordForms?, depth: Int, sentenceEnd: String = LanguageRules().sentenceEnd, quotes: String = "",
    ): Boolean {
        val m = boundary(sentenceEnd, quotes).matcher(text)
        if (!m.find()) return false
        val start = m.start(1)
        if (start < 0 || m.end(1) == start) return true // start of text, a new line or three spaces
        val last = m.end(1) - 1
        if (text[last] != '.') return true // ? or ! (another sentence end)
        if (last > 0 && text[last - 1] == '.') return false // ".."
        val word = text.substring(start, last)
        if (word.isEmpty()) return true
        val ignoreCase = depth > 0 && capitalization(word) == 1 &&
            canStartSentenceAfter(text.substring(0, start), words, depth - 1, sentenceEnd, quotes)
        return periodEndsSentenceAfter(word, ignoreCase, words)
    }

    /**
     * Whether a period after [word] ends the sentence: yes unless the case-matching forms of the
     * word (all forms when [ignoreCase] or when none matches the case) all keep their period.
     */
    internal fun periodEndsSentenceAfter(word: String, ignoreCase: Boolean, words: WordForms?): Boolean {
        val forms = words?.formsOf(word).orEmpty()
        if (forms.isEmpty()) return true
        val caps = capitalization(word)
        val matching = forms.filter { ignoreCase || capitalization(it.removeSuffix(".")) == caps }
        val considered = matching.ifEmpty { forms }
        return considered.any { !it.endsWith('.') }
    }

    /** Bit i set when character i is upper case (first 32 characters). */
    internal fun capitalization(s: String): Int {
        var mask = 0
        for ((i, c) in s.withIndex()) if (i < 32 && c.isUpperCase()) mask = mask or (1 shl i)
        return mask
    }
}

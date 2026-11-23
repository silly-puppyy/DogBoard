package io.github.sillypuppyy.dogboard.engine

/**
 * Korean 2-set (두벌식) composition: the jamo keys typed so far drawn as Hangul syllables (ㅎㅏㄴㄱㅜㄱㅇㅓ -> 한국어), as
 * every Korean keyboard does while typing. The standard automaton: a consonant starts a syllable or closes one as its
 * final; a vowel after a final takes that final as its own initial (ㅎㅏㄴㅏ -> 하나, a double final splits: ㄷㅏㄹㄱㅏ ->
 * 달가); vowels and finals combine (ㅗㅏ -> ㅘ, ㄹㄱ -> ㄺ). Keys that aren't jamo pass through. [decompose] gives a drawn
 * word's keys back (a word seeded from the text). Unicode Hangul arithmetic, no dictionary.
 */
object HangulComposer {
    private const val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private const val JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
    private const val JONG = "\u0000ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"
    private val VOWEL_PAIRS = mapOf("ㅗㅏ" to 'ㅘ', "ㅗㅐ" to 'ㅙ', "ㅗㅣ" to 'ㅚ', "ㅜㅓ" to 'ㅝ', "ㅜㅔ" to 'ㅞ', "ㅜㅣ" to 'ㅟ', "ㅡㅣ" to 'ㅢ')
    private val FINAL_PAIRS = mapOf(
        "ㄱㅅ" to 'ㄳ', "ㄴㅈ" to 'ㄵ', "ㄴㅎ" to 'ㄶ', "ㄹㄱ" to 'ㄺ', "ㄹㅁ" to 'ㄻ', "ㄹㅂ" to 'ㄼ', "ㄹㅅ" to 'ㄽ',
        "ㄹㅌ" to 'ㄾ', "ㄹㅍ" to 'ㄿ', "ㄹㅎ" to 'ㅀ', "ㅂㅅ" to 'ㅄ',
    )
    private val SPLIT_FINAL = FINAL_PAIRS.entries.associate { (k, v) -> v to k }
    private val SPLIT_VOWEL = VOWEL_PAIRS.entries.associate { (k, v) -> v to k }

    private fun isConsonant(c: Char) = c in CHO
    private fun isVowel(c: Char) = c in JUNG

    /** A syllable being built: initial, vowel, final (null: not yet). */
    private class Syl(var cho: Char? = null, var jung: Char? = null, var jong: Char? = null) {
        fun isEmpty() = cho == null && jung == null && jong == null
        fun draw(out: StringBuilder) {
            val c = cho; val j = jung
            when {
                c != null && j != null -> out.append((0xAC00 + (CHO.indexOf(c) * 21 + JUNG.indexOf(j)) * 28 + (jong?.let { JONG.indexOf(it) } ?: 0)).toChar())
                else -> { c?.let(out::append); j?.let(out::append); jong?.let(out::append) }
            }
        }
    }

    /** The syllables [keys] make. */
    fun compose(keys: String): String {
        val out = StringBuilder()
        var s = Syl()
        fun flush() { if (!s.isEmpty()) s.draw(out); s = Syl() }
        for (k in keys) {
            when {
                isConsonant(k) -> when {
                    s.cho != null && s.jung != null && s.jong == null && JONG.indexOf(k) > 0 -> s.jong = k
                    s.jong != null && FINAL_PAIRS["${s.jong}$k"] != null -> s.jong = FINAL_PAIRS["${s.jong}$k"]
                    else -> { flush(); s.cho = k }
                }
                isVowel(k) -> when {
                    s.jong != null -> {
                        // the final opens the next syllable (a double final gives its second half)
                        val split = SPLIT_FINAL[s.jong!!]
                        val next = if (split != null) { s.jong = split[0]; split[1] } else { val j = s.jong!!; s.jong = null; j }
                        flush(); s.cho = next; s.jung = k
                    }
                    s.jung != null && VOWEL_PAIRS["${s.jung}$k"] != null -> s.jung = VOWEL_PAIRS["${s.jung}$k"]
                    s.cho != null && s.jung == null -> s.jung = k
                    else -> { flush(); s.jung = k }
                }
                else -> { flush(); out.append(k) }
            }
        }
        flush()
        return out.toString()
    }

    /** The keys of a drawn word (한국어 -> ㅎㅏㄴㄱㅜㄱㅇㅓ; jamo and other characters as they are). */
    fun decompose(text: String): String = buildString {
        for (ch in text) {
            val code = ch.code - 0xAC00
            if (code !in 0 until 11172) {
                SPLIT_VOWEL[ch]?.let { append(it) } ?: SPLIT_FINAL[ch]?.let { append(it) } ?: append(ch)
                continue
            }
            append(CHO[code / (21 * 28)])
            val j = JUNG[(code / 28) % 21]
            append(SPLIT_VOWEL[j] ?: j.toString())
            val f = code % 28
            if (f > 0) JONG[f].let { append(SPLIT_FINAL[it] ?: it.toString()) }
        }
    }
}

package io.github.sillypuppyy.dogboard.engine

/**
 * Indic keyboards (Gboard's Hindi compact, Marathi, Bengali, Tamil, Telugu, Gujarati...): the keys carry the
 * independent vowels (आ ই ஆ ...); one typed after a consonant writes its vowel sign instead (क + आ -> का, ক + আ -> কা),
 * as Gboard's keyboards do (their conditional key mappings). After the virama, at a word's start or after a vowel: the
 * vowel itself. One rule for every Indic block: Unicode places each script's vowel sign 0x38 after its independent
 * vowel (आ U+0906 / ा U+093E, ஆ U+0B86 / ா U+0BBE), where the script has that sign. [decompose] gives the keys back.
 */
object DevanagariComposer {
    /** The Indic blocks: Devanagari, Bengali, Gurmukhi, Gujarati, Oriya, Tamil, Telugu, Kannada, Malayalam. */
    private fun blockOf(c: Char): Int? = (c.code and 0xFF80).takeIf { it in 0x0900..0x0D00 }

    private fun isSign(code: Int) = Character.getType(code).let {
        it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt()
    }

    /** The vowel sign of an independent vowel, or null (ऌ's sign isn't at the offset: left as it is). */
    private fun signOf(c: Char): Char? {
        val base = blockOf(c) ?: return null
        val i = c.code - base
        if (i !in 0x06..0x14 || i == 0x0C) return null
        return (c.code + 0x38).takeIf { Character.isDefined(it) && isSign(it) }?.toChar()
    }

    private fun vowelOf(c: Char): Char? {
        val base = blockOf(c) ?: return null
        val i = c.code - base
        if (i !in 0x3E..0x4C || i == 0x44) return null
        return (c.code - 0x38).takeIf { Character.isDefined(it) && Character.isLetter(it) }?.toChar()
    }

    /** A consonant (with a nukta too: ড়): what a vowel sign attaches to. */
    private fun isConsonant(c: Char): Boolean {
        val base = blockOf(c) ?: return false
        val i = c.code - base
        return (i in 0x15..0x39 || i in 0x58..0x5F || i in 0x78..0x7F) && Character.isLetter(c) || i == 0x3C
    }

    fun compose(keys: String): String = buildString {
        for (k in keys) {
            val sign = signOf(k)
            if (sign != null && isNotEmpty() && isConsonant(last())) append(sign) else append(k)
        }
    }

    fun decompose(text: String): String = buildString { for (c in text) append(vowelOf(c) ?: c) }
}

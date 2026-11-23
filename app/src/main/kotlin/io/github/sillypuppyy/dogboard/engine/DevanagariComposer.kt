package io.github.sillypuppyy.dogboard.engine

/**
 * Hindi on the compact layout (Gboard's default): the keys carry the independent vowels (आ इ ए ...); one typed after
 * a consonant writes its vowel sign instead (क + आ -> का), as Gboard's keyboard does (its conditional key mappings).
 * After the virama (्), at a word's start or after another vowel, the vowel itself. [decompose] gives the keys back.
 */
object DevanagariComposer {
    private val SIGN = mapOf(
        'आ' to 'ा', 'इ' to 'ि', 'ई' to 'ी', 'उ' to 'ु', 'ऊ' to 'ू', 'ऋ' to 'ृ', 'ए' to 'े', 'ऐ' to 'ै',
        'ओ' to 'ो', 'औ' to 'ौ', 'ऑ' to 'ॉ', 'ऍ' to 'ॅ',
    )
    private val VOWEL = SIGN.entries.associate { (v, s) -> s to v }

    /** A consonant (with a nukta too: ड़): what a vowel sign attaches to. */
    private fun isConsonant(c: Char) = c in 'क'..'ह' || c in 'क़'..'य़' || c in 'ॸ'..'ॿ' || c == '़'

    fun compose(keys: String): String = buildString {
        for (k in keys) {
            val sign = SIGN[k]
            if (sign != null && isNotEmpty() && isConsonant(last())) append(sign) else append(k)
        }
    }

    fun decompose(text: String): String = buildString { for (c in text) append(VOWEL[c] ?: c) }
}

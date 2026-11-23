package io.github.sillypuppyy.dogboard.engine

import java.text.Normalizer

/**
 * Live Telex composition (notes/research/language-families.md 36b): the keys of the word typed so far drawn as the
 * syllable they make ("vieetj" -> việt) while typing, as Gboard and the system Telex keyboards do. Deterministic, no
 * dictionary: shapes (aa â, aw ă, ee ê, oo ô, ow ơ, uw ư, uow ươ, dd đ, a w after u / o), a doubled shape key taking
 * the shape back ("aaa" -> "aa"), the tone key (s f r x j) on the syllable's vowel, the same tone key twice typing the
 * letter. Only keys that can be one Vietnamese syllable compose; anything else ("website") stays as typed. The decoder
 * still reads the keys (corrections run in Telex space); this is what the field shows. Prototype checked on the word
 * list: 99.5 % of word uses compose back to themselves (the rest: foreign words like "max", which Telex types as mã).
 */
object TelexComposer {
    private val SHAPES = mapOf("aa" to 'â', "aw" to 'ă', "ee" to 'ê', "oo" to 'ô', "ow" to 'ơ', "uw" to 'ư', "dd" to 'đ')
    private val UNSHAPE = mapOf('â' to 'a', 'ê' to 'e', 'ô' to 'o', 'đ' to 'd')
    private val TONES = mapOf('s' to '́', 'f' to '̀', 'r' to '̉', 'x' to '̃', 'j' to '̣')
    private const val VOWELS = "aăâeêioôơuưy"
    private const val SHAPED = "ăâêôơư"
    /** The start of a syllable before its vowel ("đ", "ng", "tr"): drawn at once, as the system Telex keyboards do. */
    private val INITIAL = Regex("^(ngh|ng|nh|ch|gh|gi|kh|ph|qu|th|tr|[bcdđghklmnpqrstvx])$")
    private val SYLLABLE = Regex("^(ngh|ng|nh|ch|gh|gi|kh|ph|qu|th|tr|[bcdđghklmnpqrstvx])?([aăâeêioôơuưy]+)(ch|ng|nh|[cmnpt])?$")

    /** The syllable [keys] make, lowercase; [keys] themselves (lowercased) when they can't be one. */
    fun compose(keys: String, newStyle: Boolean = false): String {
        val (letters, tone, undone) = shape(keys.lowercase())
        // an undo ("ass") asked for the letter itself: what it gives is kept, syllable or not
        if (undone) return letters
        if (letters.none { it in VOWELS }) return if (INITIAL.matches(letters)) letters else keys.lowercase()
        if (!SYLLABLE.matches(letters)) return keys.lowercase()
        if (tone == null) return letters
        val i = toneIndex(letters, newStyle) ?: return letters
        return letters.substring(0, i) + Normalizer.normalize("${letters[i]}$tone", Normalizer.Form.NFC) + letters.substring(i + 1)
    }

    /** The letters with shapes applied, the tone mark typed (null: none), and whether a key was undone. */
    private fun shape(keys: String): Triple<String, Char?, Boolean> {
        val out = StringBuilder()
        var tone: Char? = null
        var undone = false
        for (k in keys) {
            val t = TONES[k]
            if (t != null && out.any { it in VOWELS }) {
                if (tone == t) { tone = null; out.append(k); undone = true } else tone = t // the same tone key twice: the letter
                continue
            }
            if (out.isNotEmpty()) {
                val prev = out.last()
                val shaped = SHAPES["$prev$k"]
                if (shaped != null) {
                    out.setCharAt(out.length - 1, shaped)
                    if (shaped == 'ơ' && out.length > 1 && out[out.length - 2] == 'u') out.setCharAt(out.length - 2, 'ư') // uow
                    continue
                }
                if (UNSHAPE[prev] == k) { out.setCharAt(out.length - 1, k); out.append(k); undone = true; continue } // aaa -> aa
                if (k == 'w') {
                    // a w after the vowels: their u / o takes the horn (huw -> hư, "uow" written as "uwo")
                    var i = out.length - 1
                    while (i >= 0 && out[i] in VOWELS && out[i] !in "uo") i--
                    if (i >= 0 && out[i] in "uo") {
                        out.setCharAt(i, if (out[i] == 'u') 'ư' else 'ơ')
                        if (out[i] == 'ơ' && i > 0 && out[i - 1] == 'u') out.setCharAt(i - 1, 'ư')
                        continue
                    }
                }
            }
            out.append(k)
        }
        return Triple(out.toString(), tone, undone)
    }

    /** Where the tone mark goes in [s]: a shaped vowel, else the last vowel before a final consonant, else by count. */
    private fun toneIndex(s: String, newStyle: Boolean): Int? {
        val start = when {
            s.startsWith("qu") -> 2
            s.startsWith("gi") && s.length > 2 && s[2] in VOWELS -> 2
            else -> 0
        }
        val first = (start until s.length).firstOrNull { s[it] in VOWELS } ?: return null
        var end = first
        while (end + 1 < s.length && s[end + 1] in VOWELS) end++
        val run = first..end
        run.lastOrNull { s[it] in SHAPED }?.let { return it } // ươ: the ơ
        if (run.first == run.last) return run.first
        if (end + 1 < s.length) return run.last // a final consonant: the last vowel
        if (run.count() == 3) return run.first + 1
        // two vowels, open: oa oe uy on the second in the new style
        if (newStyle && s.substring(run.first, run.first + 2) in setOf("oa", "oe", "uy")) return run.last
        return run.first
    }
}

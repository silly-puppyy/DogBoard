package io.github.sillypuppyy.dogboard.engine

import java.text.Normalizer
import kotlin.math.exp
import kotlin.math.ln

/**
 * Decides whether the word typed so far is replaced when the user ends it (space, punctuation),
 * following the iOS rules: the top-ranked candidate
 * wins unless it is the typed word itself; completions of what was typed need a long enough stem
 * and a clear lead over the runner-up.
 */
class Autocorrect(private val lexicon: Lexicon) {
    companion object {
        /** A completion must be this many times more likely than the next candidate. */
        const val MIN_DOMINANCE_RATIO = 2.7

        /** Minimum typed length for completing 1, 2, or 3+ more letters. */
        private const val MIN_STEM_ONE_LETTER = 8
        private const val MIN_STEM_TWO_LETTERS = 6
        private const val MIN_STEM_MORE_LETTERS = 4

        /**
         * Rule 3: words run together are only split when the words are at least this likely in
         * context compared with on their own (iOS "insufficient linguistic support").
         */
        private val MIN_CONTEXT_SUPPORT = ln(0.1)

        /** iOS BoostMinimumVariation: a plainer spelling this close to the top goes first. */
        private val MIN_VARIATION_RATIO = ln(1.1)

        /** Characters that make a completion a "special edit" (contractions and the like). */
        private val SPECIAL_EDIT_CHARS = setOf('\'', '’', '&')

        /** The casing rules when nothing more is known: capitals in [typed] count as shifted by the user. */
        fun inferredCase(typed: String): (String) -> String? {
            val flags = typed.map { if (it.isUpperCase()) CaseAdapt.SHIFTED else 0 }
            val context = if (flags.firstOrNull() == CaseAdapt.SHIFTED) CaseAdapt.Context.SHIFT else CaseAdapt.Context.NONE
            return { word -> CaseAdapt.apply(word, typed, flags, context, CaseAdapt.NoDictionary) }
        }
    }

    /**
     * Returns the word to commit instead of [typed] (as typed, with its casing), or null to keep it.
     *
     * @param candidates decoder output for the same taps, best first, including the typed string
     *   itself when it is not a known word
     * @param previousCorrection what this rule chose at the previous keystroke, if anything
     *   (a completion that was already the choice one letter earlier skips the length gate)
     */
    fun correction(
        typed: String,
        candidates: List<Candidate>,
        previousCorrection: String? = null,
        case: (String) -> String? = inferredCase(typed),
    ): String? {
        // iOS apply_case_changes: each candidate takes the typed capitals; ones the case rules
        // don't accept for this input are filtered out
        val cased = candidates
            .filter { it.words == 1 || it.contextSupport >= MIN_CONTEXT_SUPPORT } // Rule 3
            .mapNotNull { c -> case(c.word)?.let { c to it } }
            .let(::boostPlainer)
        val (top, t) = cased.firstOrNull() ?: return null
        // Rule 1: the typed word wins.
        if (t == typed) return null
        // Rule 2: never complete a one-letter word into a longer word ("a" -> "and").
        if (typed.length == 1 && t.length > 1 && t.startsWith(typed, ignoreCase = true)) return null
        // Rule 4: a correction that is not a completion of what was typed is taken as is.
        if (!(t.length >= typed.length && t.startsWith(typed))) return t
        // Rule 5: a completion needs a long enough stem (or a contraction-like ending, or the same
        // choice as one keystroke earlier) and must clearly beat the runner-up.
        val gain = t.length - typed.length
        val specialEdit = t.substring(typed.length).any { it in SPECIAL_EDIT_CHARS }
        val minStem = when {
            gain <= 1 -> MIN_STEM_ONE_LETTER
            gain == 2 -> MIN_STEM_TWO_LETTERS
            else -> MIN_STEM_MORE_LETTERS
        }
        val stemOk = specialEdit || (previousCorrection != null && t.equals(previousCorrection, ignoreCase = true)) ||
            typed.length >= minStem
        if (!stemOk) return null
        val (second, s) = cased.getOrNull(1) ?: return t
        if (s.equals(t, ignoreCase = true) || s.startsWith(t, ignoreCase = true)) return t
        return if (MIN_DOMINANCE_RATIO * exp(second.score) < exp(top.score)) t else null
    }

    /**
     * iOS BoostMinimumVariation (ratio 1.1): when a spelling of the same letters with fewer
     * apostrophes, hyphens or accents scores within 10% of the top ("were" vs "we're"), it goes
     * first. Only spellings without capitals after the first letter qualify.
     */
    private fun boostPlainer(cased: List<Pair<Candidate, String>>): List<Pair<Candidate, String>> {
        if (cased.size < 2) return cased
        val (top, t) = cased[0]
        val key = sortKey(t)
        var best = variation(t)
        var pick = -1
        for (j in 1 until cased.size) {
            val (c, w) = cased[j]
            if (c.score + MIN_VARIATION_RATIO < top.score) break
            val v = variation(w)
            if (v < best && w.drop(1).none { it.isUpperCase() } && sortKey(w) == key) {
                best = v
                pick = j
            }
        }
        if (pick < 0) return cased
        return listOf(cased[pick]) + cased.filterIndexed { i, _ -> i != pick }
    }

    /** Marks inside words (apostrophes, hyphens, dots) plus accented letters. */
    private fun variation(word: String): Int {
        val medial = (1 until word.length - 1).count { i -> !word[i].isLetterOrDigit() && word[i] != ' ' }
        return medial + word.count { Normalizer.normalize(it.toString(), Normalizer.Form.NFD).length > 1 }
    }

    /** The letters only, without case or accents ("We're" and "were" share one). */
    private fun sortKey(word: String): String =
        Normalizer.normalize(word, Normalizer.Form.NFD).filter { it.isLetterOrDigit() }.lowercase()
}

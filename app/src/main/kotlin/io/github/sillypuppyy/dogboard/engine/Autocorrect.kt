package io.github.sillypuppyy.dogboard.engine

import kotlin.math.exp

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

        /** Characters that make a completion a "special edit" (contractions and the like). */
        private val SPECIAL_EDIT_CHARS = setOf('\'', '’', '&')

        /**
         * Applies the casing the user typed: all caps stays all caps, a capitalised first letter
         * (typed or from auto-shift) carries over; the word's own capitals ("I", names) are kept.
         */
        fun matchCase(word: String, typed: String): String = when {
            typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
            typed.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.uppercaseChar() }
            else -> word
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
    fun correction(typed: String, candidates: List<Candidate>, previousCorrection: String? = null): String? {
        val top = candidates.firstOrNull() ?: return null
        val t = matchCase(top.word, typed)
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
        val second = candidates.getOrNull(1) ?: return t
        val s = matchCase(second.word, typed)
        if (s.equals(t, ignoreCase = true) || s.startsWith(t, ignoreCase = true)) return t
        return if (MIN_DOMINANCE_RATIO * exp(second.score) < exp(top.score)) t else null
    }
}

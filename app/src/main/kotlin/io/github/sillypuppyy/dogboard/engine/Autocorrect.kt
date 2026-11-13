package io.github.sillypuppyy.dogboard.engine

/**
 * Decides whether the word typed so far is replaced when the user ends it (space, punctuation).
 */
class Autocorrect(private val lexicon: Lexicon, private val config: Config = Config()) {
    data class Config(
        /** A known word is only replaced if another word is cheaper by at least this much. */
        val knownWordMargin: Double = 4.0,
        /**
         * Cost of the user meaning a word the lexicon does not know (a name, slang): an unknown
         * word is replaced only if the best word is cheaper than its taps' cost plus this.
         */
        val unknownWordCost: Double = 13.0, // from EngineSweepTest: 96.8% right at 1.5x2.0 mm, 89% of unknown words kept
        /** An unknown word is only replaced by a word at most this many typing errors away. */
        val maxUnknownEdits: Int = 2,
    )

    /**
     * Returns the word to commit instead of [literal] (the letters of the keys hit), already in
     * the literal's casing, or null to keep what was typed.
     *
     * @param candidates decoder output for the same taps, best first
     * @param literalSpatialCost spatial cost of the taps read as exactly [literal]
     *   ([Decoder.literalCost]), or null if unknown (then the literal costs nothing extra)
     */
    fun correction(literal: String, candidates: List<Candidate>, literalSpatialCost: Double? = null): String? {
        val best = candidates.firstOrNull { !it.isCompletion } ?: return null
        if (best.word.equals(literal, ignoreCase = true)) return fixedForm(literal)
        val literalCandidate = candidates.firstOrNull { !it.isCompletion && it.word.equals(literal, ignoreCase = true) }
        val replace = if (lexicon.contains(literal)) {
            literalCandidate == null || literalCandidate.cost - best.cost >= config.knownWordMargin
        } else {
            best.edits <= config.maxUnknownEdits &&
                best.cost < (literalSpatialCost ?: 0.0) + config.unknownWordCost
        }
        return if (replace) matchCase(best.word, literal) else null
    }

    /** The stored form of a known word when it differs only in casing ("i" -> "I"), else null. */
    private fun fixedForm(literal: String): String? {
        val stored = lexicon.storedForm(literal) ?: return null
        val cased = matchCase(stored, literal)
        return cased.takeIf { it != literal }
    }

    companion object {
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
}

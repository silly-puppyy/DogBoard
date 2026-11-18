package io.github.sillypuppyy.dogboard.engine

/** One slot of the suggestion strip. */
data class Suggestion(val word: String, val kind: Kind) {
    /** The typed word the strip was built for (null: next-word predictions); not part of equality. */
    var forTyped: String? = null

    /** Set on the original of a recent autocorrection: picking it undoes that correction. */
    var revertOf: String? = null

    /** Set on another reading of a swipe: picking it replaces this text (the swiped word and its space). */
    var swipeOf: String? = null

    enum class Kind {
        /** Exactly what was typed, shown in quotes; picking it keeps the word as typed. */
        LITERAL,

        /** What space will turn the word into. */
        AUTOCORRECT,

        /** Another likely word or completion. */
        WORD,

        /** A likely next word, shown before anything is typed. */
        PREDICTION,

        /** An emoji for the word(s) before the cursor; picking it adds it after the word. */
        EMOJI,
    }
}

/**
 * Builds the three-slot suggestion strip: the typed word in quotes on the left, the
 * autocorrection (or best guess) in the middle, the next best word on the right.
 */
object SuggestionStrip {
    const val SLOTS = 3

    fun build(
        typed: String,
        candidates: List<Candidate>,
        correction: String?,
        case: (String) -> String? = Autocorrect.inferredCase(typed),
        emoji: String? = null,
    ): List<Suggestion> {
        if (typed.isEmpty()) return emptyList()
        val result = ArrayList<Suggestion>(SLOTS)
        result.add(Suggestion(typed, Suggestion.Kind.LITERAL))
        if (correction != null) result.add(Suggestion(correction, Suggestion.Kind.AUTOCORRECT))
        val seen = HashSet<String>()
        result.forEach { seen.add(it.word.lowercase()) }
        for (candidate in candidates) {
            if (result.size >= SLOTS) break
            if (candidate.isTypedString) continue
            val word = case(candidate.word) ?: continue
            if (seen.add(word.lowercase())) result.add(Suggestion(word, Suggestion.Kind.WORD))
        }
        // the design's third slot offers an emoji for the word (Panels:99)
        if (emoji != null) {
            if (result.size >= SLOTS) result[SLOTS - 1] = Suggestion(emoji, Suggestion.Kind.EMOJI)
            else result.add(Suggestion(emoji, Suggestion.Kind.EMOJI))
        }
        return result.onEach { it.forTyped = typed }
    }
}

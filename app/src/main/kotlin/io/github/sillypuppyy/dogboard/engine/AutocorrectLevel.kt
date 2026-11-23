package io.github.sillypuppyy.dogboard.engine

/**
 * The autocorrect strength setting (design: Off / Gentle / Standard / Strong; Off is the
 * autocorrect switch). Standard is iOS; Gentle and Strong were measured on the eval words
 * (notes/design/settings-model.md §4).
 */
enum class AutocorrectLevel(val level: Autocorrect.Level) {
    /** A correction must be twice as likely as the typed letters: the fewest wrong changes. */
    GENTLE(Autocorrect.Level.STANDARD.copy(leadOverTyped = 2.0)),
    STANDARD(Autocorrect.Level.STANDARD),
    /**
     * No keep-the-typed-word margin and more weight on context: fixes the most for very sloppy typing
     * (eval: 2.5 mm scatter; real phone typos: language power 0.40, "that's the point of
     * the autocorrect strengths").
     */
    STRONG(Autocorrect.Level.STANDARD.copy(leadOverTyped = 1.0, languagePower = 0.40)),
}

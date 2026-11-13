package io.github.sillypuppyy.dogboard.engine

/**
 * The autocorrect strength setting (design: Off / Gentle / Standard / Strong; Off is the
 * autocorrect switch). Standard is iOS; Gentle and Strong were measured on the eval words
 * (notes/design/settings-model.md §4).
 */
enum class AutocorrectLevel(val level: Autocorrect.Level) {
    /** A correction must be twice as likely as the typed letters: wrong changes ~1/3 of Standard. */
    GENTLE(Autocorrect.Level.STANDARD.copy(leadOverTyped = 2.0)),
    STANDARD(Autocorrect.Level.STANDARD),
    /** More and shorter completions. Provisional: the eval has no unfinished words yet to measure it. */
    STRONG(Autocorrect.Level(dominanceRatio = 1.8, minStemOneLetter = 5, minStemTwoLetters = 4, minStemMoreLetters = 3)),
}

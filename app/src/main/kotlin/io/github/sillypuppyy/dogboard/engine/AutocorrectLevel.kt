package io.github.sillypuppyy.dogboard.engine

/**
 * The autocorrect strength setting (design: Off / Gentle / Standard / Strong; Off is the
 * autocorrect switch). Standard is iOS; Gentle and Strong were measured on the eval words
 * (notes/design/settings-model.md §4).
 */
enum class AutocorrectLevel(val level: Autocorrect.Level) {
    /** A correction must be twice as likely as the typed letters: the fewest wrong changes. */
    GENTLE(Autocorrect.Level.STANDARD.copy(leadOverTyped = 2.0, correctionDominance = 3.0)),
    // every level: a correction must clearly beat the next different word (near ties stay as typed, both in the bar).
    // Held-out real typos (ITE dev, 2026-11-24) fixed / wrong / broken %: Gentle 55.6/5.4/0.1 -> 50.6/2.0/0.1,
    // Standard 58.2/6.0/0.1 -> 56.2/3.3/0.1, Strong 66.1/8.8/0.7 -> 59.7/3.2/0.4 (English, with its letter costs below);
    // Finnish real typos (Standard, check alone) 56.8/6.6/1.2 -> 54.3/4.4/0.8. Gboard on the tuning set: 45.2/2.5/0.0
    STANDARD(Autocorrect.Level.STANDARD.copy(correctionDominance = 2.0)),
    /**
     * No keep-the-typed-word margin and more weight on context: fixes the most for very sloppy typing
     * (eval: 2.5 mm scatter; real phone typos: language power 0.40, "that's the point of
     * the autocorrect strengths").
     */
    STRONG(Autocorrect.Level.STANDARD.copy(leadOverTyped = 1.0, languagePower = 0.40, correctionDominance = 3.0)),
}

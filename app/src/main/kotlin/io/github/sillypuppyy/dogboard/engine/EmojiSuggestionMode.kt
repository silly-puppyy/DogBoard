package io.github.sillypuppyy.dogboard.engine

/** When the suggestion bar offers emoji (settings-model §1; iOS shows them only after emoji use). */
enum class EmojiSuggestionMode {
    OFF,

    /** iOS: once the user has used emoji, overall and in the app they're typing in. */
    AFTER_USE,

    ALWAYS,
}

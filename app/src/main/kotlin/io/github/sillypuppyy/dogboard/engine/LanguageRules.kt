package io.github.sillypuppyy.dogboard.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A language's rules as data (notes/design/i18n-pipeline.md, step 3): what differs by language is read
 * from the pack's `rules.json` instead of being written in the engine. Moved here one family at a time,
 * each with the evaluations unchanged; [ENGLISH] is the en-US pack's rules.json (a test keeps the two
 * equal), so the engine and its tests run as before without a pack.
 */
@Serializable
data class LanguageRules(
    /**
     * Short forms scored as their long form's language probability (iOS RescoreShortWords: "u" -> "you"),
     * in order; for a short form listed twice the last wins.
     */
    @SerialName("short_words") val shortWords: List<List<String>> = emptyList(),
    /**
     * iOS FilterCommonLearnedTypos (the language's adaptation-overrides asset): for these typed words
     * (exact case), corrections people tend to undo are never made.
     */
    @SerialName("learned_typo_overrides") val learnedTypoOverrides: Map<String, List<String>> = emptyMap(),
    /**
     * Marks that keep a space typed before them (French in France and Belgium: "Bonjour !"), so the
     * keyboard does not move them before that space as it does elsewhere ("word ?" -> "word? ").
     */
    @SerialName("space_before_marks") val spaceBeforeMarks: String = "",
    /**
     * Characters that end a sentence: the next word starts without a previous word (CurrentWord) and is
     * capitalised (AutoShift). The default is Latin-script typography; a pack sets its own (Hindi "।", Chinese
     * "。！？", Arabic "؟"). A period is still checked for abbreviations ("e.g.") by AutoShift.
     */
    @SerialName("sentence_end") val sentenceEnd: String = ".!?",
) {
    companion object {
        /** en-US (assets/dogboard/packs/en-US/rules.json). */
        val ENGLISH = LanguageRules(
            shortWords = listOf(listOf("u", "you"), listOf("o", "oh")),
            learnedTypoOverrides = linkedMapOf("i" to listOf("o", "O"), "I" to listOf("O", "o"), "can" to listOf("can't"), "and" to listOf("abs")),
            sentenceEnd = ".!?",
        )

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): LanguageRules = json.decodeFromString(text)
    }
}

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
    /**
     * Smart quotes, five characters: left and right double, left and right single, apostrophe. The default is
     * the English set every layout used so far; a pack sets its own (German "„“‚‘’", French "«»‹›’").
     */
    @SerialName("quotes") val quotes: String = "“”‘’’",
    /**
     * What a double space becomes (iOS UIKeyboardDoubleSpaceReplacement, per language): ". " by default,
     * "। " for Hindi and the other Indic modes, "۔ " Urdu, "։ " Armenian, "። " Amharic, "။ " Burmese, "。"
     * without a space for Yi and Ainu; two plain spaces (Japanese, Thai) mean no replacement at all.
     */
    @SerialName("double_space") val doubleSpace: String = ". ",
    /**
     * A suffix the decoder adds when a word is followed by its letters typed as a word of their own ("puppy s"
     * -> "puppy's"; the lexicon keeps possessives only when frequent). English "'s"; empty (the default) for
     * languages without one, so a pack that does not set it never gets English possessives.
     */
    @SerialName("possessive_suffix") val possessiveSuffix: String = "",
    /**
     * Words written with a capital mid-sentence that are not names (English "I", also as the start of "I'm",
     * "I'll"): the look-back (hindsight) reconsiders them, while other capitalised words are left alone as names.
     */
    @SerialName("always_capitalised") val alwaysCapitalised: List<String> = emptyList(),
) {
    /** The closing quotes of [quotes] (right double and right single: German "“‘", French "»›"). */
    val closingQuotes: String
        get() = quotes.takeIf { it.length == 5 }?.let { "${it[1]}${it[3]}" } ?: ""

    /** Whether [word] is one of [alwaysCapitalised] or starts with one and an apostrophe ("I'm"). */
    fun isAlwaysCapitalised(word: String): Boolean =
        alwaysCapitalised.any { word == it || word.startsWith("$it'") || word.startsWith("$it’") }

    /** [possessiveSuffix]'s letters: the lone word that stands for it ("s"); null when there is none. */
    @kotlinx.serialization.Transient // worked out once: the decoder checks it for every word
    val possessiveLetters: String? = possessiveSuffix.filter { it.isLetter() }.ifEmpty { null }

    /** [quotes] as SmartPunctuation takes them (the default set when the rule is malformed). */
    val smartQuotes: io.github.sillypuppyy.dogboard.feel.SmartPunctuation.Quotes
        get() = (quotes.takeIf { it.length == 5 } ?: "“”‘’’").let {
            io.github.sillypuppyy.dogboard.feel.SmartPunctuation.Quotes(it[0], it[1], it[2], it[3], it[4])
        }
    companion object {
        /** en-US (assets/dogboard/packs/en-US/rules.json). */
        val ENGLISH = LanguageRules(
            shortWords = listOf(listOf("u", "you"), listOf("o", "oh")),
            learnedTypoOverrides = linkedMapOf("i" to listOf("o", "O"), "I" to listOf("O", "o"), "can" to listOf("can't"), "and" to listOf("abs")),
            sentenceEnd = ".!?",
            possessiveSuffix = "'s",
            alwaysCapitalised = listOf("I"),
        )

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): LanguageRules = json.decodeFromString(text)
    }
}

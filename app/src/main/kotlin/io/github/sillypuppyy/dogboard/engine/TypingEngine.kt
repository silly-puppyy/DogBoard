package io.github.sillypuppyy.dogboard.engine

import java.io.BufferedReader

/**
 * The app-wide typing engine: one lexicon, bigram model, decoder and autocorrect, shared by every
 * input controller. Loads in the background; until then, nothing is corrected or suggested.
 */
object TypingEngine {
    @Volatile
    private var loaded: Loaded? = null

    private class Loaded(val lexicon: Lexicon, val bigrams: Bigrams?) {
        val decoder = Decoder(lexicon, bigrams = bigrams)
        val autocorrect = Autocorrect(lexicon)
    }

    val isReady: Boolean get() = loaded != null

    @Synchronized
    fun load(openLexicon: () -> BufferedReader, openBigrams: (() -> BufferedReader)? = null) {
        if (loaded != null) return
        val lexicon = openLexicon().use { Lexicon.parse(it) }
        val bigrams = openBigrams?.let { open -> open().use { Bigrams.parse(it, lexicon) } }
        loaded = Loaded(lexicon, bigrams)
    }

    private fun Loaded.contextId(previous: String?): Int =
        previous?.let { bigrams?.contextId(it) } ?: -1

    /**
     * Decoded words for [word]'s taps, best first; empty if not ready or not decodable.
     * [previous] is the word before it ([previousContext]), or null.
     */
    fun candidates(word: CurrentWord, previous: String?, maxResults: Int = 8): List<Candidate> {
        val engine = loaded ?: return emptyList()
        val taps = word.tapsOrNull() ?: return emptyList()
        return engine.decoder.decode(taps, word.keys, word.params!!, maxResults, previous = engine.contextId(previous))
    }

    /** What [word] should become when it is finished, or null to keep it as typed. */
    fun correction(word: CurrentWord, candidates: List<Candidate>): String? {
        val engine = loaded ?: return null
        if (candidates.isEmpty()) return null
        val literalCost = word.tapsOrNull()?.let { engine.decoder.literalCost(it, word.text, word.keys, word.params!!) }
        return engine.autocorrect.correction(word.text, candidates, literalCost)
    }

    /** The [n] most likely next words after [previous] (stored casing), for the empty strip. */
    fun predictions(previous: String?, n: Int = SuggestionStrip.SLOTS): List<String> {
        val engine = loaded ?: return emptyList()
        val bigrams = engine.bigrams ?: return emptyList()
        return bigrams.followers(engine.contextId(previous), n).map { (id, _) -> engine.lexicon.node(id).word!! }
    }

    fun isKnownWord(word: String): Boolean = loaded?.lexicon?.contains(word) == true
}

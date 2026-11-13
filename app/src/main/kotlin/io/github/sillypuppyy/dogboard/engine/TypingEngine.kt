package io.github.sillypuppyy.dogboard.engine

import java.io.BufferedReader

/**
 * The app-wide typing engine: one lexicon, decoder and autocorrect, shared by every input
 * controller. Loads in the background; until then, nothing is corrected or suggested.
 */
object TypingEngine {
    @Volatile
    private var loaded: Loaded? = null

    private class Loaded(val lexicon: Lexicon) {
        val decoder = Decoder(lexicon)
        val autocorrect = Autocorrect(lexicon)
    }

    val isReady: Boolean get() = loaded != null

    @Synchronized
    fun load(open: () -> BufferedReader) {
        if (loaded != null) return
        loaded = Loaded(open().use { Lexicon.parse(it) })
    }

    /** Decoded words for [word]'s taps, best first; empty if not ready or not decodable. */
    fun candidates(word: CurrentWord, maxResults: Int = 8): List<Candidate> {
        val engine = loaded ?: return emptyList()
        val taps = word.tapsOrNull() ?: return emptyList()
        return engine.decoder.decode(taps, word.keys, word.params!!, maxResults)
    }

    /** What [word] should become when it is finished, or null to keep it as typed. */
    fun correction(word: CurrentWord, candidates: List<Candidate> = candidates(word)): String? {
        val engine = loaded ?: return null
        if (candidates.isEmpty()) return null
        val literalCost = word.tapsOrNull()?.let { engine.decoder.literalCost(it, word.text, word.keys, word.params!!) }
        return engine.autocorrect.correction(word.text, candidates, literalCost)
    }

    fun isKnownWord(word: String): Boolean = loaded?.lexicon?.contains(word) == true
}

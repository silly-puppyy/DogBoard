package io.github.sillypuppyy.dogboard.engine

import java.io.BufferedReader
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * The app-wide typing engine: one lexicon, bigram model, decoder and autocorrect, shared by every
 * input controller. Loads in the background; until then, nothing is corrected or suggested.
 * Words the user teaches it are added to the lexicon under a write lock; lookups take the read lock.
 */
object TypingEngine {
    @Volatile
    private var loaded: Loaded? = null
    private val lock = ReentrantReadWriteLock()
    private var userWordsFile: File? = null

    private class Loaded(val lexicon: Lexicon, val bigrams: Bigrams?) {
        val decoder = Decoder(lexicon, bigrams = bigrams)
        val autocorrect = Autocorrect(lexicon)
        /** Learned words get the probability of a moderately common word. */
        val userWordLogProb = lexicon.node(minOf(USER_WORD_RANK, lexicon.size - 1)).logProb
    }

    private const val USER_WORD_RANK = 3000

    val isReady: Boolean get() = loaded != null

    /**
     * Loads the word data. [userWords] is a plain text file, one learned word per line; it is
     * read now and appended to by [learn].
     */
    @Synchronized
    fun load(openLexicon: () -> BufferedReader, openBigrams: (() -> BufferedReader)? = null, userWords: File? = null) {
        if (loaded != null) return
        val lexicon = openLexicon().use { Lexicon.parse(it) }
        val bigrams = openBigrams?.let { open -> open().use { Bigrams.parse(it, lexicon) } }
        val engine = Loaded(lexicon, bigrams)
        userWords?.takeIf { it.exists() }?.forEachLine { line ->
            if (line.isNotBlank()) lexicon.addWord(line.trim(), engine.userWordLogProb)
        }
        userWordsFile = userWords
        loaded = engine
    }

    /**
     * Teaches the engine [word] (the user kept it against autocorrect): it is never corrected
     * again and can be suggested. Saved to the user-words file.
     */
    fun learn(word: String) {
        val engine = loaded ?: return
        if (word.isBlank() || word.length > 48 || !word.all { isWordChar(it) }) return
        val isNew = lock.write {
            val wasKnown = engine.lexicon.contains(word)
            engine.lexicon.addWord(word, engine.userWordLogProb)
            !wasKnown
        }
        if (isNew) {
            userWordsFile?.let { file ->
                runCatching {
                    file.parentFile?.mkdirs()
                    file.appendText(word + "\n")
                }
            }
        }
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
        return lock.read {
            engine.decoder.decode(taps, word.keys, word.params!!, maxResults, previous = engine.contextId(previous))
        }
    }

    /** What [word] should become when it is finished, or null to keep it as typed. */
    fun correction(word: CurrentWord, candidates: List<Candidate>): String? {
        val engine = loaded ?: return null
        if (candidates.isEmpty()) return null
        val literalCost = word.tapsOrNull()?.let { engine.decoder.literalCost(it, word.text, word.keys, word.params!!) }
        return lock.read { engine.autocorrect.correction(word.text, candidates, literalCost) }
    }

    /** The [n] most likely next words after [previous] (stored casing), for the empty strip. */
    fun predictions(previous: String?, n: Int = SuggestionStrip.SLOTS): List<String> {
        val engine = loaded ?: return emptyList()
        val bigrams = engine.bigrams ?: return emptyList()
        return lock.read {
            bigrams.followers(engine.contextId(previous), n).map { (id, _) -> engine.lexicon.node(id).word!! }
        }
    }

    /**
     * Log prior for each letter key given the word typed so far ([prefix]), for the touch model:
     * likely next letters get slightly larger keys. Null when not loaded or no known word starts
     * with [prefix]. Weight and floor from TouchPriorSweepTest.
     */
    fun keyPrior(prefix: CharSequence): ((Char) -> Double)? {
        val engine = loaded ?: return null
        val logProbs = lock.read { engine.lexicon.nextLetterLogProbs(prefix) } ?: return null
        return { c -> KEY_PRIOR_WEIGHT * maxOf(KEY_PRIOR_FLOOR, logProbs[c.lowercaseChar()] ?: KEY_PRIOR_FLOOR) }
    }

    private const val KEY_PRIOR_WEIGHT = 0.10
    private const val KEY_PRIOR_FLOOR = -6.0

    fun isKnownWord(word: String): Boolean = lock.read { loaded?.lexicon?.contains(word) == true }

    /** Forgets everything loaded (tests only). */
    internal fun unloadForTest() {
        loaded = null
        userWordsFile = null
    }
}

package io.github.sillypuppyy.dogboard.engine

import java.io.BufferedReader
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.exp
import kotlin.math.ln

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
    private var blockedWordsFile: File? = null
    private var rejectionsFile: File? = null
    /** (typed, correction) pairs the user undid; never auto-applied again (iOS rejections). */
    private val rejections = HashSet<Pair<String, String>>()
    /** How often each unknown word was typed and kept in this session. */
    private val keptUnknown = HashMap<String, Int>()

    /** Words the user removed from suggestions (lowercase); never suggested or corrected to. */
    private val blocked = HashSet<String>()

    private class Loaded(val lexicon: Lexicon, val bigrams: Bigrams?) {
        val decoder = Decoder(
            lexicon,
            Decoder.Config(languagePower = if (bigrams != null) Decoder.LANGUAGE_POWER_CONTEXT else Decoder.LANGUAGE_POWER_UNIGRAM),
            bigrams,
        )
        val autocorrect = Autocorrect(lexicon)
        /** Learned words get the probability of a moderately common word. */
        val userWordLogProb = lexicon.logProb(lexicon.nodeOfWord(minOf(USER_WORD_RANK, lexicon.size - 1)))
    }

    private const val USER_WORD_RANK = 3000

    val isReady: Boolean get() = loaded != null

    /**
     * Loads the word data. [userWords] is a plain text file, one learned word per line; it is
     * read now and appended to by [learn].
     */
    @Synchronized
    fun load(
        openLexicon: () -> BufferedReader,
        openBigrams: (() -> BufferedReader)? = null,
        userWords: File? = null,
        blockedWords: File? = null,
        rejectedCorrections: File? = null,
        openAbbreviations: (() -> BufferedReader)? = null,
    ) {
        if (loaded != null) return
        abbreviations = openAbbreviations?.let { open ->
            open().useLines { lines -> lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList() }
        }.orEmpty().groupBy { it.removeSuffix(".").lowercase() }
        val lexicon = openLexicon().use { Lexicon.parse(it) }
        val bigrams = openBigrams?.let { open -> open().use { Bigrams.parse(it, lexicon) } }
        val engine = Loaded(lexicon, bigrams)
        userWords?.takeIf { it.exists() }?.forEachLine { line ->
            if (line.isNotBlank()) lexicon.addWord(line.trim(), engine.userWordLogProb)
        }
        userWordsFile = userWords
        blockedWordsFile = blockedWords
        rejectionsFile = rejectedCorrections
        synchronized(rejections) {
            rejections.clear()
            rejectedCorrections?.takeIf { it.exists() }?.forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) rejections.add(line.substring(0, tab) to line.substring(tab + 1))
            }
        }
        lock.write {
            blocked.clear()
            blockedWords?.takeIf { it.exists() }?.forEachLine { if (it.isNotBlank()) blocked.add(it.trim().lowercase()) }
        }
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
            engine.decoder.decode(
                taps, word.keys, word.params!!, maxResults + blocked.size.coerceAtMost(4),
                previous = engine.contextId(previous), typed = word.text,
            )
                .filter { !isBlocked(it.word) }
                .take(maxResults)
        }
    }

    /**
     * What [word] should become when it is finished, or null to keep it as typed.
     * [previousCorrection] is what this returned one keystroke earlier (iOS hysteresis).
     */
    fun correction(word: CurrentWord, candidates: List<Candidate>, previousCorrection: String? = null): String? {
        val engine = loaded ?: return null
        if (candidates.isEmpty()) return null
        val result = engine.autocorrect.correction(word.text, candidates, previousCorrection) ?: return null
        val rejected = synchronized(rejections) { (word.text.lowercase() to result.lowercase()) in rejections }
        return if (rejected) null else result
    }

    /** The user undid the correction [typed] -> [correction]: it is not auto-applied again. */
    fun reject(typed: String, correction: String) {
        val pair = typed.lowercase() to correction.lowercase()
        val isNew = synchronized(rejections) { rejections.add(pair) }
        if (!isNew) return
        runCatching {
            rejectionsFile?.let { file ->
                file.parentFile?.mkdirs()
                file.appendText("${pair.first}\t${pair.second}\n")
            }
        }
    }

    /** The [n] most likely next words after [previous] (stored casing), for the empty strip. */
    fun predictions(previous: String?, n: Int = SuggestionStrip.SLOTS): List<String> {
        val engine = loaded ?: return emptyList()
        val bigrams = engine.bigrams ?: return emptyList()
        return lock.read {
            bigrams.followers(engine.contextId(previous), n + blocked.size.coerceAtMost(4))
                .map { (id, _) -> engine.lexicon.word(engine.lexicon.nodeOfWord(id))!! }
                .filter { !isBlocked(it) }
                .take(n)
        }
    }

    /**
     * Log prior for each letter key given the word typed so far ([prefix]), for the touch model:
     * likely next letters get slightly larger keys. Null when not loaded or no known word starts
     * with [prefix]. Weight and floor from TouchPriorSweepTest.
     */
    fun keyPrior(prefix: CharSequence, previous: String? = null): ((Char) -> Double)? {
        val engine = loaded ?: return null
        return lock.read { keyPrior(engine.lexicon, engine.bigrams, prefix, engine.contextId(previous)) }
    }

    /**
     * Key prior from the lexicon, mixed with the letters of words seen after the previous word
     * ([BIGRAM_PRIOR_SHARE]) when there are any. Shared with the evals.
     */
    internal fun keyPrior(lexicon: Lexicon, bigrams: Bigrams?, prefix: CharSequence, previous: Int): ((Char) -> Double)? {
        val unigram = lexicon.nextLetterLogProbs(prefix) ?: return null
        val context = bigrams?.nextLetterProbs(previous, prefix.toString())
        val logProbs = if (context == null) unigram else unigram.mapValues { (c, lp) ->
            ln(BIGRAM_PRIOR_SHARE * (context[c] ?: 0.0) + (1 - BIGRAM_PRIOR_SHARE) * exp(lp))
        }
        return { c -> KEY_PRIOR_WEIGHT * maxOf(KEY_PRIOR_FLOOR, logProbs[c.lowercaseChar()] ?: KEY_PRIOR_FLOOR) }
    }

    private const val BIGRAM_PRIOR_SHARE = 0.6

    private const val KEY_PRIOR_WEIGHT = 0.10
    private const val KEY_PRIOR_FLOOR = -6.0

    /**
     * The user removed [word] from the suggestions: it is never suggested or corrected to again,
     * and forgotten if it was learned. Saved to the blocked-words file.
     */
    fun block(word: String) {
        val key = word.lowercase()
        val isNew = lock.write { blocked.add(key) }
        if (!isNew) return
        runCatching {
            blockedWordsFile?.let { file ->
                file.parentFile?.mkdirs()
                file.appendText(key + "\n")
            }
            userWordsFile?.takeIf { it.exists() }?.let { file ->
                val kept = file.readLines().filter { it.isNotBlank() && it.trim().lowercase() != key }
                file.writeText(kept.joinToString("") { it + "\n" })
            }
        }
    }

    /**
     * [word] was finished as typed (not corrected). An unknown word kept [LEARN_AFTER_KEPT] times
     * in a session is learned.
     */
    fun noteKept(word: String) {
        val engine = loaded ?: return
        if (lock.read { engine.lexicon.contains(word) }) return
        val n = synchronized(keptUnknown) { ((keptUnknown[word] ?: 0) + 1).also { keptUnknown[word] = it } }
        if (n >= LEARN_AFTER_KEPT) learn(word)
    }

    private const val LEARN_AFTER_KEPT = 3

    /** Words that keep their period ("e.g.", "U.S."), by lowercase form without the final period. */
    @Volatile
    private var abbreviations: Map<String, List<String>> = emptyMap()

    /**
     * Whether the keyboard should turn shift on for the next letter after [before] in a field of
     * [type] (iOS auto-shift, [AutoShift]); the lexicon and the abbreviations say which words keep
     * their period. Before the lexicon is loaded every period ends a sentence.
     */
    fun shouldAutoShift(before: CharSequence, type: AutoShift.Type): Boolean {
        val engine = loaded
        val forms = AutoShift.WordForms { word ->
            val stored = engine?.let { e -> lock.read { e.lexicon.storedForm(word)?.takeIf { e.lexicon.contains(word) } } }
            listOfNotNull(stored) + abbreviations[word.lowercase()].orEmpty()
        }
        return AutoShift.shouldShift(before, type, forms)
    }

    private fun isBlocked(word: String): Boolean = word.lowercase() in blocked

    fun isKnownWord(word: String): Boolean = lock.read { loaded?.lexicon?.contains(word) == true }

    /** Forgets everything loaded (tests only). */
    internal fun unloadForTest() {
        loaded = null
        userWordsFile = null
        blockedWordsFile = null
        blocked.clear()
        rejectionsFile = null
        synchronized(rejections) { rejections.clear() }
        synchronized(keptUnknown) { keptUnknown.clear() }
        abbreviations = emptyMap()
    }
}

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

    /**
     * Whether words take the dictionary's capitals when typed without any ("john" → "John",
     * "i" → "I"); off keeps them as typed. Sentence starts are a separate setting.
     */
    @Volatile
    var dictionaryCapitals: () -> Boolean = { true }
    private val lock = ReentrantReadWriteLock()
    private var userWordsFile: File? = null
    private var blockedWordsFile: File? = null
    /** The words and pairs the user accepted, mixed into the language model ([PersonalModel]). */
    @Volatile
    private var personal = PersonalModel()

    /** Emoji suggestions and the user's emoji use ([EmojiSuggest], [EmojiUsage]). */
    @Volatile
    private var emoji: EmojiSuggest? = null
    @Volatile
    private var emojiUsage = EmojiUsage()

    /** The user's text replacements ([TextReplacements]). */
    @Volatile
    private var replacements = TextReplacements()

    /** Undone and deleted autocorrections, iOS rules ([Rejections]). */
    @Volatile
    private var rejections = Rejections()

    /** Words the user removed from suggestions (lowercase); never suggested or corrected to. */
    private val blocked = HashSet<String>()

    /**
     * Words never offered (vulgar or sensitive, en.nosuggest.txt), as iOS never introduces a
     * not-suggestible word through correction, completion or prediction; typing one still works.
     */
    private var noSuggest: Set<String> = emptySet()

    /** The "block possibly offensive" setting: off lets never-suggest words be offered again. */
    @Volatile
    var blockNotSuggestible = true

    /** Words the user taught the keyboard (not in the shipped lexicon), lowercase. */
    private val learnedWords = HashSet<String>()

    private class Loaded(val lexicon: Lexicon, val bigrams: Bigrams?) {
        val decoder = Decoder(
            lexicon,
            Decoder.Config(languagePower = if (bigrams != null) Decoder.LANGUAGE_POWER_CONTEXT else Decoder.LANGUAGE_POWER_UNIGRAM),
            bigrams,
        )
        val autocorrect = Autocorrect(lexicon)

        /** A second decoder for looking back at the previous word, so the live one keeps its search. */
        val retroDecoder = Decoder(
            lexicon,
            Decoder.Config(languagePower = if (bigrams != null) Decoder.LANGUAGE_POWER_CONTEXT else Decoder.LANGUAGE_POWER_UNIGRAM),
            bigrams,
        )
        /** Learned words get the probability of a moderately common word. */
        val userWordLogProb = lexicon.logProb(lexicon.nodeOfWord(minOf(USER_WORD_RANK, lexicon.size - 1)))

        /** A word learned by being accepted once starts rare; its use in the personal model lifts it. */
        val acceptedWordLogProb = lexicon.logProb(lexicon.nodeOfWord(minOf(ACCEPTED_WORD_RANK, lexicon.size - 1)))
    }

    private const val USER_WORD_RANK = 3000
    private const val ACCEPTED_WORD_RANK = 30000

    /** iOS NonRetrocorrectionBoostRatio: changing the previous word must win by 1.65x. */
    private val RETRO_RATIO = kotlin.math.ln(1.65)

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
        personalModel: File? = null,
        textReplacements: File? = null,
        openEmojiIndex: (() -> BufferedReader)? = null,
        openEmojiTones: (() -> BufferedReader)? = null,
        emojiUsageFile: File? = null,
        openNoSuggest: (() -> BufferedReader)? = null,
        snapshotDir: File? = null,
        snapshotKey: String = "",
        addedWords: File? = null,
    ) {
        if (loaded != null) return
        addedWordsFile = addedWords
        reload = {
            load(
                openLexicon, openBigrams, userWords, blockedWords, rejectedCorrections, openAbbreviations, personalModel,
                textReplacements, openEmojiIndex, openEmojiTones, emojiUsageFile, openNoSuggest, snapshotDir, snapshotKey, addedWords,
            )
        }
        noSuggest = openNoSuggest?.let { open ->
            open().useLines { lines -> lines.map { it.trim().lowercase() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toHashSet() }
        }.orEmpty()
        abbreviations = openAbbreviations?.let { open ->
            open().useLines { lines -> lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList() }
        }.orEmpty().groupBy { it.removeSuffix(".").lowercase() }
        // the parsed word data is kept as a binary snapshot (per app version), so later starts
        // copy arrays instead of parsing text
        fun snapshot(name: String) = snapshotDir?.let { File(it, "$name-$snapshotKey.bin") }
        val lexiconSnapshot = snapshot("lexicon")
        val bigramSnapshot = snapshot("bigrams")
        val cachedLexicon = lexiconSnapshot?.takeIf { it.isFile }?.let { runCatching { Lexicon.readSnapshot(it.readBytes()) }.getOrNull() }
        val lexicon = cachedLexicon ?: openLexicon().use { Lexicon.parse(it) }.also { lex ->
            snapshotDir?.let { dir -> dir.listFiles()?.forEach { if (!it.name.endsWith("-$snapshotKey.bin")) it.delete() } }
            lexiconSnapshot?.let { f -> writeAtomically(f) { lex.writeSnapshot(it) } }
        }
        val bigrams = openBigrams?.let { open ->
            bigramSnapshot?.takeIf { cachedLexicon != null && it.isFile }
                ?.let { runCatching { Bigrams.readSnapshot(it.readBytes(), lexicon) }.getOrNull() }
                ?: open().use { Bigrams.parse(it, lexicon) }.also { b -> bigramSnapshot?.let { f -> writeAtomically(f) { b.writeSnapshot(it) } } }
        }
        val engine = Loaded(lexicon, bigrams)
        userWords?.takeIf { it.exists() }?.forEachLine { line ->
            if (line.isNotBlank()) {
                if (!lexicon.contains(line.trim())) learnedWords.add(line.trim().lowercase())
                lexicon.addWord(line.trim(), engine.userWordLogProb)
            }
        }
        userWordsFile = userWords
        blockedWordsFile = blockedWords
        rejections = Rejections(rejectedCorrections)
        personal = PersonalModel(personalModel)
        replacements = TextReplacements(textReplacements)
        emojiUsage = EmojiUsage(emojiUsageFile)
        val tones = openEmojiTones?.let { open -> open().use { EmojiSuggest.parseTones(it) } }.orEmpty()
        emoji = openEmojiIndex?.let { open -> EmojiSuggest(open().use { EmojiSuggest.parse(it) }, emojiUsage, tones) }
        // words the user accepted that the lexicon lacks are decodable again
        for (w in personal.knownWords()) if (!lexicon.contains(w)) {
            learnedWords.add(w.lowercase())
            lexicon.addWord(w, engine.acceptedWordLogProb)
        }
        lock.write {
            blocked.clear()
            blockedWords?.takeIf { it.exists() }?.forEachLine { if (it.isNotBlank()) blocked.add(it.trim().lowercase()) }
        }
        loaded = engine
    }

    /**
     * Names from the user's contacts (opt-in, ContactNames), known as words at the probability of
     * a moderately common word. Kept in memory only; a different set reloads the word data so
     * removed names are forgotten.
     */
    fun setContactNames(names: Collection<String>) {
        val engine = loaded ?: return
        val fresh = names.toSet()
        if (fresh == contactNames) return
        if (!contactNames.all { it in fresh }) {
            contactNames = emptySet()
            loaded = null
            reload?.invoke()
            return setContactNames(fresh)
        }
        lock.write { for (n in fresh) if (!engine.lexicon.contains(n)) engine.lexicon.addWord(n, engine.userWordLogProb) }
        contactNames = fresh
    }

    @Volatile
    private var contactNames: Set<String> = emptySet()

    private fun writeAtomically(file: File, write: (java.io.OutputStream) -> Unit) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use(write)
            if (!tmp.renameTo(file)) tmp.delete()
        }
    }

    /** How much the user uses [emoji], by the iOS recents score (EmojiUsage), for ordering recents. */
    fun emojiScore(emoji: String): Double = emojiUsage.score(emoji)

    /** Words the user added in settings (a subset of the learned words, listed apart). */
    private var addedWordsFile: File? = null

    /** The settings screen's word lists (design SetDict: Learned, Added, Blocked, Undone). */
    data class DictionaryLists(
        /** Learned from typing: (word as stored, why). */
        val learned: List<Pair<String, String>>,
        val added: List<String>,
        val blocked: List<String>,
        /** Undone corrections: (typed, correction, times). */
        val undone: List<Triple<String, String, Int>>,
    )

    fun dictionaryLists(): DictionaryLists {
        val engine = loaded ?: return DictionaryLists(emptyList(), emptyList(), emptyList(), emptyList())
        val added = readLines(addedWordsFile)
        val addedKeys = added.map { it.lowercase() }.toSet()
        val learned = lock.read { learnedWords.toList() }.filter { it !in addedKeys }.sorted().map { w ->
            val shown = lock.read { engine.lexicon.storedForm(w) } ?: w
            val uses = personal.uses(w)
            shown to if (uses > 0) "You used it $uses ${if (uses == 1) "time" else "times"}" else "You kept it as typed"
        }
        return DictionaryLists(learned, added, lock.read { blocked.sorted() }, rejections.undone())
    }

    /** The user added [word] in settings: learned now, and listed under Added. */
    fun addWord(word: String) {
        val w = word.trim()
        if (w.isEmpty() || !w.all { isWordChar(it) }) return
        learn(w)
        if (readLines(addedWordsFile).none { it.equals(w, ignoreCase = true) }) {
            addedWordsFile?.let { f -> runCatching { f.parentFile?.mkdirs(); f.appendText(w + "\n") } }
        }
    }

    /**
     * The user removed [word] in settings: it goes back to being corrected (out of the learned
     * and added lists and the personal model; the word data is loaded again, as learned words
     * live in the lexicon).
     */
    @Synchronized
    fun forgetWord(word: String) {
        loaded ?: return
        val key = word.lowercase()
        for (f in listOfNotNull(userWordsFile, addedWordsFile)) {
            runCatching { if (f.exists()) f.writeText(f.readLines().filter { it.isNotBlank() && it.trim().lowercase() != key }.joinToString("") { it + "\n" }) }
        }
        personal.remove(word)
        lock.write { learnedWords.remove(key) }
        lastMix = null
        loaded = null
        reload?.invoke()
        val names = contactNames
        contactNames = emptySet()
        setContactNames(names)
    }

    /** The user unblocked [word] in settings: it can be suggested again. */
    fun unblock(word: String) {
        val key = word.lowercase()
        if (!lock.write { blocked.remove(key) }) return
        blockedWordsFile?.let { f ->
            runCatching { if (f.exists()) f.writeText(f.readLines().filter { it.isNotBlank() && it.trim().lowercase() != key }.joinToString("") { it + "\n" }) }
        }
    }

    /** The text replacements, (shortcut, phrase), for the settings screen. */
    fun textReplacements(): List<Pair<String, String>> = replacements.all()

    /** Adds a text replacement from settings. */
    fun addTextReplacement(shortcut: String, phrase: String) = replacements.put(shortcut, phrase)

    /** The user took back an undo in settings: [typed] -> [correction] may be made again. */
    fun restoreCorrection(typed: String, correction: String) = rejections.restore(typed, correction)

    private fun readLines(f: File?): List<String> =
        f?.takeIf { it.exists() }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    /** Loads again with what [load] was given (after a dictionary reset). */
    private var reload: (() -> Unit)? = null

    /**
     * iOS "Reset Keyboard Dictionary": forgets every learned word, the personal model and the
     * rejected corrections; text replacements, blocked words and emoji use stay. The word data is
     * loaded again, as learned words live in the lexicon.
     */
    @Synchronized
    fun resetDictionary() {
        loaded ?: return
        personal.clear()
        rejections.clear()
        // iOS "Reset Keyboard Dictionary": every word the keyboard was taught goes, added ones too
        for (f in listOfNotNull(userWordsFile, addedWordsFile)) runCatching { f.delete() }
        lock.write { learnedWords.clear() }
        lastMix = null
        loaded = null
        reload?.invoke()
        val names = contactNames // contact names aren't learned words: they come back
        contactNames = emptySet()
        setContactNames(names)
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
            if (!wasKnown) learnedWords.add(word.lowercase())
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
                taps, word.keys, word.params!!, maxResults + blocked.size.coerceAtMost(4) + 2,
                previous = engine.contextId(previous), typed = word.text,
                personal = personalMix(engine, previous),
            )
                .filter { !isBlocked(it.word) && (it.isTypedString || it.word.equals(word.text, ignoreCase = true) || !isNotSuggestible(it.word)) }
                .take(maxResults)
        }
    }

    /**
     * What [word] should become when it is finished, or null to keep it as typed.
     * [previousCorrection] is what this returned one keystroke earlier (iOS hysteresis).
     */
    /**
     * iOS retrocorrection: when [current] is finished, the word before it ([previous], committed as
     * [previousCommitted] after [beforeContext]) may change too, if the two read much better
     * together. Each of the previous word's top candidates is scored with the best reading of the
     * current word after it; a different previous word must beat the committed one by
     * [RETRO_RATIO] (iOS NonRetrocorrectionBoostRatio 1.65). Returns the new previous word, cased
     * as it was typed, or null.
     */
    fun retrocorrection(
        previous: CurrentWord,
        previousCommitted: String,
        beforeContext: String?,
        current: CurrentWord,
        curly: Boolean = false,
    ): String? {
        val engine = loaded ?: return null
        val bigrams = engine.bigrams ?: return null // the look back needs a context model
        val prevTaps = previous.tapsOrNull() ?: return null
        val curTaps = current.tapsOrNull() ?: return null
        if (previousCommitted.any { it.isDigit() } || isBlocked(previousCommitted)) return null
        if (rejections.isBlocked(previous.text, previousCommitted)) return null
        return lock.read {
            val d = engine.retroDecoder
            val prevCandidates = d.decode(
                prevTaps, previous.keys, previous.params!!, 4, includeCompletions = false,
                previous = engine.contextId(beforeContext), typed = previous.text,
            ).filter { it.words == 1 && !isBlocked(it.word) && !isNotSuggestible(it.word) }
            fun joint(prevWord: String, prevScore: Double): Double {
                val cur = d.decode(curTaps, current.keys, current.params!!, 1, includeCompletions = false,
                    previous = bigrams.contextId(prevWord.lowercase()), typed = current.text)
                return prevScore + (cur.firstOrNull()?.score ?: return Double.NEGATIVE_INFINITY)
            }
            val committed = prevCandidates.firstOrNull { it.word.equals(previousCommitted, ignoreCase = true) }
                ?: return@read null // the committed word isn't even a candidate: nothing to compare with
            val base = joint(committed.word, committed.score)
            val best = prevCandidates
                .filter { !it.word.equals(previousCommitted, ignoreCase = true) && !it.isTypedString }
                .map { it to joint(it.word, it.score) }
                .maxByOrNull { it.second } ?: return@read null
            if (best.second - base < RETRO_RATIO) return@read null
            caseFor(previous, curly)(best.first.word)?.takeIf { !rejections.isBlocked(previous.text, it) }
        }
    }

    fun correction(
        word: CurrentWord,
        candidates: List<Candidate>,
        previousCorrection: String? = null,
        curly: Boolean = false,
        level: AutocorrectLevel = AutocorrectLevel.STANDARD,
        /** The word is being finished (not a suggestion-bar preview): soft blocks count. */
        commit: Boolean = false,
    ): String? {
        val engine = loaded ?: return null
        // iOS: text replacements are tried before the engine, so a shortcut beats autocorrection
        replacementFor(word.text, word.flags, word.caseContext)?.let { phrase ->
            if (!rejections.isBlocked(word.text, phrase, consume = commit)) return phrase
        }
        if (candidates.isEmpty()) return null
        val result = engine.autocorrect.correction(word.text, withoutLearnedTypo(word.text, candidates), previousCorrection, caseFor(word, curly), level.level)
            ?: return null
        return if (rejections.isBlocked(word.text, result, consume = commit)) null else result
    }

    /**
     * The case rules for [word] as it was typed (its shift flags), with the lexicon saying which
     * words exist capitalised: maps a candidate to its cased form, or null if not acceptable.
     */
    fun caseFor(word: CurrentWord, curly: Boolean = false): (String) -> String? {
        val engine = loaded
        val casing = object : CaseAdapt.Casing {
            override fun hasCapitalisedForm(word: String): Boolean =
                engine != null && lock.read { engine.lexicon.formsOf(word) }.any { it.firstOrNull()?.isUpperCase() == true }
        }
        val typed = word.text
        val flags = word.flags.takeIf { it.size == typed.length } ?: typed.map { if (it.isUpperCase()) CaseAdapt.SHIFTED else 0 }
        // with smart quotes on, apostrophes are curly, as the user's own are (iOS treats ' and ’ alike)
        val keepLowercase = !dictionaryCapitals() && flags.none { it and CaseAdapt.SHIFTED != 0 }
        return { candidate ->
            val c = if (keepLowercase) candidate.lowercase() else candidate
            CaseAdapt.apply(c, typed, flags, word.caseContext, casing)?.let { if (curly) it.replace('\'', '’') else it }
        }
    }

    /** The user undid [typed] -> [correction] (or kept their word while it was offered): a hard rejection. */
    fun reject(typed: String, correction: String) = rejections.recordHard(typed, correction)

    /** Autocorrection just turned [typed] into [correction]. */
    fun noteCorrected(typed: String, correction: String) = rejections.noteCorrected(typed, correction)

    /** [typed] -> [correction] was made and the user typed on: earlier undos of it are forgiven. */
    fun correctionKept(typed: String, correction: String) = rejections.recordKept(typed, correction)

    /** The user deleted the whole word [word] (a soft rejection if it was just autocorrected). */
    fun noteDeleted(word: String) = rejections.noteDeleted(word)

    /** The [n] most likely next words after [previous] (stored casing), for the empty strip. */
    fun predictions(previous: String?, n: Int = SuggestionStrip.SLOTS): List<String> {
        val engine = loaded ?: return emptyList()
        val bigrams = engine.bigrams ?: return emptyList()
        return lock.read {
            bigrams.followers(engine.contextId(previous), n + blocked.size.coerceAtMost(4) + 4)
                .map { (id, _) -> engine.lexicon.word(engine.lexicon.nodeOfWord(id))!! }
                .filter { !isBlocked(it) && !isNotSuggestible(it) }
                .take(n)
                .map { if (dictionaryCapitals()) it else it.lowercase() }
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
        personal.forget(word)
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


    /** The emoji to offer for the words at the end of [textBefore] in [app], or null ([mode] decides when). */
    fun emojiFor(textBefore: CharSequence, app: String?, mode: EmojiSuggestionMode, tone: Int = 0): String? {
        val e = emoji ?: return null
        when (mode) {
            EmojiSuggestionMode.OFF -> return null
            EmojiSuggestionMode.AFTER_USE -> if (!emojiUsage.hasUsedEmoji(app)) return null
            EmojiSuggestionMode.ALWAYS -> Unit
        }
        return e.suggest(textBefore, 1, tone).firstOrNull()
    }

    /** The user inserted [emoji] in [app] (panel, search or the bar). */
    fun emojiUsed(emoji: String, app: String?) = emojiUsage.record(emoji, app)

    /** The phrase for the shortcut [typed], with the typed capitals ([CaseAdapt]), or null. */
    fun replacementFor(typed: String, flags: List<Int>, context: CaseAdapt.Context): String? {
        val phrase = replacements.phraseFor(typed) ?: return null
        val f = flags.takeIf { it.size == typed.length } ?: CaseAdapt.inferredFlags(typed)
        return CaseAdapt.apply(phrase, typed, f, context, CaseAdapt.NoDictionary) ?: phrase
    }

    /** The decoder's hook for the personal model, or null while it has nothing to add. */
    /** The last mix handed out, reused while nothing it depends on changes (the decoder then continues its search). */
    private var lastMix: Triple<Any?, Int, ((Int, Double) -> Double)?>? = null

    private fun personalMix(engine: Loaded, previous: String?): ((Int, Double) -> Double)? {
        val p = personal
        val version = p.version
        lastMix?.let { (prev, v, mix) -> if (prev == (engine to previous) && v == version) return mix }
        if (p.weight(previous != null) <= 0.0) return null.also { lastMix = Triple(engine to previous, version, null) }
        return makeMix(engine, p, previous).also { lastMix = Triple(engine to previous, version, it) }
    }

    private fun makeMix(engine: Loaded, p: PersonalModel, previous: String?): (Int, Double) -> Double {
        return { node, logProb ->
            val word = engine.lexicon.word(node)
            if (word == null) logProb else ln(p.mix(exp(logProb), previous, word).coerceAtLeast(1e-300))
        }
    }

    /**
     * The user accepted [word] after [previous] (finished and not undone, a correction kept, or a
     * suggestion picked): counted in the personal model; an unknown word is learned (iOS: one
     * accept, strength from use).
     */
    fun accepted(previous: String?, word: String) {
        val engine = loaded ?: return
        if (word.isBlank() || word.length > 48 || !word.all { isWordChar(it) } || !word.any { it.isLetter() }) return
        personal.accept(previous, word)
        val isNew = lock.write {
            val known = engine.lexicon.contains(word)
            if (!known) {
                learnedWords.add(word.lowercase())
                engine.lexicon.addWord(word, engine.acceptedWordLogProb)
            }
            !known
        }
        if (isNew) userWordsFile?.let { f -> runCatching { f.parentFile?.mkdirs(); f.appendText(word + "\n") } }
    }

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
            val stored = engine?.let { e -> lock.read { e.lexicon.formsOf(word) } }.orEmpty()
            stored + abbreviations[word.lowercase()].orEmpty()
        }
        return AutoShift.shouldShift(before, type, forms)
    }

    private fun isBlocked(word: String): Boolean = word.lowercase() in blocked

    /**
     * iOS FilterLearnedTypo: a word learned from a slip ("ofthe", accepted fewer than 2 times)
     * gives way when the same letters split into words ("of the").
     */
    private fun withoutLearnedTypo(typed: String, candidates: List<Candidate>): List<Candidate> {
        val t = typed.lowercase()
        if (lock.read { t !in learnedWords } || personal.uses(t) >= 2) return candidates
        val split = candidates.any { it.words > 1 && it.word.replace(" ", "").equals(typed, ignoreCase = true) }
        return if (split) candidates.filter { !it.word.equals(typed, ignoreCase = true) } else candidates
    }

    /** True if any word of [candidate] (one word, or several when words were split) is never offered. */
    private fun isNotSuggestible(candidate: String): Boolean =
        blockNotSuggestible && noSuggest.isNotEmpty() && candidate.split(' ').any { it.lowercase() in noSuggest }

    fun isKnownWord(word: String): Boolean = lock.read { loaded?.lexicon?.contains(word) == true }

    /** Forgets everything loaded (tests only). */
    internal fun unloadForTest() {
        loaded = null
        userWordsFile = null
        blockedWordsFile = null
        blocked.clear()
        rejections = Rejections()
        personal = PersonalModel()
        replacements = TextReplacements()
        emoji = null
        emojiUsage = EmojiUsage()
        abbreviations = emptyMap()
        noSuggest = emptySet()
        learnedWords.clear()
        lastMix = null
        reload = null
        contactNames = emptySet()
    }
}

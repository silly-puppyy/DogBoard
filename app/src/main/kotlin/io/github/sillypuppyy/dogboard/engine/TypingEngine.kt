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
    /** The last load parsed the word lists (a first start: no snapshot yet), the heap's peak. */
    @Volatile
    var parsedAtLoad: Boolean = false
        private set

    @Volatile
    private var loaded: Loaded? = null

    /** The attached trigram model, re-attached when the word data reloads (reset, contacts). */
    @Volatile
    private var ngramModel: NgramModel? = null

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
    /** Autocorrect that adapts to what the user keeps and undoes (off unless [adaptiveStrength]). */
    private var strength = AutocorrectStrength().also { hookStep(it) }

    /** Hears when the adaptive strength moves to another step (+1 more careful, -1 bolder, 0 back), only while it adapts. */
    @Volatile
    var onStrengthStepChange: ((Int) -> Unit)? = null

    private fun hookStep(s: AutocorrectStrength) {
        s.onStepChange = { step -> if (adaptiveStrength) onStrengthStepChange?.invoke(step) }
    }
    /** Where this user really taps each key (Gboard's spatial personalization); applied when decoding. */
    private var personalOffsets = PersonalOffsets()
    @Volatile var adaptiveStrength = false

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

    /** The loaded language's rules ([LanguageRules]). */
    @Volatile var rules: LanguageRules = LanguageRules.ENGLISH
        private set

    private class Loaded(val lexicon: Lexicon, val bigrams: Bigrams?, val rules: LanguageRules) {
        val decoder = Decoder(
            lexicon,
            Decoder.Config(languagePower = if (bigrams != null) Decoder.LANGUAGE_POWER_CONTEXT else Decoder.LANGUAGE_POWER_UNIGRAM),
            bigrams,
            rules = rules,
        )
        val autocorrect = Autocorrect(lexicon).also { ac ->
            ac.keepCompounds = rules.compounds
            // a typed non-word that is two words run together is split only when the words go together
            if (bigrams != null) ac.pairSupport = Autocorrect.pairSupport(lexicon, bigrams)
        }

        /** A second decoder for looking back at the previous word, so the live one keeps its search. */
        val retroDecoder = Decoder(
            lexicon,
            Decoder.Config(languagePower = if (bigrams != null) Decoder.LANGUAGE_POWER_CONTEXT else Decoder.LANGUAGE_POWER_UNIGRAM),
            bigrams,
            rules = rules,
        )
        /**
         * A third decoder for rejoining a word split by a stray space (decoded in the background, on
         * every keystroke after a tapped space): one-word readings only, so no word breaks (they were
         * the bulk of its work: 40 MB of garbage a word on the phone, the typing stalls).
         */
        val joinDecoder = Decoder(
            lexicon,
            Decoder.Config(languagePower = if (bigrams != null) Decoder.LANGUAGE_POWER_CONTEXT else Decoder.LANGUAGE_POWER_UNIGRAM, splitWords = false),
            bigrams,
            rules = rules,
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
        autocorrectStrength: File? = null,
        personalOffsetsFile: File? = null,
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
        /** The unknown-word letter model (en.charlm.tsv, [CharModel]); parsed once. */
        openCharModel: (() -> BufferedReader)? = null,
        /** Known misspellings (en.misspellings.txt): never scored as plausible unknown words. */
        openMisspellings: (() -> BufferedReader)? = null,
        /** The language's rules (the pack's rules.json). */
        rules: LanguageRules = LanguageRules.ENGLISH,
        /**
         * The word-data snapshots built with the app ("lexicon", "bigrams"), unpacked, or null when it has none for
         * these word lists: a first start copies them instead of parsing (the parse was the heap's peak on 2 GB
         * phones, and ~20 s on a Galaxy A10).
         */
        openShipped: ((String) -> java.io.InputStream?)? = null,
    ) {
        if (loaded != null) return
        // load stages timed for the log (cold start; i18n step 2 decides what to prebuild from this)
        var tMark = System.nanoTime()
        val stages = StringBuilder()
        fun mark(name: String) { val t = System.nanoTime(); stages.append(name).append(' ').append((t - tMark) / 1_000_000).append(" ms, "); tMark = t }
        misspellings = openMisspellings?.let { open ->
            runCatching { open().useLines { lines -> lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toHashSet() } }.getOrNull()
        } ?: misspellings
        addedWordsFile = addedWords
        lock.write { this.addedWords.clear(); readLines(addedWords).forEach { this.addedWords.add(it.trim().lowercase()) } }
        if (charModel == null) charModel = openCharModel?.let { open -> runCatching { open().use { CharModel.parse(it) } }.getOrNull() }
        mark("lists")
        reload = {
            load(
                openLexicon, openBigrams, userWords, blockedWords, rejectedCorrections, autocorrectStrength, personalOffsetsFile, openAbbreviations, personalModel,
                textReplacements, openEmojiIndex, openEmojiTones, emojiUsageFile, openNoSuggest, snapshotDir, snapshotKey, addedWords,
                openCharModel, openMisspellings, rules, openShipped,
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
        // mapped, not read into a byte array: the arrays are copied straight from the file's pages
        fun map(f: File) = java.io.RandomAccessFile(f, "r").channel.use { it.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, it.size()) }
        mark("small lists")
        // a first start: the snapshots that came with the app, when they match these word lists
        if (lexiconSnapshot != null && !lexiconSnapshot.isFile) openShipped?.let { open ->
            runCatching {
                val lex = open("lexicon") ?: return@runCatching
                snapshotDir?.listFiles()?.forEach { if (!it.name.endsWith("-$snapshotKey.bin")) it.delete() }
                lex.use { s -> writeAtomically(lexiconSnapshot) { s.copyTo(it, 64 * 1024) } }
                bigramSnapshot?.let { f -> open("bigrams")?.use { s -> writeAtomically(f) { s.copyTo(it, 64 * 1024) } } }
                mark("snapshots unpacked")
            }.onFailure { lexiconSnapshot.delete(); bigramSnapshot?.delete() }
        }
        val cachedLexicon = lexiconSnapshot?.takeIf { it.isFile }?.let { runCatching { Lexicon.readSnapshot(map(it)) }.getOrNull() }
        val lexicon = cachedLexicon ?: openLexicon().use { Lexicon.parse(it) }.let { lex ->
            snapshotDir?.let { dir -> dir.listFiles()?.forEach { if (!it.name.endsWith("-$snapshotKey.bin")) it.delete() } }
            lexiconSnapshot?.let { f -> writeAtomically(f) { lex.writeSnapshot(it) } }
            // the parsed lexicon holds everything on the heap; the snapshot just written maps its cold half, ~40 MB
            // less for the rest of the session (a Galaxy A10 stayed at its 128 MB cap after a parse, 2026-11-21)
            lexiconSnapshot?.takeIf { it.isFile }?.let { f -> runCatching { Lexicon.readSnapshot(map(f)) }.getOrNull() } ?: lex
        }
        mark(if (cachedLexicon != null) "lexicon (snapshot)" else "lexicon (parsed)")
        parsedAtLoad = cachedLexicon == null
        val bigrams = openBigrams?.let { open ->
            bigramSnapshot?.takeIf { cachedLexicon != null && it.isFile }
                ?.let { runCatching { Bigrams.readSnapshot(map(it), lexicon) }.getOrNull() }
                ?: open().use { Bigrams.parse(it, lexicon) }.also { b -> bigramSnapshot?.let { f -> writeAtomically(f) { b.writeSnapshot(it) } } }
        }
        mark("bigrams")
        this.rules = rules
        val engine = Loaded(lexicon, bigrams, rules)
        engine.decoder.unknownWords = charModel
        engine.retroDecoder.unknownWords = charModel
        engine.joinDecoder.unknownWords = charModel
        for (d in listOf(engine.decoder, engine.retroDecoder, engine.joinDecoder)) d.knownMisspellings = misspellings
        engine.autocorrect.knownMisspellings = misspellings
        for (d in listOf(engine.decoder, engine.retroDecoder, engine.joinDecoder)) d.beamWidth = beam
        userWords?.takeIf { it.exists() }?.forEachLine { line ->
            if (line.isNotBlank()) {
                val w = line.trim()
                if (!lexicon.contains(w)) {
                    learnedWords.add(w.lowercase())
                    lexicon.addWord(w, engine.userWordLogProb)
                } else if (!keptCasing(lexicon, w)) lexicon.addWord(w, engine.userWordLogProb)
            }
        }
        userWordsFile = userWords
        blockedWordsFile = blockedWords
        rejections = Rejections(rejectedCorrections)
        strength = AutocorrectStrength(autocorrectStrength).also { hookStep(it) }
        personalOffsets = PersonalOffsets(personalOffsetsFile)
        mark("engine + user words + files")
        personal = PersonalModel(personalModel)
        mark("personal model")
        replacements = TextReplacements(textReplacements)
        emojiUsage = EmojiUsage(emojiUsageFile)
        val tones = openEmojiTones?.let { open -> open().use { EmojiSuggest.parseTones(it) } }.orEmpty()
        emoji = openEmojiIndex?.let { open -> EmojiSuggest(open().use { EmojiSuggest.parse(it) }, emojiUsage, tones) }
        // words the user accepted that the lexicon lacks are decodable again
        for (w in personal.knownWords()) if (!lexicon.contains(w)) {
            learnedWords.add(w.lowercase())
            lexicon.addWord(w, engine.acceptedWordLogProb)
        }
        mark("emoji")
        for (n in appNames) if (!lexicon.contains(n)) lexicon.addWord(n, engine.userWordLogProb)
        lock.write {
            blocked.clear()
            blockedWords?.takeIf { it.exists() }?.forEachLine { if (it.isNotBlank()) blocked.add(it.trim().lowercase()) }
        }
        loaded = engine
        ngramModel?.let { attachNgrams(it) }
        mark("rest")
        runCatching { android.util.Log.i("DogBoardPerf", "load stages: $stages") }
    }

    /**
     * Attaches the trigram model to the loaded engine (call after [load], off the main thread):
     * kept apart so the keyboard is ready with the bigram model first and upgrades a moment later.
     */
    /** The unknown-word letter model ([CharModel]), attached after loading so startup isn't slowed; kept across reloads. */
    fun attachCharModel(model: CharModel?) {
        if (model != null) charModel = model
        val engine = loaded ?: return
        engine.decoder.unknownWords = charModel
        engine.retroDecoder.unknownWords = charModel
        engine.joinDecoder.unknownWords = charModel
        for (d in listOf(engine.decoder, engine.retroDecoder, engine.joinDecoder)) d.knownMisspellings = misspellings
        engine.autocorrect.knownMisspellings = misspellings
        for (d in listOf(engine.decoder, engine.retroDecoder, engine.joinDecoder)) d.beamWidth = beam
    }

    @Volatile
    private var neuralLm: io.github.sillypuppyy.dogboard.engine.lm.NeuralLm? = null

    /** Milliseconds the last neural prediction took (-1: none yet), logged by the keyboard. */
    @Volatile
    var lastNeuralMillis = -1L

    /** How long the last candidate refinement by the neural model took (ms), for the perf log. */
    @Volatile
    var lastNeuralScoreMillis = -1L

    /** The neural model's share of a candidate's language score (RealTypoEvalTest: 0.5 best of 0.5 / 0.75 / 1.0). */
    const val NEURAL_WEIGHT = 0.5

    /** iOS: a learned word with negative evidence is removed while its usage is below 7 (LanguageModeling, notes). */
    const val FORGET_BELOW_USES = 7

    /** The neural language model for next-word predictions (EngineLoader, when its files are in the app). */
    fun attachNeuralLm(model: io.github.sillypuppyy.dogboard.engine.lm.NeuralLm?) {
        neuralLm = model
    }

    val hasNeuralLm: Boolean get() = neuralLm != null

    // swipe typing: the recogniser (tools/swipe: trained on FUTO's MIT swipe data), when its file is in the app
    @Volatile private var swipeNet: io.github.sillypuppyy.dogboard.engine.swipe.SwipeNet? = null

    fun attachSwipeNet(net: io.github.sillypuppyy.dogboard.engine.swipe.SwipeNet?) {
        swipeNet = net
    }

    val hasSwipe: Boolean get() = swipeNet != null && loaded != null

    /** The swipe recogniser's interpreter released under critical memory pressure (~12 MB native); loaded again on the next show. */
    fun releaseSwipeNet() {
        val net = swipeNet ?: return
        swipeNet = null
        net.close()
    }

    val hasSwipeNet: Boolean get() = swipeNet != null

    /** Trigram weight in swipe decoding (SwipeContextEvalTest: 0.7 chosen on held-out FUTO swipes, 88.3 -> 89.9 % top-1). */
    const val SWIPE_CONTEXT_WEIGHT = 0.7

    /**
     * The words a swipe most likely spells, best first, in their stored casing ("I", "Paris"). [xs], [ys]:
     * the finger's path; [centres]: where the letter keys are, in the same units (any: the path is mapped
     * onto the recogniser's key grid by a fit to the keys). [previous], [previous2]: the two words before
     * (or "<s>"). As iOS: the network reads the path, a beam over the lexicon spells words, then the
     * sentence context reorders them.
     */
    fun swipeWords(
        xs: FloatArray, ys: FloatArray, centres: Map<Char, Pair<Float, Float>>,
        previous: String?, previous2: String?, top: Int = 5,
    ): List<String> {
        val net = swipeNet ?: return emptyList()
        val engine = loaded ?: return emptyList()
        if (xs.size < 2) return emptyList()
        val map = io.github.sillypuppyy.dogboard.engine.swipe.SwipePath.ToKeyUnits(centres)
        val f = io.github.sillypuppyy.dogboard.engine.swipe.SwipePath.features(
            FloatArray(xs.size) { map.x(xs[it]) }, FloatArray(ys.size) { map.y(ys[it]) },
        )
        val t0 = System.nanoTime()
        val logp = net.run(f)
        val t1 = System.nanoTime()
        return lock.read {
            val lex = engine.lexicon
            val beam = io.github.sillypuppyy.dogboard.engine.swipe.SwipeBeam.decode(logp, logp.size / 27, lex, top = 10)
            val scorer = engine.decoder.context
            val p1 = engine.contextId(previous)
            val p2 = if (previous == null || previous == Bigrams.START) -1 else engine.contextId(previous2)
            fun total(c: io.github.sillypuppyy.dogboard.engine.swipe.SwipeBeam.Candidate): Double {
                val uni = lex.logProb(c.node)
                val path = c.score - uni
                return if (scorer != null && p1 >= 0) path + SWIPE_CONTEXT_WEIGHT * scorer.logProb(p2, p1, lex.wordId(c.node), uni) else path + uni
            }
            if (swipeDebugLog) android.util.Log.d("DogBoardSwipe", "prev=" + previous + " steps=" + f.size / 4 + " beam=" +
                beam.take(6).joinToString { c -> lex.firstForm(c.node) + ":" + "%.1f".format(java.util.Locale.ROOT, c.score - lex.logProb(c.node)) + "/" + "%.1f".format(java.util.Locale.ROOT, total(c)) })
            beam.sortedByDescending { total(it) }.mapNotNull { lex.firstForm(it.node) }.filter { it.lowercase() !in blocked }.distinct().take(top)
                .also { lastSwipeTiming = "network ${(t1 - t0) / 1_000_000} ms, search ${(System.nanoTime() - t1) / 1_000_000} ms, ${f.size / 4} steps" }
        }
    }

    /** Debug builds: log each swipe's beam (word: path score / total with context), for the replay diagnosis. */
    @Volatile var swipeDebugLog: Boolean = false

    /** The last swipe's timing (for the DogBoardPerf log). */
    @Volatile var lastSwipeTiming: String = ""

    /**
     * Lets the neural model go when the system is short of memory (iOS releases its language model
     * buffers under memory pressure); the keyboard loads it again when typing resumes.
     */
    fun releaseNeuralLm() {
        val lm = neuralLm ?: return
        neuralLm = null
        runCatching { lm.close() }
    }

    fun attachNgrams(model: NgramModel?) {
        if (model != null) ngramModel = model
        val engine = loaded ?: return
        val bigrams = engine.bigrams ?: return
        val scorer = model?.let { NgramScorer(it, engine.lexicon, bigrams.sentenceStart) } ?: return
        engine.decoder.context = scorer
        engine.retroDecoder.context = scorer
        engine.joinDecoder.context = scorer
    }

    /**
     * Names from the user's contacts (opt-in, ContactNames), known as words at the probability of
     * a moderately common word. Kept in memory only; a different set reloads the word data so
     * removed names are forgotten.
     */
    /**
     * The names of the apps on the phone ([io.github.sillypuppyy.dogboard.apps.AppNames]) as known
     * words, the ones the lexicon lacks (iOS: installed app names in the dynamic dictionary). Only
     * added: an uninstalled app's name stays known until the next start.
     */
    fun addAppNames(names: Collection<String>) {
        appNames = appNames + names
        val engine = loaded ?: return
        lock.write { for (n in names) if (!engine.lexicon.contains(n)) engine.lexicon.addWord(n, engine.userWordLogProb) }
    }

    /** Kept for reloads (the lexicon is rebuilt when a contact name goes away). */
    @Volatile
    private var appNames: Set<String> = emptySet()

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
    private var charModel: CharModel? = null

    @Volatile
    private var contactNames: Set<String> = emptySet()
        set(value) {
            field = value
            contactKeys = value.mapTo(HashSet()) { sortKey(it) }
        }

    @Volatile
    private var contactKeys: Set<String> = emptySet()

    /**
     * iOS FilterWordsConflictingWithValidContactNames: when the typed letters are a contact's
     * name, no word outside the contacts replaces them ("Jon" stays Jon, not John); case and
     * accents may still follow the name. (iOS also compares scores with language power 0.24; not decoded exactly.)
     */
    private fun respectingContactNames(typed: String, candidates: List<Candidate>): List<Candidate> {
        val key = sortKey(typed)
        if (key !in contactKeys) return candidates
        val names = contactNames
        return candidates.filter { sortKey(it.word) == key || it.word in names }
    }

    private fun writeAtomically(file: File, write: (java.io.OutputStream) -> Unit) = file.writeAtomically(write)

    /** How much the user uses [emoji], by the iOS recents score (EmojiUsage), for ordering recents. */
    fun emojiScore(emoji: String): Double = emojiUsage.score(emoji)

    /** Known misspellings (Decoder.knownMisspellings), kept across reloads. */
    private var misspellings: Set<String> = emptySet()

    /** Words the user added in settings (a subset of the learned words, listed apart). */
    private var addedWordsFile: File? = null

    /** The added words, lowercase (iOS user dictionary entries: FilterInvalidTextReplacement). */
    private val addedWords = HashSet<String>()

    /**
     * iOS FilterInvalidTextReplacement (#24): a word from the user's own list may only correct a typed
     * word with the same non-letter core (the string with letters trimmed from both ends: "c++" isn't
     * offered for "cpp"); words from the lexicon or learned from typing are not affected.
     */
    private fun isInvalidUserEntry(typed: String, candidate: String): Boolean {
        if (candidate.lowercase() !in addedWords) return false
        if (candidate.isEmpty()) return true
        val core = candidate.trim { it.isLetter() }
        return core.isNotEmpty() && core != typed.trim { it.isLetter() }
    }

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
        lock.write { addedWords.add(w.lowercase()) }
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
            runCatching { if (f.exists()) f.writeTextAtomically(f.readLines().filter { it.isNotBlank() && it.trim().lowercase() != key }.joinToString("") { it + "\n" }) }
        }
        personal.remove(word)
        lock.write { learnedWords.remove(key); addedWords.remove(key) }
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
            runCatching { if (f.exists()) f.writeTextAtomically(f.readLines().filter { it.isNotBlank() && it.trim().lowercase() != key }.joinToString("") { it + "\n" }) }
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
     * rejected corrections (DogBoard: also the adaptive autocorrect strength and personal key
     * offsets); text replacements, blocked words and emoji use stay. The word data is
     * loaded again, as learned words live in the lexicon.
     */
    @Synchronized
    fun resetDictionary() {
        loaded ?: return
        personal.clear()
        rejections.clear()
        // DogBoard: what was learned from the user's typing goes too
        strength.reset()
        personalOffsets.reset()
        // iOS "Reset Keyboard Dictionary": every word the keyboard was taught goes, added ones too
        for (f in listOfNotNull(userWordsFile, addedWordsFile)) runCatching { f.delete() }
        lock.write { learnedWords.clear(); addedWords.clear() }
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
            val newCasing = wasKnown && keptCasing(engine.lexicon, word)
            engine.lexicon.addWord(word, engine.userWordLogProb)
            !wasKnown || newCasing
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

    private fun Loaded.contextId(previous: String?): Int {
        val id = previous?.let { bigrams?.contextId(it) } ?: -1
        // an accent twin as the previous word ("il a" vs "il à"): its own spelling's context, not its node's
        // representative spelling ("a mangé" was scored as "à mange", 2026-11-22). Needs the trigram model.
        if (previous == null || id < 0 || previous == Bigrams.START) return id
        val scorer = decoder.context as? NgramScorer ?: return id
        val node = lexicon.find(previous).takeIf { it != Lexicon.NONE } ?: return id
        val forms = lexicon.forms(node)
        if (forms.size < 2 || forms.all { it.first.equals(forms[0].first, ignoreCase = true) }) return id
        return scorer.spellingContext(previous) ?: id
    }

    /**
     * Decoded words for [word]'s taps, best first; empty if not ready or not decodable.
     * [previous] is the word before it ([previousContext]), or null.
     */
    /** [previous2]: the word before [previous] (or [Bigrams.START]), for the two-word-context model. */
    /**
     * [textBefore]: the text before the word, when known: the candidates are then refined with the
     * neural model as iOS does (each candidate's language score mixed with the model's, [NEURAL_WEIGHT];
     * real phone typos: fixed 47.9 -> 49.3 %, correct words broken 0.6 -> 0.4 %, RealTypoEvalTest).
     * Runs the model: call it off the main thread.
     */
    /** The beam the decoders use now (full, or [SLOW_BEAM] on a slow phone). */
    /** The main decoder's own time for its last decode (ms; no lock wait), what [adaptBeam] should judge. */
    val lastDecodeMillis: Long get() = loaded?.decoder?.lastDecodeMillis ?: 0L

    @Volatile var beam: Int = FULL_BEAM
        private set
    private var slowDecodes = 0
    private var fastDecodes = 0

    /**
     * iOS bounds each touch's search by time (>= 30 expansions, then up to 90 within 10 ms); ours keeps a fixed beam, so
     * a slow phone fell behind (Galaxy A10 word ends 52-149 ms; an emulator on 2 threads: decodes p90 120 ms). From
     * each keystroke's decode time: 3 slow decodes in a row narrow the beam, 20 fast ones widen it again.
     */
    fun adaptBeam(decodeMs: Long) {
        val engine = loaded ?: return
        if (decodeMs >= SLOW_DECODE_MS) { slowDecodes++; fastDecodes = 0 } else if (decodeMs <= FAST_DECODE_MS) { fastDecodes++; slowDecodes = 0 }
        val next = when {
            beam == FULL_BEAM && slowDecodes >= 3 -> SLOW_BEAM
            beam == SLOW_BEAM && fastDecodes >= 20 -> FULL_BEAM
            else -> return
        }
        beam = next; slowDecodes = 0; fastDecodes = 0
        for (d in listOf(engine.decoder, engine.retroDecoder, engine.joinDecoder)) d.beamWidth = next
    }

    fun candidates(word: CurrentWord, previous: String?, maxResults: Int = 8, previous2: String? = null, textBefore: String? = null): List<Candidate> {
        val engine = loaded ?: return emptyList()
        val taps = word.tapsOrNull() ?: return emptyList()
        val decoded = lock.read {
            engine.decoder.decode(
                taps, personalOffsets.apply(word.keys), word.params!!, maxResults + blocked.size.coerceAtMost(4) + 2,
                previous = engine.contextId(previous), typed = word.decoderText,
                personal = personalMix(engine, previous, previous2),
                previous2 = if (previous == null || previous == Bigrams.START) -1 else engine.contextId(previous2),
            )
                .let { withTypedApostrophes(word, it) }
                .filter { !isBlocked(it.word) && (it.isTypedString || it.word.equals(word.text, ignoreCase = true) || !isNotSuggestible(it.word) && !isUnderLearned(it.word)) }
                .let { onlyTypedUnderVulgarTop(word.text, it) }
                .take(maxResults)
        }
        if (textBefore == null) return decoded
        return refineWithNeural(decoded, textBefore) ?: decoded
    }

    /**
     * [decoded] refined with the neural model (see [candidates]), or null when there is no model or
     * nothing to refine. The keyboard runs it as a second stage after publishing the decoder's
     * candidates, so a word end never waits for it.
     */
    private val stepTally = java.util.concurrent.ConcurrentHashMap<String, LongArray>()
    private val tallyCalls = java.util.concurrent.atomic.AtomicLong()

    /** Model steps per feature, logged every 200 calls (perf: where the neural time goes). */
    private inline fun <T> tallySteps(lm: io.github.sillypuppyy.dogboard.engine.lm.NeuralLm, what: String, block: () -> T): T {
        val s0 = lm.steps; val t0 = System.nanoTime()
        try { return block() } finally {
            val a = stepTally.getOrPut(what) { LongArray(3) }
            synchronized(a) { a[0]++; a[1] += lm.steps - s0; a[2] += System.nanoTime() - t0 }
            if (tallyCalls.incrementAndGet() % 50 == 0L) android.util.Log.i("DogBoardPerf", "neural steps: " +
                stepTally.entries.joinToString { (k, v) -> "$k ${v[0]} calls ${v[1]} steps ${v[2] / 1_000_000} ms" } +
                "; in the interpreter ${lm.invokeNanos / 1_000_000} ms, softmax ${lm.softmaxNanos / 1_000_000} ms, tokenizing ${lm.encodeNanos / 1_000_000} ms")
        }
    }

    fun refineWithNeural(decoded: List<Candidate>, textBefore: String): List<Candidate>? {
        val engine = loaded ?: return null
        val lm = neuralLm?.takeIf { decoded.size > 1 } ?: return null
        val t0 = System.nanoTime()
        val scores = runCatching { tallySteps(lm, "refine") {
            lm.wordScores(textBefore, decoded.map { it.word }, maxSteps = NEURAL_REFINE_STEPS, deadlineNanos = t0 + NEURAL_REFINE_MS * 1_000_000)
        } }.getOrNull() ?: return null
        lastNeuralScoreMillis = (System.nanoTime() - t0) / 1_000_000
        lastNeuralUnscored = decoded.count { it.word !in scores }
        lastNeuralUnscoredMulti = decoded.count { it.word !in scores && ' ' in it.word }
        val power = if (engine.bigrams != null) Decoder.LANGUAGE_POWER_CONTEXT else Decoder.LANGUAGE_POWER_UNIGRAM
        // Rule 3's inputs for splits (debug, the emulator's checks): the neural ln P and the words' static ln P
        // typed text: debug builds only, and only with `setprop log.tag.DogBoardAc DEBUG` (it logged in every build until
        // 2026-11-22, typed words and the text before them; the user's phone must never log what is typed)
        if (dev.patrickgold.florisboard.BuildConfig.DEBUG && android.util.Log.isLoggable("DogBoardAc", android.util.Log.DEBUG))
        for (c in decoded) if (c.words > 1) scores[c.word]?.let { n ->
            android.util.Log.d("DogBoardAc", "split '${c.word}' after '${textBefore.takeLast(24)}': " +
                // typed text stays out of the pattern: a '%' in it was read as a format code (crash 2026-11-21)
                "neural %.2f, static %.2f, n-gram lm %.2f".format(n, c.languageModel - c.contextSupport, c.languageModel))
        }
        return withNeural(decoded, { scores[it] }, NEURAL_WEIGHT, power)
    }

    /**
     * The two-word readings the look back compares ([retrocorrection] with neural scores): the
     * previous word's top single words, each followed by the current word's best two readings.
     */
    private fun retroPairs(previous: List<Candidate>, current: List<Candidate>): List<Pair<Candidate, Candidate>> {
        val prev = previous.filter { !it.isCompletion && it.words == 1 }.take(4)
        val cur = current.filter { !it.isCompletion && it.words == 1 && !it.isTypedString }.take(2)
        return prev.flatMap { p -> cur.map { c -> p to c } }
    }

    /**
     * The neural model's ln P(previous + current | text before the previous word) for each reading
     * [retrocorrection] will compare, keyed "previous current"; null without the model. Runs the model
     * in another context than the current word's: call it off the main thread, after the refinement.
     */
    fun retroNeuralScores(previousDecoded: List<Candidate>, current: List<Candidate>, textBeforePrevious: String): Map<String, Double>? {
        val lm = neuralLm ?: return null
        val phrases = retroPairs(previousDecoded, current).map { (p, c) -> "${p.word} ${c.word}" }.distinct()
        if (phrases.size < 2) return null
        val t0 = System.nanoTime()
        val scores = runCatching { tallySteps(lm, "retro") { lm.backgroundWordScores(textBeforePrevious, phrases, maxSteps = 48) } }.getOrNull() ?: return null
        lastNeuralRetroMillis = (System.nanoTime() - t0) / 1_000_000
        return scores
    }

    @Volatile
    var lastNeuralRetroMillis = -1L
    /** Candidates the last refinement left unscored (its step budget ran out), and of those the multi-word ones. */
    @Volatile var lastNeuralUnscored = 0
    /**
     * Model steps a keystroke's refinement may run (words of several pieces and multi-word readings need
     * them; unscored ones keep the n-gram score). 6 left ~8 % of candidates unscored, a third of them
     * multi-word readings ("do you"); with the faster model (export_kv2) more fit well within a keystroke.
     */
    const val NEURAL_REFINE_STEPS = 16
    /** ...and stops starting new ones after this long (well inside the ~100 ms between a fast typist's keys). */
    const val NEURAL_REFINE_MS = 40L
    @Volatile var lastNeuralUnscoredMulti = 0

    /**
     * Hindsight retrocorrection (user 2026-11-18: one general mechanism, as iOS's retrocorrection: the
     * language model judges word sequences, no hand lists). A finished word kept as typed is read again
     * once [HINDSIGHT_WORDS] more words follow: its alternatives come from the lexicon alone (apostrophe
     * twins: its/it's; real words one typo away: form/from), each read with the words after it by the
     * neural model; it changes when ln P(alternative ...) - ln P(typed ...) - [HINDSIGHT_EDIT_PENALTY] x
     * typos > [HINDSIGHT_THRESHOLD]. Measured (tools/lm/retro_general.py, Tatoeba, unseen by the model):
     * 0.05 % of right words changed (4 of 8,000), 92 % of dropped apostrophes and 87 % of real-word typos
     * fixed (lowercase dictionary words; names and words the lexicon lacks are left alone).
     */
    const val HINDSIGHT_WORDS = 3
    const val HINDSIGHT_EDIT_PENALTY = 5.0
    const val HINDSIGHT_THRESHOLD = 1.0
    const val HINDSIGHT_CANDIDATES = 4

    /** Words rarer than the 120,000th most frequent aren't offered (as measured). */
    @Volatile private var hindsightFloor: Double = Double.NEGATIVE_INFINITY

    private fun hindsightFloorOf(lex: Lexicon): Double {
        if (hindsightFloor != Double.NEGATIVE_INFINITY) return hindsightFloor
        val lps = DoubleArray(lex.size) { lex.logProb(lex.nodeOfWord(it)) }
        lps.sortDescending()
        return lps.getOrElse(119_999) { Double.NEGATIVE_INFINITY }.also { hindsightFloor = it }
    }

    /** Whether [typed] is a word the look back may reconsider: in the lexicon, not a name typed mid-sentence. */
    fun hindsightEligible(typed: String): Boolean {
        val engine = loaded ?: return false
        if (typed.length < 2 || typed.any { it.isDigit() }) return false
        if (typed.first().isUpperCase() && !rules.isAlwaysCapitalised(typed)) return false
        return lock.read { engine.lexicon.contains(typed) }
    }

    /**
     * [typed]'s alternatives from the lexicon alone, with how many typos apart (0: the same letters with
     * or without an apostrophe): apostrophe twins and real words one typo away (an adjacent key, a missed
     * or extra letter, two letters swapped), in their stored casing.
     */
    fun hindsightCandidates(typed: String): Map<String, Int> {
        val engine = loaded ?: return emptyMap()
        return lock.read {
            val lex = engine.lexicon
            val floor = hindsightFloorOf(lex)
            val low = typed.lowercase()
            val out = LinkedHashMap<String, Int>()
            val plausibility = HashMap<String, Double>()
            fun offer(v: String, edits: Int) {
                if (v == low || v.isEmpty()) return
                val node = lex.find(v)
                if (node == Lexicon.NONE || !lex.isWord(node) || lex.logProb(node) < floor) return
                val form = lex.firstForm(node) ?: return
                if (form !in out && !isBlocked(form) && !isNotSuggestible(form)) {
                    out[form] = edits
                    plausibility[form] = lex.logProb(node) - HINDSIGHT_EDIT_PENALTY * edits
                }
            }
            // apostrophe twins
            if ('\'' in low) offer(low.replace("'", ""), 0)
            else for (i in 1 until low.length) offer(low.substring(0, i) + "'" + low.substring(i), 0)
            // one typo away
            val letters = low.filter { it != '\'' }
            if (letters == low) {
                for (i in low.indices) {
                    for (n in neighbourKeys(low[i])) offer(low.substring(0, i) + n + low.substring(i + 1), 1)
                    offer(low.removeRange(i, i + 1), 1)
                    if (i + 1 < low.length) offer(low.substring(0, i) + low[i + 1] + low[i] + low.substring(i + 2), 1)
                }
                // a missed letter: any of the language's letters (its letter_rows; a-z for English)
                for (i in 0..low.length) for (c in rules.letterRows.centres.keys.sorted()) offer(low.substring(0, i) + c + low.substring(i), 1)
            }
            // only the most plausible few reach the model (measured: the top 4 decide exactly as all of them,
            // at a fraction of the model time)
            out.entries.sortedByDescending { plausibility[it.key] }.take(HINDSIGHT_CANDIDATES).associate { it.key to it.value }
        }
    }

    private fun neighbourKeys(c: Char): List<Char> = rules.letterRows.neighbours(c)

    /**
     * The alternative [typed] should become, read with [after] (the words typed after it) given [before],
     * or null (keep it). Runs the neural model in its own context: call it off the main thread; null
     * without the model.
     */
    fun hindsightFix(before: String, typed: String, after: String): Pair<String, Double>? {
        val lm = neuralLm ?: return null
        val cands = hindsightCandidates(typed)
        if (cands.isEmpty()) return null
        val phrases = listOf("$typed $after") + cands.keys.map { "$it $after" }
        val t0 = System.nanoTime()
        val s0 = lm.steps
        // one phrase per hold of the model: a keystroke's refinement waits for one phrase at most, not the
        // whole check (a trace at fast typing showed keystrokes waiting up to 50 ms, perf 2026-11-18); a
        // keystroke in between moves the model's context, which the next phrase brings back
        val scores = runCatching {
            tallySteps(lm, "hindsight") {
                HashMap<String, Double>().also { out ->
                    for (p in phrases) lm.backgroundWordScores(before, listOf(p), maxSteps = 200)[p]?.let { out[p] = it }
                }
            }.also {
                android.util.Log.i("DogBoardPerf", "hindsight: ${cands.size} alternatives in ${(System.nanoTime() - t0) / 1_000_000} ms (${lm.steps - s0} model steps)")
            }
        }.getOrNull() ?: return null
        val base = scores["$typed $after"] ?: return null
        val best = cands.entries.mapNotNull { (c, edits) ->
            scores["$c $after"]?.let { c to it - base - HINDSIGHT_EDIT_PENALTY * edits }
        }.maxByOrNull { it.second } ?: return null
        val cased = if (typed.first().isUpperCase() && !best.first.first().isUpperCase()) best.first.replaceFirstChar { it.uppercase() } else best.first
        return cased to best.second
    }

    /** True when the user undid this change before (the look back shares the rejections of corrections). */
    fun hindsightRejected(typed: String, fix: String): Boolean = rejections.isBlocked(typed, fix)

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
        /**
         * The candidates already decoded for the previous word (when it was finished) and for the
         * current one (while typing): given both, nothing is decoded again, only rescored (it runs
         * as a word is finished, on the main thread: full decodes took ~0.5 s on a phone).
         */
        previousCandidates: List<Candidate>? = null,
        currentCandidates: List<Candidate>? = null,
        /**
         * [retroNeuralScores] for [previousCandidates] (the decoder's, before any neural refinement)
         * and [currentCandidates]: each reading's language score blended with the neural model's.
         */
        neuralPairs: Map<String, Double>? = null,
    ): String? {
        val engine = loaded ?: return null
        val bigrams = engine.bigrams ?: return null // the look back needs a context model
        val prevTaps = previous.tapsOrNull() ?: return null
        val curTaps = current.tapsOrNull() ?: return null
        if (previousCommitted.any { it.isDigit() } || lock.read { isBlocked(previousCommitted) }) return null
        if (previous.typedApostrophes > 0) return null // its letters alone aren't what was typed
        if (rejections.isBlocked(previous.text, previousCommitted)) return null
        return lock.read {
            val d = engine.retroDecoder
            // a typed word the lexicon knows isn't turned into itself plus a letter ("the" -> "they"):
            // the decoder reads a skipped last key as a typo fix for autocorrect's missing-last-letter fix
            val typedIsWord = engine.lexicon.contains(previous.text)
            fun extendsTyped(w: String) = typedIsWord && w.length == previous.text.length + 1 && w.startsWith(previous.text, ignoreCase = true)
            val prevCandidates = (previousCandidates?.filter { !it.isCompletion && !extendsTyped(it.word) }?.take(4) ?: d.decode(
                prevTaps, personalOffsets.apply(previous.keys), previous.params!!, 4, includeCompletions = false,
                previous = engine.contextId(beforeContext), typed = previous.text,
            )).filter { it.words == 1 && !extendsTyped(it.word) && !isBlocked(it.word) && !isNotSuggestible(it.word) && keepsPopupLetters(previous.text, it.word) }
                .let { respectingContactNames(previous.text, it) }
            val curReadings = currentCandidates?.filter { !it.isCompletion && it.words == 1 }
            val pairs = if (neuralPairs != null && currentCandidates != null) retroPairs(prevCandidates, currentCandidates) else emptyList()
            fun neuralJoint(p: Candidate): Double? = pairs.filter { it.first.word == p.word }.mapNotNull { (_, c) ->
                val n = neuralPairs?.get("${p.word} ${c.word}") ?: return@mapNotNull null
                val r = d.rescored(c, bigrams.contextId(p.word.lowercase()))
                p.geometry + r.geometry + Decoder.LANGUAGE_POWER_CONTEXT * ((1 - NEURAL_WEIGHT) * (p.languageModel + r.languageModel) + NEURAL_WEIGHT * n)
            }.maxOrNull()
            // with neural scores for every reading the comparison uses them, else the n-gram joint as before
            val useNeural = pairs.isNotEmpty() && prevCandidates.all { neuralJoint(it) != null }
            fun joint(prevWord: String, prevScore: Double): Double {
                if (useNeural) prevCandidates.firstOrNull { it.word == prevWord }?.let { p -> neuralJoint(p)?.let { return it } }
                val context = bigrams.contextId(prevWord.lowercase())
                val best = if (curReadings != null) {
                    curReadings.maxOfOrNull { d.rescored(it, context).score }
                } else {
                    d.decode(curTaps, personalOffsets.apply(current.keys), current.params!!, 1, includeCompletions = false,
                        previous = context, typed = current.text).firstOrNull()?.score
                }
                return prevScore + (best ?: return Double.NEGATIVE_INFINITY)
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

    /**
     * iOS retrocorrection across a plain space ([SpaceJoin]): the previous word ([previous], committed
     * as [previousCommitted]), the space tap and the [current] word read as one word ("goi g" ->
     * "going"), cased as the previous word was typed, when it beats the two words by 1.65x; else
     * null. Decodes: call it off the main thread (the keyboard does, while the next word is typed).
     */
    /** The last [spaceJoin] comparison, for the debug log. */
    @Volatile
    var lastJoinDebug = ""

    fun spaceJoin(
        previous: CurrentWord, previousCommitted: String, beforeContext: String?, spaceTap: Tap,
        current: CurrentWord, previousCandidates: List<Candidate>, currentCandidates: List<Candidate>, curly: Boolean = false,
    ): String? {
        val engine = loaded ?: return null
        val bigrams = engine.bigrams ?: return null
        val prevTaps = previous.tapsOrNull() ?: return null
        val curTaps = current.tapsOrNull() ?: return null
        if (previous.typedApostrophes > 0 || current.typedApostrophes > 0) return null
        if (previousCommitted.any { it.isDigit() } || current.text.any { it.isDigit() }) return null
        return lock.read {
            val committed = previousCandidates.firstOrNull { it.word.equals(previousCommitted, ignoreCase = true) } ?: return@read null
            val after = bigrams.contextId(previousCommitted.lowercase())
            val second = currentCandidates.filter { !it.isCompletion && it.words == 1 }.maxOfOrNull { engine.joinDecoder.rescored(it, after).score }
                ?: return@read null
            val readings = SpaceJoin.readings(
                engine.joinDecoder, prevTaps, spaceTap, curTaps, personalOffsets.apply(previous.keys), previous.params!!,
                engine.contextId(beforeContext),
            )
            val join = readings.firstOrNull { SpaceJoin.commonEnough(engine.lexicon, it.word) } ?: return@read null
            lastJoinDebug = "%s %.2f vs %s %.2f + %.2f; readings %s".format(
                join.word, join.score, committed.word, committed.score, second, readings.joinToString { "%s %.2f".format(it.word, it.score) },
            )
            if (!SpaceJoin.wins(join, committed.score + second)) return@read null
            if (isBlocked(join.word) || isNotSuggestible(join.word) || rejections.isBlocked(previous.text + " " + current.text, join.word)) return@read null
            caseFor(previous, curly)(join.word)
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
        val kept = respectingContactNames(word.text, withoutLearnedTypo(word.text, candidates))
            .filter {
                keepsPopupLetters(word.text, it.word) && keepsOneLetterWord(word.text, it.word, engine) &&
                    keepsAmpersands(word.text, it.word) && noDoubleApostrophe(word.text, it.word) && !isAllCapsNoSuggest(word.text, it.word) &&
                    !isCommonLearnedTypo(word.text, it.word, rules.learnedTypoOverrides) && !isInvalidUserEntry(word.text, it.word)
            }
        // iOS exact-typed-string veto: a typed word at least as likely as the best candidate is left alone
        // ("Kaito", not "Jaito"; a valid word too)
        val adjusted = if (adaptiveStrength) strength.adjust(level.level) else level.level
        if (vetoesCorrection(ranked(kept, adjusted), word.text)) return null
        val result = engine.autocorrect.correction(word.text, kept, previousCorrection, caseFor(word, curly), adjusted)
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

    /** The user added "never correct [typed] to [correction]" in settings: permanent until removed. */
    fun neverCorrect(typed: String, correction: String) = rejections.neverCorrect(typed, correction)

    /** The user undid [typed] -> [correction] (or kept their word while it was offered): a hard rejection. */
    fun reject(typed: String, correction: String) {
        rejections.recordHard(typed, correction)
        if (adaptiveStrength) strength.reverted() // off: it neither learns nor changes step
        forgetRejectedLearnedWords(correction)
    }

    /**
     * iOS negative learning (decompiled 2026-11-20): undoing a correction registers negative evidence against the
     * rejected correction (TIRevisionHistory rejectCandidate:forInput:hint: -> registerNegativeEvidence, the typed
     * word as "intended"), and a learned word with a penalty and fewer than [FORGET_BELOW_USES] uses leaves the
     * learned words. So a typo DogBoard learned and then corrected another word into is forgotten on undo.
     * Words added on the dictionary page are the user's list and stay.
     */
    private fun forgetRejectedLearnedWords(correction: String) {
        val forget = correction.split(' ').map { it.lowercase() }.filter { k ->
            lock.read { k in learnedWords && k !in addedWords } && personal.uses(k) < FORGET_BELOW_USES
        }
        if (forget.isNotEmpty()) Thread { forget.forEach(::forgetWord) }.apply { isDaemon = true; name = "DogBoardForget" }.start()
    }

    /** Autocorrection just turned [typed] into [correction]. */
    fun noteCorrected(typed: String, correction: String) = rejections.noteCorrected(typed, correction)

    /** [typed] -> [correction] was made and the user typed on: earlier undos of it are forgiven. */
    fun correctionKept(typed: String, correction: String) {
        rejections.recordKept(typed, correction)
        if (adaptiveStrength) strength.kept()
    }

    /**
     * A word typed by tapping and kept as typed: its taps' offsets from the key centres feed the
     * personal key offsets ([PersonalOffsets]).
     */
    fun recordTouches(word: String, taps: List<Tap>, keys: Map<Char, io.github.sillypuppyy.dogboard.touch.KeyTarget>) {
        if (word.length != taps.size) return
        val samples = word.mapIndexedNotNull { i, c ->
            val k = keys[c.lowercaseChar()] ?: keys[c.uppercaseChar()] ?: return@mapIndexedNotNull null
            if (k.halfWidth <= 0f || k.halfHeight <= 0f) return@mapIndexedNotNull null
            Triple(c.lowercaseChar(), ((taps[i].x - k.centerX) / k.halfWidth).toDouble(), ((taps[i].y - k.centerY) / k.halfHeight).toDouble())
        }
        if (samples.size == word.length) personalOffsets.record(samples)
    }

    /** Taps in the personal key-offset history (for settings and tests). */
    val personalOffsetTaps: Int get() = personalOffsets.taps

    /** The user picked a correction from the bar for a word autocorrect left alone. */
    fun fixPicked() { if (adaptiveStrength) strength.fixPicked() }

    /** The adaptive score (> 0: more careful, < 0: bolder), for settings and tests. */
    val strengthScore: Double get() = strength.score

    /** The user deleted the whole word [word] (a soft rejection if it was just autocorrected). */
    fun noteDeleted(word: String) {
        if (rejections.noteDeleted(word) && adaptiveStrength) strength.deletedAfterCorrection()
    }

    /**
     * The [n] most likely next words after [previous2] [previous] (stored casing), for the empty strip:
     * from the trigram model when attached (two words of context: top-1 15.0 % vs 9.1 % for the
     * bigram followers on the control set, 22.0 % vs 14.8 % on chat; PredictionBenchTest), topped up
     * from the bigram followers.
     */
    fun predictions(
        previous: String?, previous2: String? = null, n: Int = SuggestionStrip.SLOTS, textBefore: String? = null,
        stillWanted: () -> Boolean = { true },
    ): List<String> {
        val engine = loaded ?: return emptyList()
        val bigrams = engine.bigrams ?: return emptyList()
        val want = n + blocked.size.coerceAtMost(4) + 4
        // the neural model first (the whole text before the cursor, run outside the engine lock: it
        // takes milliseconds), its words kept to ones the lexicon knows, in the lexicon's casing
        val fromNeural = neuralLm?.takeIf { textBefore != null }?.let { lm ->
            val t0 = System.nanoTime()
            val words = runCatching { tallySteps(lm, "predict") { lm.predict(textBefore!!, n = want, isWord = { w -> lock.read { engine.lexicon.contains(w) } }, stillWanted = stillWanted) } }.getOrDefault(emptyList())
            lastNeuralMillis = (System.nanoTime() - t0) / 1_000_000
            lock.read {
                words.mapNotNull { (w, _) ->
                    val node = engine.lexicon.find(w).takeIf { it != Lexicon.NONE && engine.lexicon.isWord(it) }
                    node?.let { engine.lexicon.word(it) }
                }
            }
        }.orEmpty()
        return lock.read {
            val fromTrigram = ngramModel?.let { m ->
                val b = m.idOf(previous ?: NgramModel.START)
                val a = if (previous == null || previous == Bigrams.START) -1 else m.idOf(previous2 ?: NgramModel.START)
                m.followers(a, b, want * 2).mapNotNull { (id, _) ->
                    val node = engine.lexicon.find(m.word(id)).takeIf { it != Lexicon.NONE && engine.lexicon.isWord(it) }
                    node?.let { engine.lexicon.word(it) }
                }
            }.orEmpty()
            val fromBigram = bigrams.followers(engine.contextId(previous), want)
                .map { (id, _) -> engine.lexicon.word(engine.lexicon.nodeOfWord(id))!! }
            (fromNeural + fromTrigram + fromBigram).distinct()
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

    /** What [keyPrior] gives a letter of probability [p]. */
    fun keyPriorOf(p: Double): Double = KEY_PRIOR_WEIGHT * maxOf(KEY_PRIOR_FLOOR, ln(p))

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
                file.writeTextAtomically(kept.joinToString("") { it + "\n" })
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
    private var lastMix: Triple<Any?, Int, PersonalMix?>? = null

    private fun personalMix(engine: Loaded, previous: String?, previous2: String? = null): PersonalMix? {
        val p = personal
        val version = p.version
        val key = Triple(engine, previous, previous2)
        lastMix?.let { (prev, v, mix) -> if (prev == key && v == version) return mix }
        if (p.weight(previous != null) <= 0.0) return null.also { lastMix = Triple(key, version, null) }
        return makeMix(engine, p, previous, previous2).also { lastMix = Triple(key, version, it) }
    }

    private fun makeMix(engine: Loaded, p: PersonalModel, previous: String?, previous2: String?): PersonalMix {
        // the start of a text has no word before it
        val p2 = previous2?.takeIf { previous != null && previous != Bigrams.START && it != Bigrams.START }
        return p.decoderMix(engine.lexicon, previous, p2)
    }

    /**
     * The user accepted [word] after [previous] (finished and not undone, a correction kept, or a
     * suggestion picked): counted in the personal model; an unknown word is learned (iOS: one
     * accept, strength from use).
     */
    fun accepted(previous: String?, word: String, previous2: String? = null) {
        val engine = loaded ?: return
        if (word.isBlank() || word.length > 48 || !word.all { isWordChar(it) } || !word.any { it.isLetter() }) return
        personal.accept(previous, word, previous2?.takeIf { previous != null && previous != Bigrams.START && it != Bigrams.START })
        val isNew = lock.write {
            val known = engine.lexicon.contains(word)
            if (!known) {
                learnedWords.add(word.lowercase())
                engine.lexicon.addWord(word, engine.acceptedWordLogProb)
            }
            !known || keptCasing(engine.lexicon, word)
        }
        if (isNew) userWordsFile?.let { f -> runCatching { f.parentFile?.mkdirs(); f.appendText(word + "\n") } }
    }

    /**
     * A lowercase [word] the user kept that the lexicon has only in other casings ("hom" beside the name "Hom"):
     * added as a casing, so keeping it no longer lifts the capitalised form over what was typed (emulator
     * 2026-11-19: "hom" kept twice was then corrected to "Hom"). True when it was added.
     */
    private fun keptCasing(lexicon: Lexicon, word: String): Boolean =
        word == word.lowercase() && lexicon.addCasing(word)

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
        return AutoShift.shouldShift(before, type, forms, rules.sentenceEnd, rules.quotes)
    }

    private fun isBlocked(word: String): Boolean = word.lowercase() in blocked

    /**
     * iOS FilterLearnedTypo: a word learned from a slip ("ofthe", accepted fewer than 2 times)
     * gives way when the same letters split into words ("of the"). The space bar is one of the keys
     * a tap may have meant, so a letter typed for a space splits them too ("ofna" -> "of a").
     */
    private fun withoutLearnedTypo(typed: String, candidates: List<Candidate>): List<Candidate> {
        val t = typed.lowercase()
        if (lock.read { t !in learnedWords } || personal.uses(t) >= 2) return candidates
        val split = candidates.any { it.words > 1 && splitsTyped(it.word, typed) }
        return if (split) candidates.filter { !it.word.equals(typed, ignoreCase = true) } else candidates
    }

    /** [candidate] is [typed] split into words: its spaces added between letters or typed as letters. */
    internal fun splitsTyped(candidate: String, typed: String): Boolean {
        if (candidate.replace(" ", "").equals(typed, ignoreCase = true)) return true
        return candidate.length == typed.length &&
            candidate.indices.all { i -> candidate[i] == ' ' || candidate[i].equals(typed[i], ignoreCase = true) }
    }

    /**
     * A word typed with an apostrophe from the symbol keys was decoded from its letters only: the
     * reading of exactly those letters is the word as typed ("dogs" → "dog's"), and every other
     * candidate keeps at least as many apostrophes ("dpg's" → "dog's", not "dogs").
     */
    private fun withTypedApostrophes(word: CurrentWord, candidates: List<Candidate>): List<Candidate> {
        val need = word.typedApostrophes
        if (need == 0) return candidates
        val letters = word.decoderText
        val out = LinkedHashMap<String, Candidate>()
        for (c in candidates) {
            val mapped = if (c.word.equals(letters, ignoreCase = true)) c.copy(word = word.text) else c
            if (mapped.word.count { it == '\'' || it == '’' } < need) continue
            val old = out[mapped.word.lowercase()]
            if (old == null || mapped.score > old.score) out[mapped.word.lowercase()] = mapped
        }
        return out.values.sortedByDescending { it.score }
    }

    /**
     * The system spell checker's question: is [word] known, and if not, what was likely meant (up
     * to [max], best first)? Null while the word data loads. Numbers and words with other symbols
     * are never flagged; a curly apostrophe counts as a straight one.
     */
    fun spell(word: String, max: Int): Pair<Boolean, List<String>>? {
        val engine = loaded ?: return null
        val w = word.replace('’', '\'')
        if (w.isEmpty() || !w.all { isWordChar(it) } || w.none { it.isLetter() }) return true to emptyList()
        if (lock.read { engine.lexicon.contains(w) }) return true to emptyList()
        // the letters on their key centres, as for a word the cursor rests in
        val typed = CurrentWord().apply { for (c in w) addLetter(c, null, SPELL_KEYS, SPELL_PARAMS) }
        val fixes = candidates(typed, null, max + 3)
            .filter { !it.isTypedString && !it.isCompletion && !it.word.equals(w, ignoreCase = true) }
            .map { if (w.first().isUpperCase()) it.word.replaceFirstChar(Char::uppercaseChar) else it.word }
            .distinct()
            .take(max)
        return false to fixes
    }

    private val SPELL_KEYS = QwertyGeometry.letterKeys(375f, 216f)
    private val SPELL_PARAMS = io.github.sillypuppyy.dogboard.touch.GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)

    /**
     * iOS compares only the language-model terms: the typed string's (here: the unknown-word letter
     * model, [CharModel]) against the top candidate's. Only with that model loaded: iOS's own
     * fallback score for an unknown string (1e-4 per letter) would never pass.
     */
    /** Without the letter model a typed non-word has no reading to compare, a valid typed word still does. */
    private fun vetoesCorrection(candidates: List<Candidate>, typed: String): Boolean = typedStringVeto(candidates, typed)

    /**
     * iOS FilterMissingOneLetterWordFromInput (#17): a valid one-letter word typed ("a", "I") is not
     * swapped for a different one-letter word; longer words ("as") are still allowed.
     */
    private fun keepsOneLetterWord(typed: String, candidate: String, engine: Loaded): Boolean {
        if (typed.length != 1 || !engine.lexicon.contains(typed)) return true
        // its accent twin is the same word ("a" -> "à": iOS compares by sort key); the twin margin still applies
        return candidate.length != 1 || candidate.equals(typed, ignoreCase = true) || isAccentTwin(candidate, typed)
    }

    /**
     * iOS FilterNonVulgarIfTopCandidateVulgar (#22) with #30/#31: when the word typed is a
     * never-suggest word and ranks first, no clean alternative is offered for it (the bar keeps
     * just the typed word).
     */
    private fun onlyTypedUnderVulgarTop(typed: String, candidates: List<Candidate>): List<Candidate> {
        val top = candidates.maxByOrNull { it.score } ?: return candidates
        return if (top.word.equals(typed, ignoreCase = true) && isNotSuggestible(top.word)) listOf(top) else candidates
    }

    /**
     * iOS FilterAllCapsNoSuggestWordsOfLength (#11): unless the input is already in capitals, a
     * correction may not bring in a two-letter all-caps word that is never offered, or a learned one
     * not used enough yet (iOS: learned once, not "sufficiently learned").
     */
    private fun isAllCapsNoSuggest(typed: String, candidate: String): Boolean {
        if (typed == typed.uppercase()) return false
        return candidate.split(' ').any { w ->
            w.length == 2 && w.all { it.isUpperCase() } &&
                (isNotSuggestible(w) || (lock.read { w.lowercase() in learnedWords } && personal.uses(w) < 2))
        }
    }

    /** True if any word of [candidate] (one word, or several when words were split) is never offered. */
    /**
     * iOS filter 21 (is_suggestible_for_input, spec 3.4): a user-acquired word is "sufficiently learned" at
     * usage 2 (word_from_dynamic_entry sets 0x2000 at usage >= 2, notes VERIFIED); before that it is kept when
     * typed but never offered for other input. Words added on the dictionary page are a user list, not
     * acquired, so they are exempt (audit 2026-11-20).
     */
    private fun isUnderLearned(candidate: String): Boolean = candidate.split(' ').any { w ->
        val k = w.lowercase()
        k in learnedWords && k !in addedWords && personal.uses(k) < 2
    }

    private fun isNotSuggestible(candidate: String): Boolean =
        blockNotSuggestible && noSuggest.isNotEmpty() && candidate.split(' ').any { it.lowercase() in noSuggest }

    fun isKnownWord(word: String): Boolean = lock.read { loaded?.lexicon?.contains(word) == true }

    /** Forgets everything loaded (tests only). */
    /** The loaded engine's lexicon and decision rules (evals that decide through [correction]). */
    internal fun lexiconForTest(): Lexicon? = loaded?.lexicon
    internal fun autocorrectForTest(): Autocorrect? = loaded?.autocorrect

    /** Drops the loaded language (its words, models and the user's data read with them), to load another. */
    fun unload() = unloadForTest()

    internal fun unloadForTest() {
        loaded = null
        charModel = null
        appNames = emptySet()
        ngramModel = null
        userWordsFile = null
        blockedWordsFile = null
        blocked.clear()
        rejections = Rejections()
        strength = AutocorrectStrength()
        personalOffsets = PersonalOffsets()
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

/**
 * iOS FilterCommonLearnedTypos: for these typed words (exact case), corrections people tend to undo
 * are never made ([LanguageRules.learnedTypoOverrides], iOS's adaptation-overrides asset).
 */
internal fun isCommonLearnedTypo(typed: String, candidate: String, overrides: Map<String, List<String>> = LanguageRules.ENGLISH.learnedTypoOverrides): Boolean =
    overrides[typed]?.let { bad -> candidate.split(' ').any { it.replace('’', '\'') in bad } } == true

/**
 * iOS FilterDoubleApostrophe (#9, spec 3.2): when the input has an apostrophe, no candidate with two
 * apostrophes side by side across words ("dogs' 's"; audit 2026-11-20).
 */
internal fun noDoubleApostrophe(typed: String, candidate: String): Boolean {
    if (typed.none { it == '\'' || it == '’' }) return true
    return !Regex("['’] ['’]").containsMatchIn(candidate)
}

/** iOS FilterCharacterCount (#13): a correction keeps at least as many "&" as were typed ("AT&T"). */
internal fun keepsAmpersands(typed: String, candidate: String): Boolean =
    candidate.count { it == '&' } >= typed.count { it == '&' }

/**
 * iOS FilterPopupVariantMismatch: a letter typed from a long-press popup stays in a correction, at
 * the same place ("café" typed with é is not corrected to "cafe"). The letter keys are plain a-z,
 * so an accented letter in [typed] came from a popup.
 */
internal fun keepsPopupLetters(typed: String, candidate: String): Boolean {
    for ((i, c) in typed.withIndex()) {
        if (Lexicon.fold(c) == c.lowercaseChar()) continue
        if (candidate.getOrNull(i)?.lowercaseChar() != c.lowercaseChar()) return false
    }
    return true
}

private const val FULL_BEAM = 100
private const val SLOW_BEAM = 30
private const val SLOW_DECODE_MS = 30L
private const val FAST_DECODE_MS = 8L

package io.github.sillypuppyy.dogboard.ime

import android.content.Context
import io.github.sillypuppyy.dogboard.engine.CharModel
import io.github.sillypuppyy.dogboard.engine.TypingEngine
import java.io.File

/**
 * Loads the word engine with DogBoard's files (assets, the user's learned data, the binary
 * snapshot). Used by the keyboard and by the settings screens, which share one process: whichever
 * comes first loads it. Call off the main thread.
 */
object EngineLoader {
    /** The language pack in use (notes/design/i18n-pipeline.md); English until the keyboard picks packs by language. */
    @Volatile var pack: LanguagePack? = null
        private set

    private fun pack(app: Context): LanguagePack =
        pack ?: (LanguagePack.open(app.assets, LanguagePack.DEFAULT_TAG) ?: error("no language pack ${LanguagePack.DEFAULT_TAG}")).also { pack = it }

    /**
     * The keyboard's language changed ([tag], the subtype's locale such as "es-ES"): when a pack with a dictionary exists
     * for it (downloaded or built in) and it isn't the one loaded, the engine unloads and loads that pack. Without one
     * the loaded pack stays (English typing goes on), never a silent switch to the wrong words. Off the main thread.
     */
    @Synchronized
    fun switchTo(context: Context, tag: String): Boolean {
        val app = context.applicationContext
        val current = pack
        if (current != null && (current.tag.equals(tag, true) || current.tag.equals(tag.substringBefore('-'), true))) return false
        val next = LanguagePack.find(app, tag)?.takeIf { it.hasWords } ?: return false
        if (next.tag == current?.tag) return false
        io.github.sillypuppyy.dogboard.perf.EventLog.add("language: ${current?.tag} -> ${next.tag}")
        TypingEngine.unload()
        TypingEngine.attachNgrams(null); TypingEngine.attachNeuralLm(null); TypingEngine.attachSwipeNet(null); TypingEngine.attachCharModel(null)
        pack = next
        System.gc() // the old language's words go before the new one's are parsed (its first start parses the word lists)
        loadOrFallBack(app)
        return true
    }

    /** The languages typed together with the first (Subtype.secondaryLocales, at most [MAX_TOGETHER] - 1). */
    @Volatile var secondaryPacks: List<LanguagePack> = emptyList()
        private set

    /**
     * Types [tags] together with the loaded language (notes/design/multilingual.md): each pack's words, pairs, trigram
     * and letter model beside the first's, every word decoded in all and merged. Tags without a dictionary on the phone,
     * or the loaded language's own, are skipped. Off the main thread; true when anything changed.
     */
    @Synchronized
    fun setSecondaries(context: Context, tags: List<String>): Boolean {
        val app = context.applicationContext
        val next = tags.mapNotNull { t -> LanguagePack.find(app, t)?.takeIf { it.hasWords && it.tag != pack?.tag } }
            // three languages took the java heap to 128 MB on the emulator (en + es + fr): a 128 MB heap gets one beside
            .distinctBy { it.tag }.take(if (heapMb(app) <= LOW_HEAP_MB) 1 else MAX_TOGETHER - 1)
        if (next.map { it.tag } == secondaryPacks.map { it.tag }) return false
        TypingEngine.unloadSecondary()
        secondaryPacks = emptyList()
        if (next.isEmpty()) { io.github.sillypuppyy.dogboard.perf.EventLog.add("second language: none"); return true }
        System.gc() // the languages that went before the new ones are read
        for (p in next) {
            val t0 = android.os.SystemClock.uptimeMillis()
            runCatching {
                val rules = runCatching { p.open("rules")?.bufferedReader()?.use { io.github.sillypuppyy.dogboard.engine.LanguageRules.parse(it.readText()) } }.getOrNull()
                    ?: io.github.sillypuppyy.dogboard.engine.LanguageRules()
                TypingEngine.loadSecondary(
                    openLexicon = { (p.open("lexicon") ?: error("${p.tag}: no lexicon")).bufferedReader() },
                    openBigrams = p.open("bigrams")?.let { s -> s.close(); { p.open("bigrams")!!.bufferedReader() } },
                    snapshotDir = dataDir(app, p),
                    snapshotKey = snapshotKey(app, p),
                    openShipped = { name -> shippedSnapshot(app, p, name) },
                    rules = rules,
                    // the trigram only where the heap has room for more than one language's (M8 measures the phone)
                    ngrams = if (heapMb(app) > LOW_HEAP_MB) loadNgrams(app, p) else null,
                    letters = runCatching { p.open("charlm")?.use { CharModel.read(it) } }.getOrNull(),
                )
                secondaryPacks = secondaryPacks + p
                io.github.sillypuppyy.dogboard.perf.EventLog.add("second language ${p.tag} loaded after ${android.os.SystemClock.uptimeMillis() - t0} ms")
            }.onFailure { io.github.sillypuppyy.dogboard.perf.EventLog.add("second language ${p.tag} failed: ${it.javaClass.simpleName}") }
            nativeMark("second language")
        }
        return true
    }

    /**
     * A pack that was just updated ([tag]): loaded again when the engine has it, as the first language or one typed
     * together with it (the old files are gone: its snapshots are keyed by the manifest's hashes). True if it was loaded.
     */
    @Synchronized
    fun reloadIfLoaded(context: Context, tag: String): Boolean {
        val app = context.applicationContext
        if (secondaryPacks.any { it.tag == tag }) {
            val tags = secondaryPacks.map { it.tag }
            secondaryPacks = emptyList()
            TypingEngine.unloadSecondary()
            setSecondaries(app, tags)
            return true
        }
        val current = pack ?: return false
        if (current.tag != tag) return false
        // a built-in pack is replaced only by a downloaded one of its language (English: the built-in has no word
        // statistics or neural model, the download brings them); before, the download waited for a restart
        if (current.bundled && LanguagePack.find(app, tag)?.bundled != false) return false
        io.github.sillypuppyy.dogboard.perf.EventLog.add("language ${current.tag}: updated pack, loading again")
        // the languages typed together stay loaded (unload leaves them; the first's autocorrect gets their lists again)
        TypingEngine.unload()
        TypingEngine.attachNgrams(null); TypingEngine.attachNeuralLm(null); TypingEngine.attachSwipeNet(null); TypingEngine.attachCharModel(null)
        pack = LanguagePack.find(app, tag)
        System.gc()
        loadOrFallBack(app)
        return true
    }

    /**
     * Everything loaded again from the packs on the phone (Advanced › Reload the keyboard): the first language and those
     * typed together with it. For an engine left empty (a reload after a pack update that failed: no suggestions, no
     * autocorrect until the app restarted). Off the main thread.
     */
    @Synchronized
    fun reloadAll(context: Context) {
        val app = context.applicationContext
        val tag = pack?.tag
        val together = secondaryPacks.map { it.tag }
        io.github.sillypuppyy.dogboard.perf.EventLog.add("reload all: ${tag ?: "none"}${if (together.isEmpty()) "" else " + " + together.joinToString()}")
        secondaryPacks = emptyList()
        TypingEngine.unloadSecondary()
        TypingEngine.unload()
        TypingEngine.attachNgrams(null); TypingEngine.attachNeuralLm(null); TypingEngine.attachSwipeNet(null); TypingEngine.attachCharModel(null)
        pack = tag?.let { LanguagePack.find(app, it) }
        System.gc()
        loadOrFallBack(app)
        if (together.isNotEmpty()) setSecondaries(app, together)
        io.github.sillypuppyy.dogboard.perf.EventLog.add("reload all: engine ${if (TypingEngine.isReady) "ready" else "NOT READY"}")
    }

    /**
     * [ensureLoaded], and when it throws (a pack missing a part, memory) the built-in pack of the same language, then the
     * default: an engine left empty gave no suggestions and no autocorrect until the app restarted (user 2026-11-24:
     * autocorrect stopped after a pack update and stayed off).
     */
    private fun loadOrFallBack(app: Context) {
        val tried = pack
        runCatching { ensureLoaded(app) }.onSuccess { return }.onFailure {
            io.github.sillypuppyy.dogboard.perf.EventLog.add("language ${tried?.tag} failed to load: ${it.javaClass.simpleName} ${it.message}")
        }
        TypingEngine.unload()
        val builtIn = tried?.let { t -> LanguagePack.all(app).firstOrNull { it.bundled && it.tag == t.tag && it.hasWords && it !== t } }
        pack = builtIn
        runCatching { ensureLoaded(app) }.onSuccess {
            io.github.sillypuppyy.dogboard.perf.EventLog.add("language ${pack?.tag}: loaded ${if (builtIn != null) "its built-in pack" else "the default"} instead")
        }.onFailure { io.github.sillypuppyy.dogboard.perf.EventLog.add("fallback failed too: ${it.javaClass.simpleName} ${it.message}") }
    }

    /**
     * An empty engine (a load that failed earlier) loaded again when a text field opens, so typing heals itself without
     * restarting the app. Cheap when loaded. Off the main thread.
     */
    /** The start-up load has run (done or failed): healing waits for it, so the two never load at once. */
    @Volatile var firstLoadDone = false

    @Synchronized
    fun heal(context: Context) {
        if (TypingEngine.isReady) return
        io.github.sillypuppyy.dogboard.perf.EventLog.add("engine empty at a text field: loading again")
        loadOrFallBack(context.applicationContext)
    }

    /** Before the first load: start with [tag]'s pack when one with a dictionary exists (else the default, English). */
    @Synchronized
    fun prefer(context: Context, tag: String) {
        if (pack != null || TypingEngine.isReady) return
        LanguagePack.find(context.applicationContext, tag)?.takeIf { it.hasWords }?.let { pack = it }
    }

    /** Where a pack's parsed word data lives: English where it always has (no re-parse on update), others by tag. */
    private fun dataDir(app: Context, p: LanguagePack): File =
        if (p.tag == LanguagePack.DEFAULT_TAG) File(app.noBackupFilesDir, "dogboard-data") else File(app.noBackupFilesDir, "dogboard-data/packs/${p.tag}")

    /**
     * A word-data snapshot built with the app (tools: SnapshotExportTest), gunzipped as it streams, or null when the
     * app has none for this pack's word lists: its name carries the first 8 hex of the lexicon's and the bigrams'
     * SHA-256 from the pack manifest, so a snapshot left over from older word lists is never used.
     */
    private fun shippedSnapshot(app: Context, p: LanguagePack, name: String): java.io.InputStream? = runCatching {
        // a downloaded pack brings its own (snapshot_lexicon, snapshot_bigrams: built with the pack, nothing parsed on
        // the phone; parsing Spanish's 666k words ran a 192 MB heap out of memory)
        if (!p.bundled) return p.open("snapshot_$name")?.let { java.util.zip.GZIPInputStream(it, 64 * 1024) }
        val key = listOf("lexicon", "bigrams").joinToString("") { p.manifest.files[it]?.sha256?.take(8).orEmpty() }
        if (key.length != 16) return null
        java.util.zip.GZIPInputStream(app.assets.open("dogboard/snapshot/${p.tag}-$name-$key.dgz"), 64 * 1024)
    }.getOrNull()

    /**
     * The key of this install's cached data files (an update makes new ones); a downloaded pack's also carries its
     * snapshots' hashes, so a pack updated without an app update is unpacked again (it kept the old dictionary).
     */
    private fun snapshotKey(app: Context, p: LanguagePack): String =
        app.packageManager.getPackageInfo(app.packageName, 0).let { "${it.longVersionCode}-${it.lastUpdateTime}" } +
            if (p.bundled) "" else "-" + listOf("snapshot_lexicon", "snapshot_bigrams", "snapshot_ngramindex")
                .joinToString("") { p.manifest.files[it]?.sha256?.take(6).orEmpty() }

    private fun loadNgrams(app: Context, p: LanguagePack = pack(app)): io.github.sillypuppyy.dogboard.engine.NgramModel? = runCatching {
        val buffer = p.map("ngrams") ?: return null
        run {
                val t0 = android.os.SystemClock.uptimeMillis()
                // the vocabulary index (~7 MB built on the heap) mapped from a file written on the first start, kept
                // beside the lexicon snapshot (same key: an app update builds it again)
                val indexFile = File(dataDir(app, p), "ngramindex-${snapshotKey(app, p)}.bin")
                // a first start: the index built with the app, when it matches this trigram file (as the word data)
                // a downloaded pack brings its own (snapshot_ngramindex)
                if (!indexFile.isFile && !p.bundled) runCatching {
                    p.open("snapshot_ngramindex")?.let { java.util.zip.GZIPInputStream(it, 64 * 1024) }?.use { s ->
                        indexFile.parentFile?.mkdirs()
                        val tmp = File(indexFile.path + ".tmp")
                        tmp.outputStream().use { s.copyTo(it, 64 * 1024) }
                        tmp.renameTo(indexFile)
                    }
                }
                if (!indexFile.isFile && p.bundled) runCatching {
                    val sha = p.manifest.files["ngrams"]?.sha256?.take(8)?.takeIf { it.length == 8 } ?: return@runCatching
                    java.util.zip.GZIPInputStream(app.assets.open("dogboard/snapshot/${p.tag}-ngramindex-$sha.dgz"), 64 * 1024).use { s ->
                        indexFile.parentFile?.mkdirs()
                        val tmp = File(indexFile.path + ".tmp")
                        tmp.outputStream().use { s.copyTo(it, 64 * 1024) }
                        tmp.renameTo(indexFile)
                    }
                }
                val index = indexFile.takeIf { it.isFile }?.let { f ->
                    runCatching { java.io.RandomAccessFile(f, "r").channel.use { c -> c.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, c.size()) } }.getOrNull()
                }
                io.github.sillypuppyy.dogboard.engine.NgramModel.fromBuffer(buffer, index).also { m ->
                    io.github.sillypuppyy.dogboard.perf.EventLog.add("trigram model: ${m?.pairCount} pairs, ${m?.tripleCount} triples, " +
                        "${android.os.SystemClock.uptimeMillis() - t0} ms, index ${if (index != null) "mapped" else "built"}")
                    // the next start maps it (written atomically: a half-written file is never read)
                    if (m != null && index == null) runCatching {
                        indexFile.parentFile?.mkdirs()
                        val tmp = File(indexFile.path + ".tmp")
                        tmp.outputStream().use { m.writeVocabIndex(it) }
                        tmp.renameTo(indexFile)
                    }
                }
        }
    }.getOrNull()

    private fun loadNeuralLm(app: Context): io.github.sillypuppyy.dogboard.engine.lm.NeuralLm? = runCatching {
        val t0 = android.os.SystemClock.uptimeMillis()
        val p = pack(app)
        val tokenizer = (p.open("lm_pieces") ?: return null).bufferedReader().use {
            io.github.sillypuppyy.dogboard.engine.lm.PieceTokenizer.parse(it)
        }
        io.github.sillypuppyy.dogboard.engine.lm.NeuralLm(p.map("lm_model") ?: return null, tokenizer).also { io.github.sillypuppyy.dogboard.perf.EventLog.add("neural model: ${android.os.SystemClock.uptimeMillis() - t0} ms") }
    }.onFailure { io.github.sillypuppyy.dogboard.perf.EventLog.add("neural model not loaded: ${it.javaClass.simpleName} ${it.message}") }.getOrNull()

    /**
     * Whether this phone runs the neural model: not a low-RAM device, and more than
     * [NEURAL_MIN_RAM_BYTES] of memory (phones sold as 6 GB report about 5.5 GB: they run it; 4 GB
     * phones and below keep the trigram).
     */
    /** Phones whose app heap is this small (2 GB devices) hold back the trigram on a first start. */
    private const val LOW_HEAP_MB = 128

    /** Languages typed at once, the keyboard's own included (Gboard: 3; M4 measured what a third costs). */
    const val MAX_TOGETHER = 3

    private fun heapMb(context: Context): Int =
        context.getSystemService(android.app.ActivityManager::class.java)?.memoryClass ?: Int.MAX_VALUE

    fun neuralTier(context: Context): Boolean {
        val prefs by dev.patrickgold.florisboard.app.FlorisPreferenceStore
        if (!prefs.correction.neuralModel.get()) return false // Advanced > Neural model
        // debug and benchmark builds: `adb shell setprop debug.dogboard.neural 1` runs it on any device (emulator tests)
        if ((dev.patrickgold.florisboard.BuildConfig.DEBUG || dev.patrickgold.florisboard.BuildConfig.APPLICATION_ID.endsWith(".bench")) && runCatching {
                Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.dogboard.neural") == "1"
            }.getOrDefault(false)) return true
        val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return false
        if (am.isLowRamDevice) return false
        val info = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return info.totalMem > NEURAL_MIN_RAM_BYTES
    }

    private const val NEURAL_MIN_RAM_BYTES = 5_000_000_000L

    /** The neural model again after a memory-pressure release, when this phone runs it (call off the main thread). */
    /** When memory pressure last released a model (uptime ms), or 0. */
    @Volatile private var pressureAt = 0L

    /** Memory pressure released a model: it stays released for [PRESSURE_HOLD_MS] (FlorisImeService.onTrimMemory). */
    fun notePressure() { pressureAt = android.os.SystemClock.uptimeMillis() }

    /**
     * Whether a released model may come back: not within [PRESSURE_HOLD_MS] of the pressure, nor while the system
     * reports low memory. A Galaxy A10 released both models during the load, then reloaded them at once on the next
     * show, twice in parallel, and ran out of memory (Test Lab 2026-11-21).
     */
    private fun mayReload(context: Context): Boolean {
        if (pressureAt != 0L && android.os.SystemClock.uptimeMillis() - pressureAt < PRESSURE_HOLD_MS) return false
        val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return true
        return !android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.lowMemory
    }

    private const val PRESSURE_HOLD_MS = 120_000L

    // one load at a time: two shows in a row started two (each ~50 MB with its buffers)
    @Synchronized
    fun ensureNeural(context: Context) {
        if (!TypingEngine.isReady || TypingEngine.hasNeuralLm || !neuralTier(context) || !mayReload(context)) return
        TypingEngine.attachNeuralLm(loadNeuralLm(context.applicationContext))
    }

    private fun loadSwipeNet(app: Context): io.github.sillypuppyy.dogboard.engine.swipe.SwipeNet? = runCatching {
        io.github.sillypuppyy.dogboard.engine.swipe.SwipeNet(pack(app).map("swipe_qwerty") ?: error("no swipe model"), threads = 1)
    }.onFailure { io.github.sillypuppyy.dogboard.perf.EventLog.add("swipe recogniser not loaded: ${it.javaClass.simpleName} ${it.message}") }.getOrNull()

    /** The swipe recogniser again after a memory-pressure release (call off the main thread). */
    @Synchronized
    /** The universal swipe model, built into the app (assets/dogboard/swipe/uni.tflite, stored uncompressed: mapped). */
    private fun loadUniSwipeNet(app: Context): io.github.sillypuppyy.dogboard.engine.swipe.UniSwipeNet? = runCatching {
        val buffer = app.assets.openFd("dogboard/swipe/uni.tflite").use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).channel.use { ch -> ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length) }
        }
        io.github.sillypuppyy.dogboard.engine.swipe.UniSwipeNet(buffer, threads = 1)
    }.onFailure { io.github.sillypuppyy.dogboard.perf.EventLog.add("universal swipe model not loaded: ${it.javaClass.simpleName} ${it.message}") }.getOrNull()

    fun ensureSwipe(context: Context) {
        if (TypingEngine.isReady && !TypingEngine.hasUniSwipeNet && mayReload(context)) TypingEngine.attachUniSwipeNet(loadUniSwipeNet(context.applicationContext))
        if (!TypingEngine.isReady || TypingEngine.hasSwipeNet || !mayReload(context)) return
        TypingEngine.attachSwipeNet(loadSwipeNet(context.applicationContext))
        io.github.sillypuppyy.dogboard.perf.EventLog.add("swipe recogniser loaded again")
    }

    private fun nativeMark(what: String) {
        val rt = Runtime.getRuntime()
        val used = (rt.totalMemory() - rt.freeMemory()) / 1048576
        // debug builds: what is live, after a collection (the used heap includes garbage: the phone's "java 200 MB"
        // after the engine load was mostly the parse's, 2026-11-23); release builds skip the collection's time
        val live = if (dev.patrickgold.florisboard.BuildConfig.DEBUG) { rt.gc(); ", live ${(rt.totalMemory() - rt.freeMemory()) / 1048576} MB" } else ""
        android.util.Log.i("DogBoardPerf", "memory after $what: native ${android.os.Debug.getNativeHeapAllocatedSize() / 1048576} MB, java $used MB$live")
    }

    // one load at a time, and a language switch waits for it: a switch during the first load attached English's
    // trigram to Spanish and held both languages in memory (emulator OOM at 192 MB, 2026-11-22)
    @Synchronized
    /**
     * A language's own learned data (words learned from typing, the personal word-pair model, corrections turned down),
     * as iOS and Gboard keep them: one shared list put a language's words into the others' suggestions. The shared file
     * from before (2026-11-26) seeds each language once with its lines in the language's own scripts (a Bulgarian
     * keyboard gets the Cyrillic words, a German one the Latin ones) and stays as it was. Explicitly added words, blocked
     * words, text replacements and the touch calibration stay shared.
     */
    private fun languageFile(files: File, tag: String, name: String, scripts: Set<Character.UnicodeScript>): File {
        val f = File(files, "dogboard/lang/$tag/$name")
        val legacy = File(files, "dogboard/$name")
        if (!f.exists() && legacy.exists()) runCatching {
            f.parentFile?.mkdirs()
            // personal.tsv's lines start with a kind tag ("w", "p", "t" and a tab) before their words, and its "#" line
            // holds the typed-word count: judged by the words, the header always kept (by the tag every line looked Latin)
            fun words(line: String) = if (name == "personal.tsv") line.substringAfter('\t') else line
            val kept = legacy.readLines().filter { line ->
                line.startsWith("#") || words(line).firstOrNull { it.isLetter() }?.let { Character.UnicodeScript.of(it.code) in scripts } == true
            }
            f.writeText(kept.joinToString("") { it + "\n" })
        }
        return f
    }

    /** The learned data's file names kept per language ([languageFile]). */
    private val PER_LANGUAGE = listOf("user_words.txt", "personal.tsv", "rejected_corrections.txt")

    /**
     * "Forget everything DogBoard learned" means every language's: their own files and the shared one from before (it
     * would seed a language loaded later with the forgotten words). The loaded language's go with
     * TypingEngine.resetDictionary.
     */
    fun forgetLearnedEverywhere(context: Context) {
        val files = context.applicationContext.filesDir
        runCatching { File(files, "dogboard/lang").deleteRecursively() }
        for (name in PER_LANGUAGE) runCatching { File(files, "dogboard/$name").delete() }
    }

    /** A word forgotten in one language goes from the others' learned words too (and from the shared file). */
    private fun forgetWordEverywhere(files: File, word: String) {
        val key = word.lowercase()
        val lists = (File(files, "dogboard/lang").listFiles().orEmpty().map { File(it, "user_words.txt") } + File(files, "dogboard/user_words.txt"))
        for (f in lists) runCatching {
            if (f.exists()) f.writeText(f.readLines().filter { it.isNotBlank() && it.trim().lowercase() != key }.joinToString("") { it + "\n" })
        }
    }

    fun ensureLoaded(context: Context) {
        if (TypingEngine.isReady) return
        nativeMark("start")
        val t0 = android.os.SystemClock.uptimeMillis()
        val app = context.applicationContext
        val files = app.filesDir
        val p = pack(app)
        fun asset(component: String) = p.open(component) ?: error("${p.tag}: no $component")
        // optional parts a new language may not have yet (abbreviations, emoji, misspellings): read as empty
        fun optional(component: String) = (p.open(component) ?: java.io.ByteArrayInputStream(ByteArray(0)))
        val rules = runCatching { p.open("rules")?.bufferedReader()?.use { io.github.sillypuppyy.dogboard.engine.LanguageRules.parse(it.readText()) } }.getOrNull()
            ?: io.github.sillypuppyy.dogboard.engine.LanguageRules()
        // the language's letters of their own (å ä ö, й; rules distinct_letters) stay distinct in the trie: set before
        // the lexicon is built (English and Spanish: none)
        io.github.sillypuppyy.dogboard.engine.Lexicon.distinctLetters = rules.distinctLetters.toSet()
        io.github.sillypuppyy.dogboard.engine.Lexicon.keyOf = rules.keyOfLetters
        // the language's casing (Turkish İ): automatic capitals follow it
        io.github.sillypuppyy.dogboard.engine.CaseAdapt.locale = java.util.Locale.forLanguageTag(p.tag)
        TypingEngine.onForgetWord = { w -> forgetWordEverywhere(files, w) }
        val scripts = rules.letterRows.rows.flatMap { it.asIterable() }.filter { it.isLetter() }
            .map { Character.UnicodeScript.of(it.code) }.toSet().ifEmpty { setOf(Character.UnicodeScript.LATIN) }
        TypingEngine.load(
            openLexicon = { asset("lexicon").bufferedReader() },
            openBigrams = { asset("bigrams").bufferedReader() },
            userWords = languageFile(files, p.tag, "user_words.txt", scripts),
            blockedWords = File(files, "dogboard/blocked_words.txt"),
            rejectedCorrections = languageFile(files, p.tag, "rejected_corrections.txt", scripts),
            autocorrectStrength = File(files, "dogboard/autocorrect_strength.txt"),
            personalOffsetsFile = File(files, "dogboard/personal_offsets.txt"),
            openAbbreviations = { optional("abbreviations").bufferedReader() },
            openNoSuggest = { optional("nosuggest").bufferedReader() },
            personalModel = languageFile(files, p.tag, "personal.tsv", scripts),
            textReplacements = File(files, "dogboard/text_replacements.tsv"),
            openEmojiIndex = { optional("emoji").bufferedReader() },
            openEmojiTones = { optional("emoji_tones").bufferedReader() },
            emojiUsageFile = File(files, "dogboard/emoji_usage.tsv"),
            addedWords = File(files, "dogboard/added_words.txt"),
            openMisspellings = { optional("misspellings").bufferedReader() },
            // parsed word data, rebuilt when the app (and so its assets) changes
            snapshotDir = dataDir(app, p),
            snapshotKey = snapshotKey(app, p),
            openShipped = { name -> shippedSnapshot(app, p, name) },
            rules = rules,
        )
        io.github.sillypuppyy.dogboard.perf.EventLog.add("engine ready (suggestions on) after ${android.os.SystemClock.uptimeMillis() - t0} ms")
        nativeMark("engine")
        // the trigram model (when built into this app), attached after the engine is ready so
        // startup isn't slowed: memory-mapped from the package, which stores it uncompressed
        // (build.gradle.kts noCompress), so nothing is copied to the heap
        // a first start parses the word lists, the heap's peak: on a 128 MB heap (2 GB phones) that and the trigram
        // index built on top ran out of memory (Test Lab Galaxy A10, 2026-11-20). The trigram (the smallest loss:
        // -0.5 fixed, T7; the neural model or the n-grams cover context) waits for the next start, which maps it
        if (TypingEngine.parsedAtLoad && heapMb(app) <= LOW_HEAP_MB) io.github.sillypuppyy.dogboard.perf.EventLog.add("trigram held back (first start on a ${heapMb(app)} MB heap)")
        else TypingEngine.attachNgrams(loadNgrams(app))
        nativeMark("trigram")
        // the neural language model for next-word predictions, when its files are in this build
        // (assets/dogboard/lm: built privately, not in git yet): memory-mapped like the trigram.
        // Only on phones with room for it (iOS runs its transformer only where the Neural Engine is,
        // older devices use the n-gram model): ~50 MB (mapped weights + the runtime's copy) and most
        // of the typing path's CPU; without it the trigram alone predicts and scores
        if (neuralTier(app)) TypingEngine.attachNeuralLm(loadNeuralLm(app))
        else io.github.sillypuppyy.dogboard.perf.EventLog.add("neural model skipped on this device (memory)")
        nativeMark("neural")
        // the swipe recogniser (4.4 MB, memory-mapped like the trigram; every phone: ~2 ms of work per
        // swipe on a desktop core, run only when a swipe ends)
        TypingEngine.attachSwipeNet(loadSwipeNet(app))
        TypingEngine.attachUniSwipeNet(loadUniSwipeNet(app))
        nativeMark("swipe")
        // the unknown-word letter model, also after (a bulk read of its binary form)
        val t1 = android.os.SystemClock.uptimeMillis()
        TypingEngine.attachCharModel(runCatching { p.open("charlm")?.use { CharModel.read(it) } }.getOrNull())
        io.github.sillypuppyy.dogboard.perf.EventLog.add("letter model attached after ${android.os.SystemClock.uptimeMillis() - t1} ms")
        nativeMark("letter model")
    }
}

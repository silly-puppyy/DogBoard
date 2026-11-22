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
        val next = LanguagePack.find(app, tag)?.takeIf { it.has("lexicon") } ?: return false
        if (next.tag == current?.tag) return false
        android.util.Log.i("DogBoardPerf", "language: ${current?.tag} -> ${next.tag}")
        TypingEngine.unload()
        TypingEngine.attachNgrams(null); TypingEngine.attachNeuralLm(null); TypingEngine.attachSwipeNet(null); TypingEngine.attachCharModel(null)
        pack = next
        System.gc() // the old language's words go before the new one's are parsed (its first start parses the word lists)
        ensureLoaded(app)
        return true
    }

    /** Before the first load: start with [tag]'s pack when one with a dictionary exists (else the default, English). */
    @Synchronized
    fun prefer(context: Context, tag: String) {
        if (pack != null || TypingEngine.isReady) return
        LanguagePack.find(context.applicationContext, tag)?.takeIf { it.has("lexicon") }?.let { pack = it }
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

    /** The key of this install's cached data files (an update makes new ones). */
    private fun snapshotKey(app: Context): String =
        app.packageManager.getPackageInfo(app.packageName, 0).let { "${it.longVersionCode}-${it.lastUpdateTime}" }

    private fun loadNgrams(app: Context): io.github.sillypuppyy.dogboard.engine.NgramModel? = runCatching {
        val p = pack(app)
        val buffer = p.map("ngrams") ?: return null
        run {
                val t0 = android.os.SystemClock.uptimeMillis()
                // the vocabulary index (~7 MB built on the heap) mapped from a file written on the first start, kept
                // beside the lexicon snapshot (same key: an app update builds it again)
                val indexFile = File(dataDir(app, p), "ngramindex-${snapshotKey(app)}.bin")
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
                    val sha = pack(app).manifest.files["ngrams"]?.sha256?.take(8)?.takeIf { it.length == 8 } ?: return@runCatching
                    java.util.zip.GZIPInputStream(app.assets.open("dogboard/snapshot/${pack(app).tag}-ngramindex-$sha.dgz"), 64 * 1024).use { s ->
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
                    android.util.Log.i("DogBoardPerf", "trigram model: ${m?.pairCount} pairs, ${m?.tripleCount} triples, " +
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
        io.github.sillypuppyy.dogboard.engine.lm.NeuralLm(p.map("lm_model") ?: return null, tokenizer).also { android.util.Log.i("DogBoardPerf", "neural model: ${android.os.SystemClock.uptimeMillis() - t0} ms") }
    }.onFailure { android.util.Log.i("DogBoardPerf", "neural model not loaded: ${it.javaClass.simpleName} ${it.message}") }.getOrNull()

    /**
     * Whether this phone runs the neural model: not a low-RAM device, and more than
     * [NEURAL_MIN_RAM_BYTES] of memory (phones sold as 6 GB report about 5.5 GB: they run it; 4 GB
     * phones and below keep the trigram).
     */
    /** Phones whose app heap is this small (2 GB devices) hold back the trigram on a first start. */
    private const val LOW_HEAP_MB = 128

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
    }.onFailure { android.util.Log.i("DogBoardPerf", "swipe recogniser not loaded: ${it.javaClass.simpleName} ${it.message}") }.getOrNull()

    /** The swipe recogniser again after a memory-pressure release (call off the main thread). */
    @Synchronized
    fun ensureSwipe(context: Context) {
        if (!TypingEngine.isReady || TypingEngine.hasSwipeNet || !mayReload(context)) return
        TypingEngine.attachSwipeNet(loadSwipeNet(context.applicationContext))
        android.util.Log.i("DogBoardPerf", "swipe recogniser loaded again")
    }

    private fun nativeMark(what: String) =
        android.util.Log.i("DogBoardPerf", "memory after $what: native ${android.os.Debug.getNativeHeapAllocatedSize() / 1048576} MB, " +
            "java ${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576} MB")

    // one load at a time, and a language switch waits for it: a switch during the first load attached English's
    // trigram to Spanish and held both languages in memory (emulator OOM at 192 MB, 2026-11-22)
    @Synchronized
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
        TypingEngine.load(
            openLexicon = { asset("lexicon").bufferedReader() },
            openBigrams = { asset("bigrams").bufferedReader() },
            userWords = File(files, "dogboard/user_words.txt"),
            blockedWords = File(files, "dogboard/blocked_words.txt"),
            rejectedCorrections = File(files, "dogboard/rejected_corrections.txt"),
            autocorrectStrength = File(files, "dogboard/autocorrect_strength.txt"),
            personalOffsetsFile = File(files, "dogboard/personal_offsets.txt"),
            openAbbreviations = { optional("abbreviations").bufferedReader() },
            openNoSuggest = { optional("nosuggest").bufferedReader() },
            personalModel = File(files, "dogboard/personal.tsv"),
            textReplacements = File(files, "dogboard/text_replacements.tsv"),
            openEmojiIndex = { optional("emoji").bufferedReader() },
            openEmojiTones = { optional("emoji_tones").bufferedReader() },
            emojiUsageFile = File(files, "dogboard/emoji_usage.tsv"),
            addedWords = File(files, "dogboard/added_words.txt"),
            openMisspellings = { optional("misspellings").bufferedReader() },
            // parsed word data, rebuilt when the app (and so its assets) changes
            snapshotDir = dataDir(app, p),
            snapshotKey = snapshotKey(app),
            openShipped = { name -> shippedSnapshot(app, p, name) },
            rules = rules,
        )
        android.util.Log.i("DogBoardPerf", "engine ready (suggestions on) after ${android.os.SystemClock.uptimeMillis() - t0} ms")
        nativeMark("engine")
        // the trigram model (when built into this app), attached after the engine is ready so
        // startup isn't slowed: memory-mapped from the package, which stores it uncompressed
        // (build.gradle.kts noCompress), so nothing is copied to the heap
        // a first start parses the word lists, the heap's peak: on a 128 MB heap (2 GB phones) that and the trigram
        // index built on top ran out of memory (Test Lab Galaxy A10, 2026-11-20). The trigram (the smallest loss:
        // -0.5 fixed, T7; the neural model or the n-grams cover context) waits for the next start, which maps it
        if (TypingEngine.parsedAtLoad && heapMb(app) <= LOW_HEAP_MB) android.util.Log.i("DogBoardPerf", "trigram held back (first start on a ${heapMb(app)} MB heap)")
        else TypingEngine.attachNgrams(loadNgrams(app))
        nativeMark("trigram")
        // the neural language model for next-word predictions, when its files are in this build
        // (assets/dogboard/lm: built privately, not in git yet): memory-mapped like the trigram.
        // Only on phones with room for it (iOS runs its transformer only where the Neural Engine is,
        // older devices use the n-gram model): ~50 MB (mapped weights + the runtime's copy) and most
        // of the typing path's CPU; without it the trigram alone predicts and scores
        if (neuralTier(app)) TypingEngine.attachNeuralLm(loadNeuralLm(app))
        else android.util.Log.i("DogBoardPerf", "neural model skipped on this device (memory)")
        nativeMark("neural")
        // the swipe recogniser (4.4 MB, memory-mapped like the trigram; every phone: ~2 ms of work per
        // swipe on a desktop core, run only when a swipe ends)
        TypingEngine.attachSwipeNet(loadSwipeNet(app))
        nativeMark("swipe")
        // the unknown-word letter model, also after (a bulk read of its binary form)
        val t1 = android.os.SystemClock.uptimeMillis()
        TypingEngine.attachCharModel(runCatching { p.open("charlm")?.use { CharModel.read(it) } }.getOrNull())
        android.util.Log.i("DogBoardPerf", "letter model attached after ${android.os.SystemClock.uptimeMillis() - t1} ms")
        nativeMark("letter model")
    }
}

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

    /** The key of this install's cached data files (an update makes new ones). */
    private fun snapshotKey(app: Context): String =
        app.packageManager.getPackageInfo(app.packageName, 0).let { "${it.longVersionCode}-${it.lastUpdateTime}" }

    private fun loadNgrams(app: Context): io.github.sillypuppyy.dogboard.engine.NgramModel? = runCatching {
        app.assets.openFd(pack(app).path("ngrams") ?: return null).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).channel.use { ch ->
                val t0 = android.os.SystemClock.uptimeMillis()
                // the vocabulary index (~7 MB built on the heap) mapped from a file written on the first start, kept
                // beside the lexicon snapshot (same key: an app update builds it again)
                val indexFile = File(File(app.noBackupFilesDir, "dogboard-data"), "ngramindex-${snapshotKey(app)}.bin")
                val index = indexFile.takeIf { it.isFile }?.let { f ->
                    runCatching { java.io.RandomAccessFile(f, "r").channel.use { c -> c.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, c.size()) } }.getOrNull()
                }
                io.github.sillypuppyy.dogboard.engine.NgramModel.fromBuffer(
                    ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length), index,
                ).also { m ->
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
        }
    }.getOrNull()

    private fun loadNeuralLm(app: Context): io.github.sillypuppyy.dogboard.engine.lm.NeuralLm? = runCatching {
        val t0 = android.os.SystemClock.uptimeMillis()
        val p = pack(app)
        val pieces = p.path("lm_pieces") ?: return null
        val model = p.path("lm_model") ?: return null
        val tokenizer = app.assets.open(pieces).bufferedReader().use {
            io.github.sillypuppyy.dogboard.engine.lm.PieceTokenizer.parse(it)
        }
        app.assets.openFd(model).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).channel.use { ch ->
                io.github.sillypuppyy.dogboard.engine.lm.NeuralLm(
                    ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length), tokenizer,
                )
            }
        }.also { android.util.Log.i("DogBoardPerf", "neural model: ${android.os.SystemClock.uptimeMillis() - t0} ms") }
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
    fun ensureNeural(context: Context) {
        if (!TypingEngine.isReady || TypingEngine.hasNeuralLm || !neuralTier(context)) return
        TypingEngine.attachNeuralLm(loadNeuralLm(context.applicationContext))
    }

    private fun loadSwipeNet(app: Context): io.github.sillypuppyy.dogboard.engine.swipe.SwipeNet? = runCatching {
        app.assets.openFd(pack(app).path("swipe_qwerty") ?: error("no swipe model")).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).channel.use { ch ->
                io.github.sillypuppyy.dogboard.engine.swipe.SwipeNet(
                    ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length), threads = 1,
                )
            }
        }
    }.onFailure { android.util.Log.i("DogBoardPerf", "swipe recogniser not loaded: ${it.javaClass.simpleName} ${it.message}") }.getOrNull()

    /** The swipe recogniser again after a memory-pressure release (call off the main thread). */
    fun ensureSwipe(context: Context) {
        if (!TypingEngine.isReady || TypingEngine.hasSwipeNet) return
        TypingEngine.attachSwipeNet(loadSwipeNet(context.applicationContext))
        android.util.Log.i("DogBoardPerf", "swipe recogniser loaded again")
    }

    private fun nativeMark(what: String) =
        android.util.Log.i("DogBoardPerf", "memory after $what: native ${android.os.Debug.getNativeHeapAllocatedSize() / 1048576} MB, " +
            "java ${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576} MB")

    fun ensureLoaded(context: Context) {
        if (TypingEngine.isReady) return
        nativeMark("start")
        val t0 = android.os.SystemClock.uptimeMillis()
        val app = context.applicationContext
        val assets = app.assets
        val files = app.filesDir
        val p = pack(app)
        fun asset(component: String) = p.path(component) ?: error("${p.tag}: no $component")
        TypingEngine.load(
            openLexicon = { assets.open(asset("lexicon")).bufferedReader() },
            openBigrams = { assets.open(asset("bigrams")).bufferedReader() },
            userWords = File(files, "dogboard/user_words.txt"),
            blockedWords = File(files, "dogboard/blocked_words.txt"),
            rejectedCorrections = File(files, "dogboard/rejected_corrections.txt"),
            autocorrectStrength = File(files, "dogboard/autocorrect_strength.txt"),
            personalOffsetsFile = File(files, "dogboard/personal_offsets.txt"),
            openAbbreviations = { assets.open(asset("abbreviations")).bufferedReader() },
            openNoSuggest = { assets.open(asset("nosuggest")).bufferedReader() },
            personalModel = File(files, "dogboard/personal.tsv"),
            textReplacements = File(files, "dogboard/text_replacements.tsv"),
            openEmojiIndex = { assets.open(asset("emoji")).bufferedReader() },
            openEmojiTones = { assets.open(asset("emoji_tones")).bufferedReader() },
            emojiUsageFile = File(files, "dogboard/emoji_usage.tsv"),
            addedWords = File(files, "dogboard/added_words.txt"),
            openMisspellings = { assets.open(asset("misspellings")).bufferedReader() },
            // parsed word data, rebuilt when the app (and so its assets) changes
            snapshotDir = File(app.noBackupFilesDir, "dogboard-data"),
            snapshotKey = snapshotKey(app),
            rules = p.path("rules")?.let { r -> runCatching { io.github.sillypuppyy.dogboard.engine.LanguageRules.parse(assets.open(r).bufferedReader().use { it.readText() }) }.getOrNull() }
                ?: io.github.sillypuppyy.dogboard.engine.LanguageRules(),
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
        TypingEngine.attachCharModel(runCatching { assets.open(asset("charlm")).use { CharModel.read(it) } }.getOrNull())
        android.util.Log.i("DogBoardPerf", "letter model attached after ${android.os.SystemClock.uptimeMillis() - t1} ms")
        nativeMark("letter model")
    }
}

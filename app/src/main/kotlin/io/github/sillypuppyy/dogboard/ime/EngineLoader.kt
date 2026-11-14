package io.github.sillypuppyy.dogboard.ime

import android.content.Context
import io.github.sillypuppyy.dogboard.engine.TypingEngine
import java.io.File

/**
 * Loads the word engine with DogBoard's files (assets, the user's learned data, the binary
 * snapshot). Used by the keyboard and by the settings screens, which share one process: whichever
 * comes first loads it. Call off the main thread.
 */
object EngineLoader {
    private fun loadNgrams(app: Context): io.github.sillypuppyy.dogboard.engine.NgramModel? = runCatching {
        app.assets.openFd("dogboard/en.ngrams.bin").use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).channel.use { ch ->
                val t0 = android.os.SystemClock.uptimeMillis()
                io.github.sillypuppyy.dogboard.engine.NgramModel.fromBuffer(
                    ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length),
                ).also { m ->
                    android.util.Log.i("DogBoardPerf", "trigram model: ${m?.pairCount} pairs, ${m?.tripleCount} triples, " +
                        "${android.os.SystemClock.uptimeMillis() - t0} ms")
                }
            }
        }
    }.getOrNull()

    fun ensureLoaded(context: Context) {
        if (TypingEngine.isReady) return
        val t0 = android.os.SystemClock.uptimeMillis()
        val app = context.applicationContext
        val assets = app.assets
        val files = app.filesDir
        TypingEngine.load(
            openLexicon = { assets.open("dogboard/en.lexicon.tsv").bufferedReader() },
            openBigrams = { assets.open("dogboard/en.bigrams.tsv").bufferedReader() },
            userWords = File(files, "dogboard/user_words.txt"),
            blockedWords = File(files, "dogboard/blocked_words.txt"),
            rejectedCorrections = File(files, "dogboard/rejected_corrections.txt"),
            openAbbreviations = { assets.open("dogboard/en.abbreviations.txt").bufferedReader() },
            openNoSuggest = { assets.open("dogboard/en.nosuggest.txt").bufferedReader() },
            personalModel = File(files, "dogboard/personal.tsv"),
            textReplacements = File(files, "dogboard/text_replacements.tsv"),
            openEmojiIndex = { assets.open("dogboard/en.emoji.tsv").bufferedReader() },
            openEmojiTones = { assets.open("dogboard/en.emoji-tones.tsv").bufferedReader() },
            emojiUsageFile = File(files, "dogboard/emoji_usage.tsv"),
            addedWords = File(files, "dogboard/added_words.txt"),
            // parsed word data, rebuilt when the app (and so its assets) changes
            snapshotDir = File(app.noBackupFilesDir, "dogboard-data"),
            snapshotKey = app.packageManager.getPackageInfo(app.packageName, 0).let { "${it.longVersionCode}-${it.lastUpdateTime}" },
        )
        android.util.Log.i("DogBoardPerf", "engine ready (suggestions on) after ${android.os.SystemClock.uptimeMillis() - t0} ms")
        // the trigram model (when built into this app), attached after the engine is ready so
        // startup isn't slowed: memory-mapped from the package, which stores it uncompressed
        // (build.gradle.kts noCompress), so nothing is copied to the heap
        TypingEngine.attachNgrams(loadNgrams(app))
    }
}

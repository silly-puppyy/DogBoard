package io.github.sillypuppyy.dogboard.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldNotBe
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Builds the word-data snapshots the app ships (assets/dogboard/snapshot/, not in git), so a first start copies them
 * instead of parsing the word lists (EngineLoader.shippedSnapshot). Runs only with DOGBOARD_EXPORT_SNAPSHOT=1:
 *
 *     DOGBOARD_EXPORT_SNAPSHOT=1 ./gradlew :app:testDebugUnitTest --rerun
 *
 * Run it again whenever a pack's lexicon or bigrams change: the file names carry their SHA-256 from the manifest,
 * so a stale snapshot is simply not found (the app parses, as before).
 */
class SnapshotExportTest : FunSpec({
    test("export the shipped snapshots").config(enabled = System.getenv("DOGBOARD_EXPORT_SNAPSHOT") == "1") {
        val assets = File("src/main/assets")
        val out = File(assets, "dogboard/snapshot").apply { mkdirs() }
        for (pack in File(assets, "dogboard/packs").listFiles().orEmpty().filter { File(it, "manifest.json").isFile }) {
            val files = kotlinx.serialization.json.Json.parseToJsonElement(File(pack, "manifest.json").readText())
                .let { (it as kotlinx.serialization.json.JsonObject)["files"] as? kotlinx.serialization.json.JsonObject } ?: continue
            fun field(c: String, k: String) = ((files[c] as? kotlinx.serialization.json.JsonObject)?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.content
            val lexPath = field("lexicon", "path") ?: continue
            val bigPath = field("bigrams", "path") ?: continue
            val key = (field("lexicon", "sha256")?.take(8).orEmpty()) + (field("bigrams", "sha256")?.take(8).orEmpty())
            if (key.length != 16) continue
            val tag = pack.name
            out.listFiles()?.filter { it.name.startsWith("$tag-") }?.forEach { it.delete() }
            val lexicon = File(assets, lexPath).bufferedReader().use { Lexicon.parse(it) }
            val bigrams = File(assets, bigPath).bufferedReader().use { Bigrams.parse(it, lexicon) }
            GZIPOutputStream(File(out, "$tag-lexicon-$key.dgz").outputStream().buffered(), 64 * 1024).use { lexicon.writeSnapshot(it) }
            GZIPOutputStream(File(out, "$tag-bigrams-$key.dgz").outputStream().buffered(), 64 * 1024).use { bigrams.writeSnapshot(it) }
            // the trigram model's vocabulary index (when the private trigram file is in this checkout)
            val ngPath = field("ngrams", "path")
            val ngSha = field("ngrams", "sha256")?.take(8)
            val ngFile = ngPath?.let { File(assets, it) }?.takeIf { it.isFile }
            if (ngFile != null && ngSha?.length == 8) {
                val model = java.io.RandomAccessFile(ngFile, "r").channel.use { c -> NgramModel.fromBuffer(c.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, c.size()), null) }
                if (model != null) GZIPOutputStream(File(out, "$tag-ngramindex-$ngSha.dgz").outputStream().buffered(), 64 * 1024).use { model.writeVocabIndex(it) }
            }
            println("SNAPSHOT $tag $key")
            out.listFiles() shouldNotBe null
        }
    }

    // DOGBOARD_EXPORT_PACK_DIR=<dir> DOGBOARD_EXPORT_PACK_LANG=es: a downloadable pack's snapshots (<lang>.lexicon.tsv,
    // <lang>.bigrams.tsv, rules.json in <dir>) as <dir>/<lang>-lexicon.dgz and <lang>-bigrams.dgz, built with the pack's
    // own-key letters (rules letter_rows) as the phone builds the trie
    test("export a pack's snapshots").config(enabled = System.getenv("DOGBOARD_EXPORT_PACK_DIR") != null) {
        val dir = File(System.getenv("DOGBOARD_EXPORT_PACK_DIR"))
        val lang = System.getenv("DOGBOARD_EXPORT_PACK_LANG") ?: dir.name
        val rules = File(dir, "rules.json").takeIf { it.isFile }?.let { LanguageRules.parse(it.readText()) } ?: LanguageRules()
        Lexicon.distinctLetters = rules.distinctLetters.toSet()
        Lexicon.keyOf = rules.keyOfLetters
        try {
            // a Telex-typed language's words along their typed spelling, as the phone builds them (TypingEngine.pathOf)
            val lexicon = File(dir, "$lang.lexicon.tsv").bufferedReader().use { Lexicon.parse(it, TypingEngine.pathOf(rules)) }
            val bigrams = File(dir, "$lang.bigrams.tsv").bufferedReader().use { Bigrams.parse(it, lexicon) }
            GZIPOutputStream(File(dir, "$lang-lexicon.dgz").outputStream().buffered(), 64 * 1024).use { lexicon.writeSnapshot(it) }
            GZIPOutputStream(File(dir, "$lang-bigrams.dgz").outputStream().buffered(), 64 * 1024).use { bigrams.writeSnapshot(it) }
            // the trigram vocabulary's index (snapshot_ngramindex): built on the phone it cost Russian's 442k-word
            // vocabulary ~60 MB of heap (emulator 103 -> 165 MB, 2026-11-22)
            File(dir, "$lang.ngrams.bin").takeIf { it.isFile }?.let { f ->
                val model = java.io.RandomAccessFile(f, "r").channel.use { c -> NgramModel.fromBuffer(c.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, c.size()), null) }
                if (model != null) GZIPOutputStream(File(dir, "$lang-ngramindex.dgz").outputStream().buffered(), 64 * 1024).use { model.writeVocabIndex(it) }
            }
        } finally {
            Lexicon.distinctLetters = emptySet()
            Lexicon.keyOf = emptyMap()
        }
    }
})

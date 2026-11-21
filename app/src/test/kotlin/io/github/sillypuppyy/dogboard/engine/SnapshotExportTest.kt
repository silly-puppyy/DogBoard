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
})

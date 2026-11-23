package io.github.sillypuppyy.dogboard.ime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.security.MessageDigest

/** A pack installs only whole and checked; a bad download leaves the installed pack as it was. */
class PackInstallerTest : FunSpec({
    fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** A pack source: files by path, and a manifest describing [described] (default: the files themselves). */
    fun source(tag: String, version: String, files: Map<String, ByteArray>, described: Map<String, ByteArray> = files, extra: String = ""): Map<String, ByteArray> {
        val entries = described.entries.joinToString(",") { (p, b) -> "\"${p.substringBefore('.')}\": {\"path\": \"$p\", \"size\": ${b.size}, \"sha256\": \"${sha(b)}\"}" }
        val manifest = """{"format": 1, "tag": "$tag", "name": "Test", "script": "Latn", "version": "$version", "files": {$entries$extra}}"""
        return files + ("manifest.json" to manifest.toByteArray())
    }

    fun fetchFrom(src: Map<String, ByteArray>): (String) -> java.io.InputStream = { p -> (src[p] ?: throw java.io.FileNotFoundException(p)).inputStream() }

    fun tmp() = kotlin.io.path.createTempDirectory("packs").toFile()

    test("a good pack installs, and an update replaces it") {
        val dir = tmp()
        try {
            val v1 = mapOf("xx.lexicon.tsv" to "a\t1\n".toByteArray(), "rules.json" to "{}".toByteArray())
            PackInstaller(dir).install(fetchFrom(source("xx", "1", v1))).shouldBeInstanceOf<PackInstaller.Result.Installed>()
            File(dir, "xx/xx.lexicon.tsv").readText() shouldBe "a\t1\n"
            File(dir, "xx/manifest.json").isFile shouldBe true
            val v2 = mapOf("xx.lexicon.tsv" to "b\t2\n".toByteArray(), "rules.json" to "{}".toByteArray())
            val r = PackInstaller(dir).install(fetchFrom(source("xx", "2", v2)))
            (r as PackInstaller.Result.Installed).version shouldBe "2"
            File(dir, "xx/xx.lexicon.tsv").readText() shouldBe "b\t2\n"
            dir.list()!!.sorted() shouldBe listOf("xx") // no staging or old folders left
        } finally { dir.deleteRecursively() }
    }

    test("a wrong checksum, a short file or a missing file fails and keeps the installed pack") {
        val dir = tmp()
        try {
            val good = mapOf("xx.lexicon.tsv" to "a\t1\n".toByteArray())
            PackInstaller(dir).install(fetchFrom(source("xx", "1", good)))
            // same size, other bytes: the checksum catches it
            val tampered = source("xx", "2", mapOf("xx.lexicon.tsv" to "b\t1\n".toByteArray()), described = good)
            PackInstaller(dir).install(fetchFrom(tampered)).shouldBeInstanceOf<PackInstaller.Result.Failed>()
            // shorter than the manifest says
            val short = source("xx", "2", mapOf("xx.lexicon.tsv" to "a\t".toByteArray()), described = good)
            PackInstaller(dir).install(fetchFrom(short)).shouldBeInstanceOf<PackInstaller.Result.Failed>()
            // longer: stopped while streaming
            val long = source("xx", "2", mapOf("xx.lexicon.tsv" to "a\t1\nmore\n".toByteArray()), described = good)
            PackInstaller(dir).install(fetchFrom(long)).shouldBeInstanceOf<PackInstaller.Result.Failed>()
            // a file the manifest lists but the source lacks
            val missing = source("xx", "2", emptyMap(), described = good)
            PackInstaller(dir).install(fetchFrom(missing)).shouldBeInstanceOf<PackInstaller.Result.Failed>()
            File(dir, "xx/xx.lexicon.tsv").readText() shouldBe "a\t1\n"
            dir.list()!!.sorted() shouldBe listOf("xx")
        } finally { dir.deleteRecursively() }
    }

    test("paths and tags that could leave the packs folder are refused") {
        val dir = tmp()
        try {
            val f = mapOf("x.tsv" to "a".toByteArray())
            for (bad in listOf("../evil.tsv", "/etc/evil", "a/../../evil", ".staging-xx/x", "a\\b")) {
                val src = source("xx", "1", f, extra = ", \"evil\": {\"path\": \"${bad.replace("\\", "\\\\")}\", \"size\": 1, \"sha256\": \"${sha("a".toByteArray())}\"}")
                PackInstaller(dir).install(fetchFrom(src + (bad to "a".toByteArray()))).shouldBeInstanceOf<PackInstaller.Result.Failed>()
            }
            PackInstaller(dir).install(fetchFrom(source("../xx", "1", f))).shouldBeInstanceOf<PackInstaller.Result.Failed>()
            File(dir.parentFile, "evil.tsv").exists() shouldBe false
            dir.list()!!.isEmpty() shouldBe true
        } finally { dir.deleteRecursively() }
    }

    test("a downloaded pack can be removed") {
        val dir = tmp()
        try {
            PackInstaller(dir).install(fetchFrom(source("xx", "1", mapOf("a.tsv" to "a".toByteArray()))))
            PackInstaller(dir).remove("xx") shouldBe true
            PackInstaller(dir).remove("../x") shouldBe false
            File(dir, "xx").exists() shouldBe false
        } finally { dir.deleteRecursively() }
    }
})

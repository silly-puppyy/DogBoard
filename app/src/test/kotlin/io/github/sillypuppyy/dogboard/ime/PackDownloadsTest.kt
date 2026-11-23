package io.github.sillypuppyy.dogboard.ime

import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest

/** Packs over HTTP from a folder laid out as dist_pack.py writes it, served by a local server. */
class PackDownloadsTest : FunSpec({
    fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    test("index, install, update check, and a pack the host lacks") {
        val host = kotlin.io.path.createTempDirectory("dist").toFile()
        val packs = kotlin.io.path.createTempDirectory("packs").toFile()
        val lex = "#dogboard-lexicon 1\nhola\t5\n".toByteArray()
        File(host, "xx").mkdirs()
        File(host, "xx/xx.lexicon.tsv").writeBytes(lex)
        File(host, "xx/manifest.json").writeText("""{"format": 1, "tag": "xx", "name": "Test", "script": "Latn", "version": "2026.11.23",
            "files": {"lexicon": {"path": "xx.lexicon.tsv", "size": ${lex.size}, "sha256": "${sha(lex)}"}}}""")
        File(host, "index.json").writeText("""{"format": 1, "packs": [{"tag": "xx", "name": "Test", "version": "2026.11.23", "size": ${lex.size}}]}""")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val f = File(host, ex.requestURI.path.removePrefix("/"))
            if (f.isFile && f.canonicalPath.startsWith(host.canonicalPath)) {
                val b = f.readBytes(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
            } else { ex.sendResponseHeaders(404, -1); ex.close() }
        }
        server.start()
        try {
            val d = PackDownloads("http://127.0.0.1:${server.address.port}/", packs)
            val index = d.index()!!
            index.packs.map { it.tag } shouldBe listOf("xx")
            d.install("xx").shouldBeInstanceOf<PackInstaller.Result.Installed>()
            val installed = LanguagePack.openDir(File(packs, "xx"))!!
            installed.has("lexicon") shouldBe true
            d.updateFor(installed, index) shouldBe null
            d.updateFor(installed, index.copy(packs = listOf(index.packs[0].copy(version = "2026.12.01"))))!!.version shouldBe "2026.12.01"
            d.install("zz").shouldBeInstanceOf<PackInstaller.Result.Failed>() // not on the host: 404, nothing installed
            File(packs, "zz").exists() shouldBe false
            PackDownloads("", packs).index() shouldBe null // no host chosen: nothing offered
        } finally {
            server.stop(0); host.deleteRecursively(); packs.deleteRecursively()
        }
    }
})

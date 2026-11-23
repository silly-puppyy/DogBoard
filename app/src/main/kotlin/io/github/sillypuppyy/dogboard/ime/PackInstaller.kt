package io.github.sillypuppyy.dogboard.ime

import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Installs a language pack into packs/<tag>/ (the folder [LanguagePack.find] reads first) from any source: [fetch]
 * opens a file by its path in the pack (manifest.json first), so a download, a file picker or a test can feed it. The
 * host for real downloads is not settled yet (checklist: release gap), so nothing here names one.
 *
 * Every file is checked against the manifest (size and SHA-256) while it streams into a staging folder; only a
 * complete pack replaces the installed one, in one rename, so a failed or interrupted download leaves the old pack
 * working. Paths that could leave the pack's folder ("../", absolute) are refused: a pack comes from the network.
 */
class PackInstaller(private val packsDir: File) {
    sealed interface Result {
        data class Installed(val tag: String, val version: String, val bytes: Long) : Result
        data class Failed(val reason: String) : Result
    }

    fun install(fetch: (path: String) -> InputStream, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): Result {
        val manifestBytes = runCatching { fetch("manifest.json").use { it.readBytes() } }.getOrElse { return Result.Failed("manifest: ${it.message}") }
        val manifest = runCatching { json.decodeFromString<PackManifest>(manifestBytes.decodeToString()) }.getOrElse { return Result.Failed("manifest: ${it.message}") }
        if (manifest.format > LanguagePack.FORMAT) return Result.Failed("pack format ${manifest.format} is newer than this app's ${LanguagePack.FORMAT}")
        if (!safeName(manifest.tag)) return Result.Failed("bad tag '${manifest.tag}'")
        val files = manifest.files.values.distinctBy { it.path }
        files.firstOrNull { !safePath(it.path) }?.let { return Result.Failed("bad path '${it.path}'") }
        files.firstOrNull { it.sha256.isEmpty() && !it.optional }?.let { return Result.Failed("${it.path}: no checksum") }

        packsDir.mkdirs()
        val staging = File(packsDir, ".staging-${manifest.tag}")
        staging.deleteRecursively()
        val total = files.sumOf { it.size.coerceAtLeast(0) }
        var done = 0L
        try {
            for (f in files) {
                val target = File(staging, f.path).also { it.parentFile?.mkdirs() }
                val stream = try {
                    fetch(f.path)
                } catch (e: Exception) {
                    if (f.optional) continue
                    return fail(staging, "${f.path}: ${e.message}")
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                stream.use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); digest.update(buf, 0, n); size += n; done += n
                            if (f.size >= 0 && size > f.size) return fail(staging, "${f.path}: larger than the manifest's ${f.size} bytes")
                            onProgress(done, total)
                        }
                    }
                }
                if (f.size >= 0 && size != f.size) return fail(staging, "${f.path}: $size bytes, the manifest says ${f.size}")
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                if (f.sha256.isNotEmpty() && !sha.equals(f.sha256, ignoreCase = true)) return fail(staging, "${f.path}: checksum differs")
            }
            File(staging, "manifest.json").writeBytes(manifestBytes)
        } catch (e: java.io.IOException) {
            return fail(staging, e.message ?: "read error")
        }

        return swapIn(staging, manifest, done)
    }

    /**
     * A pack from one .tar.xz (tools/i18n/dist_pack.py: manifest.json first, then the files), streamed: the archive's
     * own SHA-256 ([archiveSha256], from the index) and every file's size and SHA-256 against the manifest are checked
     * on the way; a file the manifest doesn't name, or one it names missing, fails the whole pack.
     */
    fun installArchive(input: InputStream, archiveSha256: String = "", onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): Result {
        val archiveDigest = MessageDigest.getInstance("SHA-256")
        var staging: File? = null
        try {
            val counted = java.security.DigestInputStream(input.buffered(1 shl 16), archiveDigest)
            val tar = TarReader(org.tukaani.xz.XZInputStream(counted))
            val first = tar.next() ?: return Result.Failed("empty archive")
            if (first.name != "manifest.json") return Result.Failed("the archive does not start with its manifest")
            val manifestBytes = tar.readEntry()
            val manifest = runCatching { json.decodeFromString<PackManifest>(manifestBytes.decodeToString()) }.getOrElse { return Result.Failed("manifest: ${it.message}") }
            if (manifest.format > LanguagePack.FORMAT) return Result.Failed("pack format ${manifest.format} is newer than this app's ${LanguagePack.FORMAT}")
            if (!safeName(manifest.tag)) return Result.Failed("bad tag '${manifest.tag}'")
            val files = manifest.files.values.distinctBy { it.path }.associateBy { it.path }
            files.values.firstOrNull { !safePath(it.path) }?.let { return Result.Failed("bad path '${it.path}'") }
            files.values.firstOrNull { it.sha256.isEmpty() && !it.optional }?.let { return Result.Failed("${it.path}: no checksum") }
            packsDir.mkdirs()
            val stage = File(packsDir, ".staging-${manifest.tag}").also { it.deleteRecursively(); it.mkdirs() }
            staging = stage
            val total = files.values.sumOf { it.size.coerceAtLeast(0) }
            var done = 0L
            val seen = HashSet<String>()
            while (true) {
                val e = tar.next() ?: break
                val f = files[e.name] ?: return fail(stage, "${e.name}: not in the manifest")
                if (!seen.add(e.name)) return fail(stage, "${e.name}: twice in the archive")
                if (f.size >= 0 && e.size != f.size) return fail(stage, "${e.name}: ${e.size} bytes, the manifest says ${f.size}")
                val digest = MessageDigest.getInstance("SHA-256")
                File(stage, f.path).also { it.parentFile?.mkdirs() }.outputStream().use { out ->
                    tar.copyEntry { buf, n -> out.write(buf, 0, n); digest.update(buf, 0, n); done += n; onProgress(done, total) }
                }
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                if (f.sha256.isNotEmpty() && !sha.equals(f.sha256, ignoreCase = true)) return fail(stage, "${e.name}: checksum differs")
            }
            files.values.firstOrNull { it.path !in seen && !it.optional }?.let { return fail(stage, "${it.path}: missing from the archive") }
            // the rest of the stream (tar padding, xz index) so the archive's checksum covers all of it
            val sink = ByteArray(1 shl 16)
            while (counted.read(sink) >= 0) Unit
            val archiveSha = archiveDigest.digest().joinToString("") { "%02x".format(it) }
            if (archiveSha256.isNotEmpty() && !archiveSha.equals(archiveSha256, ignoreCase = true)) return fail(stage, "archive checksum differs")
            File(stage, "manifest.json").writeBytes(manifestBytes)
            return swapIn(stage, manifest, done)
        } catch (e: java.io.IOException) {
            staging?.let { return fail(it, e.message ?: "read error") }
            return Result.Failed(e.message ?: "read error")
        }
    }

    private fun swapIn(staging: File, manifest: PackManifest, done: Long): Result {
        // the swap: the old pack aside, the new one in, the old one removed (a crash in between leaves one of them whole)
        val dest = File(packsDir, manifest.tag)
        val old = File(packsDir, ".old-${manifest.tag}")
        old.deleteRecursively()
        if (dest.exists() && !dest.renameTo(old)) return fail(staging, "could not move the installed pack aside")
        if (!staging.renameTo(dest)) {
            old.renameTo(dest)
            return fail(staging, "could not move the new pack in")
        }
        old.deleteRecursively()
        return Result.Installed(manifest.tag, manifest.version, done)
    }

    /** A tar stream's entries (ustar / pax as Python's tarfile writes them; pax and GNU long-name headers skipped). */
    private class TarReader(private val input: InputStream) {
        class Entry(val name: String, val size: Long)
        private var left = 0L
        private var pad = 0L
        private val header = ByteArray(512)

        fun next(): Entry? {
            skip(left + pad); left = 0; pad = 0
            while (true) {
                if (!readFully(header)) return null
                if (header.all { it == 0.toByte() }) return null // end of archive
                val name = field(0, 100)
                val prefix = field(345, 155)
                val size = field(124, 12).trim().ifEmpty { "0" }.toLong(8)
                val type = header[156].toInt().toChar()
                val padded = (size + 511) / 512 * 512
                if (type == 'x' || type == 'g' || type == 'L' || type == 'K') { skip(padded); continue }
                // folders and links: not in our packs, skipped with their data
                if (type != '0' && type != '\u0000') { skip(padded); continue }
                left = size; pad = padded - size
                return Entry(if (prefix.isEmpty()) name else "$prefix/$name", size)
            }
        }

        fun readEntry(): ByteArray {
            val out = java.io.ByteArrayOutputStream(left.toInt())
            copyEntry { b, n -> out.write(b, 0, n) }
            return out.toByteArray()
        }

        fun copyEntry(sink: (ByteArray, Int) -> Unit) {
            val buf = ByteArray(1 shl 16)
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw java.io.EOFException("archive ends inside a file")
                sink(buf, n); left -= n
            }
        }

        private fun field(at: Int, len: Int): String {
            var end = at
            while (end < at + len && header[end] != 0.toByte()) end++
            return String(header, at, end - at, Charsets.UTF_8)
        }

        private fun readFully(b: ByteArray): Boolean {
            var off = 0
            while (off < b.size) { val n = input.read(b, off, b.size - off); if (n < 0) return false; off += n }
            return true
        }

        private fun skip(n: Long) {
            var r = n
            val buf = ByteArray(8192)
            while (r > 0) { val k = input.read(buf, 0, minOf(buf.size.toLong(), r).toInt()); if (k < 0) throw java.io.EOFException(); r -= k }
        }
    }

    /** Removes a downloaded pack (a built-in one of the same tag takes over again). */
    fun remove(tag: String): Boolean = safeName(tag) && File(packsDir, tag).let { it.exists() && it.deleteRecursively() }

    private fun fail(staging: File, reason: String): Result {
        staging.deleteRecursively()
        return Result.Failed(reason)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun safeName(s: String) = s.isNotEmpty() && s.all { it.isLetterOrDigit() || it == '-' || it == '_' } && !s.startsWith("-")

        /** A relative path inside the pack: no absolute paths, no "..", no hidden names (the staging folders). */
        fun safePath(p: String) = p.isNotEmpty() && !p.startsWith("/") && '\\' !in p &&
            p.split('/').all { it.isNotEmpty() && it != "." && it != ".." && !it.startsWith(".") }
    }
}

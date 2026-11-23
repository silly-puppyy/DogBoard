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

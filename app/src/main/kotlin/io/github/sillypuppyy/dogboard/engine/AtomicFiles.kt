package io.github.sillypuppyy.dogboard.engine

import java.io.File

/**
 * Writes the whole file through a temporary file and a rename, so a process killed mid-write
 * leaves the old file intact instead of a truncated one (the user's words and settings).
 */
internal fun File.writeAtomically(write: (java.io.OutputStream) -> Unit) {
    runCatching {
        parentFile?.mkdirs()
        val tmp = File(parentFile, "$name.tmp")
        tmp.outputStream().use(write)
        if (!tmp.renameTo(this)) tmp.delete()
    }
}

internal fun File.writeTextAtomically(text: String) = writeAtomically { it.write(text.toByteArray()) }

private val laterWriter = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "dogboard-save").apply { isDaemon = true } }

/**
 * [writeTextAtomically] on a background thread, for state saved after every word (the adaptive
 * autocorrect strength, personal key offsets): one writer thread keeps the writes in order, so the
 * last one wins. Readers of such a file call [awaitPendingWrites] first.
 */
internal fun File.writeTextLater(text: String) {
    laterWriter.execute { writeTextAtomically(text) }
}

/** Blocks until every [writeTextLater] queued so far is on disk. */
internal fun awaitPendingWrites() {
    runCatching { laterWriter.submit {}.get() }
}

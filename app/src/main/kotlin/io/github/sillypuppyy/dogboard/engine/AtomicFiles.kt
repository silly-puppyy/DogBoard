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

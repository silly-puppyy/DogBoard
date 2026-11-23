package io.github.sillypuppyy.dogboard.testing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The Testing page's keystroke log (user 2026-11-23: "a dedicated test typing field where you can log all my
 * keystrokes without any privacy issues"). Filled only while the keyboard types into DogBoard's own test field (its
 * private IME options carry [MARK]), never in any other app or field; kept in memory only, until cleared or the app
 * stops; nothing is written or sent. The Testing page shows it and can copy it.
 */
object KeyLog {
    /** The test field's privateImeOptions mark. */
    const val MARK = "io.github.sillypuppyy.dogboard.keylog"
    private const val MAX = 2000

    private val start = System.currentTimeMillis()
    private val lines = MutableStateFlow(emptyList<String>())
    val events: StateFlow<List<String>> = lines

    fun add(kind: String, detail: String) {
        val t = (System.currentTimeMillis() - start) / 1000.0
        val line = "%9.3f  %-7s %s".format(java.util.Locale.ROOT, t, kind, detail)
        lines.value = (lines.value + line).takeLast(MAX)
    }

    fun clear() { lines.value = emptyList() }
}

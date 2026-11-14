package io.github.sillypuppyy.dogboard.ime

import io.github.sillypuppyy.dogboard.engine.Suggestion
import io.github.sillypuppyy.dogboard.engine.TypingEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** The latest suggestion strip, for the design's bar (stage 2); set where the engine publishes it. */
object LiveStrip {
    val strip = MutableStateFlow<List<Suggestion>>(emptyList())

    /** The word just removed from suggestions by a long-press, while its Undo shows (Kb:117-120). */
    val removed = MutableStateFlow<String?>(null)

    /** How long Undo stays (design Panels:102: "Undo stays 4 s"). */
    private const val UNDO_MS = 4_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var undoJob: Job? = null

    /** A long-press on [s]: never suggest it again, with Undo for a few seconds. */
    fun remove(s: Suggestion, refresh: () -> Unit) {
        // the typed word itself and emoji are not removable words
        if (s.kind == Suggestion.Kind.LITERAL || s.kind == Suggestion.Kind.EMOJI) return
        TypingEngine.block(s.word)
        refresh()
        removed.value = s.word
        undoJob?.cancel()
        undoJob = scope.launch { delay(UNDO_MS); removed.value = null }
    }

    /** Undo on the removed bar: the word may be suggested again (its learned counts stay forgotten). */
    fun undo(refresh: () -> Unit) {
        val word = removed.value ?: return
        undoJob?.cancel()
        removed.value = null
        TypingEngine.unblock(word)
        refresh()
    }
}

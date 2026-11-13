package io.github.sillypuppyy.dogboard.ime

import io.github.sillypuppyy.dogboard.engine.Suggestion
import kotlinx.coroutines.flow.MutableStateFlow

/** The latest suggestion strip, for the design's bar (stage 2); set where the engine publishes it. */
object LiveStrip {
    val strip = MutableStateFlow<List<Suggestion>>(emptyList())
}

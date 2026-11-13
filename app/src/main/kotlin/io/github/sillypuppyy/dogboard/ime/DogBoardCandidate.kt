package io.github.sillypuppyy.dogboard.ime

import androidx.compose.ui.graphics.vector.ImageVector
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import io.github.sillypuppyy.dogboard.engine.Suggestion

/** A suggestion-strip slot from DogBoard's engine, shown in FlorisBoard's candidates row. */
data class DogBoardCandidate(val suggestion: Suggestion) : SuggestionCandidate {
    override val text: CharSequence = when (suggestion.kind) {
        Suggestion.Kind.LITERAL -> "“${suggestion.word}”"
        else -> suggestion.word
    }
    override val secondaryText: CharSequence? = null
    override val confidence: Double = if (suggestion.kind == Suggestion.Kind.AUTOCORRECT) 1.0 else 0.0
    // marks the slot space will commit; themes style it via smartbar-candidate-word[auto-commit=1]
    override val isEligibleForAutoCommit: Boolean = suggestion.kind == Suggestion.Kind.AUTOCORRECT
    // long-press removes a suggested word for good (not the typed word itself)
    override val isEligibleForUserRemoval: Boolean = suggestion.kind != Suggestion.Kind.LITERAL
    override val sourceProvider: SuggestionProvider? = null
    override val icon: ImageVector? = null
}

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

    /** Smart chips in place of the suggestions (contact chips while a contact's first name is typed). */
    val chips = MutableStateFlow<List<io.github.sillypuppyy.dogboard.design.KbChipData>>(emptyList())

    /** Whether the bar shows Gboard's Undo chip (after a paste from the clip chip, a held or swipe delete). */
    val undoShown = MutableStateFlow(false)

    /** Contacts by lowercase first name ([io.github.sillypuppyy.dogboard.contacts.ContactCards]); empty unless allowed. */
    @Volatile
    var contactCards: Map<String, List<io.github.sillypuppyy.dogboard.contacts.ContactCard>> = emptyMap()

    /**
     * The designer's date chips (More board, Smart chips: "Date field: today, tomorrow and the next
     * weekday, in the field's format"): Today, Tomorrow and the day after by name ("Mon 17 Nov"), each
     * typing the date in the phone's short date format (a field doesn't say its own format).
     */
    fun dateChips(today: java.time.LocalDate, locale: java.util.Locale = java.util.Locale.getDefault()): List<io.github.sillypuppyy.dogboard.design.KbChipData> {
        val short = java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.SHORT).withLocale(locale)
        val named = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", locale)
        val d2 = today.plusDays(2)
        return listOf(
            io.github.sillypuppyy.dogboard.design.KbChipData("today", "Today", insert = today.format(short)),
            io.github.sillypuppyy.dogboard.design.KbChipData("event", "Tomorrow", insert = today.plusDays(1).format(short)),
            io.github.sillypuppyy.dogboard.design.KbChipData("date_range", d2.format(named), insert = d2.format(short)),
        )
    }

    /** A field that asks for a date: the date input type, or a text field whose hint says "date" or "birth". */
    fun isDateField(type: dev.patrickgold.florisboard.ime.editor.InputAttributes, hint: CharSequence?): Boolean {
        if (type.type == dev.patrickgold.florisboard.ime.editor.InputAttributes.Type.DATETIME &&
            type.variation == dev.patrickgold.florisboard.ime.editor.InputAttributes.Variation.DATE) return true
        val h = hint?.toString()?.lowercase() ?: return false
        return type.type == dev.patrickgold.florisboard.ime.editor.InputAttributes.Type.TEXT &&
            Regex("\\b(date|dd/mm|mm/dd|day)\\b").containsMatchIn(h)
    }

    /** The chips for a typed word: one contact's name, number and birthday, or up to three names. */
    fun contactChips(typed: String): List<io.github.sillypuppyy.dogboard.design.KbChipData> {
        val cards = contactCards[typed.lowercase()].orEmpty()
        if (typed.length < 2 || cards.isEmpty()) return emptyList()
        if (cards.size > 1) return cards.take(3).map { io.github.sillypuppyy.dogboard.design.KbChipData("person", it.name) }
        val c = cards[0]
        return listOfNotNull(
            io.github.sillypuppyy.dogboard.design.KbChipData("person", c.name),
            c.phone?.let { io.github.sillypuppyy.dogboard.design.KbChipData("call", it) },
            c.birthday?.let { io.github.sillypuppyy.dogboard.design.KbChipData("cake", it) },
        )
    }

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

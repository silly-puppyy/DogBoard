/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
import dev.patrickgold.florisboard.ime.text.key.KeyCode
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.keyboard3

import android.icu.text.BreakIterator
import android.os.SystemClock
import android.text.TextUtils
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.staticCompositionLocalOf
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.clipboard.ClipboardManager
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import dev.patrickgold.florisboard.ime.editor.ImeOptions
import dev.patrickgold.florisboard.ime.editor.InputAttributes
import dev.patrickgold.florisboard.ime.io.StorageController
import dev.patrickgold.florisboard.ime.keyboard.IncognitoMode
import dev.patrickgold.florisboard.ime.keyboard3.extension.loadFoundationKeyboard
import dev.patrickgold.florisboard.ime.keyboard3.hint.FlickKeyHintPlacement
import dev.patrickgold.florisboard.ime.keyboard3.hint.LongPressKeyHintPlacement
import dev.patrickgold.florisboard.ime.keyboard3.touch.FnKeyArrangement
import dev.patrickgold.florisboard.ime.keyboard3.touch.InputShiftState
import dev.patrickgold.florisboard.ime.keyboard3.touch.TouchModelCache
import dev.patrickgold.florisboard.ime.keyboard3.touch.TouchModelOptions
import dev.patrickgold.florisboard.ime.media.emoji.EmojiSuggestionType
import dev.patrickgold.florisboard.ime.nlp.BreakIterators
import dev.patrickgold.florisboard.ime.nlp.ClipboardSuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import dev.patrickgold.florisboard.lib.FlorisLocale
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.florisboard.lib.kotlin.collectIn
import org.k3lp.lib.text.K3Descriptor
import org.k3lp.lib.text.K3String
import org.k3lp.lib.text.K3StringOrDescriptor
import org.k3lp.lib.text.asK3String
import org.k3lp.model.K3Model
import org.k3lp.model.layer.K3LayerId
import org.k3lp.runtime.K3Content
import org.k3lp.runtime.K3InputMethod
import org.k3lp.runtime.K3SurroundingText
import org.k3lp.runtime.K3TextRange
import java.lang.ref.WeakReference
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import io.github.sillypuppyy.dogboard.engine.Bigrams
import io.github.sillypuppyy.dogboard.engine.Candidate
import io.github.sillypuppyy.dogboard.engine.CurrentWord
import io.github.sillypuppyy.dogboard.engine.Suggestion
import io.github.sillypuppyy.dogboard.engine.SuggestionStrip
import io.github.sillypuppyy.dogboard.engine.Tap
import io.github.sillypuppyy.dogboard.engine.TypingEngine
import io.github.sillypuppyy.dogboard.engine.isWordChar
import io.github.sillypuppyy.dogboard.engine.previousContext
import io.github.sillypuppyy.dogboard.engine.trailingWord
import io.github.sillypuppyy.dogboard.feel.DoubleSpace
import io.github.sillypuppyy.dogboard.ime.DogBoardCandidate
import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.github.sillypuppyy.dogboard.touch.KeyTarget
import io.github.sillypuppyy.dogboard.feel.WordDelete

/**
 * Provides the [ImeController] instance this composition tree is associated with.
 */
val LocalImeController = staticCompositionLocalOf<ImeController> {
    error("No IME controller is associated with this composition tree.")
}

@OptIn(ExperimentalAtomicApi::class)
class ImeController(
    storageController: StorageController,
    val clipboardManager: ClipboardManager, // TODO ClipboardController
    initialState: ImeState = ImeState(),
    val touchModelCache: TouchModelCache = TouchModelCache(),
) : K3InputMethod<ImeState, ImeEditor, ImeController.UpdateImeStateScope>(
    initialState = initialState,
) {
    private val prefs by FlorisPreferenceStore
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val breakIterators = BreakIterators()
    private val expectedContentQueue = ExpectedContentQueue()
    private var emitInterceptor = AtomicReference<EmitInterceptor?>(null)
    // DogBoard: uptime of the last typed space, or null after any other edit (double-space period)
    private var lastSpaceUptime: Long? = null
    // DogBoard: word tracking, autocorrect and suggestions
    private class PendingTap(val tap: Tap?, val keys: Map<Char, KeyTarget>, val params: GaussianTouch.Params?)
    private class LastCorrection(val word: CurrentWord, val corrected: String, val separator: String)
    private var currentWord = CurrentWord()
    private var pendingTap: PendingTap? = null
    private var lastCorrection: LastCorrection? = null

    // DogBoard: an autocorrection that was made, until the next word shows it was kept (iOS
    // acceptance: forgives earlier undos), and the word being deleted into (iOS soft rejections)
    private var unconfirmedCorrection: Pair<String, String>? = null

    // DogBoard: the last finished word and the word before it, accepted into the personal model
    // once the next word is finished or a suggestion picked without an undo (iOS token acceptance)
    private var unconfirmedWord: Pair<String?, String>? = null
    private var deletingWord: String? = null
    private var rejectedWord: String? = null
    private var suggestionJob: Job? = null
    @Volatile private var latestDecode: Pair<String, List<Candidate>>? = null
    // DogBoard: the autocorrection chosen for the word one keystroke ago, as (typed text, choice)
    @Volatile private var previousKeystrokeCorrection: Pair<String, String?>? = null
    @Volatile private var latestCorrection: Pair<String, String?>? = null

    /** Receives the suggestion strip whenever the current word changes. */
    var suggestionSink: ((List<Suggestion>) -> Unit)? = null

    /**
     * DogBoard: prior for the next letter key from the word at the cursor (likely letters get
     * slightly larger keys), or null where the word engine is off (passwords, email, URLs).
     */
    fun letterPrior(): ((Char) -> Double)? {
        val state = activeState.value
        val attrs = state.editor.info.inputAttributes
        if (attrs.type != InputAttributes.Type.TEXT || attrs.variation in NO_DOUBLE_SPACE_VARIATIONS ||
            attrs.flagTextNoSuggestions || state.content.selection.isNotCollapsed()
        ) return null
        val textBefore = state.content.surroundingText.textBefore
        val prefix = trailingWord(textBefore)
        return TypingEngine.keyPrior(prefix, previousContext(textBefore.substring(0, textBefore.length - prefix.length)))
    }

    /** DogBoard: where the next letter key was tapped (set on touch down, used when it emits). */
    fun notePendingTap(tap: Tap?, keys: Map<Char, KeyTarget>, params: GaussianTouch.Params?) {
        pendingTap = PendingTap(tap, keys, params)
    }

    val activeTouchModelOptions = combine<Any, TouchModelOptions>(
        prefs.keyboard.numberRow.asFlow(),
        prefs.keyboard.fnKeyEnabled.asFlow(),
        prefs.keyboard.fnKeyArrangement.asFlow(),
        prefs.keyboard.longPressKeyHintEnabled.asFlow(),
        prefs.keyboard.longPressKeyHintPlacement.asFlow(),
        prefs.keyboard.multiTapHighlightEnabled.asFlow(),
        prefs.keyboard.flickKeyHintEnabled.asFlow(),
        prefs.keyboard.flickKeyHintPlacement.asFlow(),
    ) { values ->
        TouchModelOptions(
            showNumberRow = values[0] as Boolean,
            fnKeyEnabled = values[1] as Boolean,
            fnKeyArrangement = values[2] as FnKeyArrangement,
            longPressKeyHintEnabled = values[3] as Boolean,
            longPressKeyHintPlacement = values[4] as LongPressKeyHintPlacement,
            multiTapHighlightEnabled = values[5] as Boolean,
            flickKeyHintEnabled = values[6] as Boolean,
            flickKeyHintPlacement = values[7] as FlickKeyHintPlacement,
        )
    }.stateIn(scope, SharingStarted.Eagerly, TouchModelOptions.Default)

    // TODO check if we can implement this differently
    val activeSmartbarVisibleDynamicActionsCount = MutableStateFlow(0)

    init {
        combine(
            prefs.devtools.enabled.asFlow(),
            prefs.devtools.showDragAndDropHelpers.asFlow(),
        ) { devtoolsEnabled, showDragAndDropHelpers ->
            devtoolsEnabled && showDragAndDropHelpers
        }.collectIn(scope) { showDragAndDropHelpers ->
            updateState {
                state = state.copy(
                    flags = state.flags
                        .withDebugShowDragAndDropHelpers(showDragAndDropHelpers),
                )
            }
        }

        // TODO proper stateful compilation
        scope.launch {
            loadFoundationKeyboard(this@ImeController, storageController.activeStorage.value)
        }
    }

    fun onHardwareKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null) {
            return false
        }
        return when (keyCode) {
            // TODO KeyEvent.KEYCODE_SPACE (auto-commit candidate)
            KeyEvent.KEYCODE_DEL -> true
            KeyEvent.KEYCODE_FORWARD_DEL -> true
            // TODO KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT
            else -> false
        }
    }

    fun onHardwareKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null) {
            return false
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                updateStateBlocking {
                    emitBackspace()
                }
                true
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                updateStateBlocking {
                    emitForwardDelete()
                }
                true
            }
            // TODO KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT
            else -> false
        }
    }

    fun setEmitInterceptor(interceptor: EmitInterceptor?) {
        emitInterceptor.store(interceptor)
    }

    fun snapshotState(): ImeState = activeState.value

    // TODO evaluate if we can move to a clean coroutine-based approach in FlorisBoard
    //  for now this helper can be used to update the state from non-suspending contexts
    inline fun updateStateBlocking(crossinline function: UpdateImeStateScope.() -> Unit) {
        runBlocking {
            updateState(function)
        }
    }

    override fun updateStateScopeOf(state: ImeState): UpdateImeStateScope {
        return UpdateImeStateScope(state)
    }

    inner class UpdateImeStateScope(
        state: ImeState,
    ) : UpdateStateScope<ImeState, ImeEditor>(state) {
        fun handleStartInputView(
            ic: WeakReference<InputConnection>,
            info: FlorisEditorInfo,
        ) {
            val newTouchLayerId: K3LayerId
            val keyVariation: KeyVariation
            when (info.inputAttributes.type) {
                InputAttributes.Type.NUMBER -> {
                    keyVariation = KeyVariation.NORMAL
                    newTouchLayerId = ImeLayerIds.Numpad
                }
                InputAttributes.Type.PHONE -> {
                    keyVariation = KeyVariation.NORMAL
                    newTouchLayerId = ImeLayerIds.Telpad
                }
                InputAttributes.Type.TEXT -> {
                    keyVariation = when (info.inputAttributes.variation) {
                        InputAttributes.Variation.EMAIL_ADDRESS,
                        InputAttributes.Variation.WEB_EMAIL_ADDRESS,
                            -> {
                            KeyVariation.EMAIL_ADDRESS
                        }
                        InputAttributes.Variation.PASSWORD,
                        InputAttributes.Variation.VISIBLE_PASSWORD,
                        InputAttributes.Variation.WEB_PASSWORD,
                            -> {
                            KeyVariation.PASSWORD
                        }
                        InputAttributes.Variation.URI -> {
                            KeyVariation.URI
                        }
                        else -> {
                            KeyVariation.NORMAL
                        }
                    }
                    newTouchLayerId = ImeLayerIds.Base
                }
                else -> {
                    keyVariation = KeyVariation.NORMAL
                    newTouchLayerId = ImeLayerIds.Base
                }
            }
            val initialSelection = info.initialSelection
            val initialSurrounding = K3SurroundingText(
                textBefore = info.getInitialTextBeforeCursor(20)?.toString() ?: "",
                textSelected = info.getInitialSelectedText()?.toString() ?: "",
                textAfter = info.getInitialTextAfterCursor(20)?.toString() ?: "",
            )

            val rememberCapsLockState = prefs.typing.rememberCapsLockState.get() &&
                state.flags.inputShiftState == InputShiftState.CAPS_LOCK

            state = state.copy(
                editor = ImeEditor(ic, info),
                touchLayerId = when {
                    rememberCapsLockState && newTouchLayerId.isTextLayer() -> ImeLayerIds.Caps
                    else -> newTouchLayerId
                },
                flags = state.flags
                    .withKeyVariation(keyVariation)
                    .withImeUiMode(
                        if (state.flags.imeUiMode != ImeUiMode.CLIPBOARD || prefs.clipboard.historyHideOnNextTextField.get()) {
                            ImeUiMode.TEXT
                        } else {
                            state.flags.imeUiMode
                        }
                    )
                    .withActionsOverflowVisible(false)
                    .withActionsEditorVisible(false)
                    .withInputShiftState(
                        when {
                            rememberCapsLockState -> InputShiftState.CAPS_LOCK
                            else -> InputShiftState.UNSHIFTED
                        }
                    )
                    .withComposingEnabled(
                        when (newTouchLayerId) {
                            ImeLayerIds.Numpad, ImeLayerIds.Telpad -> false
                            else -> keyVariation != KeyVariation.PASSWORD &&
                                // TODO review if this is the correct approach for composing region support detection
                                //  important: for codemirror6 in browsers, it is important that composing is disabled,
                                //  else all sorts of weird behavior starts to occur
                                info.isRichInputEditor &&
                                info.inputAttributes.flagTextAutoCorrect
                        }
                    )
                    .withIncognitoMode(
                        when (prefs.suggestion.incognitoMode.get()) {
                            IncognitoMode.FORCE_OFF -> false
                            IncognitoMode.FORCE_ON -> true
                            IncognitoMode.DYNAMIC_ON_OFF -> {
                                info.imeOptions.flagNoPersonalizedLearning ||
                                    prefs.suggestion.forceIncognitoModeFromDynamic.get()
                            }
                        }
                    )
            )
            resetContent(initialSelection, initialSurrounding)
            expectedContentQueue.clear()
            resetWordTracking() // after the new field's text is known, so predictions fit it
        }

        fun handleUpdateSelection(newSelection: K3TextRange) {
            val content = expectedContentQueue.popUntilOrNull { it.selection == newSelection }
            if (content != null) {
                flogDebug { "DEDUPLICATED!!1" }
                return
            }
            lastSpaceUptime = null
            resetWordTracking()
            resetContent(newSelection, state.editor.getSurroundingText(50, 10))
            expectedContentQueue.push(state.content)
        }

        override fun emit(value: K3StringOrDescriptor) {
            val interceptor = emitInterceptor.load()
            if (interceptor != null) {
                interceptor.onEmit(value)
                return
            }
            super.emit(value)
        }

        override fun emitText(value: K3String) {
            // DogBoard: track the word being typed; finishing it may autocorrect it first
            deletingWord = null
            val text = value.toText()
            // emoji typed from the panel or search count as emoji use (iOS ranking and show rule)
            if (text.isNotEmpty() && text.codePoints().anyMatch { Character.getType(it) == Character.OTHER_SYMBOL.toInt() && it >= 0x2190 } &&
                !state.flags.isIncognitoMode
            ) {
                val app = state.editor.info.packageName
                scope.launch { TypingEngine.emojiUsed(text, app) }
            }
            val wordChar = text.singleOrNull()?.takeIf { isWordChar(it) }
            when {
                wordChar != null -> {
                    val tap = pendingTap
                    if (currentWord.isEmpty && !state.content.selection.isNotCollapsed()) {
                        // typing on at the end of an existing word continues that word
                        currentWord.seedFrom(state.content.surroundingText.textBefore, tap?.keys ?: emptyMap(), tap?.params)
                    }
                    // iOS case rules need how each letter was shifted, and the context the word started in
                    val shiftState = state.flags.inputShiftState
                    val shiftFlag = when {
                        !wordChar.isUpperCase() -> 0
                        shiftState == InputShiftState.SHIFTED_AUTOMATIC -> io.github.sillypuppyy.dogboard.engine.CaseAdapt.AUTOSHIFTED
                        else -> io.github.sillypuppyy.dogboard.engine.CaseAdapt.SHIFTED
                    }
                    val caseContext = when (shiftState) {
                        InputShiftState.SHIFTED_MANUAL -> io.github.sillypuppyy.dogboard.engine.CaseAdapt.Context.SHIFT
                        InputShiftState.CAPS_LOCK -> io.github.sillypuppyy.dogboard.engine.CaseAdapt.Context.CAPS_LOCK
                        InputShiftState.SHIFTED_AUTOMATIC -> when (state.editor.info.inputAttributes.capsMode) {
                            InputAttributes.CapsMode.WORDS -> io.github.sillypuppyy.dogboard.engine.CaseAdapt.Context.AUTOSHIFT_WORDS
                            InputAttributes.CapsMode.ALL -> io.github.sillypuppyy.dogboard.engine.CaseAdapt.Context.AUTOSHIFT_ALL
                            else -> io.github.sillypuppyy.dogboard.engine.CaseAdapt.Context.AUTOSHIFT
                        }
                        InputShiftState.UNSHIFTED -> io.github.sillypuppyy.dogboard.engine.CaseAdapt.Context.NONE
                    }
                    currentWord.addLetter(wordChar, tap?.tap, tap?.keys ?: emptyMap(), tap?.params, shiftFlag, caseContext)
                    lastCorrection = null
                }
                text in WORD_SEPARATORS -> finishWord(text)
                else -> {
                    currentWord.clear()
                    lastCorrection = null
                }
            }
            pendingTap = null

            if (value == SPACE) {
                val now = SystemClock.uptimeMillis()
                val since = lastSpaceUptime?.let { now - it }
                if (doubleSpacePeriodAllowed() &&
                    DoubleSpace.shouldInsertPeriod(state.content.surroundingText.textBefore, since)
                ) {
                    rawBackspace()
                    super.emitText(PERIOD_SPACE)
                    lastSpaceUptime = null
                } else {
                    super.emitText(value)
                    lastSpaceUptime = now
                }
            } else {
                super.emitText(value)
                lastSpaceUptime = null
            }
            expectedContentQueue.push(state.content)
            // iOS: on the 123 and #+= planes, space or an apostrophe returns to the letters
            if ((value == SPACE || text == "'") && state.touchLayerId in SYMBOL_LAYERS) {
                switchTouchLayer(ImeLayerIds.Base)
            }
            reevaluateInputShiftState()
            publishSuggestions()
        }

        // DogBoard: edits made by the keyboard itself, not by a key press
        private fun rawBackspace() {
            super.emitBackspace()
            expectedContentQueue.push(state.content)
        }

        private fun rawText(text: String) {
            super.emitText(text.asK3String())
            expectedContentQueue.push(state.content)
        }

        /** Replaces [old] right before the cursor with [new], if the text there is [old]. */
        private fun replaceBeforeCursor(old: String, new: String): Boolean {
            if (state.content.selection.isNotCollapsed()) return false
            if (!state.content.surroundingText.textBefore.endsWith(old)) return false
            repeat(old.length) { rawBackspace() }
            rawText(new)
            return true
        }

        /** Called before a word separator is typed: autocorrects the finished word. */
        private fun finishWord(separator: String) {
            lastCorrection = null
            confirmKeptCorrection()
            confirmAcceptedWord()
            val word = currentWord
            // a reverted autocorrection that is finished unchanged is a word the user wants
            if (!word.isEmpty && word.text == rejectedWord) learnWord(word.text)
            if (!word.isEmpty && correctionAllowed() && word.text != rejectedWord && !cursorInsideWord()) {
                val typed = word.text
                val context = wordContext()
                val candidates = latestDecode?.takeIf { it.first == decodeKey(typed, context) }?.second
                    ?: TypingEngine.candidates(word, context)
                val previousChoice = previousKeystrokeCorrection?.takeIf { it.first == typed.dropLast(1) }?.second
                val corrected = TypingEngine.correction(word, candidates, previousChoice)
                if (corrected != null && corrected != typed && replaceBeforeCursor(typed, corrected)) {
                    lastCorrection = LastCorrection(word.snapshot(), corrected, separator)
                    if (!state.flags.isIncognitoMode) {
                        unconfirmedCorrection = typed to corrected
                        scope.launch { TypingEngine.noteCorrected(typed, corrected) }
                    }
                    noteFinishedWord(context, corrected)
                } else {
                    noteFinishedWord(context, typed)
                }
            }
            currentWord.clear()
            rejectedWord = null
        }

        /** Backspace right after an autocorrection restores what was typed. */
        private fun revertLastCorrection(): Boolean {
            val correction = lastCorrection ?: return false
            lastCorrection = null
            unconfirmedCorrection = null
            unconfirmedWord = null // the typed word is accepted when it is finished again
            if (!replaceBeforeCursor(correction.corrected + correction.separator, correction.word.text)) return false
            currentWord = correction.word
            rejectedWord = correction.word.text
            if (!state.flags.isIncognitoMode) {
                scope.launch { TypingEngine.reject(correction.word.text, correction.corrected) }
            }
            return true
        }

        /** Commits a suggestion-strip pick in place of the current word, followed by a space. */
        private fun acceptSuggestion(suggestion: Suggestion): Boolean {
            val typed = currentWord.text
            if (suggestion.kind == Suggestion.Kind.EMOJI) return acceptEmojiSuggestion(typed, suggestion.word)
            if (typed.isEmpty() && suggestion.kind != Suggestion.Kind.PREDICTION) return false
            val previous = wordContext()
            if (!replaceBeforeCursor(typed, suggestion.word + " ")) return false
            if (suggestion.kind == Suggestion.Kind.LITERAL) learnWord(suggestion.word)
            confirmKeptCorrection()
            confirmAcceptedWord()
            noteFinishedWord(previous, suggestion.word)
            currentWord.clear()
            lastCorrection = null
            rejectedWord = null
            lastSpaceUptime = SystemClock.uptimeMillis()
            reevaluateInputShiftState()
            publishSuggestions()
            return true
        }

        /** The design's emoji slot: the emoji goes after the word ("dog 🐕 "), Panels:99. */
        private fun acceptEmojiSuggestion(typed: String, emoji: String): Boolean {
            val previous = wordContext()
            val replacement = if (typed.isEmpty()) "$emoji " else "$typed $emoji "
            if (!replaceBeforeCursor(typed, replacement)) return false
            confirmKeptCorrection()
            confirmAcceptedWord()
            if (typed.isNotEmpty()) noteFinishedWord(previous, typed)
            val app = state.editor.info.packageName
            if (!state.flags.isIncognitoMode) scope.launch { TypingEngine.emojiUsed(emoji, app) }
            currentWord.clear()
            lastCorrection = null
            rejectedWord = null
            lastSpaceUptime = SystemClock.uptimeMillis()
            reevaluateInputShiftState()
            publishSuggestions()
            return true
        }

        /** [word] (after [previous]) was finished; it is accepted when the next word confirms it. */
        private fun noteFinishedWord(previous: String?, word: String) {
            if (state.flags.isIncognitoMode || !wordEngineAllowed()) return
            unconfirmedWord = previous to word
        }

        /** The word finished before this one was not undone: accept it (personal model, learning). */
        private fun confirmAcceptedWord() {
            val (previous, word) = unconfirmedWord ?: return
            unconfirmedWord = null
            scope.launch { TypingEngine.accepted(previous, word) }
        }

        /** The autocorrection before this word was not undone: record it as kept. */
        private fun confirmKeptCorrection() {
            val (typed, corrected) = unconfirmedCorrection ?: return
            unconfirmedCorrection = null
            scope.launch { TypingEngine.correctionKept(typed, corrected) }
        }


        /** The user kept [word] against autocorrect: remember it, except in incognito mode. */
        private fun learnWord(word: String) {
            if (state.flags.isIncognitoMode || !wordEngineAllowed()) return
            scope.launch { TypingEngine.learn(word) }
        }

        private fun resetWordTracking() {
            currentWord.clear()
            pendingTap = null
            lastCorrection = null
            rejectedWord = null
            publishSuggestions()
        }

        /** True when a letter follows the cursor: only part of the word is before it. */
        private fun cursorInsideWord(): Boolean =
            state.content.surroundingText.textAfter.firstOrNull()?.let { isWordChar(it) } == true

        private fun correctionAllowed(): Boolean = prefs.correction.autocorrect.get() && wordEngineAllowed()

        private fun wordEngineAllowed(): Boolean {
            val attrs = state.editor.info.inputAttributes
            return attrs.type == InputAttributes.Type.TEXT &&
                attrs.variation !in NO_DOUBLE_SPACE_VARIATIONS &&
                !attrs.flagTextNoSuggestions
        }

        /** The previous word (or sentence start) for the word being typed at the cursor. */
        private fun wordContext(): String? {
            val textBefore = state.content.surroundingText.textBefore
            val typed = currentWord.text
            val end = if (textBefore.endsWith(typed)) textBefore.length - typed.length else textBefore.length
            return previousContext(textBefore.subSequence(0, end))
        }

        private fun decodeKey(typed: String, context: String?) = "$typed\u0000$context"

        /** Recomputes the suggestion strip, e.g. once the engine has finished loading. */
        fun refreshSuggestions() = publishSuggestions()

        private fun publishSuggestions() {
            val sink = suggestionSink ?: return
            suggestionJob?.cancel()
            if (!wordEngineAllowed() || state.content.selection.isNotCollapsed() || cursorInsideWord()) {
                sink(emptyList())
                return
            }
            val context = wordContext()
            if (currentWord.isEmpty) {
                // next-word predictions, only right after a space or at the start of the text
                val textBefore = state.content.surroundingText.textBefore
                if (textBefore.isNotEmpty() && !textBefore.last().isWhitespace()) {
                    sink(emptyList())
                    return
                }
                suggestionJob = scope.launch {
                    val words = TypingEngine.predictions(context)
                    val cased = if (context == Bigrams.START) words.map { w -> w.replaceFirstChar { it.uppercaseChar() } } else words
                    if (isActive) sink(cased.map { Suggestion(it, Suggestion.Kind.PREDICTION) })
                }
                return
            }
            val word = currentWord.snapshot()
            val rejected = rejectedWord
            val withCorrection = prefs.correction.autocorrect.get()
            val textBefore = state.content.surroundingText.textBefore.toString()
            val app = state.editor.info.packageName
            val emojiMode = if (state.flags.isIncognitoMode) io.github.sillypuppyy.dogboard.engine.EmojiSuggestionMode.OFF
                else prefs.suggestion.emojiMode.get()
            suggestionJob = scope.launch {
                val candidates = TypingEngine.candidates(word, context)
                latestDecode = decodeKey(word.text, context) to candidates
                val previousChoice = latestCorrection?.takeIf { it.first == word.text.dropLast(1) }?.second
                val correction = if (withCorrection && word.text != rejected) TypingEngine.correction(word, candidates, previousChoice) else null
                latestCorrection?.takeIf { it.first != word.text }?.let { previousKeystrokeCorrection = it }
                latestCorrection = word.text to correction
                val emoji = TypingEngine.emojiFor(textBefore, app, emojiMode, prefs.emoji.preferredSkinTone.get().id)
                if (isActive) sink(SuggestionStrip.build(word.text, candidates, correction, TypingEngine.caseFor(word), emoji))
            }
        }

        // DogBoard
        private fun doubleSpacePeriodAllowed(): Boolean {
            val attrs = state.editor.info.inputAttributes
            return prefs.correction.doubleSpacePeriod.get() &&
                attrs.type == InputAttributes.Type.TEXT &&
                attrs.variation !in NO_DOUBLE_SPACE_VARIATIONS
        }

        override fun emitDescriptor(descriptor: K3Descriptor) {
            val windowController = FlorisImeService.windowControllerOrNull()
            when (descriptor) {
                // TODO once k3lp supports this remove
                ImeActions.Delete -> emitForwardDelete()
                // DogBoard: delete back to the previous word boundary (used by held delete)
                ImeActions.BackspaceWord -> emitBackspaceWord()
                // TODO evaluate use of modern cursor anchor API instead of sending raw key events
                ImeActions.ArrowDown -> state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN)
                ImeActions.ArrowLeft -> state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT)
                ImeActions.ArrowRight -> state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT)
                ImeActions.ArrowUp -> state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_UP)
                // TODO clipboard manager needs a rewrite to match the new state paradigm
                ImeActions.ClipboardCopy -> {
                    if (state.editor.info.isRichInputEditor) {
                        val text = state.content.surroundingText.textSelected
                        if (text.isNotEmpty()) {
                            clipboardManager.addNewPlaintext(text)
                        }
                    } else {
                        state.editor.performClipboardCopy()
                    }
                }
                ImeActions.ClipboardCut -> {
                    if (state.editor.info.isRichInputEditor) {
                        val text = state.content.surroundingText.textSelected
                        if (text.isNotEmpty()) {
                            clipboardManager.addNewPlaintext(text)
                            emitBackspace()
                        }
                    } else {
                        state.editor.performClipboardCut()
                    }
                }
                ImeActions.ClipboardPaste -> {
                    if (state.editor.info.isRichInputEditor) {
                        emitClipboardItem(clipboardManager.primaryClip)
                    } else {
                        state.editor.performClipboardPaste()
                    }
                }
                ImeActions.ClipboardClearHistory -> clipboardManager.clearHistory()
                ImeActions.ClipboardClearFullHistory -> clipboardManager.clearFullHistory()
                ImeActions.ClipboardClearPrimaryClip -> {
                    if (prefs.clipboard.clearPrimaryClipAffectsHistoryIfUnpinned.get()) {
                        clipboardManager.primaryClip?.let { clipboardManager.deleteClip(it, onlyIfUnpinned = true) }
                    }
                    clipboardManager.updatePrimaryClip(null)
                    // TODO we have no ref here:
                    //  appContext.showShortToastSync(R.string.clipboard__cleared_primary_clip)
                }
                ImeActions.SelectAll -> state.editor.performSelectAll()
                ImeActions.ShowImeWindow -> FlorisImeService.showUi()
                ImeActions.HideImeWindow -> FlorisImeService.hideUi()
                ImeActions.ShowTextPanel -> {
                    state = state.copy(
                        flags = state.flags.withImeUiMode(ImeUiMode.TEXT),
                    )
                }
                ImeActions.ShowMediaPanel -> {
                    state = state.copy(
                        flags = state.flags.withImeUiMode(ImeUiMode.MEDIA),
                    )
                }
                ImeActions.ShowClipboardPanel -> {
                    state = state.copy(
                        flags = state.flags.withImeUiMode(ImeUiMode.CLIPBOARD),
                    )
                }
                ImeActions.Undo -> state.editor.performUndo()
                ImeActions.Redo -> state.editor.performRedo()
                ImeActions.Settings -> FlorisImeService.launchSettings()
                ImeActions.ShowInputMethodPicker -> FlorisImeService.showImePicker()
                ImeActions.SwitchToPrevInputMethod -> FlorisImeService.switchToPrevInputMethod()
                ImeActions.SwitchToNextInputMethod -> FlorisImeService.switchToNextInputMethod()
                ImeActions.ShowSubtypePicker -> {} // TODO
                ImeActions.SwitchToPrevSubtype -> {} // TODO
                ImeActions.SwitchToNextSubtype -> {} // TODO
                ImeActions.ToggleActionsEditor -> {
                    state = state.copy(
                        flags = state.flags
                            .withActionsEditorVisible(!state.flags.isActionsEditorVisible),
                    )
                }
                ImeActions.ToggleActionsOverflow -> {
                    state = state.copy(
                        flags = state.flags
                            .withActionsOverflowVisible(!state.flags.isActionsOverflowVisible),
                    )
                }
                ImeActions.ToggleCompactLayout -> windowController?.actions?.toggleCompactLayout()
                ImeActions.ToggleFloatingWindow -> windowController?.actions?.toggleFloatingWindow()
                ImeActions.ToggleResizeMode -> windowController?.editor?.toggleEnabled()
                ImeActions.CompactLayoutToLeft -> windowController?.actions?.compactLayoutToLeft()
                ImeActions.CompactLayoutToRight -> windowController?.actions?.compactLayoutToRight()
                ImeActions.ExternalVoiceInput -> FlorisImeService.switchToVoiceInputMethod()
                else -> super.emitDescriptor(descriptor)
            }
            reevaluateInputShiftState()
        }

        override fun emitBackspace() {
            lastSpaceUptime = null
            pendingTap = null
            if (revertLastCorrection()) {
                reevaluateInputShiftState()
                publishSuggestions()
                return
            }
            // DogBoard: remember the word being deleted into; report it once it is gone (iOS soft rejections)
            val wordBeforeDelete = trailingWord(state.content.surroundingText.textBefore)
            if (deletingWord == null && wordBeforeDelete.isNotEmpty()) deletingWord = wordBeforeDelete
            super.emitBackspace()
            expectedContentQueue.push(state.content)
            if (trailingWord(state.content.surroundingText.textBefore).isEmpty()) {
                deletingWord?.let { gone -> if (!state.flags.isIncognitoMode) scope.launch { TypingEngine.noteDeleted(gone) } }
                deletingWord = null
            }
            // DogBoard: keep the current word in step with the text
            val textBefore = state.content.surroundingText.textBefore
            currentWord.removeLast()
            if (currentWord.isEmpty || !textBefore.endsWith(currentWord.text)) {
                // deleted back into (or within) a word: track that word
                currentWord.seedFrom(textBefore, currentWord.keys, currentWord.params)
            }
            publishSuggestions()
        }

        // DogBoard
        fun emitBackspaceWord() {
            lastCorrection = null
            if (state.content.selection.isNotCollapsed()) {
                emitBackspace()
                return
            }
            val textBefore = state.content.surroundingText.textBefore
            val target = textBefore.length - WordDelete.charsToDelete(textBefore)
            var remaining = textBefore.length
            while (remaining > target) {
                emitBackspace()
                val now = state.content.surroundingText.textBefore.length
                if (now >= remaining) break // no progress (e.g. no more context), stop
                remaining = now
            }
        }

        override fun emitEnter() {
            val info = state.editor.info
            val isShiftPressed = false // TODO inputEventDispatcher.isPressed(KeyCode.SHIFT)
            if (info.imeOptions.flagNoEnterAction || info.inputAttributes.flagTextMultiLine && isShiftPressed) {
                emitEnterKey()
            } else {
                when (val action = info.imeOptions.action) {
                    ImeOptions.Action.UNSPECIFIED,
                    ImeOptions.Action.DONE,
                    ImeOptions.Action.GO,
                    ImeOptions.Action.NEXT,
                    ImeOptions.Action.PREVIOUS,
                    ImeOptions.Action.SEARCH,
                    ImeOptions.Action.SEND -> emitEnterAction(action)
                    else -> emitEnterKey()
                }
            }
        }

        fun emitEnterKey() {
            val info = state.editor.info
            if (info.isRawInputEditor) {
                state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_ENTER)
            } else {
                emitText(NEWLINE_SEQ)
            }
        }

        fun emitEnterAction(action: ImeOptions.Action) {
            state.editor.performEditorAction(action)
        }

        fun emitForwardDelete() {
            // TODO request additional text if too low on context length
            if (state.content.selection.isNotCollapsed()) {
                emitBackspace()
            } else {
                val newSurroundingText = state.content.surroundingText.copy(
                    textAfter = state.content.surroundingText.textAfter.let { text ->
                        // TODO unicode
                        if (text.isEmpty()) text else text.substring(1)
                    },
                )
                state = state.copy(
                    content = state.content.copy(
                        surroundingText = newSurroundingText,
                    ),
                )
                state.editor.deleteSurroundingText(
                    charsBefore = 0,
                    charsAfter = 1, // TODO
                )
            }
        }

        fun emitCandidate(candidate: SuggestionCandidate) {
//            scope.launch {
//                candidate.sourceProvider?.notifySuggestionAccepted(subtypeManager.activeSubtype, candidate)
//            }
            when (candidate) {
                is ClipboardSuggestionCandidate -> emitClipboardItem(candidate.clipboardItem)
                else -> emitCompletion(candidate)
            }
        }

        fun emitClipboardItem(item: ClipboardItem?): Boolean {
            return item != null && when (item.type) {
                ItemType.TEXT -> {
                    val text = item.text?.asK3String()
                    if (text != null) {
                        emitText(text)
                        true
                    } else {
                        false
                    }
                }
                ItemType.IMAGE, ItemType.VIDEO -> {
                    state.editor.performClipboardPasteMedia(item)
                }
            }.also {
                if (prefs.clipboard.historyHideOnPaste.get()) {
                    state = state.copy(
                        flags = state.flags.withImeUiMode(ImeUiMode.TEXT)
                    )
                }
            }
        }

        fun emitCompletion(candidate: SuggestionCandidate): Boolean {
            if (candidate is DogBoardCandidate) return acceptSuggestion(candidate.suggestion)
            val text = candidate.text.toString()
            if (text.isEmpty() || state.editor.info.isRawInputEditor) return false
//            val content = activeContent
//            return if (content.composing.isValid) {
//                phantomSpace.setActive(showComposingRegion = false, candidate = candidate)
//                super.finalizeComposingText(text)
//            } else {
//                val isPhantomSpaceActive = phantomSpace.determine(text)
//                phantomSpace.setActive(showComposingRegion = false, candidate = candidate)
//                return if (isPhantomSpaceActive) {
//                    super.commitText("$SPACE$text")
//                } else {
//                    super.commitText(text)
//                }.also {
//                    // handled in finalizeComposingText if content.composing.isValid
//                    updateLastCommitPosition()
//                }
//            }
            return false
        }

        override fun switchTouchLayer(newTouchLayerId: K3LayerId) {
            if (newTouchLayerId.isTextLayer()) {
                super.switchTouchLayer(state.flags.inputShiftState.correspondingLayerId)
            } else {
                super.switchTouchLayer(newTouchLayerId)
            }
        }

        fun cycleInputShiftState(isDoubleTap: Boolean) {
            // DogBoard: the rule lives in ShiftKey (tested), with the "Enable Caps Lock" setting
            val newInputShiftState = io.github.sillypuppyy.dogboard.feel.ShiftKey.next(
                state.flags.inputShiftState, isDoubleTap,
                prefs.typing.shiftKeyBehavior.get(), prefs.typing.capsLockEnabled.get(),
            )
            state = state.copy(
                touchLayerId = newInputShiftState.correspondingLayerId,
                flags = state.flags.withInputShiftState(newInputShiftState),
            )
        }

        fun revertCycleInputShiftState(
            newTouchLayerId: K3LayerId,
            newInputShiftState: InputShiftState,
        ) {
            state = state.copy(
                touchLayerId = newTouchLayerId,
                flags = state.flags.withInputShiftState(newInputShiftState),
            )
        }

        fun reevaluateInputShiftState() {
            val isPeekOngoing = false // TODO
            if (state.flags.inputShiftState == InputShiftState.CAPS_LOCK || isPeekOngoing) return
            // DogBoard: iOS auto-shift rules (AutoShift, notes/design/settings-model.md §3) instead of
            // Android's TextUtils.getCapsMode, which treats every period as a sentence end
            val type = when (state.editor.info.inputAttributes.capsMode) {
                InputAttributes.CapsMode.ALL -> io.github.sillypuppyy.dogboard.engine.AutoShift.Type.ALL
                InputAttributes.CapsMode.WORDS -> io.github.sillypuppyy.dogboard.engine.AutoShift.Type.WORDS
                InputAttributes.CapsMode.SENTENCES -> io.github.sillypuppyy.dogboard.engine.AutoShift.Type.SENTENCES
                InputAttributes.CapsMode.NONE -> io.github.sillypuppyy.dogboard.engine.AutoShift.Type.NONE
            }
            val shift = prefs.typing.autoCapitalization.get() &&
                io.github.sillypuppyy.dogboard.engine.TypingEngine.shouldAutoShift(state.content.surroundingText.textBefore, type)
                // && subtypeManager.activeSubtype.primaryLocale.supportsCapitalization
            val inputShiftState = when {
                shift -> InputShiftState.SHIFTED_AUTOMATIC
                else -> InputShiftState.UNSHIFTED
            }
            val touchLayerId = when {
                state.touchLayerId.isTextLayer() -> inputShiftState.correspondingLayerId
                else -> state.touchLayerId
            }
            state = state.copy(
                touchLayerId = touchLayerId,
                flags = state.flags.withInputShiftState(inputShiftState),
            )
        }

        override fun resetContent(newSelection: K3TextRange, newSurrounding: K3SurroundingText) {
            super.resetContent(newSelection, newSurrounding)
            reevaluateInputShiftState()
        }

        override fun resetContent() {
            super.resetContent()
            reevaluateInputShiftState()
        }

        fun handleFinishInputView() {
            resetWordTracking()
            resetContent()
            state = state.copy(editor = ImeEditor.Disconnected)
            expectedContentQueue.clear()
        }

        override fun evaluateCompositionOf(
            model: K3Model,
            selection: K3TextRange,
            surroundingText: K3SurroundingText
        ): K3TextRange? {
            if (selection.isNotCollapsed() || !state.flags.isComposingEnabled) {
                return null
            }
            // TODO rework how we get the primary locale
            val locale = FlorisLocale.fromTag(model.locales.getOrElse(0) { "" })
            return breakIterators.word(locale) {
                it.setText(surroundingText.textBefore)
                val end = it.last()
                val isWord = it.ruleStatus != BreakIterator.WORD_NONE
                if (isWord) {
                    val start = it.previous().let { pos ->
                        // Include Emoji indicator in local composing. This is required so that emoji suggestion indicator
                        // can be detected in the composing text.
                        (pos - 1).takeIf { updatedPos ->
                            surroundingText.textBefore.getOrNull(updatedPos) == EmojiSuggestionType.LEADING_COLON.prefix.first()
                        } ?: pos
                    }
                    val offset = (selection.min - surroundingText.textBefore.length).coerceAtLeast(0)
                    K3TextRange(start + offset, end + offset)
                } else {
                    null
                }
            }
        }
    }

    companion object {
        private val NEWLINE_SEQ = "\n".asK3String()
    }
}

enum class OperationUnit {
    CHARACTERS,
    WORDS;
}

enum class OperationScope {
    BEFORE_CURSOR,
    AFTER_CURSOR;
}

private val SPACE = " ".asK3String()
private val WORD_SEPARATORS = setOf(" ", ".", ",", "!", "?", ";", ":")
private val SYMBOL_LAYERS = setOf(K3LayerId("symbol-1"), K3LayerId("symbol-2"))
private val PERIOD_SPACE = ". ".asK3String()
private val NO_DOUBLE_SPACE_VARIATIONS = setOf(
    InputAttributes.Variation.EMAIL_ADDRESS,
    InputAttributes.Variation.WEB_EMAIL_ADDRESS,
    InputAttributes.Variation.URI,
    InputAttributes.Variation.PASSWORD,
    InputAttributes.Variation.VISIBLE_PASSWORD,
    InputAttributes.Variation.WEB_PASSWORD,
)

fun interface EmitInterceptor {
    fun onEmit(value: K3StringOrDescriptor)
}

private class ExpectedContentQueue {
    private val list = mutableListOf<K3Content>()

    fun popUntilOrNull(predicate: (K3Content) -> Boolean): K3Content? {
        while (list.isNotEmpty()) {
            val item = list[0]
            if (predicate(item)) return item
            list.removeAt(0)
        }
        return null
    }

    fun push(item: K3Content) {
        list.add(item)
    }

    fun clear() {
        list.clear()
    }
}

fun K3Content.cursorCapsMode(inputAttributes: InputAttributes): InputAttributes.CapsMode {
    return InputAttributes.CapsMode.fromFlags(
        TextUtils.getCapsMode(surroundingText.textBefore, surroundingText.textBefore.length, inputAttributes.raw)
    )
}

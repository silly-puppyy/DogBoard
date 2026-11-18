/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
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
import dev.patrickgold.florisboard.BuildConfig
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
import io.github.sillypuppyy.dogboard.engine.leadingWord
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

    /**
     * DogBoard: the text before the cursor when the user last tapped shift. While it is unchanged
     * (nothing typed, cursor not moved) automatic shifting leaves the user's choice alone (iOS
     * preventAutoshift: shift tapped off at a sentence start stays off).
     */
    private var shiftChosenAt: String? = null

    /** DogBoard: the space before the cursor was added by the keyboard (after a picked suggestion). */
    private var autoSpace = false
    // DogBoard: word tracking, autocorrect and suggestions
    private class PendingTap(val tap: Tap?, val keys: Map<Char, KeyTarget>, val params: GaussianTouch.Params?)
    private class LastCorrection(val word: CurrentWord, val corrected: String, val separator: String)
    private var currentWord = CurrentWord()
    private var pendingTap: PendingTap? = null

    /** DogBoard: the letter keys and touch parameters of the last letter tap, for words not typed just now. */
    private var lastLetterKeys: Map<Char, KeyTarget> = emptyMap()
    private var lastLetterParams: GaussianTouch.Params? = null

    /**
     * DogBoard: the last finished word, kept while only a plain space follows it, so the next word
     * can still change it (iOS retrocorrection reaches back across spaces, not punctuation).
     */
    private class LookBack(
        val word: io.github.sillypuppyy.dogboard.engine.CurrentWord,
        val committed: String,
        val context: String?,
        /** The word's candidates as decoded when it was finished (retrocorrection reuses them). */
        val candidates: List<io.github.sillypuppyy.dogboard.engine.Candidate>? = null,
        /** Where the space after it was tapped (a word split by a stray space may be rejoined). */
        val spaceTap: Tap? = null,
        /** The decoder's candidates before the neural refinement (the neural look back scores these). */
        val decoded: List<io.github.sillypuppyy.dogboard.engine.Candidate>? = null,
    )
    private var lookBack: LookBack? = null

    /** DogBoard: the rejoined reading of [lookBack]'s word and the word being typed (look back, typed, word or null). */
    @Volatile
    private var latestJoin: Triple<LookBack, String, String?>? = null

    /** DogBoard: the last retrocorrection ([old] -> [new], followed by [tail]), undone by a backspace right after. */
    private class LastRetro(val old: String, val new: String, val typedOld: String, val tail: String)
    private var lastRetro: LastRetro? = null

    // DogBoard: hindsight retrocorrection (TypingEngine.hindsightFix): words kept as typed, by where they
    // start in the text, read again once three more words of the same sentence are typed
    private class HindsightWatch(val typed: String, val before: String, val start: Int)
    private val hindsightWatches = ArrayList<HindsightWatch>()
    private var readyHindsightFix: Pair<HindsightWatch, String>? = null

    /** DogBoard: the word the cursor rests in (text before and after it) while the bar offers corrections for it. */
    private var wordAroundCursor: Pair<String, String>? = null

    // DogBoard: swipe typing: the text a swipe just put in (word and space) and the other words it may
    // have been, offered on the bar until something else is typed
    private var swipeCommitted: String? = null
    private var swipeOthers: List<String> = emptyList()

    /**
     * DogBoard: a finished swipe ([xs], [ys]: the finger's path, [centres]: the letter keys, all in dp).
     * The recogniser runs off the main thread with the two words before as context; its word goes in
     * as a bar pick would (UpdateImeStateScope.acceptSwipe).
     */
    suspend fun commitSwipe(xs: FloatArray, ys: FloatArray, centres: Map<Char, Pair<Float, Float>>) {
        val textBefore = snapshotState().content.surroundingText.textBefore
        val t0 = SystemClock.uptimeMillis()
        val words = kotlinx.coroutines.withContext(Dispatchers.Default) {
            io.github.sillypuppyy.dogboard.engine.TypingEngine.swipeWords(
                xs, ys, centres, previousContext(textBefore), io.github.sillypuppyy.dogboard.engine.previousContext2(textBefore),
            )
        }
        android.util.Log.i("DogBoardPerf", "swipe: ${xs.size} points -> ${words.firstOrNull()} in ${SystemClock.uptimeMillis() - t0} ms (${io.github.sillypuppyy.dogboard.engine.TypingEngine.lastSwipeTiming})")
        if (words.isEmpty()) return
        updateState { acceptSwipe(words) }
    }
    private var lastCorrection: LastCorrection? = null

    // DogBoard: an autocorrection that was made, until the next word shows it was kept (iOS
    // acceptance: forgives earlier undos), and the word being deleted into (iOS soft rejections)
    private var unconfirmedCorrection: Pair<String, String>? = null

    // DogBoard: the last finished word and the word before it, accepted into the personal model
    // once the next word is finished or a suggestion picked without an undo (iOS token acceptance)
    private var unconfirmedWord: Pair<String?, String>? = null
    /** The word before [unconfirmedWord]'s previous one (the personal model's two-word context). */
    private var unconfirmedWord2: String? = null
    /** DogBoard: the finished word's taps and key centres, recorded for personal key offsets once it is confirmed. */
    private var unconfirmedTouches: Triple<String, List<io.github.sillypuppyy.dogboard.engine.Tap>, Map<Char, io.github.sillypuppyy.dogboard.touch.KeyTarget>>? = null
    // DogBoard: a word changed by retrocorrection, accepted only once the word after the one that
    // triggered it is finished (a backspace right after can still undo it)
    private var pendingRetroAccept: Pair<String?, String>? = null
    private var pendingRetroAccept2: String? = null

    /**
     * DogBoard: the last few autocorrections in this field (original, corrected, and up to 24
     * characters before the word as an anchor): tapping back into one offers the original first,
     * like Gboard's "tap highlighted text to undo" and iOS's choice bubble.
     */
    /**
     * An autocorrection made in this field: the typed [original], what it became, the text before it
     * ([anchor]) and the other words offered then. iOS keeps these for the whole document
     * (autocorrectionListsForWordsInDocument) and shows them when the cursor goes back into the word.
     */
    private class RecentCorrection(val original: String, val corrected: String, val anchor: String, val alternatives: List<String> = emptyList())
    private val recentCorrections = ArrayDeque<RecentCorrection>()
    private var deletingWord: String? = null

    /** DogBoard: the edit the bar's Undo chip can take back (UpdateImeStateScope.undoLastEdit). */
    private var undoEdit: UndoEdit? = null
        set(v) {
            field = v
            io.github.sillypuppyy.dogboard.ime.LiveStrip.undoShown.value = v != null
            io.github.sillypuppyy.dogboard.ime.LiveStrip.originalShown.value = v?.original != null
        }
    private var rejectedWord: String? = null
    private var suggestionJob: Job? = null
    @Volatile private var latestDecode: Pair<String, List<Candidate>>? = null
    // DogBoard: the decoder's own candidates for the same key (before the neural refinement), and the
    // experimental neural look back's scores for the current word
    @Volatile private var latestStage1: Pair<String, List<Candidate>>? = null
    @Volatile private var latestRetroNeural: Pair<String, Map<String, Double>>? = null
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

    /** DogBoard: where the space bar was last tapped (set on touch down, kept with the finished word). */
    private var spaceTap: Tap? = null

    fun noteSpaceTap(tap: Tap) {
        spaceTap = tap
    }

    /** DogBoard: where the next letter key was tapped (set on touch down, used when it emits). */
    fun notePendingTap(tap: Tap?, keys: Map<Char, KeyTarget>, params: GaussianTouch.Params?) {
        pendingTap = PendingTap(tap, keys, params)
        spaceTap = null
        if (keys.isNotEmpty() && params != null) {
            lastLetterKeys = keys
            lastLetterParams = params
        }
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
        prefs.keyboard.numberRowLongPress.asFlow(),
        prefs.keyboard.hintKind.asFlow(),
        prefs.keyboard.bottomRow.asFlow(),
        prefs.keyboard.symbolPages.asFlow(),
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
            numberRowLongPress = values[8] as Boolean,
            symbolLongPress = values[9] == "symbols",
            bottomRowClassic = values[10] == "classic",
            symbolsAndroid = values[11] == "android",
        )
    }.stateIn(scope, SharingStarted.Eagerly, TouchModelOptions.Default)

    // TODO check if we can implement this differently
    val activeSmartbarVisibleDynamicActionsCount = MutableStateFlow(0)

    init {
        io.github.sillypuppyy.dogboard.engine.TypingEngine.dictionaryCapitals = { prefs.typing.dictionaryCapitals.get() }
        // DogBoard: Fine-tuning timings, kept in step with their preferences
        val feel = io.github.sillypuppyy.dogboard.feel.FeelSettings
        prefs.typing.doubleSpaceMs.asFlow().collectIn(scope) { feel.doubleSpaceMs = it.toLong() }
        prefs.typing.deleteCharMs.asFlow().collectIn(scope) { feel.deleteCharMs = it.toLong() }
        prefs.typing.deleteWordsAfter.asFlow().collectIn(scope) { feel.deleteWordsAfter = it }
        prefs.typing.deleteWordMs.asFlow().collectIn(scope) { feel.deleteWordMs = it.toLong() }
        prefs.typing.trackpadHoldMs.asFlow().collectIn(scope) { feel.trackpadHoldMs = it.toLong() }
        prefs.typing.trackpadEnabled.asFlow().collectIn(scope) { feel.trackpadEnabled = it }
        prefs.typing.swipeEnabled.asFlow().collectIn(scope) { feel.swipeEnabled = it }
        prefs.typing.swipeTrail.asFlow().collectIn(scope) { feel.swipeTrail = it }
        prefs.typing.barTapWindowMs.asFlow().collectIn(scope) { io.github.sillypuppyy.dogboard.ime.BarTapGuard.windowMs = it.toLong() }
        prefs.typing.trackpadStepDp.asFlow().collectIn(scope) { feel.trackpadStepDp = it.toFloat() }
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
        // DogBoard: the chosen layout, compiled again when the setting changes
        scope.launch {
            prefs.keyboard.layoutId.asFlow().collectLatest { id ->
                loadFoundationKeyboard(this@ImeController, storageController.activeStorage.value, id)
            }
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
            resetFieldState()
            resetWordTracking() // after the new field's text is known, so predictions fit it
            io.github.sillypuppyy.dogboard.ime.LiveUi.closePanel() // a new field starts on the keys
        }

        fun handleUpdateSelection(newSelection: K3TextRange) {
            val content = expectedContentQueue.popUntilOrNull { it.selection == newSelection }
            if (content != null) {
                flogDebug { "DEDUPLICATED!!1" }
                return
            }
            lastSpaceUptime = null
            autoSpace = false
            // the new text and cursor first: resetWordTracking refreshes the bar from them (in the
            // other order the bar described the old cursor position after a tap into another word)
            resetContent(newSelection, state.editor.getSurroundingText(SURROUNDING_BEFORE, SURROUNDING_AFTER))
            resetWordTracking()
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

        override fun emitText(value: K3String) = io.github.sillypuppyy.dogboard.perf.GcStats.also { it.markUiThread() }.track("key (main thread)") { emitTextTracked(value) }

        private fun emitTextTracked(raw: K3String) {
            // composed, as other keyboards type it: the layout library keeps accented letters
            // decomposed ("ǔ" = u + U+030C: two deletes for one letter, user 2026-11-17), which also
            // breaks apps' search and our word lookups
            val composed = java.text.Normalizer.normalize(raw.toText(), java.text.Normalizer.Form.NFC)
            val value = if (composed == raw.toText()) raw else composed.asK3String()
            // DogBoard: track the word being typed; finishing it may autocorrect it first
            deletingWord = null
            lastRetro = null // set again by finishWord below when this input ends a word
            val text = value.toText()
            // DogBoard: punctuation right after the keyboard's own space (a picked suggestion) goes
            // before it: "word ," -> "word, "; closing quotes and brackets just take its place
            // DogBoard (user, 2026-11-16, beyond iOS, which keeps a typed space: "hello ?" stays): a
            // sentence or clause mark typed right after a space the user typed at a word end goes
            // before it, "word ?" -> "word? " (only , . ? !: a typed space before ":)" or ";)" is kept);
            // not ? ! in France / Belgium French, whose typography keeps the space (iOS keeps it too)
            if (!autoSpace && lastSpaceUptime != null && text in SWAP_AFTER_TYPED_SPACE && wordEngineAllowed() &&
                !(text in FRENCH_SPACED && keepsSpaceBeforeMarks())
            ) {
                val tb = state.content.surroundingText.textBefore
                if (tb.length >= 2 && tb.last() == ' ' && tb[tb.length - 2].isLetterOrDigit()) autoSpace = true
            }
            if (autoSpace) {
                autoSpace = false
                if (text in SWAP_WITH_AUTO_SPACE || text in REPLACE_AUTO_SPACE) {
                    if (state.content.surroundingText.textBefore.endsWith(" ")) {
                        rawBackspace()
                        emitText(value)
                        if (text in SWAP_WITH_AUTO_SPACE) {
                            emitText(" ".asK3String())
                            lastSpaceUptime = null // not a typed space: no double-space period
                            autoSpace = true // "?!" swaps again
                        }
                        return
                    }
                }
            }
            // DogBoard: iOS smart punctuation: curly quotes, dashes, ellipsis (SmartPunctuation)
            if (wordEngineAllowed()) {
                val smart = io.github.sillypuppyy.dogboard.feel.SmartPunctuation.forInput(
                    text, state.content.surroundingText.textBefore, state.content.surroundingText.textAfter,
                    prefs.typing.smartQuotes.get(), prefs.typing.smartDashes.get(),
                )
                if (smart != null && (smart.deleteBefore > 0 || smart.insert != text)) {
                    repeat(smart.deleteBefore) { rawBackspace() }
                    emitText(smart.insert.asK3String())
                    return
                }
            }
            // emoji typed from the panel or search count as emoji use (iOS ranking and show rule)
            // only a single emoji counts, and never in password-type or incognito fields (pasted
            // text must not end up in the usage file)
            if (io.github.sillypuppyy.dogboard.engine.EmojiUsage.isSingleEmoji(text) && wordEngineAllowed() &&
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
            if ((value == SPACE || text == "'" || text == "’") && state.touchLayerId in SYMBOL_LAYERS) { // ’: smart quotes on
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
        /**
         * DogBoard: the last edit the bar's Undo chip can take back (Gboard, measured 2026-11-15: after
         * a paste from the clipboard chip, and after a held delete, a delete swipe or deleting a
         * selection). [after] is the text before the cursor right after the edit; any other change
         * (typing, moving the cursor) drops it.
         */

        /**
         * DogBoard: a held delete, called after each repeat with the text before the cursor when the
         * hold began: everything the hold deleted so far comes back with Undo (Gboard).
         */
        fun recordHeldDelete(before: String) {
            val after = state.content.surroundingText.textBefore
            if (after.length < before.length && before.startsWith(after)) undoEdit = UndoEdit("", before.substring(after.length), after)
        }

        /**
         * A copied text as DogBoard pastes it: short links expanded ("Expand short links", once the link
         * service has answered) and trackers removed ("Strip trackers from links"); two separate opt-ins
         * (user, 2026-11-17). Unchanged when both are off.
         */
        fun cleanedPaste(text: String): String {
            val expanded = if (prefs.clipboard.expandShortLinks.get()) io.github.sillypuppyy.dogboard.ime.ShortLinks.withExpanded(text) else text
            return if (prefs.clipboard.stripTrackers.get()) io.github.sillypuppyy.dogboard.ime.LinkCleaner.clean(expanded) else expanded
        }

        /** DogBoard: the "Original link" chip after a cleaned paste: the text as it was copied, trackers and all. */
        fun restoreOriginalPaste() {
            val u = undoEdit ?: return
            val original = u.original ?: return
            undoEdit = null
            if (state.content.surroundingText.textBefore != u.after) return
            val withSpace = if (u.inserted.endsWith(' ') && !original.endsWith(' ')) "$original " else original
            if (!replaceBeforeCursor(u.inserted, withSpace)) return
            expectedContentQueue.push(state.content)
            currentWord.clear()
            publishSuggestions()
        }

        /** DogBoard: the Undo chip tapped: the pasted text goes, the deleted text comes back. */
        fun undoLastEdit() {
            val u = undoEdit ?: return
            undoEdit = null
            if (state.content.surroundingText.textBefore != u.after || !replaceBeforeCursor(u.inserted, u.removed)) return
            expectedContentQueue.push(state.content)
            currentWord.clear()
            publishSuggestions()
        }

        private fun replaceBeforeCursor(old: String, new: String): Boolean {
            if (state.content.selection.isNotCollapsed()) return false
            if (!state.content.surroundingText.textBefore.endsWith(old)) return false
            // one batch: the app sees "teh" become "the", not "te", "t", "", "the"
            state.editor.batch {
                repeat(old.length) { rawBackspace() }
                rawText(new)
            }
            return true
        }

        /** Called before a word separator is typed: autocorrects the finished word. */
        private fun finishWord(separator: String) {
            lastCorrection = null
            confirmPendingRetro() // the retrocorrection before the last word wasn't undone
            if (joinAcrossSpace(separator)) { spaceTap = null; return }
            val retro = retrocorrect()
            confirmKeptCorrection()
            if (retro != null) {
                // not yet: the backspace that undoes this retrocorrection must also undo learning it
                pendingRetroAccept = unconfirmedWord
                pendingRetroAccept2 = unconfirmedWord2
                unconfirmedWord = null
            } else {
                confirmAcceptedWord()
            }
            val word = currentWord
            var committed = word.text // what this word ends up as
            // a reverted autocorrection that is finished unchanged is a word the user wants
            if (!word.isEmpty && word.text == rejectedWord) learnWord(word.text)
            // letters right after a number: one slipped key in its suffix is fixed ("1sr" → "1st");
            // a backspace right after puts back what was typed
            val digitFix = if (!word.isEmpty && correctionAllowed() && word.text != rejectedWord && !cursorInsideWord()) suffixAfterNumber(word.text) else null
            if (digitFix != null && replaceBeforeCursor(word.text, digitFix)) {
                committed = digitFix
                lastCorrection = LastCorrection(word.snapshot(), digitFix, separator)
            }
            if (!word.isEmpty && correctionAllowed() && word.text != rejectedWord && !cursorInsideWord() && !followsDot(word.text)) {
                val typed = word.text
                val context = wordContext()
                val context2 = wordContext2()
                // Gboard: the commit waits up to 40 ms for the pending suggestion decode
                // (delay_to_wait_for_suggestions_before_commit_ms); the decode is published before
                // the neural refinement, so this is short
                val key = decodeKey(typed, context, context2)
                if (latestDecode?.first != key) suggestionJob?.takeIf { it.isActive }?.let { job ->
                    runBlocking { withTimeoutOrNull(WAIT_FOR_DECODE_MS) { job.join() } }
                }
                // iOS never computes at the word end: it applies the correction already found, or keeps
                // the typed word when none is ready ("avoided autocorrect deadlock"); no decode here
                val ready = latestDecode?.takeIf { it.first == key }?.second
                if (ready == null && logsText) android.util.Log.d("DogBoardAc", "word end before its decode: '$typed' kept")
                val candidates = ready ?: emptyList()
                val previousChoice = previousKeystrokeCorrection?.takeIf { it.first == typed.dropLast(1) }?.second
                TypingEngine.adaptiveStrength = prefs.correction.adaptiveStrength.get()
                val corrected = TypingEngine.correction(word, candidates, previousChoice, curlyApostrophes(), prefs.correction.autocorrectLevel.get(), commit = true)
                if (logsText) {
                    android.util.Log.d("DogBoardAc", "typed=$typed corrected=$corrected cached=${latestDecode?.first == decodeKey(typed, context, context2)} " +
                        candidates.take(4).joinToString { "%s %.2f".format(it.word, it.score) })
                }
                if (corrected != null && corrected != typed && replaceBeforeCursor(typed, corrected)) {
                    // the app flashes the corrected word (Android's correction highlight, as Gboard)
                    state.content.selection.start.let { end -> if (end >= corrected.length) state.editor.commitCorrection(end - corrected.length, typed, corrected) }
                    noteRecentCorrection(
                        typed, corrected, state.content.surroundingText.textBefore.dropLast(corrected.length),
                        candidates.asSequence().map { it.word }
                            .filter { !it.equals(corrected, ignoreCase = true) && !it.equals(typed, ignoreCase = true) }
                            .distinctBy { it.lowercase() }.take(2).toList(),
                    )
                    committed = corrected
                    lastCorrection = LastCorrection(word.snapshot(), corrected, separator)
                    if (!state.flags.isIncognitoMode) {
                        unconfirmedCorrection = typed to corrected
                        scope.launch { TypingEngine.noteCorrected(typed, corrected) }
                    }
                    noteFinishedWord(context, corrected, context2)
                    if (separator == " ") lookBack = LookBack(word.snapshot(), corrected, context, candidates, spaceTap, latestStage1?.takeIf { it.first == key }?.second)
                } else {
                    noteFinishedWord(context, typed, context2)
                    if (separator == " ") lookBack = LookBack(word.snapshot(), typed, context, candidates, spaceTap, latestStage1?.takeIf { it.first == key }?.second)
                    watchHindsight(typed)
                }
            } else if (!word.isEmpty && separator == " " && word.text != rejectedWord) {
                lookBack = null // autocorrect off or the user insisted on this word: nothing to look back at
            }
            if (separator != " ") lookBack = null
            spaceTap = null
            // a backspace right after this word's separator undoes the retrocorrection (once this
            // word's own correction, if any, has been undone first)
            if (retro != null) {
                lastRetro = LastRetro(retro.first, retro.second, retro.third, " $committed$separator")
            }
            checkHindsightWatches()
            currentWord.clear()
            rejectedWord = null
            readyHindsightFix?.let { (w, fix) -> readyHindsightFix = null; applyHindsightFix(w, fix) }
        }

        /** A word kept as typed that the look back may reconsider (a lowercase dictionary word): watch it. */
        private fun watchHindsight(typed: String) {
            if (!prefs.correction.fixPreviousWord.get() || !correctionAllowed() || !TypingEngine.hasNeuralLm) return
            if (!TypingEngine.hindsightEligible(typed)) return
            val tb = state.content.surroundingText.textBefore
            if (!tb.endsWith(typed)) return
            if (hindsightWatches.size >= 4) hindsightWatches.removeAt(0)
            hindsightWatches.add(HindsightWatch(typed, tb.dropLast(typed.length).toString(), state.content.selection.start - typed.length))
        }

        /** The text from a watched word to the cursor, when the word is still there as typed; else null. */
        private fun watchedSpan(w: HindsightWatch): String? {
            val tb = state.content.surroundingText.textBefore
            val span = state.content.selection.start - w.start
            if (state.content.selection.isNotCollapsed() || span < w.typed.length || span > tb.length) return null
            return tb.substring(tb.length - span).takeIf { it.startsWith(w.typed) }
        }

        /** Once three words of the same sentence follow a watched word, the model reads it again (off the main thread). */
        private fun checkHindsightWatches() {
            val it = hindsightWatches.iterator()
            while (it.hasNext()) {
                val w = it.next()
                val after = watchedSpan(w)?.substring(w.typed.length)
                if (after == null) { it.remove(); continue }
                if (after.isEmpty()) continue // just watched: its separator isn't typed yet
                if (!after.startsWith(" ")) { it.remove(); continue }
                val words = after.trim().split(Regex("\\s+")).filter { s -> s.isNotEmpty() }
                // the look back doesn't cross the end of a sentence (as iOS's doesn't cross punctuation)
                if (words.dropLast(1).any { s -> s.last() in ".!?;:" }) { it.remove(); continue }
                if (words.size < TypingEngine.HINDSIGHT_WORDS) continue
                it.remove()
                val tail = words.take(TypingEngine.HINDSIGHT_WORDS).joinToString(" ").trimEnd('.', '!', '?', ',', ';', ':')
                scope.launch(Dispatchers.Default) {
                    val (fix, margin) = TypingEngine.hindsightFix(w.before, w.typed, tail) ?: return@launch
                    if (logsText) android.util.Log.d("DogBoardAc", "hindsight ${w.typed} -> $fix before '$tail': margin %.2f".format(margin))
                    if (margin <= TypingEngine.HINDSIGHT_THRESHOLD || TypingEngine.hindsightRejected(w.typed, fix)) return@launch
                    updateState { if (currentWord.isEmpty) applyHindsightFix(w, fix) else readyHindsightFix = w to fix }
                }
            }
        }

        /** Puts the better word in (underlined as a correction; Backspace right after takes it back). */
        private fun applyHindsightFix(w: HindsightWatch, lexiconForm: String) {
            val fix = if (curlyApostrophes()) lexiconForm.replace('\'', '’') else lexiconForm // smart quotes, as typed
            val span = watchedSpan(w) ?: return
            val rest = span.substring(w.typed.length)
            if (!replaceBeforeCursor(w.typed + rest, fix + rest)) return
            state.editor.commitCorrection(w.start, w.typed, fix)
            noteRecentCorrection(w.typed, fix, w.before)
            lastRetro = LastRetro(w.typed, fix, w.typed, rest)
            if (logsText) android.util.Log.d("DogBoardAc", "hindsight fixed: ${w.typed} -> $fix")
        }

        /**
         * iOS retrocorrection: the word finished before this one may change now that this one
         * gives it context ("I lobe you" -> "I love you"). Runs before this word is corrected, so
         * its correction sees the fixed context.
         */
        /**
         * DogBoard (iOS retrocorrection across a plain space): the previous word and this one were one
         * word split by a stray space ("goi g" -> "going"): replaced by it, as decoded in the
         * background; a backspace right after puts the two back. True when it happened.
         */
        private fun joinAcrossSpace(separator: String): Boolean {
            val back = lookBack ?: return false
            val word = currentWord
            if (word.isEmpty || !correctionAllowed() || cursorInsideWord()) return false
            val typed = word.text
            val (forBack, forTyped, joined) = latestJoin ?: return false
            if (forBack !== back || forTyped != typed || joined == null) return false
            val old = "${back.committed} $typed"
            if (!replaceBeforeCursor(old, joined)) return false
            lookBack = null
            latestJoin = null
            unconfirmedWord = null // the earlier word isn't a word to learn any more
            noteFinishedWord(back.context, joined, null)
            lastRetro = LastRetro(old, joined, "${back.word.text} $typed", separator)
            if (logsText) android.util.Log.d("DogBoardAc", "join '$old' -> $joined")
            currentWord.clear()
            rejectedWord = null
            return true
        }

        /** Returns (old, new, old as typed) when the previous word was changed. */
        private fun retrocorrect(): Triple<String, String, String>? {
            val back = lookBack ?: return null
            lookBack = null
            val word = currentWord
            if (word.isEmpty || !correctionAllowed() || cursorInsideWord()) return null
            if (!prefs.correction.fixPreviousWord.get()) return null // Settings › Autocorrect (all settings)
            val typed = word.text
            if (!state.content.surroundingText.textBefore.endsWith("${back.committed} $typed")) return null
            // only from what is already decoded: this runs on the main thread as the word ends; when
            // the background decode of the current word hasn't caught up, there is no look back
            val current = latestDecode?.takeIf { it.first == decodeKey(typed, wordContext(), wordContext2()) }?.second ?: return null
            // with the experimental neural look back: its scores, when they were ready for this word
            val neural = latestRetroNeural?.takeIf { it.first == decodeKey(typed, wordContext(), wordContext2()) && back.decoded != null }?.second
            val newPrevious = TypingEngine.retrocorrection(back.word, back.committed, back.context, word, curlyApostrophes(),
                previousCandidates = (if (neural != null) back.decoded else back.candidates) ?: return null, currentCandidates = current,
                neuralPairs = neural) ?: return null
            if (!replaceBeforeCursor("${back.committed} $typed", "$newPrevious $typed")) return null
            state.content.selection.start.let { end ->
                val offset = end - typed.length - 1 - newPrevious.length
                if (offset >= 0) state.editor.commitCorrection(offset, back.committed, newPrevious)
            }
            state.content.surroundingText.textBefore.let { tb ->
                noteRecentCorrection(back.committed, newPrevious, tb.dropLast(typed.length + 1 + newPrevious.length))
            }
            if (logsText) android.util.Log.d("DogBoardAc", "retro ${back.committed} -> $newPrevious before $typed")
            // the earlier word's pending learning and kept-correction now refer to the new word
            unconfirmedWord = unconfirmedWord?.let { (ctx, w) -> if (w == back.committed) ctx to newPrevious else ctx to w }
            unconfirmedCorrection = null
            return Triple(back.committed, newPrevious, back.word.text)
        }

        /** Backspace right after a retrocorrected pair restores the earlier word, remembered as rejected. */
        private fun revertLastRetro(): Boolean {
            val retro = lastRetro ?: return false
            lastRetro = null
            pendingRetroAccept = null
            if (!replaceBeforeCursor(retro.new + retro.tail, retro.old + retro.tail)) return false
            if (!state.flags.isIncognitoMode) scope.launch { TypingEngine.reject(retro.typedOld, retro.new) }
            return true
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

        /** Corrections for the word the cursor rests in ([before] + [after] the cursor), decoded from key centres. */
        private fun publishWordAroundCursor(sink: (List<Suggestion>) -> Unit, before: String, after: String) {
            val whole = before + after
            // before the first letter tap there is no live key geometry: the standard QWERTY one
            // decodes key-centre taps the same way
            val keys = lastLetterKeys.ifEmpty { io.github.sillypuppyy.dogboard.engine.QwertyGeometry.letterKeys(375f, 216f) }
            val params = lastLetterParams ?: io.github.sillypuppyy.dogboard.touch.GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)
            val word = CurrentWord().apply { seedFrom(whole, keys, params) }
            if (word.tapsOrNull() == null || whole.none { it.isLetter() }) {
                sink(emptyList())
                return
            }
            val textBefore = state.content.surroundingText.textBefore
            val context = previousContext(textBefore.subSequence(0, textBefore.length - before.length))
            val context2 = io.github.sillypuppyy.dogboard.engine.previousContext2(textBefore.subSequence(0, textBefore.length - before.length))
            val curly = curlyApostrophes()
            wordAroundCursor = before to after
            // a word autocorrected in this field: its original comes first, then the words offered at
            // the time (iOS); the same place (text before it) wins, else the latest correction to it
            val beforeWord = textBefore.subSequence(0, textBefore.length - before.length)
            val recent = recentCorrections.lastOrNull { it.corrected == whole && beforeWord.endsWith(it.anchor) }
                ?: recentCorrections.lastOrNull { it.corrected == whole }
            suggestionJob = scope.launch {
                val candidates = TypingEngine.candidates(word, context, previous2 = context2)
                var strip = SuggestionStrip.build(whole, candidates, null, TypingEngine.caseFor(word, curly), null)
                if (recent != null) {
                    val undo = Suggestion(recent.original, Suggestion.Kind.LITERAL).also { it.revertOf = recent.corrected; it.forTyped = whole }
                    val case = TypingEngine.caseFor(word, curly)
                    val offered = recent.alternatives.map { a -> Suggestion(case(a) ?: a, Suggestion.Kind.WORD).also { it.forTyped = whole } }
                    strip = (listOf(undo) + offered + strip)
                        .filter { it === undo || !it.word.equals(recent.original, ignoreCase = true) }
                        .distinctBy { it.word.lowercase() }.take(SuggestionStrip.SLOTS)
                }
                if (isActive) sink(strip)
            }
        }

        /** Replaces the word the cursor rests in with [replacement]; the cursor ends up after it. */
        private fun acceptForWordAroundCursor(suggestion: Suggestion): Boolean {
            val (before, after) = wordAroundCursor ?: return false
            wordAroundCursor = null
            val surrounding = state.content.surroundingText
            if (!surrounding.textBefore.endsWith(before) || !surrounding.textAfter.startsWith(after)) return false
            if (suggestion.kind == Suggestion.Kind.EMOJI) return false
            repeat(after.length) { emitForwardDelete() } // keeps the controller's copy of the text in step
            if (!replaceBeforeCursor(before, suggestion.word)) return false
            val revertOf = suggestion.revertOf
            if (revertOf != null) {
                // undoing an autocorrection: a rejection (iOS), not learning the typed word as such
                recentCorrections.removeAll { it.corrected == revertOf && it.original == suggestion.word }
                if (!state.flags.isIncognitoMode) scope.launch { TypingEngine.reject(suggestion.word, revertOf) }
            } else if (suggestion.kind == Suggestion.Kind.LITERAL) learnWord(suggestion.word)
            lastCorrection = null
            rejectedWord = null
            publishSuggestions()
            return true
        }

        /**
         * DogBoard: puts in a swiped word as a bar pick would (word, then an automatic space that
         * punctuation can take back), after a space if the text doesn't end in one, in the case shift
         * asks for. Letters typed before the swipe are finished first, as space would. The bar then
         * offers the other words the swipe may have been.
         */
        fun acceptSwipe(words: List<String>) {
            if (!currentWord.isEmpty) emit(SPACE)
            val textBefore = state.content.surroundingText.textBefore
            val shift = state.flags.inputShiftState
            fun cased(w: String) = when (shift) {
                InputShiftState.CAPS_LOCK -> w.uppercase()
                InputShiftState.SHIFTED_MANUAL, InputShiftState.SHIFTED_AUTOMATIC -> w.replaceFirstChar { it.titlecase() }
                else -> w
            }
            val word = cased(words.first())
            val gap = if (textBefore.isEmpty() || textBefore.last().isWhitespace() || textBefore.last() in "([{\"'“‘¿¡") "" else " "
            val previous = wordContext()
            if (state.content.selection.isNotCollapsed()) {
                // a swipe over selected text replaces it, as typing does (user 2026-11-18: swiping did
                // nothing while text was selected)
                state.editor.batch { rawText("$gap$word ") }
            } else if (!replaceBeforeCursor("", "$gap$word ")) return
            confirmKeptCorrection()
            confirmPendingRetro()
            confirmAcceptedWord()
            noteFinishedWord(previous, word, wordContext2())
            lastCorrection = null
            rejectedWord = null
            lastSpaceUptime = SystemClock.uptimeMillis()
            autoSpace = true
            swipeCommitted = "$word "
            swipeOthers = words.drop(1).map(::cased)
            reevaluateInputShiftState()
            publishSuggestions()
        }

        /** DogBoard: another reading of a swipe picked from the bar replaces the swiped word. */
        private fun acceptSwipeAlternative(suggestion: Suggestion, old: String): Boolean {
            if (!replaceBeforeCursor(old, suggestion.word + " ")) return false
            val oldWord = old.trimEnd()
            swipeOthers = (listOf(oldWord) + swipeOthers).filter { it != suggestion.word }
            swipeCommitted = suggestion.word + " "
            autoSpace = true
            publishSuggestions()
            return true
        }

        /** Commits a suggestion-strip pick in place of the current word, followed by a space. */
        private fun acceptSuggestion(suggestion: Suggestion): Boolean {
            suggestion.swipeOf?.let { old -> return acceptSwipeAlternative(suggestion, old) }
            if (currentWord.isEmpty && wordAroundCursor != null) return acceptForWordAroundCursor(suggestion)
            val typed = currentWord.text
            // a chip from a strip built for an earlier keystroke must not replace the newer word
            if (suggestion.forTyped != null && suggestion.forTyped != typed) {
                publishSuggestions()
                return false
            }
            if (suggestion.kind == Suggestion.Kind.EMOJI) return acceptEmojiSuggestion(typed, suggestion.word)
            if (typed.isEmpty() && suggestion.kind != Suggestion.Kind.PREDICTION) return false
            val previous = wordContext()
            if (!replaceBeforeCursor(typed, suggestion.word + " ")) return false
            if (suggestion.kind == Suggestion.Kind.LITERAL) learnWord(suggestion.word)
            // a fix picked for a typo autocorrect would have left: autocorrect may be bolder (not a
            // completion of the typed letters, not the typed word itself)
            if (suggestion.kind != Suggestion.Kind.LITERAL && suggestion.kind != Suggestion.Kind.PREDICTION && typed.isNotEmpty() &&
                !suggestion.word.startsWith(typed, ignoreCase = true) && learningAllowed()) scope.launch { TypingEngine.fixPicked() }
            confirmKeptCorrection()
            confirmPendingRetro()
            confirmAcceptedWord()
            noteFinishedWord(previous, suggestion.word, wordContext2())
            currentWord.clear()
            lastCorrection = null
            rejectedWord = null
            lastSpaceUptime = SystemClock.uptimeMillis()
            autoSpace = true
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
            confirmPendingRetro()
            confirmAcceptedWord()
            if (typed.isNotEmpty()) noteFinishedWord(previous, typed, wordContext2())
            val app = state.editor.info.packageName
            if (!state.flags.isIncognitoMode) scope.launch { TypingEngine.emojiUsed(emoji, app) }
            currentWord.clear()
            lastCorrection = null
            rejectedWord = null
            lastSpaceUptime = SystemClock.uptimeMillis()
            autoSpace = true
            reevaluateInputShiftState()
            publishSuggestions()
            return true
        }

        /** [word] (after [previous]) was finished; it is accepted when the next word confirms it. */
        private fun noteFinishedWord(previous: String?, word: String, previous2: String? = null) {
            if (!learningAllowed()) return
            unconfirmedWord = previous to word
            unconfirmedWord2 = previous2
            // personal key offsets: only a word typed by tapping and kept exactly as typed
            unconfirmedTouches = currentWord.tapsOrNull()?.takeIf { word == currentWord.text }?.let { Triple(word, it, currentWord.keys) }
        }

        /** The word finished before this one was not undone: accept it (personal model, learning). */
        private fun confirmPendingRetro() {
            val (previous, word) = pendingRetroAccept ?: return
            val previous2 = pendingRetroAccept2
            pendingRetroAccept = null
            if (!state.flags.isIncognitoMode) scope.launch { TypingEngine.accepted(previous, word, previous2) }
        }

        private fun confirmAcceptedWord() {
            val (previous, word) = unconfirmedWord ?: return
            val previous2 = unconfirmedWord2
            unconfirmedWord = null
            val touches = unconfirmedTouches?.takeIf { it.first == word }
            unconfirmedTouches = null
            scope.launch {
                TypingEngine.accepted(previous, word, previous2)
                touches?.let { (w, taps, keys) -> TypingEngine.recordTouches(w, taps, keys) }
            }
        }

        /** The autocorrection before this word was not undone: record it as kept. */
        private fun confirmKeptCorrection() {
            val (typed, corrected) = unconfirmedCorrection ?: return
            unconfirmedCorrection = null
            scope.launch { TypingEngine.correctionKept(typed, corrected) }
        }


        /**
         * Debug logs that show typed text (DogBoardAc): off unless switched on for the tag
         * (`adb shell setprop log.tag.DogBoardAc DEBUG`, the emulator tests), so a debug build on a
         * phone keeps what is typed out of logcat.
         */
        private val logsText: Boolean get() = BuildConfig.DEBUG && android.util.Log.isLoggable("DogBoardAc", android.util.Log.DEBUG)

        /** The user kept [word] against autocorrect: remember it, except in incognito mode. */
        private fun learnWord(word: String) {
            if (!learningAllowed()) return
            scope.launch { TypingEngine.learn(word) }
        }

        private fun noteRecentCorrection(original: String, corrected: String, textBeforeWord: CharSequence, alternatives: List<String> = emptyList()) {
            recentCorrections.addLast(RecentCorrection(original, corrected, textBeforeWord.takeLast(24).toString(), alternatives))
            while (recentCorrections.size > MAX_RECENT_CORRECTIONS) recentCorrections.removeFirst()
        }

        /**
         * DogBoard: forgets everything tied to the previous field, so nothing carries over: the
         * user's shift choice (an empty new field must auto-capitalise), the automatic space, the
         * double-space timer, and the word or correction waiting to be accepted (it is dropped,
         * not accepted: the previous field may have been incognito, or the text discarded).
         */
        private fun resetFieldState() {
            recentCorrections.clear()
            shiftChosenAt = null
            autoSpace = false
            lastSpaceUptime = null
            unconfirmedWord = null
            pendingRetroAccept = null
            unconfirmedCorrection = null
            deletingWord = null
            wordAroundCursor = null
            latestDecode = null
            latestStage1 = null
            latestRetroNeural = null
            previousKeystrokeCorrection = null
            latestCorrection = null
        }

        private fun resetWordTracking() {
            lookBack = null
            lastRetro = null
            currentWord.clear()
            pendingTap = null
            lastCorrection = null
            rejectedWord = null
            publishSuggestions()
        }

        /** True when a letter follows the cursor: only part of the word is before it. */
        /**
         * iOS FilterAlteredURLFragments: a word right after a "." with no space ("www.exmaple",
         * "file.tx") is part of an address or file name and is not corrected. DogBoard: the same
         * for letters right after a digit ("1st", "5pm", "2nd"), which are not words on their own.
         */
        private fun followsDot(word: String): Boolean {
            val before = state.content.surroundingText.textBefore
            val i = before.length - word.length - 1
            return i >= 0 && before.endsWith(word) && (before[i] == '.' || before[i].isDigit())
        }

        /** [word] typed right after a number: the fixed suffix ([io.github.sillypuppyy.dogboard.engine.DigitWords]), or null. */
        private fun suffixAfterNumber(word: String): String? {
            val before = state.content.surroundingText.textBefore
            if (!before.endsWith(word)) return null
            val digits = before.substring(0, before.length - word.length).takeLastWhile { it.isDigit() }
            return io.github.sillypuppyy.dogboard.engine.DigitWords.fix(digits, word)
        }

        private fun cursorInsideWord(): Boolean =
            state.content.surroundingText.textAfter.firstOrNull()?.let { isWordChar(it) } == true

        /** Smart quotes are on here, so apostrophes in corrections are curly like the typed ones. */
        private fun curlyApostrophes(): Boolean = wordEngineAllowed() && prefs.typing.smartQuotes.get()

        private fun correctionAllowed(): Boolean =
            prefs.correction.autocorrect.get() && wordEngineAllowed() && !formField()

        /**
         * Name, address and phonetic fields hold proper nouns the lexicon mostly lacks (and other
         * people's details): suggestions stay, but nothing is autocorrected or learned there.
         */
        private fun formField(): Boolean = state.editor.info.inputAttributes.variation in FORM_VARIATIONS

        private fun learningAllowed(): Boolean = !state.flags.isIncognitoMode && wordEngineAllowed() && !formField()

        /**
         * Suggestions in the bar: any text field except emails, URLs and passwords, also one that asks
         * for no suggestions (Google's search box does), as Gboard shows its bar there (checked on the
         * emulator, 2026-11-14); such fields still get no autocorrection and no learning.
         */
        private fun suggestionsAllowed(): Boolean {
            val attrs = state.editor.info.inputAttributes
            // English word lists only for now (internationalisation pass): no English suggestions on other layouts
            return englishLayout() && attrs.type == InputAttributes.Type.TEXT && attrs.variation !in NO_DOUBLE_SPACE_VARIATIONS
        }

        /** DogBoard: the word engine's language matches the layout (English only until the i18n pass). */
        private fun englishLayout(): Boolean = prefs.keyboard.layoutId.get().endsWith("-en")

        /** DogBoard: the layout's language tag (from the model, as composition uses it). */
        private var languageTag = ""

        /** France and Belgium French keep a space before ? ! : ; (fr_CA and fr_CH don't, as on iOS). */
        private fun keepsSpaceBeforeMarks(): Boolean {
            val t = languageTag.replace('_', '-').lowercase()
            return t == "fr" || t.startsWith("fr-fr") || t.startsWith("fr-be")
        }

        private fun wordEngineAllowed(): Boolean {
            val attrs = state.editor.info.inputAttributes
            // the word engine is English for now: other layouts type plainly until the
            // internationalisation pass brings their word lists and language models
            return englishLayout() &&
                attrs.type == InputAttributes.Type.TEXT &&
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

        private fun decodeKey(typed: String, context: String?, context2: String?) = "$typed\u0000$context\u0000$context2"

        /** The word before [wordContext]'s (two-word-context model). */
        private fun wordContext2(): String? {
            val textBefore = state.content.surroundingText.textBefore
            val typed = currentWord.text
            val end = if (textBefore.endsWith(typed)) textBefore.length - typed.length else textBefore.length
            return io.github.sillypuppyy.dogboard.engine.previousContext2(textBefore.subSequence(0, end))
        }

        /** Recomputes the suggestion strip, e.g. once the engine has finished loading. */
        fun refreshSuggestions() = publishSuggestions()

        private fun publishSuggestions() {
            undoEdit?.let { u -> if (state.content.surroundingText.textBefore != u.after || state.content.selection.isNotCollapsed()) undoEdit = null }
            val sink = suggestionSink ?: return
            // the designer's contact chips: a contact's first name typed (opt-in contacts; never in
            // incognito or password fields)
            val info = state.editor.info
            io.github.sillypuppyy.dogboard.ime.LiveStrip.chips.value = when {
                !currentWord.isEmpty && learningAllowed() && state.content.selection.isCollapsed() ->
                    io.github.sillypuppyy.dogboard.ime.LiveStrip.contactChips(currentWord.text)
                // date fields, nothing typed yet: Today / Tomorrow / the day after
                currentWord.isEmpty && state.content.surroundingText.textBefore.isEmpty() &&
                    io.github.sillypuppyy.dogboard.ime.LiveStrip.isDateField(info.inputAttributes, info.base.hintText) ->
                    io.github.sillypuppyy.dogboard.ime.LiveStrip.dateChips(java.time.LocalDate.now())
                else -> emptyList()
            }
            suggestionJob?.cancel()
            wordAroundCursor = null
            // DogBoard: right after a swipe the bar offers what else it may have been
            swipeCommitted?.let { committed ->
                if (currentWord.isEmpty && state.content.surroundingText.textBefore.endsWith(committed) && swipeOthers.isNotEmpty()) {
                    sink(swipeOthers.take(SuggestionStrip.SLOTS).map { Suggestion(it, Suggestion.Kind.WORD).also { s -> s.swipeOf = committed } })
                    return
                }
                swipeCommitted = null
            }
            if (!suggestionsAllowed() || state.content.selection.isNotCollapsed()) {
                sink(emptyList())
                return
            }
            if (currentWord.isEmpty) {
                // the cursor rests in or after a word not typed just now: offer corrections for the
                // whole word (iOS and Gboard do), picking one replaces it
                val textBefore = state.content.surroundingText.textBefore
                val textAfter = state.content.surroundingText.textAfter
                val before = trailingWord(textBefore)
                val after = leadingWord(textAfter)
                // the editor gave only a window of text (50 before, 10 after): a word that fills it
                // to the edge may go on beyond it, and replacing the visible part would leave the rest
                val truncated = (after.isNotEmpty() && after.length == textAfter.length && textAfter.length >= SURROUNDING_AFTER) ||
                    (before.isNotEmpty() && before.length == textBefore.length && textBefore.length >= SURROUNDING_BEFORE)
                if ((before.isNotEmpty() || after.isNotEmpty()) && !truncated && prefs.suggestion.enabled.get()) {
                    publishWordAroundCursor(sink, before, after)
                    return
                }
            }
            if (cursorInsideWord()) {
                sink(emptyList())
                return
            }
            // letters right after a digit or a dot belong to a token ("5th", "www.site"): no word
            // to offer for them alone ("Th" for the "th" of "5th" would replace half the token)
            if (!currentWord.isEmpty && followsDot(currentWord.text)) {
                sink(emptyList())
                return
            }
            val context = wordContext()
            val context2 = wordContext2()
            // iOS "Predictive" off: no predictions or other words, but the bar still shows the
            // autocorrection and the typed word
            val predictive = prefs.suggestion.enabled.get()
            if (currentWord.isEmpty) {
                if (!predictive) {
                    sink(emptyList())
                    return
                }
                // next-word predictions, only right after a space or at the start of the text
                val textBefore = state.content.surroundingText.textBefore
                if (textBefore.isNotEmpty() && !textBefore.last().isWhitespace()) {
                    sink(emptyList())
                    return
                }
                suggestionJob = scope.launch {
                    // a full window starts mid-word: its first word is dropped (a fragment misleads the model)
                    val text = textBefore.toString().let { t -> if (t.length >= SURROUNDING_BEFORE) t.substringAfter(' ', t) else t }
                    // a newer word end wants its own prediction: this one is skipped, also after waiting
                    // for the model (it runs one prediction at a time)
                    ensureActive()
                    val words = io.github.sillypuppyy.dogboard.perf.GcStats.track("predictions") { TypingEngine.predictions(context, wordContext2(), textBefore = text, stillWanted = { isActive }) }
                    TypingEngine.lastNeuralMillis.takeIf { it >= 0 }?.let { android.util.Log.i("DogBoardPerf", "neural predictions took ${it}ms") }
                    io.github.sillypuppyy.dogboard.perf.GcStats.logWord()
                    // iOS: predictions are capitalised after a sentence end only with auto-capitalisation on
                    val cased = if (context == Bigrams.START && prefs.typing.autoCapitalization.get()) words.map { w -> w.replaceFirstChar { it.uppercaseChar() } } else words
                    if (isActive) sink(cased.map { Suggestion(it, Suggestion.Kind.PREDICTION) })
                }
                return
            }
            val word = currentWord.snapshot()
            val rejected = rejectedWord
            // the strip marks an autocorrection only where one is made (not in no-suggestion or form fields)
            val withCorrection = correctionAllowed()
            val level = prefs.correction.autocorrectLevel.get()
            TypingEngine.blockNotSuggestible = prefs.suggestion.blockPossiblyOffensive.get()
            val textBefore = state.content.surroundingText.textBefore.toString()
            val app = state.editor.info.packageName
            // off in incognito and with the emoji-suggestions switch (Media settings) off
            val emojiMode = if (state.flags.isIncognitoMode || !prefs.emoji.suggestionEnabled.get()) {
                io.github.sillypuppyy.dogboard.engine.EmojiSuggestionMode.OFF
            } else prefs.suggestion.emojiMode.get()
            // the text before the word, for the neural model (a full window starts mid-word: cut, as for predictions)
            val neuralContext = textBefore.takeIf { it.endsWith(word.text) }?.dropLast(word.text.length)
                ?.let { t -> if (textBefore.length >= SURROUNDING_BEFORE) t.substringAfter(' ', t) else t }
            val back = lookBack
            val neuralRetro = prefs.correction.neuralRetro.get()
            suggestionJob = scope.launch {
                val key = decodeKey(word.text, context, context2)
                // stage 1: the decoder's candidates, published at once (the word end uses them: it never
                // waits for the neural model, user 2026-11-17: "pausing right before it autocorrects")
                val decoded = io.github.sillypuppyy.dogboard.perf.GcStats.track("candidates") { TypingEngine.candidates(word, context, previous2 = context2) }
                latestDecode = key to decoded
                latestStage1 = key to decoded
                fun publish(candidates: List<io.github.sillypuppyy.dogboard.engine.Candidate>) {
                    val previousChoice = latestCorrection?.takeIf { it.first == word.text.dropLast(1) }?.second
                    val correction = if (withCorrection && word.text != rejected) TypingEngine.correction(word, candidates, previousChoice, curlyApostrophes(), level) else null
                    latestCorrection?.takeIf { it.first != word.text }?.let { previousKeystrokeCorrection = it }
                    latestCorrection = word.text to correction
                    val emoji = TypingEngine.emojiFor(textBefore, app, emojiMode, prefs.emoji.preferredSkinTone.get().id)
                    val strip = SuggestionStrip.build(word.text, candidates, correction, TypingEngine.caseFor(word, curlyApostrophes()), emoji)
                    if (isActive) {
                        sink(if (predictive) strip else strip.filter { it.kind == Suggestion.Kind.LITERAL || it.kind == Suggestion.Kind.AUTOCORRECT })
                    }
                }
                publish(decoded)
                // a word split by a stray space ("goi g"): its rejoined reading, ready for the word end
                latestJoin = back?.spaceTap?.takeIf { prefs.correction.joinSplitWords.get() }?.let { tap ->
                    back.candidates?.let { prev ->
                        Triple(back, word.text, io.github.sillypuppyy.dogboard.perf.GcStats.track("join") { TypingEngine.spaceJoin(back.word, back.committed, back.context, tap, word, prev, decoded, curlyApostrophes()) })
                    }
                }
                if (logsText && back != null) android.util.Log.d("DogBoardAc", "join check '${back.committed} ${word.text}': tap=${back.spaceTap} -> ${latestJoin?.third} ${TypingEngine.lastJoinDebug}")
                // stage 2: refined by the neural model, when it finishes before the next key
                if (neuralContext != null && isActive) {
                    val refined = io.github.sillypuppyy.dogboard.perf.GcStats.track("neural") { TypingEngine.refineWithNeural(decoded, neuralContext) }
                    TypingEngine.lastNeuralScoreMillis.takeIf { it >= 0 }?.let { android.util.Log.i("DogBoardPerf", "neural candidate scores took ${it}ms") }
                    if (refined != null && isActive && latestDecode?.first == key) {
                        latestDecode = key to refined
                        publish(refined)
                    }
                    // stage 3 (experimental): the look back at the previous word, scored by the neural model
                    val prevDecoded = back?.decoded
                    if (neuralRetro && prevDecoded != null && isActive && neuralContext.endsWith(back.committed + " ")) {
                        val before = neuralContext.dropLast(back.committed.length + 1)
                        val scores = io.github.sillypuppyy.dogboard.perf.GcStats.track("neural retro") {
                            TypingEngine.retroNeuralScores(prevDecoded, refined ?: decoded, before)
                        }
                        TypingEngine.lastNeuralRetroMillis.takeIf { it >= 0 && scores != null }?.let { android.util.Log.i("DogBoardPerf", "neural look back took ${it}ms") }
                        if (scores != null && isActive) latestRetroNeural = key to scores
                    }
                }
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
            // DogBoard: deleting a selection can be undone (Gboard: selection and swipe deletes)
            val selected = state.content.surroundingText.textSelected.takeIf { state.content.selection.isNotCollapsed() }
            if (!selected.isNullOrEmpty()) {
                emitBackspaceSelection(selected)
                return
            }
            autoSpace = false
            lastSpaceUptime = null
            pendingTap = null
            if (prefs.correction.undoOnBackspace.get() && (revertLastCorrection() || revertLastRetro())) {
                reevaluateInputShiftState()
                publishSuggestions()
                return
            }
            lastRetro = null
            // DogBoard: remember the word being deleted into; report it once it is gone (iOS soft rejections)
            val wordBeforeDelete = trailingWord(state.content.surroundingText.textBefore)
            if (deletingWord == null && wordBeforeDelete.isNotEmpty()) deletingWord = wordBeforeDelete
            super.emitBackspace()
            expectedContentQueue.push(state.content)
            if (trailingWord(state.content.surroundingText.textBefore).isEmpty()) {
                deletingWord?.let { gone -> if (learningAllowed()) scope.launch { TypingEngine.noteDeleted(gone) } }
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

        private fun emitBackspaceSelection(selected: String) {
            autoSpace = false
            lastSpaceUptime = null
            pendingTap = null
            lastRetro = null
            super.emitBackspace()
            expectedContentQueue.push(state.content)
            val textBefore = state.content.surroundingText.textBefore
            currentWord.clear()
            currentWord.seedFrom(textBefore, currentWord.keys, currentWord.params)
            publishSuggestions()
            undoEdit = UndoEdit("", selected, textBefore)
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
                    // DogBoard: no action given: a new line where lines exist, else a real Enter key
                    // (custom editors and games ignore editor action 0; FlorisBoard upstream #3262)
                    ImeOptions.Action.UNSPECIFIED -> if (info.inputAttributes.flagTextMultiLine) {
                        emitEnterKey()
                    } else {
                        state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_ENTER)
                    }
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

        /** DogBoard: a smart chip tapped: its text replaces the word being typed, then a space; nothing is learned. */
        fun emitChip(text: String, space: Boolean = true, undoable: Boolean = false, original: String? = null) {
            val typed = currentWord.text
            val insert = if (space) "$text " else text
            if (typed.isNotEmpty() && !replaceBeforeCursor(typed, insert)) return
            if (typed.isEmpty()) emitText(insert.asK3String())
            currentWord.clear()
            io.github.sillypuppyy.dogboard.ime.LiveStrip.chips.value = emptyList()
            publishSuggestions()
            // a piece of the clip pasted from its chip: Undo takes it back (the word it replaced returns)
            if (undoable) state.content.surroundingText.textBefore.let { after -> if (after.endsWith(insert)) undoEdit = UndoEdit(insert, typed, after, original?.takeIf { it != text }) }
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

        fun emitClipboardItem(item: ClipboardItem?, undoable: Boolean = false): Boolean {
            return item != null && when (item.type) {
                ItemType.TEXT -> {
                    // DogBoard: Settings › Clipboard › Strip trackers from links, Expand short links (opt-in)
                    val pasted = item.text?.let { cleanedPaste(it) }
                    val text = pasted?.asK3String()
                    if (text != null) {
                        val selected = state.content.surroundingText.textSelected.takeIf { state.content.selection.isNotCollapsed() }.orEmpty()
                        emitText(text)
                        // DogBoard: Gboard's Undo chip after a paste from the clipboard chip
                        if (undoable) state.content.surroundingText.textBefore.let { after ->
                            if (after.endsWith(pasted)) undoEdit = UndoEdit(pasted, selected, after, item.text?.takeIf { it != pasted })
                        }
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
            shiftChosenAt = state.content.surroundingText.textBefore.toString()
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
            if (shiftChosenAt != null && shiftChosenAt == state.content.surroundingText.textBefore.toString()) return
            shiftChosenAt = null
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
            languageTag = model.locales.getOrElse(0) { "" }
            val locale = FlorisLocale.fromTag(languageTag)
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
        // DogBoard: the text window read around the cursor on each selection change
        private const val SURROUNDING_BEFORE = 50
        private const val SURROUNDING_AFTER = 10
        private val NEWLINE_SEQ = "\n".asK3String()

        /** DogBoard: marks that go before an automatic space, which then follows them. */
        private val SWAP_WITH_AUTO_SPACE = setOf(".", ",", "!", "?", ":", ";")

        /** DogBoard: marks that replace an automatic space (closing quotes and brackets). */
        private val REPLACE_AUTO_SPACE = setOf(")", "]", "}", "”", "’", "»") // not straight quotes: they may open one

        /** DogBoard: how long a word end waits for the pending background decode (Gboard: 40 ms). */
        private const val WAIT_FOR_DECODE_MS = 40L

        /** DogBoard: marks that also go before a space the user typed (not : ; which start emoticons). */
        private val SWAP_AFTER_TYPED_SPACE = setOf(".", ",", "!", "?")

        /** DogBoard: marks French typography spaces off (iOS fr_FR / fr_BE keep the space before them). */
        private val FRENCH_SPACED = setOf("?", "!", ":", ";")
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
private val FORM_VARIATIONS = setOf(
    InputAttributes.Variation.PERSON_NAME,
    InputAttributes.Variation.POSTAL_ADDRESS,
    InputAttributes.Variation.PHONETIC,
)
/** Autocorrections remembered per field for going back into a word (iOS keeps the whole document's). */
private const val MAX_RECENT_CORRECTIONS = 50

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

/** DogBoard: an edit the bar's Undo chip can take back (see ImeController's undoEdit). */
/** [original]: the text as copied, when the paste was cleaned (links without trackers, short links expanded). */
private data class UndoEdit(val inserted: String, val removed: String, val after: String, val original: String? = null)

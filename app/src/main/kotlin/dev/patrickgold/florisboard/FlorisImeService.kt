/*
 * Copyright (C) 2021-2026 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard

import kotlinx.coroutines.flow.first
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.inputmethodservice.ExtractEditText
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.util.Size
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import dev.patrickgold.florisboard.app.FlorisAppActivity
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import dev.patrickgold.florisboard.ime.keyboard3.isFullscreenInputRequired
import dev.patrickgold.florisboard.ime.keyboard3.ImeEditor
import dev.patrickgold.florisboard.ime.landscapeinput.ExtractedInputRootView
import dev.patrickgold.florisboard.ime.landscapeinput.LandscapeInputUiMode
import dev.patrickgold.florisboard.ime.lifecycle.LifecycleInputMethodService
import dev.patrickgold.florisboard.ime.nlp.NlpInlineAutofill
import dev.patrickgold.florisboard.ime.smartbar.createInlineSuggestionUiStyleBundle
import dev.patrickgold.florisboard.ime.theme.WallpaperChangeReceiver
import dev.patrickgold.florisboard.ime.window.ImeRootView
import dev.patrickgold.florisboard.ime.window.ImeWindowController
import dev.patrickgold.florisboard.lib.devtools.LogTopic
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.lib.devtools.flogInfo
import dev.patrickgold.florisboard.lib.devtools.flogWarning
import dev.patrickgold.florisboard.lib.util.InputMethodUtils
import dev.patrickgold.florisboard.lib.util.debugSummarize
import dev.patrickgold.florisboard.lib.util.launchActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import org.florisboard.lib.android.AndroidInternalR
import org.florisboard.lib.android.AndroidVersion
import org.florisboard.lib.android.systemServiceOrNull
import org.florisboard.lib.kotlin.collectIn
import org.florisboard.lib.kotlin.collectLatestIn
import org.k3lp.runtime.K3TextRange
import java.lang.ref.WeakReference

/**
 * Global weak reference for the [FlorisImeService] class. This is needed as certain actions (request hide, switch to
 * another input method, getting the editor instance / input connection, etc.) can only be performed by an IME
 * service class and no context-bound managers. This reference is exclusively used by the companion helper methods
 * of [FlorisImeService], which provide a safe and memory-leak-free way of performing certain actions on the Floris
 * input method service instance.
 */
private var FlorisImeServiceReference = WeakReference<FlorisImeService?>(null)

/**
 * Core class responsible for linking together all managers and UI composables to provide an IME service. Sets
 * up the window and context to be lifecycle-aware, so LiveData and Jetpack Compose can be used without issues.
 */
class FlorisImeService : LifecycleInputMethodService() {
    companion object {
        private val InlineSuggestionUiSmallestSize = Size(0, 0)
        private val InlineSuggestionUiBiggestSize = Size(Int.MAX_VALUE, Int.MAX_VALUE)

        fun currentInputConnection(): InputConnection? {
            return FlorisImeServiceReference.get()?.currentInputConnection
        }

        /**
         * Hides the IME and launches [FlorisAppActivity].
         */
        fun launchSettings() {
            val ims = FlorisImeServiceReference.get() ?: return
            ims.requestHideSelf(0)
            ims.launchActivity(FlorisAppActivity::class) {
                it.flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }

        fun showUi() {
            val ims = FlorisImeServiceReference.get() ?: return
            ims.showUi()
        }

        fun hideUi() {
            val ims = FlorisImeServiceReference.get() ?: return
            ims.hideUi()
        }

        fun switchToPrevInputMethod(): Boolean {
            val ims = FlorisImeServiceReference.get() ?: return false
            return ims.switchToPrevInputMethod()
        }

        fun switchToNextInputMethod(): Boolean {
            val ims = FlorisImeServiceReference.get() ?: return false
            return ims.switchToNextInputMethod()
        }

        fun switchToVoiceInputMethod(): Boolean {
            val ims = FlorisImeServiceReference.get() ?: return false
            return ims.switchToVoiceInputMethod()
        }

        fun showImePicker(): Boolean {
            val ims = FlorisImeServiceReference.get() ?: return false
            return InputMethodUtils.showImePicker(ims)
        }

        fun windowControllerOrNull(): ImeWindowController? {
            val ims = FlorisImeServiceReference.get() ?: return null
            return ims.windowController
        }
    }

    fun hideUi() {
        requestHideSelf(0)
    }

    /**
     * Show the Ime UI
     *
     * Note: This function can be replaced with a `requestShowSelf(0)`
     * call once we've set the minApiLevel to 28 (Android 9)
     */
    fun showUi() {
        if (AndroidVersion.ATLEAST_API28_P) {
            requestShowSelf(0)
        } else {
            @Suppress("DEPRECATION")
            systemServiceOrNull(InputMethodManager::class)
                ?.showSoftInputFromInputMethod(currentInputBinding.connectionToken, 0)
        }
    }


    /**
     * Switch to previous input method
     *
     * Note: This function can be replaced with a `switchToPreviousInputMethod()`
     * call once we've set the minApiLevel to 28 (Android 9)
     *
     * @return true if the switch was successful
     */
    fun switchToPrevInputMethod(): Boolean {
        val imm = systemServiceOrNull(InputMethodManager::class)
        try {
            if (AndroidVersion.ATLEAST_API28_P) {
                return switchToPreviousInputMethod()
            } else {
                window.window?.let { window ->
                    @Suppress("DEPRECATION")
                    return imm?.switchToLastInputMethod(window.attributes.token) == true
                }
            }
        } catch (e: Exception) {
            flogError { "Unable to switch to the previous IME" }
            imm?.showInputMethodPicker()
        }
        return false
    }

    /**
     * Switch to next input method
     *
     * Note: This function can be replaced with a `switchToNextInputMethod(false)`
     * call once we've set the minApiLevel to 28 (Android 9)
     *
     * @return true if the switch was successful
     */
    fun switchToNextInputMethod(): Boolean {
        val imm = systemServiceOrNull(InputMethodManager::class)
        try {
            if (AndroidVersion.ATLEAST_API28_P) {
                return switchToNextInputMethod(false)
            } else {
                window.window?.let { window ->
                    @Suppress("DEPRECATION")
                    return imm?.switchToNextInputMethod(window.attributes.token, false) == true
                }
            }
        } catch (e: Exception) {
            flogError { "Unable to switch to the next IME" }
            imm?.showInputMethodPicker()
        }
        return false
    }

    /**
     * Switch to next input method
     *
     * Note: The inner part of this function can be replaced with a
     *
     * `switchInputMethod(el.id, el.getSubtypeAt(i))` call once we've set the minApiLevel to 28 (Android 9)
     *
     * @return true if the switch was successful
     */
    fun switchToVoiceInputMethod(): Boolean {
        val imm = systemServiceOrNull(InputMethodManager::class) ?: return false
        val list: List<InputMethodInfo> = imm.enabledInputMethodList
        for (el in list) {
            for (i in 0 until el.subtypeCount) {
                // Check if the subtype is a voice input method.
                // We need to hardcode 'voice' here because the SUBTYPE_MODE_VOICE constant is private.
                // https://cs.android.com/android/platform/superproject/+/android-latest-release:frameworks/base/core/java/android/view/inputmethod/InputMethodManager.java;drc=2b278ab3ac73bb5596327aac1298df85cd94e454;l=309
                if (el.getSubtypeAt(i).mode != "voice") continue
                if (AndroidVersion.ATLEAST_API28_P) {
                    switchInputMethod(el.id, el.getSubtypeAt(i))
                    return true
                } else {
                    window.window?.let { window ->
                        @Suppress("DEPRECATION")
                        imm.setInputMethod(window.attributes.token, el.id)
                        return true
                    }
                }
            }
        }
        // TODO we have no access to interactionController here
        // showShortToastSync("Failed to find voice IME, do you have one installed?")
        return false
    }

    private val prefs by FlorisPreferenceStore
    private val appContext = inferFlorisApplication()
    val storageController = appContext.storageController
    val imeController = appContext.imeController
    val themeController = appContext.themeController

    private val nlpManager by nlpManager()
    private val subtypeManager by subtypeManager()

    val windowController = ImeWindowController(prefs, lifecycleScope)

    private val systemLocalesFlow = MutableStateFlow(LocaleList())
    var resourcesContext by mutableStateOf(this as Context)
        private set

    private val wallpaperChangeReceiver = WallpaperChangeReceiver()

    init {
        setTheme(R.style.FlorisImeTheme)
    }

    override fun onCreate() {
        super.onCreate()
        io.github.sillypuppyy.dogboard.perf.Perf.mark("service onCreate") // DogBoard: startup timing
        FlorisImeServiceReference = WeakReference(this)
        // DogBoard: Advanced > Neural model: off lets it go at once, on loads it (where the phone runs it)
        lifecycleScope.launch {
            prefs.correction.neuralModel.asFlow().collect { on ->
                if (!on) io.github.sillypuppyy.dogboard.engine.TypingEngine.attachNeuralLm(null)
                else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { io.github.sillypuppyy.dogboard.ime.EngineLoader.ensureNeural(this@FlorisImeService) }
            }
        }
        // DogBoard: Brave's link rules for "Strip trackers from links" (small: 31 KB)
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { io.github.sillypuppyy.dogboard.ime.LinkCleaner.ensureLoaded(this@FlorisImeService) }
        systemLocalesFlow.value = resources.configuration.locales
        // DogBoard: debug builds log main-thread stalls (DogBoardJank) and disk access on it (StrictMode)
        if (dev.patrickgold.florisboard.BuildConfig.DEBUG) io.github.sillypuppyy.dogboard.perf.JankWatch.install()
        // DogBoard: a notification when adaptive autocorrect changes step (user, 2026-11-16)
        io.github.sillypuppyy.dogboard.ime.AdaptiveNotifier.install(this)

        // DogBoard: word engine feeding the suggestion strip; the dictionary loads in the background
        imeController.suggestionSink = { strip ->
            io.github.sillypuppyy.dogboard.ime.LiveStrip.strip.value = strip // the design's bar
            nlpManager.suggestDirectly(strip.map { io.github.sillypuppyy.dogboard.ime.DogBoardCandidate(it) })
        }
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            io.github.sillypuppyy.dogboard.perf.Perf.measure("lexicon load") {
                // the saved language, not the default the subtype manager shows until its list is read: the first load
                // was English, then switched, and the first words typed in another language went through English
                // (emulator 2026-11-23: Telex "tooi ddi" unconverted, then "language: en-US -> vi")
                val saved = kotlinx.coroutines.withTimeoutOrNull(1500) { subtypeManager.subtypesFlow.first { it.isNotEmpty() } }
                val savedId = prefs.localization.activeSubtypeId.get()
                val first = saved?.let { list -> list.firstOrNull { it.id == savedId } ?: list.first() } ?: subtypeManager.activeSubtype
                io.github.sillypuppyy.dogboard.ime.EngineLoader.prefer(this@FlorisImeService, first.primaryLocale.languageTag())
                io.github.sillypuppyy.dogboard.ime.EngineLoader.ensureLoaded(this@FlorisImeService)
            }
            // DogBoard: the names of the apps on the phone are words too (iOS does this)
            io.github.sillypuppyy.dogboard.engine.TypingEngine.addAppNames(io.github.sillypuppyy.dogboard.apps.AppNames.read(this@FlorisImeService))
            imeController.updateState { refreshSuggestions() }
            // DogBoard: contact names, only while the user opts in and grants access; switching the
            // setting off takes them out again without a restart
            prefs.spelling.useContacts.asFlow().collectLatest { on ->
                val names = if (on) io.github.sillypuppyy.dogboard.contacts.ContactNames.read(this@FlorisImeService) else emptySet()
                io.github.sillypuppyy.dogboard.ime.LiveStrip.contactCards =
                    if (on) io.github.sillypuppyy.dogboard.contacts.ContactCards.read(this@FlorisImeService) else emptyMap()
                io.github.sillypuppyy.dogboard.engine.TypingEngine.setContactNames(names)
                imeController.updateState { refreshSuggestions() }
            }
        }

        // DogBoard: the keyboard's language picks the word engine's language pack (when one exists for it; English stays
        // otherwise). The first value waits for the first load above, then a change unloads and loads the other pack
        var languageJob: kotlinx.coroutines.Job? = null
        subtypeManager.activeSubtypeFlow.collectIn(lifecycleScope) { _ ->
            // one job, the latest: early values (the default before the saved languages load) woke with the real one
            // and the stale one won (es -> en-US); the language is read when the job runs, not when it was queued
            languageJob?.cancel()
            languageJob = lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                // the saved language often arrives while the first load (which saw the default) is still running
                while (!io.github.sillypuppyy.dogboard.engine.TypingEngine.isReady) kotlinx.coroutines.delay(200)
                val active = subtypeManager.activeSubtype
                val tag = active.primaryLocale.languageTag()
                // the entry's own layout (a language added with several layouts), else the language's (its default unless
                // the one in use types that language): before the switch, so the keys and the pack's own-key letters match
                val own = active.layoutId?.takeIf { id -> io.github.sillypuppyy.dogboard.ime.GeneratedLayouts.all.any { it.id == id } }
                (own?.takeIf { it != prefs.keyboard.layoutId.get() }
                    ?: if (own == null) io.github.sillypuppyy.dogboard.ime.LanguageLayouts.forLanguage(tag, prefs.keyboard.layoutId.get(), prefs.keyboard.layoutByLanguage.get()) else null)
                    ?.let { prefs.keyboard.layoutId.set(it) }
                val switched = io.github.sillypuppyy.dogboard.ime.EngineLoader.switchTo(this@FlorisImeService, tag)
                // a language typed together with this one ("Type together with"): both decoded, candidates merged
                val second = io.github.sillypuppyy.dogboard.ime.EngineLoader.setSecondaries(this@FlorisImeService, active.secondaryLocales.map { it.languageTag() })
                if (switched || second) imeController.updateState { refreshSuggestions() }
            }
        }

        WindowCompat.setDecorFitsSystemWindows(window.window!!, false)
        windowController.onConfigurationChanged(resources.configuration)
        windowController.activeWindowConfig.collectLatestIn(lifecycleScope) {
            // TODO update inputMethod state
        }

        combine(
            systemLocalesFlow,
            subtypeManager.activeSubtypeFlow,
            prefs.localization.displayKeyboardLabelsInSubtypeLanguage.asFlow(),
        ) { systemLocales, subtype, shouldUseSubtypeLanguage ->
            systemLocales to (if (shouldUseSubtypeLanguage) subtype.primaryLocale else null)
        }.collectIn(lifecycleScope) { (systemLocales, subtypeLocale) ->
            val config = Configuration().apply {
                setToDefaults()
                if (subtypeLocale != null) {
                    setLocale(subtypeLocale.base)
                } else {
                    setLocales(systemLocales)
                }
            }
            resourcesContext = createConfigurationContext(config)
        }

        prefs.physicalKeyboard.showOnScreenKeyboard.asFlow().collectIn(lifecycleScope) {
            updateInputViewShown()
        }

        @Suppress("DEPRECATION") // We do not retrieve the wallpaper but only listen to changes
        registerReceiver(wallpaperChangeReceiver, IntentFilter(Intent.ACTION_WALLPAPER_CHANGED))
    }

    override fun onCreateInputView(): View? {
        io.github.sillypuppyy.dogboard.perf.Perf.mark("onCreateInputView") // DogBoard: startup timing
        super.installViewTreeOwners()
        val content = window.window!!.findViewById<ViewGroup>(android.R.id.content)
        content.addView(ImeRootView(this))
        // Disable the default input view placement
        return null
    }

    override fun onCreateCandidatesView(): View? {
        // Disable the default candidates view
        return null
    }

    override fun onCreateExtractTextView(): View {
        super.installViewTreeOwners()
        // Consider adding a fallback to the default extract edit layout if user reports come
        // that this causes a crash, especially if the device manufacturer of the user device
        // is a known one to break AOSP standards...
        val defaultExtractView = super.onCreateExtractTextView()
        if (defaultExtractView == null || defaultExtractView !is ViewGroup) {
            return ExtractedInputRootView(this, null)
        }
        val extractEditText = defaultExtractView.findViewById<ExtractEditText>(android.R.id.inputExtractEditText)
        (extractEditText?.parent as? ViewGroup)?.removeView(extractEditText)
        defaultExtractView.let {
            it.removeAllViews()
            it.addView(ExtractedInputRootView(this, extractEditText))
        }
        return defaultExtractView
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        systemLocalesFlow.value = newConfig.locales
        windowController.onConfigurationChanged(newConfig)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(wallpaperChangeReceiver)
        FlorisImeServiceReference = WeakReference(null)
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        flogInfo { "info=${info?.debugSummarize()} restarting=$restarting" }
        super.onStartInput(info, restarting)
        if (info == null) return
    }

    // DogBoard: under memory pressure the neural model goes (iOS releases its language model buffers),
    // and comes back when the keyboard is shown again
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level in android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW..android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            io.github.sillypuppyy.dogboard.engine.TypingEngine.releaseNeuralLm()
            io.github.sillypuppyy.dogboard.ime.EngineLoader.notePressure()
            android.util.Log.i("DogBoardPerf", "memory pressure (level $level): neural model released")
        }
        // the swipe recogniser (~12 MB native) only when it is critical or the process is about to go: swiping
        // without it falls back to nothing until the next show (the neural model goes first, at lower levels)
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE
        ) {
            io.github.sillypuppyy.dogboard.engine.TypingEngine.releaseSwipeNet()
            android.util.Log.i("DogBoardPerf", "memory pressure (level $level): swipe recogniser released")
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        io.github.sillypuppyy.dogboard.perf.Perf.mark("onStartInputView") // DogBoard: startup timing
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            io.github.sillypuppyy.dogboard.ime.EngineLoader.ensureNeural(this@FlorisImeService)
            io.github.sillypuppyy.dogboard.ime.EngineLoader.ensureSwipe(this@FlorisImeService)
        }
        flogInfo { "info=${info?.debugSummarize()} restarting=$restarting" }
        super.onStartInputView(info, restarting)
        if (info == null) return
        currentInputConnection?.requestCursorUpdates(ImeEditor.CURSOR_UPDATES)
        val ic = WeakReference(currentInputConnection)
        val editorInfo = FlorisEditorInfo.wrap(info)
        imeController.updateStateBlocking {
            handleStartInputView(ic, editorInfo)
        }
    }

    override fun onEvaluateInputViewShown(): Boolean {
        val config = resources.configuration
        return super.onEvaluateInputViewShown()
            || config.keyboard == Configuration.KEYBOARD_NOKEYS
            || prefs.physicalKeyboard.showOnScreenKeyboard.get()
    }

    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo?) {
        if (cursorAnchorInfo == null) {
            return
        }
        val newSelection = K3TextRange(
            start = cursorAnchorInfo.selectionStart,
            end = cursorAnchorInfo.selectionEnd,
        )
        flogInfo { "new=$newSelection" }
        imeController.updateStateBlocking {
            handleUpdateSelection(newSelection)
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        flogInfo { "finishing=$finishingInput" }
        super.onFinishInputView(finishingInput)
        currentInputConnection?.requestCursorUpdates(0)
        imeController.updateStateBlocking {
            handleFinishInputView()
        }
    }

    override fun onFinishInput() {
        flogInfo { "(no args)" }
        super.onFinishInput()
        NlpInlineAutofill.clearInlineSuggestions()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        io.github.sillypuppyy.dogboard.perf.Perf.mark("onWindowShown") // DogBoard: startup timing
        io.github.sillypuppyy.dogboard.ime.LiveUi.shown.value++ // DogBoard: a new Dalmatian coat per opening
        // DogBoard: key sounds ready before the first key (user 2026-11-21: "when I open the keyboard, for a bit the keys
        // don't make sounds"): built off the UI thread, and the audio output woken (it sleeps after a few idle seconds;
        // a Bluetooth link takes up to a second to resume and drops clicks meanwhile)
        val fb = prefs.inputFeedback
        if (fb.audioEnabled.get() && fb.audioStyle.get() != "system") {
            val style = fb.audioStyle.get(); val variation = fb.audioVariation.get(); val bypass = fb.audioBypassSilent.get()
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                io.github.sillypuppyy.dogboard.feel.KeySounds.prepare(this@FlorisImeService, style, variation)
                io.github.sillypuppyy.dogboard.feel.KeySounds.wakeOutput(media = bypass)
            }
        }
        // DogBoard: the system clip may have changed while another keyboard was in use
        runCatching { clipboardManager().value.syncFromSystem() }
        if (windowController.onWindowShown()) {
            flogInfo(LogTopic.IMS_EVENTS)
        } else {
            flogWarning(LogTopic.IMS_EVENTS) { "Ignoring (is already shown)" }
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        if (windowController.onWindowHidden()) {
            flogInfo(LogTopic.IMS_EVENTS)
            imeController.updateStateBlocking {
                state = state.copy(
                    flags = state.flags
                        .withImeUiMode(ImeUiMode.TEXT)
                        .withActionsEditorVisible(false)
                        .withActionsOverflowVisible(false),
                )
            }
        } else {
            flogWarning(LogTopic.IMS_EVENTS) { "Ignoring (is already hidden)" }
        }
    }

    /**
     * DogBoard: the input window fills the screen height, as Gboard's (its frame is full height either way;
     * the root view is MATCH_PARENT and the insets say where the keyboard is). InputMethodService sets the
     * height to WRAP_CONTENT when it updates the fullscreen mode, and with that every layout request made
     * the window relayout with the window manager: ~2 round trips per keystroke, 680 in a minute of fast
     * typing, Gboard 0 (perf 2026-11-18, system trace).
     */
    override fun updateFullscreenMode() {
        super.updateFullscreenMode()
        window.window?.let { w ->
            if (w.attributes.height != android.view.ViewGroup.LayoutParams.MATCH_PARENT) {
                w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            }
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        val config = resources.configuration
        if (config.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            return false
        }
        return when (prefs.keyboard.landscapeInputUiMode.get()) {
            // DogBoard: no full-screen editing on a tablet (smallest width 600 dp+), as Gboard: Android's default
            // goes full screen in any landscape (Android 12 tablet emulator, 2026-11-20)
            LandscapeInputUiMode.DYNAMICALLY_SHOW -> config.smallestScreenWidthDp < 600 && super.onEvaluateFullscreenMode()
            LandscapeInputUiMode.NEVER_SHOW -> false
            LandscapeInputUiMode.ALWAYS_SHOW -> true
        }
    }

    override fun onUpdateExtractingVisibility(info: EditorInfo?) {
        if (info != null) {
            val ic = WeakReference(currentInputConnection)
            val editorInfo = FlorisEditorInfo.wrap(info)
            imeController.updateStateBlocking {
                handleStartInputView(ic, editorInfo)
            }
        }
        when (prefs.keyboard.landscapeInputUiMode.get()) {
            LandscapeInputUiMode.DYNAMICALLY_SHOW -> super.onUpdateExtractingVisibility(info)
            LandscapeInputUiMode.NEVER_SHOW -> isExtractViewShown = false
            LandscapeInputUiMode.ALWAYS_SHOW -> isExtractViewShown = true
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        // DogBoard: the design's bar shows the autofill service's chips in its slot area (Panels:
        // "Passwords: system autofill chips sit in the slot area, never over the keys"); the inflate
        // crash (FlorisBoard upstream #3231/#3244/#3294) is guarded in NlpInlineAutofill
        if (!prefs.smartbar.enabled.get() || !prefs.suggestion.api30InlineSuggestionsEnabled.get()) {
            flogInfo(LogTopic.IMS_EVENTS) {
                "Ignoring inline suggestions request because Smartbar and/or inline suggestions are disabled."
            }
            return null
        }

        flogInfo(LogTopic.IMS_EVENTS) { "Creating inline suggestions request" }
        val stylesBundle = createInlineSuggestionUiStyleBundle(this)
        if (stylesBundle == null) {
            flogWarning(LogTopic.IMS_EVENTS) { "Failed to retrieve inline suggestions style bundle" }
            return null
        }
        val spec = InlinePresentationSpec.Builder(
            InlineSuggestionUiSmallestSize,
            InlineSuggestionUiBiggestSize,
        ).run {
            setStyle(stylesBundle)
            build()
        }

        return InlineSuggestionsRequest.Builder(listOf(spec)).run {
            setMaxSuggestionCount(InlineSuggestionsRequest.SUGGESTION_COUNT_UNLIMITED)
            build()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        val inlineSuggestions = response.inlineSuggestions
        flogInfo(LogTopic.IMS_EVENTS) {
            "Received inline suggestions response with ${inlineSuggestions.size} suggestion(s) provided."
        }
        return NlpInlineAutofill.showInlineSuggestions(this, inlineSuggestions)
    }

    override fun onComputeInsets(outInsets: Insets?) {
        if (outInsets == null) return
        val imeState = imeController.snapshotState()
        windowController.onComputeInsets(outInsets, imeState.isFullscreenInputRequired())
    }

    override fun getTextForImeAction(imeOptions: Int): String? {
        return try {
            when (imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_NONE -> null
                EditorInfo.IME_ACTION_GO -> resourcesContext.getString(AndroidInternalR.string.ime_action_go)
                EditorInfo.IME_ACTION_SEARCH -> resourcesContext.getString(AndroidInternalR.string.ime_action_search)
                EditorInfo.IME_ACTION_SEND -> resourcesContext.getString(AndroidInternalR.string.ime_action_send)
                EditorInfo.IME_ACTION_NEXT -> resourcesContext.getString(AndroidInternalR.string.ime_action_next)
                EditorInfo.IME_ACTION_DONE -> resourcesContext.getString(AndroidInternalR.string.ime_action_done)
                EditorInfo.IME_ACTION_PREVIOUS -> resourcesContext.getString(AndroidInternalR.string.ime_action_previous)
                else -> resourcesContext.getString(AndroidInternalR.string.ime_action_default)
            }
        } catch (_: Throwable) {
            super.getTextForImeAction(imeOptions)?.toString()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        flogDebug { "onKeyDown(keyCode=$keyCode, event=$event)" }
        return imeController.onHardwareKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        flogDebug { "onKeyUp(keyCode=$keyCode, event=$event)" }
        return imeController.onHardwareKeyUp(keyCode, event) || super.onKeyUp(keyCode, event)
    }
}

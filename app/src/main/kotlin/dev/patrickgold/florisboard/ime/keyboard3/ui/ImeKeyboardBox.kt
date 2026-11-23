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

package dev.patrickgold.florisboard.ime.keyboard3.ui

import dev.patrickgold.florisboard.subtypeManager
import androidx.compose.ui.graphics.asImageBitmap
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastRoundToInt
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.core.DisplayLanguageNamesIn
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.keyboard.SpaceBarMode
import dev.patrickgold.florisboard.ime.keyboard3.ImeIcons
import dev.patrickgold.florisboard.ime.keyboard3.ImeLayerIds
import androidx.compose.ui.platform.LocalContext
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.keyboard3.ImeActions
import dev.patrickgold.florisboard.ime.keyboard3.LocalImeController
import dev.patrickgold.florisboard.ime.keyboard3.interaction.LongPress
import dev.patrickgold.florisboard.ime.keyboard3.interaction.rememberPointerTracker
import dev.patrickgold.florisboard.ime.keyboard3.interaction.trackPointerInput
import dev.patrickgold.florisboard.ime.keyboard3.touch.InputShiftState
import dev.patrickgold.florisboard.ime.keyboard3.touch.TouchKey
import dev.patrickgold.florisboard.ime.keyboard3.touch.TouchLayer
import dev.patrickgold.florisboard.ime.keyboard3.touch.TouchModel
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.ime.theme.ImeUiAttrs
import dev.patrickgold.florisboard.ime.theme.putAttr
import dev.patrickgold.florisboard.ime.window.LocalWindowController
import dev.patrickgold.florisboard.lib.FlorisLocale
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.florisboard.lib.compose.toMm
import org.florisboard.lib.snygg.SnyggQueryAttributes
import org.florisboard.lib.snygg.SnyggSelector
import org.florisboard.lib.snygg.ui.SnyggBox
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import org.k3lp.lib.text.K3String
import org.k3lp.lib.text.K3StringOrDescriptor
import org.k3lp.lib.text.asK3String

@Composable
fun ImeKeyboardBox(
    modifier: Modifier = Modifier,
) {
    val prefs by FlorisPreferenceStore
    val density = LocalDensity.current
    val imeController = LocalImeController.current
    val a11yScope = androidx.compose.runtime.rememberCoroutineScope() // DogBoard: TalkBack key clicks
    val windowController = LocalWindowController.current

    val imeState by imeController.activeState.collectAsState()
    val model by remember { derivedStateOf { imeState.model } }
    val touchLayerId by remember { derivedStateOf { imeState.touchLayerId } }
    val inputShiftState by remember { derivedStateOf { imeState.flags.inputShiftState } }
    val enterAction by remember {
        derivedStateOf { ImeUiAttrs.EnterAction.of(imeState.editor.info.imeOptions, imeState.editor.info.inputAttributes) }
    }

    val windowSpec by windowController.activeWindowSpec.collectAsState()
    val keyMarginHPx by remember {
        derivedStateOf { with(density) { windowSpec.keyMarginH.toPx() } }
    }
    val keyMarginVPx by remember {
        derivedStateOf { with(density) { windowSpec.keyMarginV.toPx() } }
    }

    val baseTouchModelOptions by imeController.activeTouchModelOptions.collectAsState()
    // DogBoard: email and URL fields get their own bottom row. Other variations map to NORMAL so
    // that opening an ordinary field does not swap in a new touch model (a tap during the swap
    // was lost).
    val layoutVariation = when (val v = imeState.flags.keyVariation) {
        KeyVariation.EMAIL_ADDRESS, KeyVariation.URI -> v
        else -> KeyVariation.NORMAL
    }
    // DogBoard: password fields always get the number row (Gboard's default; digits are common
    // there and nothing corrects a slip)
    val passwordField = imeState.flags.keyVariation == KeyVariation.PASSWORD
    val touchModelOptions = remember(baseTouchModelOptions, layoutVariation, passwordField) {
        baseTouchModelOptions.copy(keyVariation = layoutVariation, showNumberRow = baseTouchModelOptions.showNumberRow || passwordField)
    }
    var activeTouchModel by remember {
        mutableStateOf(imeController.touchModelCache.getFor(model, touchModelOptions) ?: TouchModel.Empty)
    }
    LaunchedEffect(model, touchModelOptions) {
        activeTouchModel = withContext(Dispatchers.Default) {
            // DogBoard: startup timing
            io.github.sillypuppyy.dogboard.perf.Perf.measure("touch model") { imeController.touchModelCache.getOrComputeFor(model, touchModelOptions) }
        }
        // DogBoard: startup timing, first frame with real keys
        if (activeTouchModel !is TouchModel.Empty) {
            androidx.compose.runtime.withFrameNanos { }
            io.github.sillypuppyy.dogboard.perf.Perf.mark("keys drawn")
        }
    }

    val spaceBarMode by prefs.keyboard.spaceBarMode.collectAsState()
    val displayLanguageNamesIn by prefs.localization.displayLanguageNamesIn.collectAsState()
    // DogBoard: the language name for the design's space-bar label (Layout element "Space-bar label")
    // the language entry in use (not the layout's locale: English on AZERTY said French), and the languages typed
    // together with it: "English + French" in their short names (it showed only one)
    val activeSubtype by androidx.compose.ui.platform.LocalContext.current.subtypeManager().value.activeSubtypeFlow.collectAsState()
    val languageName = remember(model, displayLanguageNamesIn, activeSubtype) {
        fun name(locale: FlorisLocale, short: Boolean): String {
            val l = if (short) FlorisLocale.from(locale.language) else locale
            return when (displayLanguageNamesIn) {
                DisplayLanguageNamesIn.SYSTEM_LOCALE -> l.displayName()
                DisplayLanguageNamesIn.NATIVE_LOCALE -> l.displayName(l)
            }
        }
        val all = listOf(activeSubtype.primaryLocale) + activeSubtype.secondaryLocales
        // a combined keyboard: its codes, Google style ("EN • FR"); names alone got long with three
        if (all.size == 1) name(all[0], short = false) else all.joinToString(" • ") { it.language.uppercase() }
    }
    val spaceBarDisplayOverride = remember(model, spaceBarMode, displayLanguageNamesIn) {
        when (spaceBarMode) {
            SpaceBarMode.NOTHING -> K3String.Empty
            SpaceBarMode.CURRENT_LANGUAGE -> {
                // TODO this might not be the right locale, get from new suntypes once impl
                val locale = FlorisLocale.from(model.locales.getOrElse(0) { "en" })
                when (displayLanguageNamesIn) {
                    DisplayLanguageNamesIn.SYSTEM_LOCALE -> locale.displayName()
                    DisplayLanguageNamesIn.NATIVE_LOCALE -> locale.displayName(locale)
                }.asK3String()
            }
            SpaceBarMode.SPACE_BAR_KEY -> ImeIcons.SpaceBar
        }
    }

    // DogBoard: the return key shows a word for field actions (the app's own label if it has one)
    val context = LocalContext.current
    val actionLabel by remember { derivedStateOf { imeState.editor.info.extractedActionLabel } }
    val enterDisplayOverride = remember(enterAction, actionLabel) {
        val word = actionLabel?.takeIf { enterAction != ImeUiAttrs.EnterAction.RETURN && it.isNotBlank() }
            ?: when (enterAction) {
                "done" -> context.getString(R.string.dogboard__enter_done)
                "go" -> context.getString(R.string.dogboard__enter_go)
                "next" -> context.getString(R.string.dogboard__enter_next)
                "previous" -> context.getString(R.string.dogboard__enter_previous)
                "search" -> context.getString(R.string.dogboard__enter_search)
                "send" -> context.getString(R.string.dogboard__enter_send)
                else -> null
            }
        word?.asK3String()
    }

    val devtoolsEnabled by prefs.devtools.enabled.collectAsState()
    val debugShowTouchBoundaries by prefs.devtools.showKeyTouchBoundaries.collectAsState()

    BoxWithConstraints {
        val deviceWidthMm = remember(constraints.maxWidth) {
            with(density) { constraints.maxWidth.toDp().toMm().toInt() }
        }
        val activeTouchKeyboard = remember(activeTouchModel, deviceWidthMm) {
            activeTouchModel.selectKeyboard(deviceWidthMm)
        }
        // TODO this launched effect is a hack, there must be a better way
        LaunchedEffect(activeTouchKeyboard) {
            imeController.updateState {
                state = state.copy(effRowCount = activeTouchKeyboard.rowCount, effRowUnits = activeTouchKeyboard.rowUnits)
            }
        }
        val activeTouchLayer = remember(activeTouchKeyboard, touchLayerId) {
            if (touchLayerId == ImeLayerIds.Caps) {
                activeTouchKeyboard.layers[touchLayerId]
                    ?: activeTouchKeyboard.layers[ImeLayerIds.Shift]
                    ?: activeTouchKeyboard.layers[ImeLayerIds.Base]
            } else {
                activeTouchKeyboard.layers[touchLayerId]
                    ?: activeTouchKeyboard.layers[ImeLayerIds.Base]

            } ?: TouchLayer.Empty
        }

        val pointerTracker = rememberPointerTracker(activeTouchKeyboard)
        // DogBoard: suggestion-bar taps right after a key may be top-row key taps (BarTapGuard)
        val guardScope = androidx.compose.runtime.rememberCoroutineScope()
        val keyAreaBounds = androidx.compose.runtime.remember { arrayOf<Any>(androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.unit.IntSize.Zero) }
        androidx.compose.runtime.DisposableEffect(pointerTracker) {
            val handler = { change: androidx.compose.ui.input.pointer.PointerInputChange, windowX: Float, down: Boolean ->
                val origin = keyAreaBounds[0] as androidx.compose.ui.geometry.Offset
                val size = keyAreaBounds[1] as androidx.compose.ui.unit.IntSize
                val at = if (size.width == 0) null else if (down) pointerTracker.barTapPosition(windowX - origin.x, size, change.uptimeMillis)
                    else androidx.compose.ui.geometry.Offset(windowX - origin.x, 0f)
                if (at != null) {
                    val moved = change.copy(currentPosition = at, previousPosition = at)
                    guardScope.launch { pointerTracker.mutateLocked { if (down) handleDown(moved, size) else handleUp(moved, size) } }
                }
                at != null
            }
            io.github.sillypuppyy.dogboard.ime.BarTapGuard.keyArea = handler
            onDispose { if (io.github.sillypuppyy.dogboard.ime.BarTapGuard.keyArea === handler) io.github.sillypuppyy.dogboard.ime.BarTapGuard.keyArea = null }
        }
        val trackedOutputPointer by pointerTracker.trackedOutputPointer.collectAsState()
        val trackedPeekPointer by pointerTracker.trackedPeekPointer.collectAsState()
        val trackedMultiTapSeq by pointerTracker.trackedMultiTapSeq.collectAsState()

        // DogBoard: a design panel (tools, text editing) in the key area takes the touches itself
        val livePanel by io.github.sillypuppyy.dogboard.ime.LiveUi.panel.collectAsState()
        val keyboardRowHeightDp = FlorisImeSizing.keyboardRowBaseHeight
        val peekLineWidthPx = with(density) { 8.dp.toPx() }
        // DogBoard: the swipe trail, in the theme's accent, newest part opaque, the tail fading
        val glideTrail by pointerTracker.glideTrail.collectAsState()
        // the theme's accent at Gboard's tones (measured 2026-11-18: light #566791, dark #a6b3d7, i.e.
        // Material's primary tone 40 / 80): it always stands out from the keys (the return key's
        // own colour hardly showed on dark themes)
        val accent = io.github.sillypuppyy.dogboard.design.liveAccent()
        val darkKeys = io.github.sillypuppyy.dogboard.design.liveDark()
        val trailColor = remember(accent, darkKeys) {
            val h = com.materialkolor.hct.Hct.fromInt(accent.toArgb())
            Color(com.materialkolor.hct.Hct.from(h.hue, h.chroma, if (darkKeys) 80.0 else 40.0).toInt())
        }
        val trailWidthPx = with(density) { 11.dp.toPx() }
        // the trail keeps shrinking from its tail even while the finger rests: redraw every frame
        var trailNow by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
        androidx.compose.runtime.LaunchedEffect(glideTrail != null) {
            while (glideTrail != null) androidx.compose.runtime.withFrameMillis { trailNow = android.os.SystemClock.uptimeMillis() }
        }

        Box(
            modifier = modifier
                .layout { measurable, constraints ->
                    val effConstraints = Constraints.fixed(
                        width = constraints.maxWidth,
                        height = with(density) {
                            (keyboardRowHeightDp * activeTouchKeyboard.rowUnits).roundToPx()
                        },
                    )
                    val placeable = measurable.measure(effConstraints)
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .then(if (livePanel.isEmpty()) Modifier.trackPointerInput(pointerTracker, model) else Modifier)
                // DogBoard: debug builds log the key area's screen bounds for automated typing tests
                .onGloballyPositioned { coordinates ->
                    keyAreaBounds[0] = coordinates.localToWindow(androidx.compose.ui.geometry.Offset.Zero); keyAreaBounds[1] = coordinates.size
                    if (dev.patrickgold.florisboard.BuildConfig.DEBUG || dev.patrickgold.florisboard.BuildConfig.BUILD_TYPE == "benchmark") {
                        val pos = coordinates.positionOnScreen()
                        val bounds = "${pos.x.toInt()} ${pos.y.toInt()} ${coordinates.size.width} ${coordinates.size.height}"
                        if (bounds != lastLoggedKeyAreaBounds) {
                            lastLoggedKeyAreaBounds = bounds
                            android.util.Log.i("DogBoardGeom", "key area $bounds rows ${activeTouchKeyboard.rowCount}")
                        }
                    }
                }
                .drawWithContent {
                    drawContent()
                    // the swipe trail as Gboard draws it (measured on the emulator, 2026-11-18): one smooth
                    // ribbon, ~11 dp and round at the finger, narrowing and fading to nothing at a tail
                    // ~0.7 s behind; in the theme's accent
                    glideTrail?.let { all ->
                        val now = maxOf(trailNow, all.last().t)
                        val raw = all.filter { now - it.t <= dev.patrickgold.florisboard.ime.keyboard3.interaction.TRAIL_MS }
                        // a smooth curve through the touch points every ~3 px (Catmull-Rom): a fast swipe
                        // reports points 30-50 px apart, which showed as facets in the taper
                        val pts = ArrayList<dev.patrickgold.florisboard.ime.keyboard3.interaction.TrailPoint>(raw.size * 4)
                        for (i in 0 until raw.size - 1) {
                            val p0 = raw[maxOf(i - 1, 0)]; val p1 = raw[i]; val p2 = raw[i + 1]; val p3 = raw[minOf(i + 2, raw.size - 1)]
                            val steps = maxOf(1, (kotlin.math.hypot(p2.x - p1.x, p2.y - p1.y) / 3f).toInt())
                            for (k in 0 until steps) {
                                val u = k.toFloat() / steps; val u2 = u * u; val u3 = u2 * u
                                fun cr(a: Float, b: Float, c: Float, d: Float) =
                                    0.5f * (2 * b + (-a + c) * u + (2 * a - 5 * b + 4 * c - d) * u2 + (-a + 3 * b - 3 * c + d) * u3)
                                pts.add(dev.patrickgold.florisboard.ime.keyboard3.interaction.TrailPoint(
                                    cr(p0.x, p1.x, p2.x, p3.x), cr(p0.y, p1.y, p2.y, p3.y), p1.t + ((p2.t - p1.t) * u).toLong(),
                                ))
                            }
                        }
                        raw.lastOrNull()?.let { pts.add(it) }
                        if (pts.size >= 2) {
                            val life = dev.patrickgold.florisboard.ime.keyboard3.interaction.TRAIL_MS.toFloat()
                            val left = ArrayList<androidx.compose.ui.geometry.Offset>(pts.size)
                            val right = ArrayList<androidx.compose.ui.geometry.Offset>(pts.size)
                            for (i in pts.indices) {
                                val a = pts[maxOf(i - 1, 0)]; val b = pts[minOf(i + 1, pts.size - 1)]
                                var dx = b.x - a.x; var dy = b.y - a.y
                                val len = kotlin.math.hypot(dx, dy).takeIf { it > 0f } ?: 1f
                                dx /= len; dy /= len
                                val half = trailWidthPx / 2f * (1f - (now - pts[i].t) / life).coerceIn(0f, 1f)
                                left.add(androidx.compose.ui.geometry.Offset(pts[i].x - dy * half, pts[i].y + dx * half))
                                right.add(androidx.compose.ui.geometry.Offset(pts[i].x + dy * half, pts[i].y - dx * half))
                            }
                            val ribbon = androidx.compose.ui.graphics.Path().apply {
                                moveTo(left[0].x, left[0].y)
                                for (i in 1 until left.size) lineTo(left[i].x, left[i].y)
                                for (i in right.indices.reversed()) lineTo(right[i].x, right[i].y)
                                close()
                            }
                            val tail = pts.first(); val head = pts.last()
                            drawPath(
                                ribbon,
                                androidx.compose.ui.graphics.Brush.linearGradient(
                                    listOf(trailColor.copy(alpha = 0f), trailColor),
                                    start = androidx.compose.ui.geometry.Offset(tail.x, tail.y), end = androidx.compose.ui.geometry.Offset(head.x, head.y),
                                ),
                            )
                            // opaque where the head meets the ribbon: no darker overlap; the
                            // ribbon is one filled shape, so crossings don't darken either
                            drawCircle(trailColor, trailWidthPx / 2f, androidx.compose.ui.geometry.Offset(head.x, head.y))
                        }
                    }
                    trackedPeekPointer?.let { trackedPeekPointer ->
                        val peekLine = trackedPeekPointer.peekLine
                        if (peekLine != null) {
                            drawLine(
                                color = Color.Red, // TODO customizable
                                start = peekLine.start,
                                end = peekLine.end,
                                strokeWidth = peekLineWidthPx,
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                }
        ) {
            // DogBoard: stage 2 of the design port: the ported Kb.dc.html view draws the keys
            val dogboardDesign by prefs.keyboard.dogboardDesign.collectAsState()
            if (dogboardDesign) {
                val output = trackedOutputPointer
                val ui = io.github.sillypuppyy.dogboard.ime.LiveUi
                // the space-bar trackpad in use: the keys show the design's trackpad mode, the bar fades
                val trackpadX = output?.trackpadLastXDp?.takeIf { output.cursorAnchorDp != null }
                androidx.compose.runtime.SideEffect { ui.trackpad.value = trackpadX != null }
                // the design's clipboard panel shows FlorisBoard's clipboard history
                val context = androidx.compose.ui.platform.LocalContext.current
                val clipboardManager = remember(context) { context.clipboardManager().value }
                val history by clipboardManager.historyFlow.collectAsState()
                val historyEnabled by prefs.clipboard.historyEnabled.collectAsState()
                // the panel's filter (Kb:41-43): All, Links, Media
                val clipFilter by io.github.sillypuppyy.dogboard.ime.LiveUi.clipFilter.collectAsState()
                val expandLinks by prefs.clipboard.expandShortLinks.collectAsState()
                val stripLinks by prefs.clipboard.stripTrackers.collectAsState()
                val linkPreviews by prefs.clipboard.linkPreviews.collectAsState()
                val linkChanges by io.github.sillypuppyy.dogboard.ime.LinkInfo.changes.collectAsState()
                // the clips' types from Android's on-device classifier (ClipTypes), asked once per text when the panel opens
                var typesAsked by remember { androidx.compose.runtime.mutableIntStateOf(0) }
                androidx.compose.runtime.LaunchedEffect(livePanel, history) {
                    if (livePanel != "clipboard") return@LaunchedEffect
                    val texts = history.all.filter { it.type == dev.patrickgold.florisboard.ime.clipboard.provider.ItemType.TEXT && !it.isSensitive }
                        .mapNotNull { it.text }.filter { !io.github.sillypuppyy.dogboard.ime.ClipTypes.isKnown(it) }
                    if (texts.isEmpty()) return@LaunchedEffect
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { texts.forEach { io.github.sillypuppyy.dogboard.ime.ClipTypes.classify(context, it) } }
                    typesAsked++
                }
                // links already in the history learn their target and preview when the clipboard opens
                androidx.compose.runtime.LaunchedEffect(livePanel, expandLinks, linkPreviews) {
                    if (livePanel != "clipboard" || !(expandLinks || linkPreviews)) return@LaunchedEffect
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        io.github.sillypuppyy.dogboard.ime.LinkInfo.load(context)
                        for (item in history.all) {
                            if (item.type != dev.patrickgold.florisboard.ime.clipboard.provider.ItemType.TEXT || item.isSensitive) continue
                            item.text?.let { io.github.sillypuppyy.dogboard.ime.LinkInfo.fetchAll(context, it, expandLinks, linkPreviews) }
                        }
                    }
                }
                val pasteAsFile by prefs.clipboard.pasteAsFile.collectAsState()
                val shareAsFile by prefs.clipboard.shareAsFile.collectAsState()
                val clipItems = remember(history, clipFilter) {
                    // the clipboard rows' order (design/clipboard-rows.md): pinned clips on top, then newest first
                    (when (clipFilter) {
                        1 -> history.all.filter { it.type == dev.patrickgold.florisboard.ime.clipboard.provider.ItemType.TEXT && !it.isSensitive && io.github.sillypuppyy.dogboard.design.isLinkClip(it.text.orEmpty()) }
                        2 -> history.all.filter { it.type != dev.patrickgold.florisboard.ime.clipboard.provider.ItemType.TEXT }
                        else -> history.all
                    }).sortedWith(compareByDescending<dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem> { it.isPinned }.thenByDescending { it.creationTimestampMs })
                }
                // asked when a panel opens, not on every redraw (a cross-process call per keystroke)
                // a password clip's countdown (auto-clear on): ticks each second while the clipboard is open
                val clearSensitive by prefs.clipboard.historyAutoCleanSensitiveEnabled.collectAsState()
                val clearAfterSec by prefs.clipboard.historyAutoCleanSensitiveAfter.collectAsState()
                var nowMs by remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
                androidx.compose.runtime.LaunchedEffect(livePanel, clearSensitive) {
                    while (clearSensitive && livePanel == "clipboard") { nowMs = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) }
                }
                val phoneLocked = remember(livePanel) {
                    context.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
                }
                io.github.sillypuppyy.dogboard.design.LiveKbView(
                    layerId = touchLayerId.toString(),
                    letterRows = remember(model) { io.github.sillypuppyy.dogboard.design.LiveKb.letterRows(model) },
                    symbolLabels = remember(model) { io.github.sillypuppyy.dogboard.design.LiveKb.symbols(model) },
                    shiftState = inputShiftState,
                    enterAction = enterAction,
                    numberRow = prefs.keyboard.numberRow.collectAsState().value || passwordField,
                    actionWords = prefs.keyboard.actionKeyWords.collectAsState().value,
                    numberRowLongPress = prefs.keyboard.numberRowLongPress.collectAsState().value,
                    hintKind = prefs.keyboard.hintKind.collectAsState().value,
                    spaceLabel = prefs.keyboard.spaceLabel.collectAsState().value,
                    langName = languageName,
                    multiLang = activeSubtype.secondaryLocales.isNotEmpty() ||
                        androidx.compose.ui.platform.LocalContext.current.subtypeManager().value.subtypesFlow.collectAsState().value.size > 1,
                    capsLabels = prefs.keyboard.capsLabels.collectAsState().value,
                    symbolPages = prefs.keyboard.symbolPages.collectAsState().value,
                    pillKeys = prefs.keyboard.pillKeys.collectAsState().value,
                    bottomClassic = prefs.keyboard.bottomRow.collectAsState().value == "classic",
                    actionLabel = actionLabel,
                    // the touch model swaps the email/URL bottom row into "base"/"shift" itself
                    field = when (layoutVariation) {
                        KeyVariation.EMAIL_ADDRESS -> "email"
                        KeyVariation.URI -> "url"
                        else -> "text"
                    },
                    pressedKey = output?.downKey ?: trackedPeekPointer?.peekKey,
                    longPress = output?.longPress,
                    panel = livePanel,
                    trackpadX = trackpadX,
                    // the key as it acts (ImeController: the globe when there's more than one language), not the stored setting
                    fnKey = if (imeController.activeTouchModelOptions.collectAsState().value.fnKeyArrangement.simpleAction.output == ImeActions.SwitchToNextSubtype) "globe" else "emoji",
                    actions = remember(imeController, clipItems) {
                        io.github.sillypuppyy.dogboard.design.KbActions(
                            onSlot = {},
                            onTool = { icon -> ui.run(icon, imeController) },
                            onClip = { i ->
                                clipItems.getOrNull(i)?.let { item ->
                                    ui.closePanel()
                                    imeController.updateStateBlocking { emitClipboardItem(item) }
                                }
                            },
                        )
                    },
                    clipActions = remember(imeController, clipItems) {
                        fun paste(i: Int) = clipItems.getOrNull(i)?.let { item ->
                            ui.closePanel()
                            imeController.updateStateBlocking { emitClipboardItem(item) }
                        }
                        io.github.sillypuppyy.dogboard.design.KbClipActions(
                            onPaste = { i -> paste(i) },
                            onPin = { i -> clipItems.getOrNull(i)?.let { if (it.isPinned) clipboardManager.unpinClip(it) else clipboardManager.pinClip(it) } },
                            // a mark undoes its step for this clip, an undone mark redoes it
                            onMark = { i, which -> clipItems.getOrNull(i)?.let { item ->
                                clipboardManager.updateClip(if (which == "expanded") item.copy(keepShortLink = !item.keepShortLink) else item.copy(keepTrackers = !item.keepTrackers))
                            } },
                            // a colour pasted in the format picked
                            onFormat = { i, k -> clipItems.getOrNull(i)?.text?.let { io.github.sillypuppyy.dogboard.ime.ClipKinds.color(it.trim()) }?.let { c ->
                                ui.closePanel()
                                imeController.updateStateBlocking { emitText(listOf(c.hex, c.rgb, c.hsl)[k].asK3String()) }
                            } },
                            onMenu = { i, what -> clipItems.getOrNull(i)?.let { item ->
                                when (what) {
                                    "paste" -> paste(i)
                                    "delete" -> clipboardManager.deleteClip(item, onlyIfUnpinned = false)
                                    "share" -> io.github.sillypuppyy.dogboard.ime.ClipIntents.share(context, item)
                                    // as a file: the text as it would be pasted (links cleaned per the clip)
                                    "pastefile", "sharefile" -> {
                                        val pasted = item.text?.let { io.github.sillypuppyy.dogboard.ime.ClipLinkSteps.of(it, prefs.clipboard.expandShortLinks.get() && !item.keepShortLink, prefs.clipboard.stripTrackers.get() && !item.keepTrackers).pasted }
                                        io.github.sillypuppyy.dogboard.ime.ClipFiles.of(context, item, pasted)?.let { f ->
                                            if (what == "sharefile") io.github.sillypuppyy.dogboard.ime.ClipIntents.shareFile(context, f)
                                            else {
                                                ui.closePanel()
                                                imeController.updateStateBlocking { state.editor.performPasteFile(f.uri, f.mime, f.name) }
                                            }
                                        }
                                    }
                                    "action" -> io.github.sillypuppyy.dogboard.ime.ClipIntents.open(context, item.text.orEmpty())
                                }
                            } },
                            canEdit = false,
                        )
                    },
                    clipFilter = clipFilter,
                    clips = remember(clipItems, expandLinks, stripLinks, pasteAsFile, shareAsFile, imeState.editor.info, linkPreviews, linkChanges, typesAsked) { clipItems.map { item ->
                        val media = item.type != dev.patrickgold.florisboard.ime.clipboard.provider.ItemType.TEXT
                        val video = item.type == dev.patrickgold.florisboard.ime.clipboard.provider.ItemType.VIDEO
                        io.github.sillypuppyy.dogboard.design.KbClipData(
                            media = media,
                            icon = if (video) "play_circle" else if (media) "image" else "",
                            label = if (video) "Video" else if (media) "Image" else "",
                            // clips the source app marked sensitive (passwords) are never shown in clear
                            text = if (item.isSensitive) item.displayText(context) else item.text.orEmpty(),
                            pinned = item.isPinned,
                            sensitive = item.isSensitive,
                            clearsInSec = if (item.isSensitive && clearSensitive) ((item.creationTimestampMs + clearAfterSec * 1000L - nowMs) / 1000).toInt().coerceAtLeast(0) else null,
                            thumbnail = if (media) { px -> io.github.sillypuppyy.dogboard.ime.ClipThumbnails.load(context, item, px) } else null,
                        ).let { d ->
                            // the link marks: each step that changes this clip, filled while applied
                            // the hold menu's file items (settings): pasting needs the field to take that kind of file
                            val mime = when {
                                media -> item.mimeTypes.firstOrNull() ?: "*/*"
                                io.github.sillypuppyy.dogboard.ime.ClipKinds.color(item.text.orEmpty().trim()) != null -> "image/png"
                                else -> "text/plain"
                            }
                            // the link's preview, when Link previews is on and the page has been read
                            val info = if (linkPreviews && !media && !item.isSensitive) item.text?.let { io.github.sillypuppyy.dogboard.ime.LinkInfo.linkIn(it) }
                                ?.let { io.github.sillypuppyy.dogboard.ime.LinkInfo.get(it) } else null
                            fun loader(name: String?): (suspend (Int) -> androidx.compose.ui.graphics.ImageBitmap?)? =
                                io.github.sillypuppyy.dogboard.ime.LinkInfo.imageFile(name)?.let { f -> { _: Int -> android.graphics.BitmapFactory.decodeFile(f.path)?.asImageBitmap() } }
                            val withFiles = d.copy(
                                systemType = if (media || item.isSensitive) null else item.text?.let { io.github.sillypuppyy.dogboard.ime.ClipTypes.of(it) },
                                linkTitle = info?.title, linkSite = info?.site,
                                linkDuration = info?.duration?.let { "%d:%02d".format(it / 60, it % 60) }.orEmpty(),
                                linkImage = loader(info?.image), linkIcon = loader(info?.icon),
                                pasteFile = pasteAsFile && !item.isSensitive && io.github.sillypuppyy.dogboard.ime.ClipFiles.accepts(imeState.editor.info.base, mime),
                                shareFile = shareAsFile && !item.isSensitive,
                            )
                            if (media || item.isSensitive) withFiles else {
                                val steps = io.github.sillypuppyy.dogboard.ime.ClipLinkSteps.of(item.text.orEmpty(), expandLinks && !item.keepShortLink, stripLinks && !item.keepTrackers)
                                withFiles.copy(
                                    expanded = if (expandLinks && steps.expands) !item.keepShortLink else null,
                                    cleaned = if (stripLinks && steps.strips) !item.keepTrackers else null,
                                )
                            }
                        }
                    } },
                    // the design's messages: "locked" = phone locked (clips hidden), "empty" = history off
                    panelVariant = when {
                        phoneLocked -> "locked"
                        !historyEnabled -> "empty"
                        else -> "default"
                    },
                )
                // the design draws the caps; TalkBack gets one node per touch key on top (no pointer handling, so
                // touches still go to the pointer tracker), named and typing as that key does
                if (livePanel.isEmpty()) { // "" = no panel (clipboard, emoji) over the keys
                    for (touchKey in activeTouchLayer.keys) {
                        if (touchKey.attrs.gap) continue
                        // the same modifier instance per key, so an unchanged key's node is skipped on a keystroke
                        // (2026-11-20 JankWatch: these ~30 nodes were recomposed on every key)
                        val place = remember(touchKey) { Modifier.layoutNormalized(touchKey.bounds) }
                        KeyAccessibilityNode(touchKey, place) {
                            a11yScope.launch { imeController.updateState { touchKey.attrs.output?.let { emit(it) } } }
                        }
                    }
                }
                return@Box
            }
            for (touchKey in activeTouchLayer.keys) {
                if (touchKey.attrs.gap) {
                    continue
                }
                val touchKey by rememberUpdatedState(touchKey)
                val isPressed by remember {
                    derivedStateOf {
                        trackedOutputPointer?.downKey == touchKey ||
                            trackedPeekPointer?.let {
                                it.peekKey == touchKey || it.downKey.isShiftKey && touchKey.isShiftKey
                            } == true
                    }
                }
                val longPress by remember {
                    derivedStateOf {
                        trackedOutputPointer?.takeIf { it.downKey == touchKey }?.longPress ?: LongPress.None
                    }
                }
                val multiTapIndex by remember {
                    derivedStateOf {
                        trackedMultiTapSeq?.takeIf { it.touchKey == touchKey }?.multiTapIndex ?: -1
                    }
                }
                ImeKeyboardKeyBox(
                    touchKey = touchKey,
                    displayOverride = when {
                        touchKey.isSuitableForSpaceBarDisplayOverride -> spaceBarDisplayOverride
                        touchKey.attrs.output == ImeActions.Enter -> enterDisplayOverride
                        else -> null
                    },
                    isPressed = isPressed,
                    longPress = longPress,
                    multiTapIndex = multiTapIndex,
                    modifier = Modifier
                        .layout { measurable, constraints ->
                            val effConstraints = Constraints.fixed(
                                width = (constraints.maxWidth * touchKey.bounds.width - 2 * keyMarginHPx).fastRoundToInt(),
                                height = (constraints.maxHeight * touchKey.bounds.height - 2 * keyMarginVPx).fastRoundToInt(),
                            )
                            val placeable = measurable.measure(effConstraints)
                            val offset = IntOffset(
                                x = (constraints.maxWidth * touchKey.bounds.topLeft.x + keyMarginHPx).fastRoundToInt(),
                                y = (constraints.maxHeight * touchKey.bounds.topLeft.y + keyMarginVPx).fastRoundToInt(),
                            )
                            layout(placeable.width, placeable.height) { placeable.place(offset) }
                        },
                    inputShiftState = inputShiftState,
                    enterAction = if (touchKey.attrs.output == ImeActions.Enter) enterAction else null,
                    // DogBoard: TalkBack's "lift to type" clicks the key's node: type it as a tap would
                    onAccessibilityClick = { a11yScope.launch { imeController.updateState { touchKey.attrs.output?.let { emit(it) } } } },
                )
                if (devtoolsEnabled && debugShowTouchBoundaries) {
                    Box(
                        modifier = Modifier
                            .layoutNormalized(touchKey.hitbox)
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    color = Color.Red,
                                    style = Stroke(width = 1.dp.toPx()),
                                )
                            }
                    )
                }
            }
        }
    }
}

@Composable
private fun ImeKeyboardKeyBox(
    touchKey: TouchKey,
    displayOverride: K3StringOrDescriptor?,
    isPressed: Boolean,
    longPress: LongPress,
    multiTapIndex: Int,
    inputShiftState: InputShiftState,
    enterAction: String? = null,
    onAccessibilityClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val display = displayOverride ?: touchKey.display
    // DogBoard: each key is an accessibility node with a spoken name, the Button role and a click that types it
    // (2026-11-14: TalkBack users couldn't explore or type by touch; Gboard exposes its keys with labels)
    val context = androidx.compose.ui.platform.LocalContext.current
    val spokenName = remember(display, touchKey) { keySpokenName(context, touchKey, display) }
    val a11yModifier = modifier.semantics {
        contentDescription = spokenName
        role = androidx.compose.ui.semantics.Role.Button
        onClick(label = spokenName) { onAccessibilityClick(); true }
    }
    val output = touchKey.attrs.output
    val layerId = touchKey.attrs.layerId
    val attributes: SnyggQueryAttributes = remember(output, layerId, inputShiftState, enterAction) {
        buildMap {
            if (output != null) {
                putAttr(ImeUiAttrs.Output, output)
            }
            if (layerId != null) {
                putAttr(ImeUiAttrs.TargetLayer, layerId)
            }
            putAttr(ImeUiAttrs.ShiftState, inputShiftState)
            if (enterAction != null) {
                putAttr(ImeUiAttrs.EnterAction, enterAction)
            }
        }
    }
    // TODO some keys an be disabled (copy cut etc)
    val selector = if (isPressed) SnyggSelector.PRESSED else SnyggSelector.NONE
    val multiTapKeys = touchKey.multiTapKeys

    SnyggBox(
        FlorisImeUi.Key.elementName,
        attributes = attributes,
        selector = selector,
        modifier = a11yModifier,
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .scaleToFitHorizontally(),
        ) {
            if (touchKey.shouldOverrideDisplayWithMultiTapKeys) {
                for ((index, multiTapKey) in multiTapKeys.withIndex()) {
                    Display3(
                        modifier = if (touchKey.shouldHighlightPendingMultiTapKey && index == multiTapIndex) {
                            Modifier.background(Color.Red) // TODO customizable
                        } else Modifier,
                        display = multiTapKey.display,
                    )
                }
            } else {
                Display3(display)
            }
        }
        if (touchKey.longPressKeyHint != null) {
            Display3(
                elementName = FlorisImeUi.KeyHint.elementName,
                attributes = attributes,
                modifier = Modifier
                    .align(touchKey.longPressKeyHintPlacement.alignment)
                    .scaleToFitHorizontally(),
                display = touchKey.longPressKeyHint,
            )
        }
        // TODO flick hints
    }
    LongPressBox(
        longPress = longPress,
        longPressSimpleDisplayOverride = multiTapKeys.getOrNull(multiTapIndex)?.display,
        attributes = attributes,
    )
}

/** DogBoard: an invisible accessibility node over [touchKey]: its spoken name, the Button role, a click that types it. */
@Composable
private fun KeyAccessibilityNode(touchKey: TouchKey, modifier: Modifier, onClick: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val spokenName = remember(touchKey) { keySpokenName(context, touchKey, touchKey.display) }
    Box(modifier.semantics {
        contentDescription = spokenName
        role = androidx.compose.ui.semantics.Role.Button
        onClick(label = spokenName) { onClick(); true }
    })
}

/** DogBoard: what TalkBack says for a key: its text, or a name for the action keys and icons. */
private fun keySpokenName(context: android.content.Context, touchKey: TouchKey, display: K3StringOrDescriptor): String {
    val output = touchKey.attrs.output
    return when {
        touchKey.isSuitableForSpaceBarDisplayOverride || (output as? K3String)?.toText() == " " ->
            context.getString(dev.patrickgold.florisboard.R.string.a11y_key__space)
        touchKey.isShiftKey -> context.getString(dev.patrickgold.florisboard.R.string.a11y_key__shift)
        output == ImeActions.Backspace -> context.getString(dev.patrickgold.florisboard.R.string.a11y_key__delete)
        output == ImeActions.Enter -> context.getString(dev.patrickgold.florisboard.R.string.a11y_key__return)
        output == ImeActions.ShowMediaPanel || display == ImeIcons.MediaPanel -> context.getString(dev.patrickgold.florisboard.R.string.a11y_key__emoji)
        display is K3String -> display.toText().ifBlank { context.getString(dev.patrickgold.florisboard.R.string.a11y_key__space) }
        // other icons: the icon's own name ("fl:icon:arrow_left" -> "Arrow left")
        else -> display.toString().substringAfterLast(':').substringAfterLast('/').replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

// DogBoard: last key-area bounds written to the debug log
private var lastLoggedKeyAreaBounds: String? = null

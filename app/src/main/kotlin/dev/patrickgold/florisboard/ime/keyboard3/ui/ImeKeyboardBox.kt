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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
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
    val touchModelOptions = remember(baseTouchModelOptions, layoutVariation) {
        baseTouchModelOptions.copy(keyVariation = layoutVariation)
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
                state = state.copy(effRowCount = activeTouchKeyboard.rowCount)
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
        val trackedOutputPointer by pointerTracker.trackedOutputPointer.collectAsState()
        val trackedPeekPointer by pointerTracker.trackedPeekPointer.collectAsState()
        val trackedMultiTapSeq by pointerTracker.trackedMultiTapSeq.collectAsState()

        // DogBoard: a design panel (tools, text editing) in the key area takes the touches itself
        val livePanel by io.github.sillypuppyy.dogboard.ime.LiveUi.panel.collectAsState()
        val keyboardRowHeightDp = FlorisImeSizing.keyboardRowBaseHeight
        val peekLineWidthPx = with(density) { 8.dp.toPx() }

        Box(
            modifier = modifier
                .layout { measurable, constraints ->
                    val effConstraints = Constraints.fixed(
                        width = constraints.maxWidth,
                        height = with(density) {
                            (keyboardRowHeightDp * activeTouchKeyboard.rowCount).roundToPx()
                        },
                    )
                    val placeable = measurable.measure(effConstraints)
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .then(if (livePanel.isEmpty()) Modifier.trackPointerInput(pointerTracker, model) else Modifier)
                // DogBoard: debug builds log the key area's screen bounds for automated typing tests
                .onGloballyPositioned { coordinates ->
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
                // the design's clipboard panel shows FlorisBoard's clipboard history
                val context = androidx.compose.ui.platform.LocalContext.current
                val clipboardManager = remember(context) { context.clipboardManager().value }
                val history by clipboardManager.historyFlow.collectAsState()
                val historyEnabled by prefs.clipboard.historyEnabled.collectAsState()
                val clipItems = history.all
                io.github.sillypuppyy.dogboard.design.LiveKbView(
                    layerId = touchLayerId.toString(),
                    shiftState = inputShiftState,
                    enterAction = enterAction,
                    pressedKey = output?.downKey ?: trackedPeekPointer?.peekKey,
                    longPress = output?.longPress,
                    panel = livePanel,
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
                    clips = remember(clipItems) { clipItems.map { item ->
                        val media = item.type != dev.patrickgold.florisboard.ime.clipboard.provider.ItemType.TEXT
                        val video = item.type == dev.patrickgold.florisboard.ime.clipboard.provider.ItemType.VIDEO
                        io.github.sillypuppyy.dogboard.design.KbClipData(
                            media = media,
                            icon = if (video) "play_circle" else if (media) "image" else "",
                            label = if (video) "Video" else if (media) "Image" else "",
                            text = item.text.orEmpty(),
                            pinned = item.isPinned,
                            thumbnail = if (media) { px -> io.github.sillypuppyy.dogboard.ime.ClipThumbnails.load(context, item, px) } else null,
                        )
                    } },
                    // the design's messages: "locked" = phone locked (clips hidden), "empty" = history off
                    panelVariant = when {
                        context.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true -> "locked"
                        !historyEnabled -> "empty"
                        else -> "default"
                    },
                )
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
    modifier: Modifier = Modifier,
) {
    val display = displayOverride ?: touchKey.display
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
        modifier = modifier,
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

// DogBoard: last key-area bounds written to the debug log
private var lastLoggedKeyAreaBounds: String? = null

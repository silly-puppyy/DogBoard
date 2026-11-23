package io.github.sillypuppyy.dogboard.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.app.FlorisAppActivity
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.NoBackupPreferenceStore
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.app.components.LocalPreviewFieldController
import dev.patrickgold.florisboard.ime.core.Subtype
import io.github.sillypuppyy.dogboard.engine.AutocorrectLevel
import dev.patrickgold.florisboard.lib.FlorisLocale
import dev.patrickgold.florisboard.lib.util.InputMethodUtils
import dev.patrickgold.florisboard.lib.util.launchActivity
import dev.patrickgold.florisboard.subtypeManager
import dev.patrickgold.jetpref.datastore.model.collectAsState
import io.github.sillypuppyy.dogboard.design.CssShadow
import io.github.sillypuppyy.dogboard.design.DText
import io.github.sillypuppyy.dogboard.design.DesignFonts
import io.github.sillypuppyy.dogboard.design.KbProps
import io.github.sillypuppyy.dogboard.design.KbSpec
import io.github.sillypuppyy.dogboard.design.KbView
import io.github.sillypuppyy.dogboard.design.Symbol
import io.github.sillypuppyy.dogboard.design.css
import io.github.sillypuppyy.dogboard.design.cssShadow
import io.github.sillypuppyy.dogboard.design.liveDark
import io.github.sillypuppyy.dogboard.design.liveTheme
import io.github.sillypuppyy.dogboard.design.settings.LayoutElements
import io.github.sillypuppyy.dogboard.design.settings.PawLogo
import io.github.sillypuppyy.dogboard.design.settings.Segmented
import io.github.sillypuppyy.dogboard.design.settings.SetColors
import io.github.sillypuppyy.dogboard.design.settings.setText
import io.github.sillypuppyy.dogboard.ime.GeneratedLayouts
import io.github.sillypuppyy.dogboard.ime.LanguageLayouts
import io.github.sillypuppyy.dogboard.ime.PackDownloadQueue
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * First-run setup (design Onboarding.dc.html, notes/design/port-map-onboarding.md): a welcome with two paths. Quick
 * setup asks only what can't be guessed (turn on, languages, layout, look) and turns on nothing that stores, fetches or
 * needs a permission; Full setup walks one settings category per page. Every page writes the real setting as it
 * changes, so leaving early keeps what was chosen. Replaces FlorisBoard's setup screen.
 */
@Composable
fun OnboardingScreen() {
    val noBackupPrefs by NoBackupPreferenceStore
    val context = LocalContext.current
    val nav = LocalNavController.current
    val previewFieldController = LocalPreviewFieldController.current
    val scope = rememberCoroutineScope()
    SideEffect { previewFieldController?.isVisible = false }

    // null: the welcome page; else the path and the step in it
    var path by rememberSaveable { mutableStateOf<String?>(null) }
    var i by rememberSaveable { mutableIntStateOf(0) }

    // turning DogBoard on opens Android's settings; come back here when it's done (as FlorisBoard's setup did)
    LaunchedEffect(Unit) {
        InputMethodUtils.observeIsFlorisboardEnabledFlow(context).drop(1).first { it }
        context.launchActivity(FlorisAppActivity::class) {
            it.flags = Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }

    fun finish() {
        scope.launch { noBackupPrefs.isImeSetUp.set(true) }
        nav.navigate(Routes.Settings.Home) { popUpTo(Routes.Setup.Screen::class) { inclusive = true } }
    }

    Box(Modifier.fillMaxSize().background(SetColors.screen)) {
        val p = path
        if (p == null) {
            Welcome(onQuick = { path = "quick"; i = 0 }, onFull = { path = "full"; i = 0 })
        } else {
            val steps = if (p == "quick") QUICK else FULL
            val id = steps[i.coerceIn(0, steps.size - 1)]
            val enabled by InputMethodUtils.observeIsFlorisboardEnabled()
            val selected by InputMethodUtils.observeIsFlorisboardSelected()
            // Onboarding S.enable: when both system steps become done, the page moves on by itself
            val wasDone = remember { mutableStateOf(enabled && selected) }
            LaunchedEffect(enabled && selected) {
                if (enabled && selected && !wasDone.value && id == "enable") i++
                wasDone.value = enabled && selected
            }
            StepFrame(
                stepText = "Step ${i + 1} of ${steps.size}", pathName = if (p == "quick") "Quick setup" else "Full setup",
                progress = (i + 1f) / steps.size, title = TITLES.getValue(id).first, body = TITLES.getValue(id).second,
                nextLabel = when {
                    id == "done" -> "Finish"
                    id == "enable" && !(enabled && selected) -> "Skip for now"
                    else -> "Next"
                },
                // Onboarding:121: Full setup can take the defaults for the rest from its third page on
                canSkip = p == "full" && i >= 2 && id != "done",
                onBack = { if (i == 0) path = null else i-- },
                onNext = { if (i < steps.size - 1) i++ else finish() },
                onSkip = { i = steps.size - 1 },
            ) {
                when (id) {
                    "enable" -> EnableStep(enabled, selected)
                    "langs" -> LanguagesStep()
                    "layout" -> LayoutStep()
                    "elements" -> ElementsStep()
                    "theme" -> ThemeStep()
                    "typing" -> TypingStep()
                    "clipboard" -> ClipboardStep()
                    "privacy" -> PrivacyStep()
                    else -> DoneStep()
                }
            }
        }
    }
}

// The canvas's paths as the user set them (2026-11-23): Quick is turn on, languages (the phone's language added with
// its usual layout), done; Full drops emoji, puts look and feel on one Theme page and contacts with incognito on one
// Privacy page. Writing tools, GIFs, tips and codes from messages aren't built (port map).
private val QUICK = listOf("enable", "langs", "done")
private val FULL = listOf("enable", "langs", "layout", "elements", "theme", "typing", "clipboard", "privacy", "done")

/** Onboarding script S: each page's title and body. */
private val TITLES = mapOf(
    "enable" to ("Turn on DogBoard" to "Android asks this for every keyboard. Its warning is generic: nothing you type leaves this phone."),
    "langs" to ("Your languages" to "Picked from your phone’s languages. Typing in two at once works without switching."),
    "layout" to ("Pick a layout" to "Both type the same letters. You can mix the details later in Settings › Layout."),
    "elements" to ("Fine-tune the layout" to "Each of these can be set on its own. The preview follows."),
    "theme" to ("Theme" to "Colours, sound and vibration. More themes, gradients and photo themes are in Settings › Look."),
    "typing" to ("Typing and correction" to "How much DogBoard fixes for you."),
    "clipboard" to ("Clipboard" to "DogBoard can keep what you copy so you can paste it again later."),
    "privacy" to ("Privacy" to "What DogBoard may learn and read. Everything stays on this phone."),
    "done" to ("You’re set" to "Type anything to try it. Every choice can be changed in Settings."),
)

private val ink = css("#221a14")

/** Onboarding:22-33: the paw, the wordmark, one line, the two promises and the two paths. */
@Composable
private fun Welcome(onQuick: () -> Unit, onFull: () -> Unit) {
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).padding(start = 28.dp, end = 28.dp, top = 96.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        PawLogo(88f)
        DText("DogBoard", style = TextStyle(fontFamily = DesignFonts.fredoka(600), fontSize = 44.sp, letterSpacing = (-0.03).em(), color = SetColors.onSurface, lineHeight = 44.sp))
        DText("A calm keyboard that types the way you expect, and that you can tune to the last detail.", style = setText(20f, color = SetColors.onSurface).copy(lineHeight = 28.sp), maxLines = 4)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // CHANGED (port map): the canvas's "Works offline" isn't true with language downloads
            Promise("lock", "What you type stays on this phone")
            Promise("code", "Open source")
        }
        Spacer(Modifier.weight(1f))
        PathButton("Quick setup", "3 screens, under a minute", filled = true, onClick = onQuick)
        PathButton("Full setup", "9 screens, about 3 minutes", filled = false, onClick = onFull)
    }
}

private fun Double.em() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Em)

@Composable
private fun Promise(icon: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Symbol(icon, 20.sp, SetColors.primary)
        DText(text, style = setText(15f, color = SetColors.onSurfaceVariant))
    }
}

@Composable
private fun PathButton(label: String, note: String, filled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier.fillMaxWidth().height(56.dp).clip(shape)
            .then(if (filled) Modifier.background(SetColors.primary) else Modifier.cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 1f, SetColors.outline)), shape))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        val fg = if (filled) SetColors.scheme?.onPrimary ?: Color.White else SetColors.primary
        DText(label, style = setText(16f, 600, fg))
        DText(note, style = setText(12f, 400, fg.copy(alpha = 0.85f)))
    }
}

/** Onboarding:37-43, 118-123: progress, title and body, the page's items, then Back / defaults / Next. */
@Composable
private fun StepFrame(
    stepText: String, pathName: String, progress: Float, title: String, body: String, nextLabel: String, canSkip: Boolean,
    onBack: () -> Unit, onNext: () -> Unit, onSkip: () -> Unit, content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars)) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DText(stepText, style = setText(13f, color = SetColors.onSurfaceVariant))
                DText(pathName, style = setText(13f, color = SetColors.onSurfaceVariant))
            }
            Row(Modifier.fillMaxWidth().height(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.weight(progress.coerceAtLeast(0.01f)).fillMaxHeight().background(SetColors.primary, RoundedCornerShape(2.dp)))
                if (progress < 1f) Box(Modifier.weight(1f - progress).fillMaxHeight().background(SetColors.secondaryContainer, RoundedCornerShape(2.dp)))
            }
            DText(title, Modifier.padding(top = 8.dp), style = setText(28f).copy(lineHeight = 36.sp), maxLines = 3)
            DText(body, style = setText(15f, color = SetColors.onSurfaceVariant).copy(lineHeight = 22.sp), maxLines = 6)
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), content = content,
        )
        Row(
            Modifier.fillMaxWidth().cssShadow(listOf(CssShadow(false, 0f, -1f, 0f, 0f, SetColors.surfaceContainerHighest)), RoundedCornerShape(0.dp))
                .background(SetColors.screen).padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            TextPill("Back", SetColors.primary, onClick = onBack)
            Spacer(Modifier.weight(1f))
            if (canSkip) TextPill("Use defaults for the rest", SetColors.onSurfaceVariant, size = 13f, weight = 500, onClick = onSkip)
            Box(
                Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(SetColors.primary).clickable(onClick = onNext).padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center,
            ) { DText(nextLabel, style = setText(14f, 600, SetColors.scheme?.onPrimary ?: Color.White)) }
        }
    }
}

@Composable
private fun TextPill(text: String, color: Color, size: Float = 14f, weight: Int = 600, onClick: () -> Unit) {
    Box(Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        DText(text, style = setText(size, weight, color))
    }
}

// ---- items (Onboarding:46-114)

private val cardShape = RoundedCornerShape(16.dp)
private fun Modifier.card() = fillMaxWidth().clip(cardShape).background(SetColors.row)

/** Onboarding:49-51: a switch row (label 15, sub 12.5, the M3 switch). */
@Composable
private fun Toggle(label: String, sub: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.card().heightIn(min = 60.dp).then(if (enabled) Modifier.clickable { onChange(!checked) } else Modifier)
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            DText(label, style = setText(15f, color = SetColors.onSurface.copy(alpha = if (enabled) 1f else 0.38f)), maxLines = 3)
            DText(sub, style = setText(12.5f, color = SetColors.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f)).copy(lineHeight = 17.5.sp), maxLines = 6)
        }
        Box(
            Modifier.size(52.dp, 32.dp)
                .then(if (checked) Modifier else Modifier.cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 2f, SetColors.outline)), RoundedCornerShape(16.dp)))
                .background(if (checked) SetColors.primary else SetColors.surfaceContainerHighest, RoundedCornerShape(16.dp)),
        ) {
            Box(
                Modifier.padding(start = if (checked) 24.dp else 8.dp, top = if (checked) 4.dp else 8.dp).size(if (checked) 24.dp else 16.dp)
                    .background(if (checked) Color.White else SetColors.outline, CircleShape),
            )
        }
    }
}

/** Onboarding:56-60: a labelled segmented choice. */
@Composable
private fun Choice(id: String, label: String, sub: String = "", options: List<String>, selected: Int, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    Column(Modifier.card().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            DText(label, style = setText(15f, color = SetColors.onSurface.copy(alpha = if (enabled) 1f else 0.38f)))
            if (sub.isNotEmpty()) DText(sub, style = setText(12.5f, color = SetColors.onSurfaceVariant), maxLines = 4)
        }
        // Onboarding:56: the whole control at 38 % while it doesn't apply
        Box(Modifier.then(if (enabled) Modifier else Modifier.graphicsLayer { alpha = 0.38f })) {
            Segmented("onb:$id", options, selected, onSelect = if (enabled) onSelect else null)
        }
    }
}

/** Onboarding:65-71: radio cards, with an optional four-colour swatch. */
@Composable
private fun Cards(options: List<Triple<String, String, List<String>?>>, selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((n, o) in options.withIndex()) {
            val on = n == selected
            Row(
                Modifier.card().then(if (on) Modifier.cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 2f, SetColors.primary)), cardShape) else Modifier)
                    .clickable { onSelect(n) }.padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                o.third?.let { sw ->
                    Box(Modifier.size(44.dp, 32.dp).clip(RoundedCornerShape(8.dp)).background(css(sw[0])).padding(5.dp)) {
                        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Box(Modifier.weight(1f).fillMaxHeight().background(css(sw[1]), RoundedCornerShape(2.dp)))
                            Box(Modifier.weight(1f).fillMaxHeight().background(css(sw[2]), RoundedCornerShape(2.dp)))
                            Box(Modifier.weight(1f).fillMaxHeight().background(css(sw[3]), RoundedCornerShape(2.dp)))
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    DText(o.first, style = setText(15f, 500))
                    DText(o.second, style = setText(12.5f, color = SetColors.onSurfaceVariant), maxLines = 4)
                }
                Symbol(if (on) "radio_button_checked" else "radio_button_unchecked", 22.sp, SetColors.primary, fill = if (on) 1 else 0)
            }
        }
    }
}

/** Onboarding:77: an information line. */
@Composable
private fun Info(icon: String, text: String) {
    val (bg, fg) = SetColors.container(css("#d8e2ff"), css("#2a3a5c"))
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Symbol(icon, 18.sp, fg)
        DText(text, style = setText(12.5f, color = fg).copy(lineHeight = 18.sp), maxLines = 6)
    }
}

/** Onboarding:101-102: the keyboard as the settings make it now (layout elements, theme, dark mode). */
@Composable
private fun Preview() {
    val prefs by FlorisPreferenceStore
    // read the layout settings through their flows so the preview follows each change
    prefs.keyboard.numberRow.collectAsState().value
    prefs.keyboard.numberRowLongPress.collectAsState().value
    prefs.keyboard.bottomRow.collectAsState().value
    prefs.keyboard.hintKind.collectAsState().value
    prefs.keyboard.actionKeyWords.collectAsState().value
    prefs.keyboard.themeDay.collectAsState().value
    prefs.keyboard.themeNight.collectAsState().value
    prefs.keyboard.themeFollow.collectAsState().value
    prefs.keyboard.themeFixedNight.collectAsState().value
    val st = LayoutPrefs.read(prefs)
    val dark = liveDark()
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        val keyH = 57.9f * (4f + if (st.numRow == "on") 0.58f else 0f)
        val props = liveTheme().apply(
            KbProps(
                dark = dark, width = maxWidth.value, keyH = keyH, bar = "empty", ret = "send",
                kstyle = if (st.bottom == "classic") "android" else "ios",
                numberRow = st.numRow == "on", numRow = st.numRow, hintKind = st.hints, spaceLabel = st.space,
                actionStyle = st.action, caseMode = st.caseMode, symbols = st.symbols, pillKeys = st.pill,
            ),
        )
        val render = remember(props) { KbSpec.render(props) }
        KbView(render)
    }
}

/** Onboarding:107: a field to try the keyboard in (the canvas's sample text as the hint). */
@Composable
private fun TryField() {
    var text by remember { mutableStateOf("") }
    val shape = RoundedCornerShape(26.dp)
    Box(
        Modifier.fillMaxWidth().height(52.dp).clip(shape).background(SetColors.row)
            .cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 2f, SetColors.primary)), shape).padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            text, { text = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = setText(16f), cursorBrush = SolidColor(SetColors.primary),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) DText("Woof, it works", style = setText(16f, color = SetColors.onSurfaceVariant))
                    inner()
                }
            },
        )
    }
}

// ---- pages

/** Onboarding S.enable: the two system steps with live status. */
@Composable
private fun EnableStep(enabled: Boolean, selected: Boolean) {
    val context = LocalContext.current
    Status(1, "Turn on in system settings", if (enabled) "Done" else "Opens Android’s keyboard list", enabled, "Open") {
        InputMethodUtils.showImeEnablerActivity(context)
    }
    Status(2, "Switch to DogBoard", if (selected) "Done" else "Pick it in the keyboard list", selected, "Open") {
        InputMethodUtils.showImePicker(context)
    }
}

/** Onboarding:81-85: a numbered system step: its circle (a check when done), label, status, and its button until done. */
@Composable
private fun Status(n: Int, label: String, sub: String, done: Boolean, button: String, onClick: () -> Unit) {
    Row(Modifier.card().padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).background(if (done) SetColors.primary else SetColors.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            if (done) Symbol("check", 18.sp, SetColors.scheme?.onPrimary ?: Color.White)
            else DText("$n", style = setText(14f, 600, SetColors.onSecondaryContainer))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            DText(label, style = setText(15f, 500))
            DText(sub, style = setText(12.5f, color = SetColors.onSurfaceVariant))
        }
        if (!done) Box(
            Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(SetColors.secondaryContainer).clickable(onClick = onClick).padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) { DText(button, style = setText(13f, 600, SetColors.onSecondaryContainer)) }
    }
}

/**
 * Onboarding S.langs: the languages already added (the first stays), then the phone's other languages DogBoard has a
 * layout for; ticking one adds it with its default layout and fetches its dictionary in the background.
 */
@Composable
private fun LanguagesStep() {
    val context = LocalContext.current
    val nav = LocalNavController.current
    val prefs by FlorisPreferenceStore
    val subtypeManager by context.subtypeManager()
    val subtypes by subtypeManager.subtypesFlow.collectAsState()
    val memory by prefs.keyboard.layoutByLanguage.collectAsState()
    val list = subtypes.ifEmpty { listOf(Subtype.DEFAULT) }
    val added = list.map { it.primaryLocale.language }.toSet()
    val phone = remember {
        val l = android.os.LocaleList.getDefault()
        (0 until l.size()).map { l[it] }
    }
    // a first setup (no languages saved yet): the phone's own language, with its usual layout (user 2026-11-23)
    LaunchedEffect(Unit) {
        if (subtypeManager.subtypesFlow.value.isNotEmpty()) return@LaunchedEffect
        val loc = phone.firstOrNull { l -> GeneratedLayouts.all.any { it.language == l.language } } ?: return@LaunchedEffect
        val tag = loc.toLanguageTag()
        subtypeManager.addSubtypes(Subtype(0, FlorisLocale.fromTag(tag), emptyList(), layoutId = LanguageLayouts.layoutOf(loc.language, memory)))
        if (loc.language != "en") PackDownloadQueue.ensure(context, listOf(tag))
    }
    fun layoutName(lang: String, id: String?) = GeneratedLayouts.all.firstOrNull { it.id == (id ?: LanguageLayouts.layoutOf(lang, memory)) }?.label ?: ""
    for ((n, s) in list.withIndex()) {
        LangRow(s.primaryLocale.displayName(), if (n == 0) "Your first language" else "Added", layoutName(s.primaryLocale.language, s.layoutId), checked = true, locked = n == 0) {
            subtypeManager.removeSubtype(s)
        }
    }
    for (loc in phone) {
        val lang = loc.language
        if (lang in added || GeneratedLayouts.all.none { it.language == lang }) continue
        val name = loc.getDisplayName(loc).replaceFirstChar { it.titlecase(loc) }
        LangRow(name, "From your phone; its dictionary downloads when added", layoutName(lang, null), checked = false, locked = false) {
            val tag = loc.toLanguageTag()
            val s = Subtype(0, FlorisLocale.fromTag(tag), emptyList(), layoutId = LanguageLayouts.layoutOf(lang, memory))
            if (subtypes.isEmpty()) subtypeManager.addSubtypes(Subtype.DEFAULT, s) else subtypeManager.addSubtypes(s)
            PackDownloadQueue.ensure(context, listOf(tag))
        }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { nav.navigate(Routes.Settings.Localization) }.padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol("add", 20.sp, SetColors.primary)
        DText("More languages", style = setText(14f, 500, SetColors.primary))
    }
}

/** Onboarding:89-92: a language with its checkbox and its layout. */
@Composable
private fun LangRow(label: String, sub: String, layout: String, checked: Boolean, locked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.card().heightIn(min = 60.dp).padding(start = 8.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).then(if (locked) Modifier else Modifier.clickable(onClick = onToggle)), contentAlignment = Alignment.Center) {
            Symbol(if (checked) "check_box" else "check_box_outline_blank", 24.sp, if (checked) SetColors.primary else SetColors.onSurfaceVariant, fill = if (checked) 1 else 0)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            DText(label, style = setText(15f))
            DText(sub, style = setText(12.5f, color = SetColors.onSurfaceVariant), maxLines = 3)
        }
        if (layout.isNotEmpty()) Box(
            Modifier.height(32.dp).cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 1f, SetColors.outline)), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp)
                .then(if (checked) Modifier else Modifier.background(Color.Transparent)),
            contentAlignment = Alignment.Center,
        ) { DText(layout, style = setText(13f, 500, SetColors.onSurface.copy(alpha = if (checked) 1f else 0.38f))) }
    }
}

/** Onboarding S.layout: the two presets as cards, and the keyboard. */
@Composable
private fun LayoutStep() {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    prefs.keyboard.bottomRow.collectAsState().value
    prefs.keyboard.hintKind.collectAsState().value
    val st = LayoutPrefs.read(prefs)
    val sel = when (st) { LayoutElements.SIMPLE -> 0; LayoutElements.CLASSIC -> 1; else -> -1 }
    Cards(
        listOf(
            Triple("Simple", "Punctuation on 123 and long-press. Fewer keys, bigger space bar.", null),
            Triple("Classic", "Comma and period beside space; digits and symbols on long-press.", null),
        ),
        sel,
    ) { n -> scope.launch { LayoutPrefs.write(prefs, if (n == 0) LayoutElements.SIMPLE else LayoutElements.CLASSIC) } }
    Preview()
}

/** Onboarding S.elements: number row, long-press hints, action key; the keyboard. */
@Composable
private fun ElementsStep() {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val numRow by prefs.keyboard.numberRow.collectAsState()
    val numLong by prefs.keyboard.numberRowLongPress.collectAsState()
    val hints by prefs.keyboard.hintKind.collectAsState()
    val words by prefs.keyboard.actionKeyWords.collectAsState()
    Choice("numrow", "Number row", options = listOf("Off", "On", "Long-press"), selected = if (numRow) 1 else if (numLong) 2 else 0) { n ->
        scope.launch { prefs.keyboard.numberRow.set(n == 1); prefs.keyboard.numberRowLongPress.set(n == 2) }
    }
    val kinds = listOf("off", "accents", "symbols")
    Choice("hints", "Long-press hints", options = listOf("Off", "Accents", "Symbols"), selected = kinds.indexOf(hints).coerceAtLeast(0)) { n ->
        scope.launch { prefs.keyboard.hintKind.set(kinds[n]) }
    }
    Choice("action", "Action key", options = listOf("Icon", "Word"), selected = if (words) 1 else 0) { n ->
        scope.launch { prefs.keyboard.actionKeyWords.set(n == 1) }
    }
    Preview()
}

/** Onboarding S.look + S.feel (one Theme page, user 2026-11-23): three themes, dark mode, vibration and key sounds. */
@Composable
private fun ThemeStep() {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val day by prefs.keyboard.themeDay.collectAsState()
    val follow by prefs.keyboard.themeFollow.collectAsState()
    val fixedNight by prefs.keyboard.themeFixedNight.collectAsState()
    val ids = listOf("dogboard", "brand", "ink")
    Cards(
        listOf(
            Triple("DogBoard", "Follows your wallpaper", listOf("#ededf4", "#ffffff", "#dbe2f9", "#435e91")),
            Triple("Warm", "The DogBoard palette, always", listOf("#fbebe1", "#ffffff", "#efe0d6", "#88521c")),
            Triple("Ink", "True black, easy on OLED batteries", listOf("#000000", "#1a1b1f", "#111216", "#2a4678")),
        ),
        ids.indexOf(day),
    ) { n -> scope.launch { prefs.keyboard.themeDay.set(ids[n]); prefs.keyboard.themeNight.set(ids[n]); prefs.keyboard.glassEnabled.set(false) } }
    Choice("dark", "Dark mode", options = listOf("Follow system", "Light", "Dark"), selected = if (follow) 0 else if (fixedNight) 2 else 1) { n ->
        scope.launch { prefs.keyboard.themeFollow.set(n == 0); prefs.keyboard.themeFixedNight.set(n == 2) }
    }
    Preview()
    FeelRows()
}

/** Onboarding S.typing: autocorrect strength, capitals, double-space full stop. */
@Composable
private fun TypingStep() {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val on by prefs.correction.autocorrect.collectAsState()
    val level by prefs.correction.autocorrectLevel.collectAsState()
    val caps by prefs.typing.autoCapitalization.collectAsState()
    val dbl by prefs.correction.doubleSpacePeriod.collectAsState()
    val levels = AutocorrectLevel.entries
    val sel = if (!on) 0 else when (level) { levels.first() -> 1; levels.last() -> 3; else -> 2 }
    Choice("ac", "Autocorrect", "Fixes typos when you press space. Delete right after undoes it.", listOf("Off", "Gentle", "Standard", "Strong"), sel) { n ->
        scope.launch {
            prefs.correction.autocorrect.set(n != 0)
            if (n != 0) prefs.correction.autocorrectLevel.set(when (n) { 1 -> levels.first(); 3 -> levels.last(); else -> levels[levels.size / 2] })
        }
    }
    Toggle("Capitalise sentences", "Starts each sentence with a capital.", caps) { scope.launch { prefs.typing.autoCapitalization.set(it) } }
    Toggle("Double-space for a full stop", "Two quick spaces type “. ”.", dbl) { scope.launch { prefs.correction.doubleSpacePeriod.set(it) } }
}

/** Onboarding S.feel: vibration and key sounds, and a field to try them (no invented presets: on / off as the Sound and vibration page). */
@Composable
private fun FeelRows() {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val vib by prefs.inputFeedback.hapticEnabled.collectAsState()
    val sound by prefs.inputFeedback.audioEnabled.collectAsState()
    Toggle("Vibration", "A short tap on each key. More options in Settings › Sound and vibration.", vib) { scope.launch { prefs.inputFeedback.hapticEnabled.set(it) } }
    Toggle("Key sounds", "A soft click on each key. Silent mode always wins.", sound) { scope.launch { prefs.inputFeedback.audioEnabled.set(it) } }
    TryField()
}

/** Onboarding S.clipboard: history (off by default), how long clips stay, what is never kept; smart chips. */
@Composable
private fun ClipboardStep() {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val clip by prefs.clipboard.historyEnabled.collectAsState()
    val clean by prefs.clipboard.historyAutoCleanOldEnabled.collectAsState()
    val after by prefs.clipboard.historyAutoCleanOldAfter.collectAsState()
    val chips by prefs.clipboard.smartChips.collectAsState()
    Toggle("Clipboard history", "Keeps copied text, images and links on this phone.", clip) { scope.launch { prefs.clipboard.historyEnabled.set(it) } }
    val minutes = listOf(60, 1440, 10080)
    val keep = if (!clean) 3 else minutes.indexOfFirst { after <= it }.let { if (it < 0) 2 else it }
    Choice("keep", "Keep clips for", options = listOf("1 hour", "1 day", "1 week", "Until cleared"), selected = keep, enabled = clip) { n ->
        scope.launch {
            prefs.clipboard.historyAutoCleanOldEnabled.set(n != 3)
            if (n != 3) prefs.clipboard.historyAutoCleanOldAfter.set(minutes[n])
        }
    }
    Info("shield", "Passwords and one-time codes are never kept.")
    // CHANGED (port map): the canvas's "Suggest your last copy" has no setting; DogBoard's chips for what's in a copy
    Toggle("Clipboard chips", "Phone numbers, links and addresses in what you copy show as chips in the bar.", chips) { scope.launch { prefs.clipboard.smartChips.set(it) } }
}

/** Onboarding S.smart: contacts (writing tools and message codes aren't built); the permission asked in place. */
@Composable
private fun ContactsRow() {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val contacts by prefs.spelling.useContacts.collectAsState()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scope.launch { prefs.spelling.useContacts.set(granted) }
    }
    Toggle("Contacts", "Names, numbers and birthdays of your contacts as you type them. Read on the phone, never sent.", contacts) { on ->
        if (on) ask.launch(android.Manifest.permission.READ_CONTACTS) else scope.launch { prefs.spelling.useContacts.set(false) }
    }
}

/** Onboarding S.privacy + S.smart (one page, user 2026-11-23): incognito and contacts (tips and developer keys aren't built). */
@Composable
private fun PrivacyStep() {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val mode by prefs.suggestion.incognitoMode.collectAsState()
    val modes = listOf(dev.patrickgold.florisboard.ime.keyboard.IncognitoMode.DYNAMIC_ON_OFF, dev.patrickgold.florisboard.ime.keyboard.IncognitoMode.FORCE_ON, dev.patrickgold.florisboard.ime.keyboard.IncognitoMode.FORCE_OFF)
    Choice("incog", "Incognito typing", "Nothing you type is learned or kept. Automatic: in passwords and private browsing.", listOf("Automatic", "Always", "Never"), modes.indexOf(mode).coerceAtLeast(0)) { n ->
        scope.launch { prefs.suggestion.incognitoMode.set(modes[n]) }
    }
    ContactsRow()
}

/** Onboarding S.done: the try field, what is on and off, the keyboard. */
@Composable
private fun DoneStep() {
    val prefs by FlorisPreferenceStore
    val context = LocalContext.current
    val subtypeManager by context.subtypeManager()
    val subtypes by subtypeManager.subtypesFlow.collectAsState()
    val clip by prefs.clipboard.historyEnabled.collectAsState()
    val links by prefs.clipboard.linkPreviews.collectAsState()
    val ac by prefs.correction.autocorrect.collectAsState()
    val contacts by prefs.spelling.useContacts.collectAsState()
    val st = LayoutPrefs.read(prefs)
    TryField()
    val langs = subtypes.ifEmpty { listOf(Subtype.DEFAULT) }.map { it.primaryLocale.displayName() }.distinct().joinToString(", ")
    Summary(
        listOf(
            Triple("translate", langs, true),
            Triple("keyboard", if (st == LayoutElements.CLASSIC) "Classic layout" else if (st == LayoutElements.SIMPLE) "Simple layout" else "Your own layout", true),
            Triple("auto_fix_high", if (ac) "Autocorrect on" else "Autocorrect off", ac),
            Triple("content_paste", if (clip) "Clipboard history on" else "Clipboard history off", clip),
            Triple("link", if (links) "Link previews on" else "Link previews off", links),
            Triple("contacts", if (contacts) "Contacts on" else "Contacts off", contacts),
        ),
    )
    Preview()
}

/** Onboarding:111-112: summary chips, tinted when on. */
@Composable
private fun Summary(items: List<Triple<String, String, Boolean>>) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((icon, label, on) in items) {
            val (bg, fg) = if (on) SetColors.container(css("#d8e2ff"), css("#2a4678")) else SetColors.surfaceContainerHighest to SetColors.onSurfaceVariant
            Row(
                Modifier.height(30.dp).clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Symbol(icon, 16.sp, fg)
                DText(label, Modifier.widthIn(max = 280.dp), style = setText(12f, 500, fg), overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}

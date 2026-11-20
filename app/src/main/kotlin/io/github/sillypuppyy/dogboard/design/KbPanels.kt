package io.github.sillypuppyy.dogboard.design

import androidx.compose.ui.graphics.Color

/**
 * Kb.dc.html, ported 1:1: the special bar states (Kb:92-131, 615-618, 668), the dev row
 * (Kb:146-155, 637-644) and the panels (Kb:36-72 headers, Kb:199-281 bodies, Kb:646-680 data).
 */
data class KbDevKey(
    val label: String, val icon: String, val aria: String, val isIcon: Boolean, val bg: Color, val fg: Color,
    val ind: Boolean, val indW: Float, val indMl: Float,
)

/** A live clipboard item for the panel (stage 2): text, or a media item with an icon and label. */
data class KbClipData(
    val media: Boolean, val icon: String, val label: String, val text: String, val pinned: Boolean,
    /**
     * A preview of a media clip at about the given size in pixels (DogBoard, like Gboard; the
     * design draws striped placeholders, which show until this returns, or if it returns null).
     */
    val thumbnail: (suspend (sizePx: Int) -> androidx.compose.ui.graphics.ImageBitmap?)? = null,
    /** The source app marked it sensitive (a password): [text] is already masked. */
    val sensitive: Boolean = false,
    /** Seconds until a sensitive clip clears itself (the clipboard setting), or null when it doesn't. */
    val clearsInSec: Int? = null,
    /** Link marks (clipboard rows): null = the step doesn't change this link; true = applied; false = undone. */
    val expanded: Boolean? = null,
    val cleaned: Boolean? = null,
    /** A link's preview (LinkInfo, Settings › Clipboard › Link previews): page title, site, a video's length, banner, icon. */
    val linkTitle: String? = null,
    val linkSite: String? = null,
    val linkDuration: String = "",
    val linkImage: (suspend (sizePx: Int) -> androidx.compose.ui.graphics.ImageBitmap?)? = null,
    val linkIcon: (suspend (sizePx: Int) -> androidx.compose.ui.graphics.ImageBitmap?)? = null,
    /** Android's on-device classifier's type for the whole clip ("phone", "address", "otp"; ClipTypes), or null. */
    val systemType: String? = null,
    /** The hold menu offers "Paste as file" / "Share as file" for this clip. */
    val pasteFile: Boolean = false,
    val shareFile: Boolean = false,
)

/**
 * Kb:233-235, 892-906: a typed clip's 40 x 40 lead with a title and a grey sub-line. [bg] null =
 * the striped placeholder (fn / pop, 5 px bands), which a media clip's preview replaces (DogBoard).
 */
data class KbClipLead(
    val title: String, val sub: String = "", val icon: String = "", val letter: String = "",
    val bg: Color? = null, val fg: Color, val round: Boolean = false, val mono: Boolean = false,
)

data class KbClip(
    val text: String, val pinned: Boolean, val selected: Boolean, val bg: Color, val ring: List<CssShadow>,
    /** null = a plain text tile (two lines). */
    val lead: KbClipLead?,
    val thumbnail: (suspend (sizePx: Int) -> androidx.compose.ui.graphics.ImageBitmap?)? = null,
)

/**
 * The canvas's typed tiles (Kb:892-906) for a live clip: photo / video, password, colour, link; null
 * for plain text. Links get the letter lead (the canvas's other link look needs the site's icon,
 * which would take the internet). A password's sub-line is "Password": the canvas's "clears in 0:52"
 * needs an auto-clear DogBoard doesn't have.
 */
fun clipLead(d: KbClipData, fn: Color, fnFg: Color): KbClipLead? {
    val s = d.text.trim()
    return when {
        d.media -> KbClipLead(if (d.icon == "play_circle") "Video" else "Photo", icon = d.icon, fg = fnFg)
        // the canvas's "clears in 0:52" while auto-clear is on (Settings > Clipboard)
        d.sensitive -> KbClipLead("••••••••", d.clearsInSec?.let { "clears in %d:%02d".format(it / 60, it % 60) } ?: "Password", icon = "lock", bg = fn, fg = fnFg)
        // a colour (ClipKinds: #RGB, #RRGGBB, rgb(), hsl(); the whole clip only)
        else -> io.github.sillypuppyy.dogboard.ime.ClipKinds.color(s)?.let { c ->
            KbClipLead(c.hex, c.rgb.removePrefix("rgb(").removeSuffix(")").let { "rgb $it" }, bg = Color(c.argb), fg = fnFg, round = true, mono = true)
        } ?: LINK.matchEntire(s)?.let { m ->
            val host = m.groupValues[2].lowercase()
            KbClipLead(host, m.groupValues[3].trimEnd('/'), letter = host.take(1).uppercase(), bg = fn, fg = fnFg)
        }
    }
}

/** Kb:42: what the "Links" filter keeps: a clip that is a link (the link tile's test). */
fun isLinkClip(text: String): Boolean = LINK.matchEntire(text.trim()) != null

private val LINK = Regex("(?i)(https?://)?(?:www\\.)?((?:[a-z0-9-]+\\.)+[a-z]{2,})(/\\S*)?")

data class KbTool(val icon: String, val label: String, val pinned: Boolean = false)
data class KbPad(val icon: String, val text: String, val label: String, val bg: Color, val fg: Color)
data class KbEmojiCat(val icon: String, val name: String, val fill: Int, val fg: Color)

/** Everything the bar's special modes, the dev row and the panels need. */
data class KbExtras(
    // Kb:615-619 bar modes
    val mTools: Boolean, val mClip: Boolean, val mAutofill: Boolean, val mRemoved: Boolean, val mVoice: Boolean,
    val mIncog: Boolean, val toolRow: List<KbTool>, val removedWord: String, val clipLabel: String, val clipMedia: Boolean,
    val clipThumb: androidx.compose.ui.graphics.ImageBitmap?,
    // Kb:850-851 smart chips: the first filled (chip), the rest outlined
    val mChips: Boolean, val chips: List<KbChipData>,
    // Kb:637-644
    val dev: Boolean, val devKeys: List<KbDevKey>,
    // Kb:646-680
    val panel: String, val showPanel: Boolean,
    val pClip: Boolean, val pEmoji: Boolean, val pTools: Boolean, val pEdit: Boolean,
    val clips: List<KbClip>, val clipList: Boolean, val clipMsg: Boolean,
    /** The clipboard as rows (KbClipRows.kt): the live clips, or the canvas's. */
    val clipRows: List<KbClipRow> = emptyList(),
    /** The row whose hold menu is open in the design's "select" frame, or -1. */
    val clipHeld: Int = -1,
    /** Kb:41-43: the clipboard filter picked: 0 All, 1 Links, 2 Media (ours filters; the canvas draws All). */
    val clipFilter: Int = 0,
    /** The emoji panel is searching. */
    val emojiSearch: Boolean = false,
    val clipMsgIcon: String, val clipMsgTitle: String, val clipMsgBody: String, val clipMsgBtn: Boolean,
    val tools: List<KbTool>, val pad: List<KbPad>, val editOps: List<KbTool>,
    val emojiA: List<String>, val emojiB: List<String>, val emojiCats: List<KbEmojiCat>,
    val skins: List<Pair<String, Color>>, val skinX: Float, val skinPicker: Boolean,
)

object KbExtrasSpec {
    fun extras(P: KbProps, t: KbTokens, W: Float): KbExtras {
        val panel = P.panel
        val pvar = P.panelVariant
        val barless = P.plane == "phone" || P.plane == "decimal"
        // Kb:613-618
        val special = (P.bar in listOf("tools", "clip", "autofill", "removed", "voice") || P.bar in KbSpec.CHIPS) && P.slots == null
        val mIncog = P.incognito && !special && (P.bar == "empty" || P.bar == "hi") && P.slots == null
        // Kb:668
        val toolRow = listOf(
            KbTool("undo", "Undo"), KbTool("redo", "Redo"), KbTool("content_paste", "Clipboard"),
            KbTool("text_select_start", "Text editing"), KbTool("front_hand", "One-handed"), KbTool("more_horiz", "All tools"),
        )
        // Kb:638-644
        fun devKey(label: String, st: String = "off"): KbDevKey = KbDevKey(
            label = label, icon = "", aria = label + when (st) { "armed" -> ", armed for one key"; "locked" -> ", locked"; else -> "" },
            isIcon = false,
            bg = when (st) { "armed" -> t.chip; "locked" -> t.act; else -> t.fn },
            fg = when (st) { "armed" -> t.chipFg; "locked" -> t.actFg; else -> t.fnFg },
            ind = st != "off", indW = if (st == "locked") 18f else 4f, indMl = if (st == "locked") -9f else -2f,
        )
        fun devIcon(icon: String, aria: String) = KbDevKey("", icon, aria, true, t.fn, t.fnFg, false, 0f, 0f)
        val devKeys = listOf(
            devKey("Esc"), devKey("Tab"), devKey("Ctrl", P.devCtrl), devKey("Alt", P.devAlt),
            devIcon("arrow_back", "Left"), devIcon("arrow_upward", "Up"), devIcon("arrow_downward", "Down"), devIcon("arrow_forward", "Right"),
            devKey("Home"), devKey("End"), devKey("{"), devKey("}"), devKey("|"),
        )
        // Kb:647-662
        val pClip = panel == "clipboard"; val pEmoji = panel == "emoji"; val pTools = panel == "tools"; val pEdit = panel == "edit"
        data class Raw(
            val lead: KbClipLead? = null, val text: String = "", val pinned: Boolean = false,
            val selected: Boolean = false, val thumbnail: (suspend (Int) -> androidx.compose.ui.graphics.ImageBitmap?)? = null,
        )
        // Kb:892-899
        val clipsRaw = P.clips?.map { Raw(clipLead(it, t.fn, t.fnFg), it.text, it.pinned, thumbnail = it.thumbnail) } ?: listOf(
            Raw(KbClipLead("Photo", "1080 × 1350", icon = "image", fg = t.fnFg), pinned = true),
            Raw(KbClipLead("#4B6EAF", "rgb 75 110 175", bg = Color(0xFF4B6EAF), fg = t.fnFg, round = true, mono = true)),
            Raw(KbClipLead("silly-puppyy/DogBoard", "github.com", icon = "public", fg = t.fnFg)),
            Raw(text = "Meet at the dog park at 6?", pinned = true, selected = pvar == "select"),
            Raw(KbClipLead("example.org", "/recipes/dog-biscuits", letter = "E", bg = t.fn, fg = t.fnFg)),
            Raw(KbClipLead("••••••••", "Password, clears in 0:52", icon = "lock", bg = t.fn, fg = t.fnFg)),
        )
        val clips = clipsRaw.map { c ->
            KbClip(
                text = c.text, pinned = c.pinned, selected = c.selected, bg = t.tile, lead = c.lead, thumbnail = c.thumbnail,
                ring = when {
                    c.selected -> listOf(CssShadow(true, 0f, 0f, 0f, 2f, t.linkFg))
                    t.tile == t.bg -> listOf(CssShadow(true, 0f, 0f, 0f, 1f, t.div))
                    else -> emptyList()
                },
            )
        }
        // Kb:663-667
        val tools = listOf(
            KbTool("undo", "Undo", true), KbTool("redo", "Redo", true), KbTool("select_all", "Select all"), KbTool("text_select_start", "Text editing", true),
            KbTool("content_paste", "Clipboard", true), KbTool("mood", "Emoji"), KbTool("front_hand", "One-handed", true), KbTool("picture_in_picture", "Floating"),
            KbTool("open_with", "Resize"), KbTool("keyboard_command_key", "Dev keys"), KbTool("domino_mask", "Incognito"), KbTool("palette", "Theme"),
        )
        // Kb:669-674
        fun pad(icon: String = "", text: String = "", label: String = "") =
            KbPad(icon, text, label, if (icon.isNotEmpty()) t.tile else if (text.isNotEmpty()) t.chip else Color.Transparent, if (text.isNotEmpty()) t.chipFg else t.tileFg)
        val pad = listOf(
            pad(), pad("keyboard_arrow_up", label = "Up"), pad(),
            pad("keyboard_arrow_left", label = "Left"), pad(text = "Select", label = "Select"), pad("keyboard_arrow_right", label = "Right"),
            pad(), pad("keyboard_arrow_down", label = "Down"), pad(),
        )
        val editOps = listOf(
            KbTool("select_all", "Select all"), KbTool("content_copy", "Copy"), KbTool("content_cut", "Cut"),
            KbTool("content_paste", "Paste"), KbTool("first_page", "Home"), KbTool("last_page", "End"),
        )
        // Kb:675-680
        val emojiA = listOf("😂", "❤️", "👍", "🐶", "🥹", "✨", "🙏", "😭", "🔥", "😊", "🎉", "🐾", "😅", "👀", "💛", "🙌")
        val emojiB = listOf("😀", "😃", "😄", "😁", "😆", "🥲", "😉", "😍", "😘", "😋", "😎", "🤔", "😴", "🤗", "😬", "🙃")
        val emojiCats = listOf(
            "schedule" to "Recent", "mood" to "Smileys", "pets" to "Animals", "restaurant" to "Food", "sports_soccer" to "Activities",
            "flight" to "Travel", "lightbulb" to "Objects", "emoji_symbols" to "Symbols", "flag" to "Flags",
        ).mapIndexed { i, (icon, name) -> KbEmojiCat(icon, name, if (i == 0) 1 else 0, if (i == 0) t.linkFg else t.barMuted) }
        val skins = listOf("👍", "👍🏻", "👍🏼", "👍🏽", "👍🏾", "👍🏿").mapIndexed { i, e -> e to if (i == 3) t.sel else Color.Transparent }
        val cellW = (W - 16) / 8
        val skinX = maxOf(4f, 8 + cellW * 2.5f - 120)

        return KbExtras(
            mTools = special && P.bar == "tools", mClip = special && P.bar == "clip", mAutofill = special && P.bar == "autofill",
            mRemoved = special && P.bar == "removed", mVoice = special && P.bar == "voice", mIncog = mIncog, toolRow = toolRow, removedWord = P.removedWord, clipLabel = P.clipLabel, clipMedia = P.clipMedia, clipThumb = P.clipThumb,
            mChips = special && P.bar in KbSpec.CHIPS, chips = P.chips ?: KbSpec.CHIPS[P.bar].orEmpty(),
            dev = P.dev && panel.isEmpty(), devKeys = devKeys,
            panel = panel, showPanel = panel.isNotEmpty() && !barless,
            pClip = pClip, pEmoji = pEmoji, pTools = pTools, pEdit = pEdit,
            clips = clips, clipRows = P.clips?.map(::clipRow) ?: demoClipRows(), clipHeld = if (pvar == "select") 2 else -1, clipFilter = P.clipFilter, emojiSearch = P.emojiSearch, clipList = pClip && (pvar == "default" || pvar == "select"), clipMsg = pClip && (pvar == "locked" || pvar == "empty"),
            clipMsgIcon = if (pvar == "locked") "lock" else "content_paste_off",
            clipMsgTitle = if (pvar == "locked") "Unlock to see your clipboard" else "Clipboard history is off",
            clipMsgBody = if (pvar == "locked") "Clips stay hidden on the lock screen, pinned ones too." else "Keep what you copy for an hour, including images and videos. Pin a clip to keep it longer.",
            clipMsgBtn = pvar == "empty",
            tools = tools, pad = pad, editOps = editOps, emojiA = emojiA, emojiB = emojiB, emojiCats = emojiCats,
            skins = skins, skinX = skinX, skinPicker = P.skinPicker,
        )
    }
}

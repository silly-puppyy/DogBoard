#!/usr/bin/env python3
"""Generate DogBoard's keyboard layouts (keyboard3 / CLDR Keyboard 3.0 XML), one file per layout.

Every layout shares the symbol planes, field rows and widths; only the letter rows differ. English
QWERTY is defined here; other layouts take their letter rows from FlorisBoard's layout data
(app/src/main/assets/ime/keyboard/org.florisboard.layouts/layouts/characters/*.json).

Key widths are in keyboard3 units (a row is 10 units wide). They follow a 375 dp reference
keyboard where a letter cell is 37.5 dp (1 unit) with ten letters in the widest row; with more
letters per row every letter narrows to fill it:

    row 3:  shift 1.5 units | 7 letters | delete 1.5 units (Gboard; was iOS 48 dp with gaps)
    row 4:  123 46.5 | fn 47 | space 188 | return 93.5

Run from the repository root:
    python3 tools/layouts/gen_layouts.py
"""
import json
from dataclasses import dataclass, field, replace
from pathlib import Path

OUT_DIR = Path("app/src/main/assets/extensions/org.florisboard.k3.foundation/keyboard")
FLORIS_LAYOUTS = Path("app/src/main/assets/ime/keyboard/org.florisboard.layouts/layouts/characters")

UNIT = 37.5  # dp per keyboard3 width unit on the reference keyboard


def w(dp: float) -> str:
    return f"{dp / UNIT:.4f}".rstrip("0").rstrip(".")


SHIFT = "1.5"  # Gboard: shift and delete take what the 7 letters leave, no gaps (user 2026-11-17)
# the iOS bottom row keeps iOS's widths (user 2026-11-17): 123 46.5 | emoji 47 | space 188 | return 93.5
MODE = w(46.5)       # 123 / ABC
FN = w(47)           # emoji / language key
SPACE = w(188)       # space absorbs the 37.5 dp a dictation key would take
RETURN = w(93.5)
# Email and URL bottom rows: iOS arrangement and widths (email from the 320 pt layout stretched to
# 375, URL from the 375 pt layout); TLD and currency long-press sets as in iOS
EMAIL_KEY = w(40 * 375 / 320)  # 320 pt email row (40 pt keys) stretched to 375
SPACE_EMAIL = w(375 - 46.5 - 47 - 2 * 40 * 375 / 320 - 93.5)  # space + the merged dictation slot
TLDS = ".org .edu .net"  # long-press on .com
PUNCT_WIDE = "1.4"   # the five wide punctuation keys on the symbol layers (5 x 1.4 = 7 letters)

# Long-press alternatives per letter, in popup order (iOS arrangement).
ACCENTS = {
    "a": "à á â ä ǎ æ ã å ā ă ą", "A": "À Á Â Ä Ǎ Æ Ã Å Ā Ă Ą",
    "c": "ç ć č ċ", "C": "Ç Ć Č Ċ",
    "d": "ď ð", "D": "Ď Ð",
    "e": "è é ê ë ě ẽ ē ė ę", "E": "È É Ê Ë Ě Ẽ Ē Ė Ę",
    "g": "ğ ġ", "G": "Ğ Ġ",
    "h": "ħ", "H": "Ħ",
    "i": "ì í î ï ǐ ĩ ī ı į", "I": "Ì Í Î Ï Ǐ Ĩ Ī İ Į",
    "k": "ķ", "K": "Ķ",
    "l": "ł ļ ľ", "L": "Ł Ļ Ľ",
    "n": "ñ ń ņ ň", "N": "Ñ Ń Ņ Ň",
    "o": "ò ó ô ö ǒ œ ø õ ō ő", "O": "Ò Ó Ô Ö Ǒ Œ Ø Õ Ō Ő",
    "r": "ř", "R": "Ř",
    "s": "ß ş ș ś š", "S": "ẞ Ś Š Ş Ș",
    "t": "ț ť þ", "T": "Ț Ť Þ",
    "u": "ù ú û ü ǔ ũ ū ű ů ų", "U": "Ù Ú Û Ü Ǔ Ũ Ū Ű Ů Ų",
    "w": "ŵ", "W": "Ŵ",
    "y": "ý ŷ ÿ", "Y": "Ý Ŷ Ÿ",
    "z": "ź ž ż", "Z": "Ź Ž Ż",
}

# Symbol keys: id -> (output, long-press alternatives). Arrangement follows iOS.
# Long-press sets are from known iOS behaviour; verify on a real iPhone.
SYMBOLS = {
    "s-1": ("1", ""), "s-2": ("2", ""), "s-3": ("3", ""), "s-4": ("4", ""), "s-5": ("5", ""),
    "s-6": ("6", ""), "s-7": ("7", ""), "s-8": ("8", ""), "s-9": ("9", ""), "s-0": ("0", "°"),
    "s-hyphen": ("-", "– — •"), "s-slash": ("/", "\\"), "s-colon": (":", ""), "s-semicolon": (";", ""),
    "s-lparen": ("(", ""), "s-rparen": (")", ""), "s-dollar": ("$", "€ £ ¥ ₩ ₽"), "s-amp": ("&", "§"),
    "s-at": ("@", ""), "s-dquote": ('"', "„ “ ” « »"),
    "s-lsquare": ("[", ""), "s-rsquare": ("]", ""), "s-lcurly": ("{", ""), "s-rcurly": ("}", ""),
    "s-hash": ("#", ""), "s-percent": ("%", "‰"), "s-caret": ("^", ""), "s-asterisk": ("*", ""),
    "s-plus": ("+", ""), "s-equal": ("=", "≠ ≈"),
    "s-underscore": ("_", ""), "s-backslash": ("\\", ""), "s-pipe": ("|", ""), "s-tilde": ("~", ""),
    "s-less": ("<", ""), "s-greater": (">", ""), "s-euro": ("€", ""), "s-pound": ("£", ""),
    "s-yen": ("¥", ""), "s-bullet": ("•", ""),
    # Gboard's symbol pages (Settings > Layout > Symbols: Android-style; photographed on the emulator 2026-11-17)
    "s-apos": ("'", "’ ‘ `"), "s-excl": ("!", "¡"), "s-question": ("?", "¿"), "s-backtick": ("`", ""),
    "s-sqrt": ("√", ""), "s-pi": ("π", ""), "s-divide": ("÷", ""), "s-times": ("×", ""), "s-section": ("§", ""),
    "s-delta": ("Δ", ""), "s-cent": ("¢", ""), "s-degree": ("°", ""), "s-copy": ("©", ""), "s-reg": ("®", ""),
    "s-tm": ("™", ""), "s-check": ("✓", ""),
}
WIDE_PUNCT = {
    "period-wide": (".", "…"), "comma-wide": (",", ""), "question-wide": ("?", "¿"),
    "bang-wide": ("!", "¡"), "apos-wide": ("'", "’ ‘ `"),
}



@dataclass
class Layout:
    """A layout's letter rows (one string per key: letters, or a punctuation key in a letter row)."""
    id: str
    locale: str
    name: str
    layout: str
    indicator: str
    rows: list[list[str]]
    accents: dict[str, str] = field(default_factory=lambda: ACCENTS)
    # the shift layer's key where it isn't the letter's own capital (FlorisBoard case selectors: ß -> ẞ)
    uppers: dict[str, str] = field(default_factory=dict)
    # Settings' short name where the layout name alone is ambiguous ("QWERTY (ñ)")
    label: str | None = None
    # what the Layout page says under it: "taps" (swipe reads QWERTY only), "english" (no pack: English engine off)
    note: str | None = "taps"


ENGLISH = Layout("qwerty-en", "en", "English (QWERTY)", "QWERTY", "EN",
                 [list("qwertyuiop"), list("asdfghjkl"), list("zxcvbnm")], note=None)


def floris_layout(id: str, locale: str, name: str, layout: str, indicator: str, file: str) -> Layout:
    """A layout from a FlorisBoard layout file: its key labels as letter rows, case selectors as uppers."""
    data = json.loads((FLORIS_LAYOUTS / f"{file}.json").read_text(encoding="utf-8"))
    rows, uppers = [], {}
    for row in (data.get("arrangement", data) if isinstance(data, dict) else data):
        keys = []
        for k in row:
            if k.get("$") == "case_selector":
                keys.append(k["lower"]["label"])
                uppers[k["lower"]["label"]] = k["upper"]["label"]
            else:
                keys.append(k["label"])
        rows.append(keys)
    return Layout(id, locale, name, layout, indicator, rows, uppers=uppers)


GBOARD_DATA = Path("../notes/design/data/layouts/gboard")


def gboard_layout(id: str, locale: str, name: str, layout: str, indicator: str, tag: str, variant: str) -> Layout:
    """A layout from Gboard's registry as extracted (notes/design/data/layouts/gboard, derived JSON, no Google files): its
    letter rows; a key that types two letters (Arabic's ﻻ presentation form) is left out (typed as its two letters)."""
    langs = json.loads((GBOARD_DATA / "languages.json").read_text(encoding="utf-8"))
    lays = json.loads((GBOARD_DATA / "layouts.json").read_text(encoding="utf-8"))
    v = next(v for v in langs[tag]["variants"] if v["variant"] == variant)
    rows = [[k for k in r if isinstance(k, str) and len(k) == 1 and k.isalpha() and not ("\ufb50" <= k <= "\ufeff")]
            for r in lays[v["layout_id"]]["rows"]]
    return Layout(id, locale, name, layout, indicator, [r for r in rows if r])


def no_capitals(lay: Layout) -> Layout:
    """Shift types the same letters: Georgian writes no sentence capitals (Mtavruli, what str.upper gives, is an
    all-caps style iOS offers only on long-press), so the shift layer must not turn ა into Ა."""
    lay.uppers = {c: c for r in lay.rows for c in r}
    return lay


# English variants Gboard offers (user 2026-11-19: "a good interface for picking a keyboard layout"). Dvorak as
# Gboard draws it on phones (FlorisBoard's dvorak.json lacks q and z); Colemak from FlorisBoard's data
DVORAK = Layout("dvorak-en", "en", "English (Dvorak)", "Dvorak", "EN",
                [list("',.pyfgcrl"), list("aoeuidhtns"), list("qjkxbmwvz")])

LAYOUTS = [
    ENGLISH,
    DVORAK,
    floris_layout("colemak-en", "en", "English (Colemak)", "Colemak", "EN", "colemak"),
    replace(floris_layout("qwertz-de", "de", "Deutsch (QWERTZ)", "QWERTZ", "DE", "german"), note="english"),
    replace(floris_layout("azerty-fr", "fr", "Français (AZERTY)", "AZERTY", "FR", "azerty"), note="english"),
    # 2026-11-22 languages pass: the layouts iOS defaults these languages to (notes/design/data/layouts)
    replace(floris_layout("qwerty-es", "es", "Español (QWERTY)", "QWERTY", "ES", "spanish"), label="QWERTY (ñ)"),
    replace(floris_layout("qwerty-fi", "fi", "Suomi (QWERTY)", "QWERTY", "FI", "swedish_finnish"), label="QWERTY (å ä ö)"),
    floris_layout("jcuken-ru", "ru", "Русский (ЙЦУКЕН)", "ЙЦУКЕН", "RU", "jcuken_russian"),
    # 2026-11-22 auto-built packs (tools/i18n/auto_pack.py): Czech QWERTZ (Gboard's = FlorisBoard's), the standard
    # Georgian layout (all 33 letters on one plane; Gboard's default = FlorisBoard's), Arabic from Gboard (FlorisBoard's
    # lacks ئ)
    floris_layout("qwertz-cs", "cs", "Čeština (QWERTZ)", "QWERTZ", "CS", "qwertz"),
    no_capitals(floris_layout("standard-ka", "ka", "ქართული", "ქართული", "KA", "georgian_standard")),
    gboard_layout("arabic-ar", "ar", "العربية", "عربي", "AR", "ar", "arabic"),
]

SYMBOL_1 = [
    ["s-1", "s-2", "s-3", "s-4", "s-5", "s-6", "s-7", "s-8", "s-9", "s-0"],
    ["s-hyphen", "s-slash", "s-colon", "s-semicolon", "s-lparen", "s-rparen", "s-dollar", "s-amp", "s-at", "s-dquote"],
]
# Gboard's ?123 and =\< pages: rows 1-2 of ten keys, row 3 seven keys between the page switch and delete
SYMBOL_1_ANDROID = [
    ["s-1", "s-2", "s-3", "s-4", "s-5", "s-6", "s-7", "s-8", "s-9", "s-0"],
    ["s-at", "s-hash", "s-dollar", "s-underscore", "s-amp", "s-hyphen", "s-plus", "s-lparen", "s-rparen", "s-slash"],
    ["s-asterisk", "s-dquote", "s-apos", "s-colon", "s-semicolon", "s-excl", "s-question"],
]
SYMBOL_2_ANDROID = [
    ["s-tilde", "s-backtick", "s-pipe", "s-bullet", "s-sqrt", "s-pi", "s-divide", "s-times", "s-section", "s-delta"],
    ["s-pound", "s-cent", "s-euro", "s-yen", "s-caret", "s-degree", "s-equal", "s-lcurly", "s-rcurly", "s-backslash"],
    ["s-percent", "s-copy", "s-reg", "s-tm", "s-check", "s-lsquare", "s-rsquare"],
]
SYMBOL_2 = [
    ["s-lsquare", "s-rsquare", "s-lcurly", "s-rcurly", "s-hash", "s-percent", "s-caret", "s-asterisk", "s-plus", "s-equal"],
    ["s-underscore", "s-backslash", "s-pipe", "s-tilde", "s-less", "s-greater", "s-euro", "s-pound", "s-yen", "s-bullet"],
]


def xml_char(c: str) -> str:
    return {'"': "&quot;", "&": "&amp;", "<": "&lt;", ">": "&gt;", "\\": "\\u{005C}"}.get(c, c)


def key_with_alts(key_id: str, output: str, alts: str, width: str | None = None) -> list[str]:
    width_attr = f' width="{width}"' if width else ""
    if not alts:
        return [f'<key id="{key_id}" output="{xml_char(output)}"{width_attr} />']
    ids = [f"{key_id}-alt{i}" for i in range(len(alts.split()))]
    lines = [f'<key id="{key_id}" output="{xml_char(output)}"{width_attr} '
             f'longPressKeyIds="{" ".join(ids)}" longPressDefaultKeyId="{ids[0]}" />']
    lines += [f'<key id="{i}" output="{xml_char(c)}" />' for i, c in zip(ids, alts.split())]
    return lines


def key_id(c: str) -> str:
    """A letter row key's id: the letter itself for a-z / A-Z (as before), else its code point."""
    return c if c.isascii() and c.isalpha() else f"u{ord(c):04x}" if c.islower() or not c.isalpha() else f"U{ord(c):04x}"


def upper(c: str, lay: "Layout | None" = None) -> str:
    """The shift layer's key: the layout's own capital, else a letter's capital (one character), else unchanged."""
    if lay is not None and c in lay.uppers:
        return lay.uppers[c]
    u = c.upper()
    return u if len(u) == 1 else c


def letter_keys(lay: Layout) -> list[str]:
    lines = []
    seen = set()
    for c in [c for row in lay.rows for c in row]:
        for case_c in (c, upper(c, lay)):
            if key_id(case_c) in seen:
                continue
            seen.add(key_id(case_c))
            w3 = row3_letter_width(lay)
            only_row3 = w3 is not None and c in lay.rows[2] and c not in lay.rows[0] and c not in lay.rows[1]
            width = (f"{w3:.4f}".rstrip("0").rstrip(".") if only_row3 else letter_width(lay))
            lines += key_with_alts(key_id(case_c), case_c, lay.accents.get(case_c, ""), width)
    return lines


def letter_width(lay: Layout) -> str | None:
    """Letter key width when the widest of the first two rows has more than ten keys, else None (1 unit)."""
    n = max(len(lay.rows[0]), len(lay.rows[1]))
    # rounded down: a row a hair over 10 units loses its last key in the touch layout (ü ö ä did)
    return None if n <= 10 else f"{int(10 / n * 10000) / 10000:.4f}".rstrip("0").rstrip(".")


MIN_SHIFT = 1.0  # the narrowest shift / delete beside a long third row (0.5 cut their icons, Dvorak's 9 letters)


def row3_letter_width(lay: Layout) -> float | None:
    """Third-row letters narrower than the others when shift and delete would get under [MIN_SHIFT], else None."""
    w = float(letter_width(lay) or 1)
    n = len(lay.rows[2])
    if (10 - n * w) / 2 >= MIN_SHIFT - 0.01:
        return None
    return int((10 - 2 * MIN_SHIFT) / n * 10000) / 10000


def row3(lay: Layout, shift_id: str, shifted: bool) -> str:
    """Row 3: shift, a gap, the letters, a gap, delete; narrower shift / delete when the letters need it."""
    letters = " ".join(key_id(upper(c, lay) if shifted else c) for c in lay.rows[2])
    w = float(letter_width(lay) or 1)
    rest = 10 - len(lay.rows[2]) * w - 2 * float(SHIFT)
    if rest < 0.01 and rest > -0.01:
        return f"{shift_id} {letters} bksp"
    if rest >= 0:
        return f"{shift_id} gap3-{lay.id} {letters} gap3-{lay.id} bksp"
    return f"{shift_id}-narrow-{lay.id} {letters} bksp-narrow-{lay.id}"


def row_keys(lay: Layout, i: int, shifted: bool) -> str:
    """Rows 1 and 2: the letters, centred with half-gaps when the row is shorter than the widest."""
    w = float(letter_width(lay) or 1)
    keys = " ".join(key_id(upper(c, lay) if shifted else c) for c in lay.rows[i])
    side = (10 - len(lay.rows[i]) * w) / 2
    if lay is ENGLISH or side < 0.01:
        return keys
    return f"gap{i + 1}-{lay.id} {keys} gap{i + 1}-{lay.id}"


def layout_gaps(lay: Layout) -> list[str]:
    """The per-layout gap and narrow shift / delete keys rows 1-3 use."""
    if lay is ENGLISH:
        return []
    w = float(letter_width(lay) or 1)
    fmt = lambda x: f"{int(x * 10000) / 10000:.4f}".rstrip("0").rstrip(".")
    out = []
    for i in (0, 1):
        side = (10 - len(lay.rows[i]) * w) / 2
        if side >= 0.01:
            out.append(f'<key id="gap{i + 1}-{lay.id}" gap="true" width="{fmt(side)}" />')
    rest = 10 - len(lay.rows[2]) * w - 2 * float(SHIFT)
    if 0.01 <= rest:
        out.append(f'<key id="gap3-{lay.id}" gap="true" width="{fmt(rest / 2)}" />')
    elif rest > -0.01:
        pass
    else:
        side = fmt((10 - len(lay.rows[2]) * (row3_letter_width(lay) or w)) / 2)
        out += [f'<key id="base-to-shift-narrow-{lay.id}" layerId="shift" width="{side}" />',
                f'<key id="shift-to-base-narrow-{lay.id}" layerId="base" width="{side}" />',
                f'<key id="bksp-narrow-{lay.id}" output="@k3:action/backspace" width="{side}" />']
    return out


def bottom_row(mode_key: str) -> str:
    return f"{mode_key} fn-key space enter"


# Field-specific bottom rows; the app swaps a "<layer>-<variant>" layer in for base and shift.
FIELD_ROWS = {
    "email": "to-symbol-1 fn-key space-email at-email period-email enter",
    "url": "to-symbol-1 fn-key space-email slash-url period-url enter",
    # the designer's Classic bottom row (Layout element "Bottom row"): ?123 · comma · emoji · space ·
    # period · action, 1.5 · 1 · 1 · 4 · 1 · 1.5 units; email puts @ in the comma slot, URLs /. The
    # emoji key is its narrower copy fn-key-classic (TouchModel treats both ids as the fn key).
    "classic": "to-symbol-1-classic comma-classic fn-key-classic space-classic period-classic enter-classic",
    "classic-email": "to-symbol-1-classic at-classic fn-key-classic space-classic period-classic enter-classic",
    "classic-url": "to-symbol-1-classic slash-classic fn-key-classic space-classic period-classic enter-classic",
}


def build(lay: Layout) -> str:
    keys = [
        "",
        "<!-- Layer switches -->",
        f'<key id="base-to-shift" layerId="shift" width="{SHIFT}" />',
        f'<key id="shift-to-base" layerId="base" width="{SHIFT}" />',
        f'<key id="to-symbol-1" layerId="symbol-1" width="{MODE}" />',
        f'<key id="to-symbol-1-row3" layerId="symbol-1" width="{SHIFT}" />',
        f'<key id="to-symbol-2" layerId="symbol-2" width="{SHIFT}" />',
        f'<key id="to-symbol-2-android" layerId="symbol-2" width="{SHIFT}" />',
        f'<key id="to-symbol-1-android" layerId="symbol-1" width="{SHIFT}" />',
        f'<key id="to-base" layerId="base" width="{MODE}" />',
        '<key id="numeric" layerId="numeric" />',
        "",
        "<!-- Actions -->",
        f'<key id="bksp" output="@k3:action/backspace" width="{SHIFT}" />',
        # long-press: a line break, so chats that send on the action key can still get one (designer Panels:56)
        f'<key id="enter" output="@k3:action/enter" width="{RETURN}" longPressKeyIds="newline" />',
        '<key id="newline" output="\\u{000A}" />',
        f'<key id="space" output="\\u{{0020}}" width="{SPACE}" />',
        f'<key id="space-email" output="\\u{{0020}}" width="{SPACE_EMAIL}" />',
        f'<key id="to-symbol-1-classic" layerId="symbol-1" width="1.5" />',
        '<key id="comma-classic" output="," width="1" />',
        '<key id="at-classic" output="@" width="1" />',
        '<key id="slash-classic" output="/" width="1" />',
        '<key id="fn-key-classic" gap="true" width="1" />',
        '<key id="space-classic" output="\\u{0020}" width="4" />',
        '<key id="period-classic" output="." width="1" />',
        '<key id="enter-classic" output="@k3:action/enter" width="1.5" longPressKeyIds="newline" />',
        f'<key id="at-email" output="@" width="{EMAIL_KEY}" />',
        f'<key id="period-email" output="." width="{EMAIL_KEY}" />',
        "<!-- URL fields keep a space bar (Android address bars are search boxes too; Gboard keeps one):",
        "     the email row's widths, with / and . ; the domains on a long press of . -->",
        f'<key id="period-url" output="." width="{EMAIL_KEY}" longPressKeyIds="tld-com tld-org tld-net tld-edu" longPressDefaultKeyId="tld-com" />',
        f'<key id="slash-url" output="/" width="{EMAIL_KEY}" />',
        '<key id="tld-com" output=".com" />',
        *[f'<key id="tld-{t.strip(".").replace(".", "-")}" output="{t}" />' for t in TLDS.split()],
        f'<key id="fn-key" gap="true" width="{FN}" />',
        "",
        "<!-- Digits and extra symbols -->",
        *[line for key_id, (out, alts) in SYMBOLS.items() for line in key_with_alts(key_id, out, alts)],
        *[line for key_id, (out, alts) in WIDE_PUNCT.items()
          for line in key_with_alts(key_id, out, alts, PUNCT_WIDE)],
        "",
        "<!-- Letters -->",
        *letter_keys(lay),
        *layout_gaps(lay),
        "",
        "<!-- Numeric layer (number input fields), from FlorisBoard's foundation layout -->",
        '<key id="plus-numeric" output="+" width="1.8" longPressKeyIds="s-hyphen s-asterisk s-slash" />',
        '<key id="open-paren-numeric" output="(" width="1.8" longPressKeyIds="s-lsquare s-lcurly" />',
        '<key id="close-paren-numeric" output=")" width="1.8" longPressKeyIds="s-rsquare s-rcurly" />',
        '<key id="percent-numeric" output="%" width="1.8" />',
        '<key id="space-numeric" output="\\u{0020}" width="1.8" />',
        '<key id="bksp-numeric" output="@k3:action/backspace" width="1.8" />',
        '<key id="base-numeric" layerId="base" width="1.8" />',
        '<key id="comma-numeric" output="," width="1.1" />',
        '<key id="symbols-numeric" layerId="symbol-1" width="1.9" />',
        '<key id="equal-numeric" output="=" width="1.9" />',
        '<key id="period-numeric" output="." width="1.1" />',
        '<key id="enter-numeric" output="@k3:action/enter" width="1.8" />',
        *[f'<key id="{d}-numeric" output="{d}" width="3" />' for d in "0123456789"],
    ]

    wide = " ".join(WIDE_PUNCT)
    base_rows = [row_keys(lay, 0, False), row_keys(lay, 1, False), row3(lay, "base-to-shift", False)]
    shift_rows = [row_keys(lay, 0, True), row_keys(lay, 1, True), row3(lay, "shift-to-base", True)]
    layers = [
        ("base", base_rows + [bottom_row("to-symbol-1")]),
        ("shift", shift_rows + [bottom_row("to-symbol-1")]),
        ("symbol-1", [" ".join(SYMBOL_1[0]), " ".join(SYMBOL_1[1]),
                      f"to-symbol-2 {wide} bksp", bottom_row("to-base")]),
        ("symbol-2", [" ".join(SYMBOL_2[0]), " ".join(SYMBOL_2[1]),
                      f"to-symbol-1-row3 {wide} bksp", bottom_row("to-base")]),
        # the Android-style pages; the touch layout swaps them in for symbol-1 / symbol-2 when chosen
        ("symbol-1-android", [" ".join(SYMBOL_1_ANDROID[0]), " ".join(SYMBOL_1_ANDROID[1]),
                              "to-symbol-2-android " + " ".join(SYMBOL_1_ANDROID[2]) + " bksp", bottom_row("to-base")]),
        ("symbol-2-android", [" ".join(SYMBOL_2_ANDROID[0]), " ".join(SYMBOL_2_ANDROID[1]),
                              "to-symbol-1-android " + " ".join(SYMBOL_2_ANDROID[2]) + " bksp", bottom_row("to-base")]),
        *[(f"{layer}-{variant}", rows[:3] + [row]) for variant, row in FIELD_ROWS.items()
          for layer, rows in (("base", base_rows + [""]), ("shift", shift_rows + [""]))],
        ("numeric", [
            "plus-numeric 1-numeric 2-numeric 3-numeric percent-numeric",
            "open-paren-numeric 4-numeric 5-numeric 6-numeric space-numeric",
            "close-paren-numeric 7-numeric 8-numeric 9-numeric bksp-numeric",
            "base-numeric comma-numeric symbols-numeric 0-numeric equal-numeric period-numeric enter-numeric",
        ]),
    ]

    out = [
        f'<keyboard3 xmlns="https://schemas.unicode.org/cldr/45/keyboard3" conformsTo="45" locale="{lay.locale}">',
        f'    <info author="DogBoard" name="{lay.name}" layout="{lay.layout}" indicator="{lay.indicator}" />',
        "    <!-- Generated by tools/layouts/gen_layouts.py; edit the generator, not this file. -->",
        "",
        "    <displays>",
        '        <display keyId="base-to-shift" display="@fl:icon/shift" />',
        '        <display keyId="shift-to-base" display="@fl:icon/shift" />',
        '        <display keyId="to-symbol-1" display="123" />',
        '        <display keyId="to-symbol-1-row3" display="123" />',
        '        <display keyId="to-symbol-1-classic" display="?123" />',
        '        <display keyId="space-classic" display="space" />',
        '        <display keyId="to-symbol-2" display="#+=" />',
        '        <display keyId="to-symbol-2-android" display="=\\u{005C}&lt;" />',
        '        <display keyId="to-symbol-1-android" display="?123" />',
        '        <display keyId="to-base" display="ABC" />',
        '        <display keyId="space" display="space" />',
        '        <display keyId="newline" display="New line" />',
        '        <display keyId="space-email" display="space" />',
        '        <display keyId="base-numeric" display="ABC" />',
        '        <display keyId="symbols-numeric" display="#+=" />',
        '        <display keyId="space-numeric" display="@fl:icon/space_bar" />',
        "    </displays>",
        "",
        "    <keys>",
        *[("        " + line) if line else "" for line in keys],
        "    </keys>",
        "",
        '    <layers formId="touch" minDeviceWidth="1">',
        '        <import path="flex://org.florisboard.k3.foundation/layers-touch.xml" />',
    ]
    for layer_id, rows in layers:
        out.append("")
        out.append(f'        <layer id="{layer_id}">')
        out += [f'            <row keys="{row}" />' for row in rows]
        out.append("        </layer>")
    out += ["    </layers>", "</keyboard3>", ""]
    return "\n".join(out)


# the language names the Layout page shows under a card (English, as the rest of Settings)
LANGUAGE_NAMES = {"en": "English", "de": "German", "fr": "French", "es": "Spanish", "fi": "Finnish", "ru": "Russian",
                  "cs": "Czech", "ka": "Georgian", "ar": "Arabic"}
KOTLIN_OUT = Path("app/src/main/kotlin/io/github/sillypuppyy/dogboard/ime/GeneratedLayouts.kt")


def kotlin_list() -> str:
    """GeneratedLayouts.kt: every layout this script writes, for LanguageLayouts, the Layout page and the Languages
    page (one list; they were three hand-typed ones). A language's first layout here is its default."""
    def q(t: str | None) -> str:
        return "null" if t is None else '"' + t.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'
    seen = set()
    lines = []
    for lay in LAYOUTS:
        rows = ", ".join(q("".join(r)) for r in lay.rows)
        lines.append(f"        LayoutInfo({q(lay.id)}, {q(lay.locale)}, {q(LANGUAGE_NAMES[lay.locale])}, {q(lay.layout)}, "
                     f"{q(lay.label or lay.layout)}, listOf({rows}), default = {str(lay.locale not in seen).lower()}, note = {q(lay.note)}),")
        seen.add(lay.locale)
    return "\n".join([
        "// Generated by tools/layouts/gen_layouts.py: do not edit by hand.",
        "package io.github.sillypuppyy.dogboard.ime",
        "",
        "/** A letter layout: its id (the keyboard3 file), language, card name, Settings label, letter rows as drawn. */",
        "data class LayoutInfo(",
        "    val id: String, val language: String, val languageName: String, val name: String, val label: String,",
        "    val rows: List<String>, val default: Boolean, val note: String?,",
        ")",
        "",
        "object GeneratedLayouts {",
        "    val all: List<LayoutInfo> = listOf(",
        *lines,
        "    )",
        "}",
        "",
    ])


if __name__ == "__main__":
    KOTLIN_OUT.write_text(kotlin_list(), encoding="utf-8")
    print(f"wrote {KOTLIN_OUT}")
    for lay in LAYOUTS:
        out = OUT_DIR / f"{lay.id}.xml"
        out.write_text(build(lay), encoding="utf-8")
        print(f"wrote {out}")

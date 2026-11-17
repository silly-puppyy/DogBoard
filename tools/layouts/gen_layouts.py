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
from dataclasses import dataclass, field
from pathlib import Path

OUT_DIR = Path("app/src/main/assets/extensions/org.florisboard.k3.foundation/keyboard")
FLORIS_LAYOUTS = Path("app/src/main/assets/ime/keyboard/org.florisboard.layouts/layouts/characters")

UNIT = 37.5  # dp per keyboard3 width unit on the reference keyboard


def w(dp: float) -> str:
    return f"{dp / UNIT:.4f}".rstrip("0").rstrip(".")


SHIFT = "1.5"  # Gboard: shift and delete take what the 7 letters leave, no gaps (user 2026-11-17)
# the bottom row on row 3's columns (user 2026-11-17, after row 3 took Gboard's widths): 123 / ABC
# as wide as shift, the emoji key one letter, return as wide as delete, space the rest
MODE = "1.5"         # 123 / ABC
FN = "1"             # emoji / language key
SPACE = "6"
RETURN = "1.5"
# Email and URL bottom rows: iOS arrangement and widths (email from the 320 pt layout stretched to
# 375, URL from the 375 pt layout); TLD and currency long-press sets as in iOS
EMAIL_KEY = "1.25"   # @ and . (email), / and . (URL)
SPACE_EMAIL = "3.5"  # 10 - 1.5 - 1 - 2 x 1.25 - 1.5
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


ENGLISH = Layout("qwerty-en", "en", "English (QWERTY)", "QWERTY", "EN",
                 [list("qwertyuiop"), list("asdfghjkl"), list("zxcvbnm")])


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


LAYOUTS = [
    ENGLISH,
    floris_layout("qwertz-de", "de", "Deutsch (QWERTZ)", "QWERTZ", "DE", "german"),
    floris_layout("azerty-fr", "fr", "Français (AZERTY)", "AZERTY", "FR", "azerty"),
]

SYMBOL_1 = [
    ["s-1", "s-2", "s-3", "s-4", "s-5", "s-6", "s-7", "s-8", "s-9", "s-0"],
    ["s-hyphen", "s-slash", "s-colon", "s-semicolon", "s-lparen", "s-rparen", "s-dollar", "s-amp", "s-at", "s-dquote"],
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
            lines += key_with_alts(key_id(case_c), case_c, lay.accents.get(case_c, ""), letter_width(lay))
    return lines


def letter_width(lay: Layout) -> str | None:
    """Letter key width when the widest of the first two rows has more than ten keys, else None (1 unit)."""
    n = max(len(lay.rows[0]), len(lay.rows[1]))
    # rounded down: a row a hair over 10 units loses its last key in the touch layout (ü ö ä did)
    return None if n <= 10 else f"{int(10 / n * 10000) / 10000:.4f}".rstrip("0").rstrip(".")


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
        side = fmt((10 - len(lay.rows[2]) * w) / 2)
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
    # the action key as a word (Settings > Layout > Action key: Word): the iOS widths, return wide enough
    # for "Search" / "Send"; 123 1.24, emoji 1.25, space 5.01, return 2.49 (email / URL: space 2.51)
    "word": "to-symbol-1-word fn-key-word space-word enter-word",
    "word-email": "to-symbol-1-word fn-key-word space-word-email at-email period-email enter-word",
    "word-url": "to-symbol-1-word fn-key-word space-word-email slash-url period-url enter-word",
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
        f'<key id="to-symbol-1-word" layerId="symbol-1" width="{w(46.5)}" />',
        f'<key id="fn-key-word" gap="true" width="{w(47)}" />',
        f'<key id="space-word" output="\\u{{0020}}" width="{w(188)}" />',
        f'<key id="space-word-email" output="\\u{{0020}}" width="{w(375 - 46.5 - 47 - 2 * 46.875 - 93.5)}" />',
        f'<key id="enter-word" output="@k3:action/enter" width="{w(93.5)}" longPressKeyIds="newline" />',
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
        '        <display keyId="to-symbol-1-word" display="123" />',
        '        <display keyId="space-word" display="space" />',
        '        <display keyId="space-word-email" display="space" />',
        '        <display keyId="to-symbol-2" display="#+=" />',
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


if __name__ == "__main__":
    for lay in LAYOUTS:
        out = OUT_DIR / f"{lay.id}.xml"
        out.write_text(build(lay), encoding="utf-8")
        print(f"wrote {out}")

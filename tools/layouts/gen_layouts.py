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
import re
import unicodedata
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
    # the language's own digits and punctuation on the symbol and bottom-row keys (native_punct: Arabic ، ؛ ؟ ١٢٣),
    # key id -> (output, long-press alternatives); empty for languages that use the Latin ones
    symbols: dict[str, tuple[str, str]] = field(default_factory=dict)


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


def indic_layout(id: str, locale: str, name: str, layout: str, indicator: str, tag: str, variant: str, label: str) -> Layout:
    """A Gboard Indic layout: every key of its rows (the virama, anusvara and nukta keys, punctuation keys too) and its
    long-press lists (comma-separated in the extracted data; digits and conjuncts like त्र included). A vowel key after a
    consonant types its sign (DevanagariComposer, for every Indic script); no capitals."""
    langs = json.loads((GBOARD_DATA / "languages.json").read_text(encoding="utf-8"))
    lays = json.loads((GBOARD_DATA / "layouts.json").read_text(encoding="utf-8"))
    data = lays[next(v for v in langs[tag]["variants"] if v["variant"] == variant)["layout_id"]]
    rows = [[k for k in r if isinstance(k, str) and len(k) == 1] for r in data["rows"] if r]
    accents = {k: " ".join(x for x in v.split(",") if x) for k, v in data.get("longpress", {}).items()}
    return no_capitals(Layout(id, locale, name, layout, indicator, [r for r in rows if r], accents=accents, label=label))


def hindi_compact() -> Layout:
    """Gboard's hindi_compact (its default Hindi layout)."""
    return indic_layout("compact-hi", "hi", "हिन्दी (कॉम्पैक्ट)", "कॉम्पैक्ट", "HI", "hi", "hindi_compact", "Compact")


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
    # Korean 2-set (두벌식, Gboard's korean_two_bulsik and iOS's): jamo keys, composed into syllables as typed
    # (HangulComposer); shift gives the tense consonants and ㅒ ㅖ
    replace(Layout("dubeolsik-ko", "ko", "한국어 (두벌식)", "두벌식", "KO",
                   [list("ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔ"), list("ㅁㄴㅇㄹㅎㅗㅓㅏㅣ"), list("ㅋㅌㅊㅍㅠㅜㅡ")],
                   uppers={**{c: c for c in "ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔㅁㄴㅇㄹㅎㅗㅓㅏㅣㅋㅌㅊㅍㅠㅜㅡ"},
                           "ㅂ": "ㅃ", "ㅈ": "ㅉ", "ㄷ": "ㄸ", "ㄱ": "ㄲ", "ㅅ": "ㅆ", "ㅐ": "ㅒ", "ㅔ": "ㅖ"}),
            label="두벌식"),
    # Hindi compact (Gboard's default Hindi layout): its rows and long-presses as extracted (aspirates, vowel signs,
    # digits on long-press); a vowel key after a consonant types its sign (DevanagariComposer: Gboard's conditional
    # mappings, not in the extracted data); no capitals
    no_capitals(hindi_compact()),
    # other Indic languages: Gboard's layouts (all letters on the keys, vowel signs by DevanagariComposer)
    indic_layout("marathi-mr", "mr", "मराठी", "मराठी", "MR", "mr-IN", "marathi_india", "मराठी"),
    indic_layout("bengali-bn", "bn", "বাংলা", "বাংলা", "BN", "bn-IN", "bengali_india", "বাংলা"),
    indic_layout("tamil-ta", "ta", "தமிழ்", "தமிழ்", "TA", "ta-IN", "tamil", "தமிழ்"),
    indic_layout("telugu-te", "te", "తెలుగు", "తెలుగు", "TE", "te-IN", "telugu_india", "తెలుగు"),
    indic_layout("gujarati-gu", "gu", "ગુજરાતી", "ગુજરાતી", "GU", "gu", "gujarati", "ગુજરાતી"),
    indic_layout("kannada-kn", "kn", "ಕನ್ನಡ", "ಕನ್ನಡ", "KN", "kn-IN", "kannada_india", "ಕನ್ನಡ"),
    indic_layout("malayalam-ml", "ml", "മലയാളം", "മലയാളം", "ML", "ml-IN", "malayalam_india", "മലയാളം"),
    indic_layout("gurmukhi-pa", "pa", "ਪੰਜਾਬੀ", "ਗੁਰਮੁਖੀ", "PA", "pa-XF", "punjabi_gurmukhi", "ਗੁਰਮੁਖੀ"),
    indic_layout("odia-or", "or", "ଓଡ଼ିଆ", "ଓଡ଼ିଆ", "OR", "or", "odia", "ଓଡ଼ିଆ"),
    indic_layout("nepali-ne", "ne", "नेपाली", "नेपाली", "NE", "ne-NP", "nepali_nepal_dynamic", "नेपाली"),
    indic_layout("assamese-as", "as", "অসমীয়া", "অসমীয়া", "AS", "as", "assamese", "অসমীয়া"),
    # Khmer: Gboard's native layout read the same way (every key and long-press); typed in logical order, subscripts by
    # the coeng key, so no composer. Thai and Lao wait for their shift planes (not in the extracted data: half their
    # letters), Burmese and Sinhala for their own composition
    indic_layout("khmer-km", "km", "ខ្មែរ", "ខ្មែរ", "KM", "km-KH", "khmer_cambodia_native", "ខ្មែរ"),
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
            only_row3 = w3 is not None and c in lay.rows[-1] and all(c not in r for r in lay.rows[:-1])
            width = (f"{w3:.4f}".rstrip("0").rstrip(".") if only_row3 else letter_width(lay))
            lines += key_with_alts(key_id(case_c), case_c, lay.accents.get(case_c, ""), width)
    return lines


def letter_width(lay: Layout) -> str | None:
    """Letter key width when the widest row above the shift row has more than ten keys, else None (1 unit)."""
    n = max(len(r) for r in lay.rows[:-1])
    # rounded down: a row a hair over 10 units loses its last key in the touch layout (ü ö ä did)
    return None if n <= 10 else f"{int(10 / n * 10000) / 10000:.4f}".rstrip("0").rstrip(".")


MIN_SHIFT = 1.0  # the narrowest shift / delete beside a long third row (0.5 cut their icons, Dvorak's 9 letters)


def row3_letter_width(lay: Layout) -> float | None:
    """Third-row letters narrower than the others when shift and delete would get under [MIN_SHIFT], else None."""
    w = float(letter_width(lay) or 1)
    n = len(lay.rows[-1])
    if (10 - n * w) / 2 >= MIN_SHIFT - 0.01:
        return None
    return int((10 - 2 * MIN_SHIFT) / n * 10000) / 10000


def row3(lay: Layout, shift_id: str, shifted: bool) -> str:
    """The last letter row (row 3, or 4 for Pashto / Amharic): shift, a gap, the letters, a gap, delete; narrower shift /
    delete when the letters need it."""
    letters = " ".join(key_id(upper(c, lay) if shifted else c) for c in lay.rows[-1])
    w = float(letter_width(lay) or 1)
    rest = 10 - len(lay.rows[-1]) * w - 2 * float(SHIFT)
    if rest < 0.01 and rest > -0.01:
        return f"{shift_id} {letters} bksp"
    if rest >= 0:
        # its own name: a layout with more rows (Bengali, five) has a centred row 3 with a gap3 of its own
        return f"{shift_id} gaplast-{lay.id} {letters} gaplast-{lay.id} bksp"
    return f"{shift_id}-narrow-{lay.id} {letters} bksp-narrow-{lay.id}"


def row_keys(lay: Layout, i: int, shifted: bool) -> str:
    """The rows above the shift row: the letters, centred with half-gaps when the row is shorter than the widest."""
    w = float(letter_width(lay) or 1)
    keys = " ".join(key_id(upper(c, lay) if shifted else c) for c in lay.rows[i])
    side = (10 - len(lay.rows[i]) * w) / 2
    if lay is ENGLISH or side < 0.01:
        return keys
    return f"gap{i + 1}-{lay.id} {keys} gap{i + 1}-{lay.id}"


def layout_gaps(lay: Layout) -> list[str]:
    """The per-layout gap and narrow shift / delete keys the letter rows use."""
    if lay is ENGLISH:
        return []
    w = float(letter_width(lay) or 1)
    fmt = lambda x: f"{int(x * 10000) / 10000:.4f}".rstrip("0").rstrip(".")
    out = []
    for i in range(len(lay.rows) - 1):
        side = (10 - len(lay.rows[i]) * w) / 2
        if side >= 0.01:
            out.append(f'<key id="gap{i + 1}-{lay.id}" gap="true" width="{fmt(side)}" />')
    rest = 10 - len(lay.rows[-1]) * w - 2 * float(SHIFT)
    if 0.01 <= rest:
        out.append(f'<key id="gaplast-{lay.id}" gap="true" width="{fmt(rest / 2)}" />')
    elif rest > -0.01:
        pass
    else:
        side = fmt((10 - len(lay.rows[-1]) * (row3_letter_width(lay) or w)) / 2)
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
        *key_with_alts("comma-classic", *lay.symbols.get("comma-classic", (",", "")), "1"),
        '<key id="at-classic" output="@" width="1" />',
        '<key id="slash-classic" output="/" width="1" />',
        '<key id="fn-key-classic" gap="true" width="1" />',
        '<key id="space-classic" output="\\u{0020}" width="4" />',
        *key_with_alts("period-classic", *lay.symbols.get("period-classic", (".", "")), "1"),
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
        *[line for key_id, (out, alts) in SYMBOLS.items() for line in key_with_alts(key_id, *lay.symbols.get(key_id, (out, alts)))],
        *[line for key_id, (out, alts) in WIDE_PUNCT.items()
          for line in key_with_alts(key_id, *lay.symbols.get(key_id, (out, alts)), PUNCT_WIDE)],
        *[line for key_id, (out, alts) in WIDE_PUNCT.items() if key_id in lay.symbols
          for line in key_with_alts(key_id + "-latin", out, alts, PUNCT_WIDE)],
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
    # iOS's second symbol page has the Latin . , ? where the first has the language's own (Arabic ، ؟): Latin copies
    wide2 = " ".join(k + "-latin" if k in lay.symbols else k for k in WIDE_PUNCT)
    above = range(len(lay.rows) - 1)
    base_rows = [row_keys(lay, i, False) for i in above] + [row3(lay, "base-to-shift", False)]
    shift_rows = [row_keys(lay, i, True) for i in above] + [row3(lay, "shift-to-base", True)]
    layers = [
        ("base", base_rows + [bottom_row("to-symbol-1")]),
        ("shift", shift_rows + [bottom_row("to-symbol-1")]),
        ("symbol-1", [" ".join(SYMBOL_1[0]), " ".join(SYMBOL_1[1]),
                      f"to-symbol-2 {wide} bksp", bottom_row("to-base")]),
        ("symbol-2", [" ".join(SYMBOL_2[0]), " ".join(SYMBOL_2[1]),
                      f"to-symbol-1-row3 {wide2} bksp", bottom_row("to-base")]),
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


IOS_DATA = Path("../notes/design/data/layouts/ios")
# CLDR's English names of languages and territories (Unicode licence; keyboard-data/cldr, fetched by auto_pack.py)
CLDR_EN = Path("/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data/cldr/main/en.xml")
# languages with a word pack in the app (the others type without suggestions until they get one)
PACKS = {"en", "es", "fr", "fi", "ru"}
NAMED = {"qwerty": "QWERTY", "qwertz": "QWERTZ", "azerty": "AZERTY", "dvorak": "Dvorak", "colemak": "Colemak", "pc": "PC",
         "two": "2"}
# scripts whose keyboards compose syllables (a consonant + vowel key types a vowel sign; Hangul jamo): typed as plain
# keys they'd write wrong text, so they wait for their own pass (iOS's Tamil grid has no vowel-sign keys: it composes)
COMPOSED = {"DEVANAGARI", "BENGALI", "GURMUKHI", "GUJARATI", "ORIYA", "TAMIL", "TELUGU", "KANNADA", "MALAYALAM",
            "SINHALA", "HANGUL", "MYANMAR", "KHMER", "TIBETAN", "LAO", "THAI", "CJK", "BOPOMOFO", "HIRAGANA", "KATAKANA"}
# languages typed through an input method that converts to characters (Pinyin, Zhuyin, kana, Hangul): every
# keyboard of theirs needs that conversion, so none is ported yet
INPUT_METHOD_LANGUAGES = {"zh", "ja", "ko", "yue", "wuu", "nan", "hak", "gan", "cmn"}
# languages written in Latin and another script (Serbian Cyrillic / Latin...): their Latin layouts are real ones
DIGRAPHIC = {"sr", "bs", "uz", "az", "ku", "ms", "tk", "kk"}
# Gboard's registry still uses the old ISO codes for some languages; CLDR names the current ones
OLD_CODES = {"iw": "he", "in": "id", "ji": "yi", "jw": "jv", "mo": "ro"}


def cldr_names() -> tuple[dict[str, str], set[str]]:
    text = CLDR_EN.read_text(encoding="utf-8")
    langs = dict(re.findall(r'<language type="([a-zA-Z_]+)">([^<]+)</language>', text))
    territories = {w.lower() for t in re.findall(r'<territory type="[A-Z0-9]+">([^<]+)</territory>', text) for w in t.split()}
    # and their codes ("urdu_in": India)
    territories |= {c.lower() for c in re.findall(r'<territory type="([A-Z]{2})">', text)}
    return langs, territories


def plain_rows(rows) -> list[list[str]] | None:
    """The letter rows of a layout whose keys are all plain characters (no vowel signs, no named iOS keys), else None."""
    keys = [k for r in rows or [] for k in (r or []) if isinstance(k, str)]
    if not keys or any(k.startswith("<") for k in keys) or any(unicodedata.category(c) in ("Mn", "Mc") for k in keys for c in k):
        return None
    out = [[k for k in r if isinstance(k, str) and len(k) == 1 and k.isalpha() and not ("\ufb50" <= k <= "\ufeff")]
           for r in rows if r]
    out = [r for r in out if r]
    if script_of(out) in COMPOSED:
        return None
    if len(out) not in (3, 4):  # four letter rows: Pashto, Amharic, Tigrinya (the last carries shift and delete)
        return None
    letters = [c for r in out for c in r]
    # a letter twice (extraction leftovers: Hebrew ו, Estonian õ), a row wider than 12 keys (Bulgarian BDS) or letters
    # beyond the BMP (Hanifi Rohingya: key ids are one UTF-16 unit): skipped, AllLayoutsTest failed them (2026-11-22)
    if len(set(letters)) != len(letters) or max(len(r) for r in out) > 12 or any(ord(c) > 0xFFFF for c in letters):
        SKIPPED.append("".join(out[0]))
        return None
    return out


def layout_name(variant: str, language: str, territories: set[str], rows: list[list[str]], default: str | None, code: str = "",
                iphone: bool = False) -> str:
    """'qwertz' -> QWERTZ; 'armenian_armenia_phonetic' -> Phonetic (language and country dropped); 'arabic_two' -> the
    language's first layout's name + 2. Nothing left: the language's first layout's name (an iPhone variant then reads
    "ЙЦУКЕН (iPhone)"), or for that first layout the first six letters where the script has capitals (ЙЦУКЕН, as
    layouts are named), else Standard."""
    drop = {w.lower() for w in language.split()} | territories | {"iphone", code}
    words = [NAMED.get(w, w.capitalize()) for w in re.split(r"[_\-\s]+", variant.lower()) if w and w not in drop]
    if words == ["2"] and default:
        return f"{default} 2"
    if words:
        return " ".join(words)
    if default and iphone:
        return default
    first = "".join(rows[0][:6])
    named_by_letters = all(unicodedata.name(c, "").split()[0] in ("LATIN", "CYRILLIC") and c.upper() != c for c in first)
    return first.upper() if named_by_letters else "Standard"


def script_of(rows) -> str | None:
    """The script most of a layout's letters are in (LATIN, DEVANAGARI...), vowel-sign layouts included."""
    count: dict[str, int] = {}
    for r in rows or []:
        for k in r or []:
            if isinstance(k, str) and k[:1].isalpha():
                w = unicodedata.name(k[0], "?").split()[0]
                count[w] = count.get(w, 0) + 1
    return max(count, key=count.get) if count else None


# what each punctuation role may be (iOS's page is read by position: Armenian's semicolon slot holds @, Gurmukhi
# pages put । where the period is): a value outside its role's set is not used
PUNCT_ROLES = {"semicolon": {"؛", "·"}, "comma": {"،", "՝"}, "question": {"؟", ";", "՞"}, "period": {"۔", "։"}}


def native_punct(lang: str) -> tuple[str | None, dict[str, tuple[str, str]]]:
    """The language's digits and punctuation as iOS's first number page has them (notes/design/data/layouts/ios,
    verified 2026-11-22: Arabic ١٢٣ ، ؛ ؟, Persian / Urdu / Pashto ۱۲۳, Urdu's full stop ۔, Uyghur 123 with ، ؛ ؟,
    Hebrew Latin), where they differ from the Latin ones: the native form on the key, the Latin one on long-press."""
    modes = json.loads((IOS_DATA / "input_modes.json").read_text(encoding="utf-8"))
    lays = json.loads((IOS_DATA / "layouts.json").read_text(encoding="utf-8"))
    for tag, m in modes.items():
        if re.split(r"[-_]", tag)[0].lower() != lang:
            continue
        name = m.get("default") or (m.get("layouts") or [None])[0]
        planes = lays.get(name, {}).get("planes", {})
        page = next((pl[0]["rows"] for n, pl in planes.items() if n.endswith("Numbers-And-Punctuation") and pl), None)
        if not page or len(page) < 3 or len(page[0]) != 10 or len(page[1]) < 4 or len(page[2]) < 3:
            return None, {}
        out: dict[str, tuple[str, str]] = {}
        western = "1234567890"
        # Arabic-Indic digits only (another script's digits belong to its own layouts: Adlam, Gurmukhi)
        if "".join(page[0]) != western and all(unicodedata.name(c, "").startswith(("ARABIC-INDIC", "EXTENDED ARABIC-INDIC")) for c in page[0]):
            for d, native in zip(western, page[0]):
                alts = (d + " " + SYMBOLS[f"s-{d}"][1]).strip()
                out[f"s-{d}"] = (native, alts)
        period, comma, question = page[2][0], page[2][1], page[2][2]
        semicolon = page[1][3]
        if semicolon in PUNCT_ROLES["semicolon"]:
            out["s-semicolon"] = (semicolon, ";")
        if comma in PUNCT_ROLES["comma"]:
            out["comma-classic"] = out["comma-wide"] = (comma, ",")
        if question in PUNCT_ROLES["question"]:
            out["s-question"] = out["question-wide"] = (question, "? ¿")
        if period in PUNCT_ROLES["period"]:
            out["period-classic"] = out["period-wide"] = (period, ". …")
        # the script of the iOS layout this page belongs to: only layouts in that script take it (Uzbek's iOS default is
        # Arabic-script; its Cyrillic and Latin layouts keep the Latin marks)
        return script_of(lays.get(name, {}).get("rows")), out
    return None, {}


def long_press(lp: dict[str, str]) -> dict[str, str]:
    """Gboard's long-press lists ("ё,е́") as key alternatives, capitals too where a letter has one capital."""
    out = {}
    for k, v in (lp or {}).items():
        alts = [a for a in v.split(",") if a]
        if not alts:
            continue
        out[k] = " ".join(alts)
        if len(k.upper()) == 1 and k.upper() != k and all(len(a.upper()) == len(a) for a in alts):
            out[k.upper()] = " ".join(a.upper() for a in alts)
    return out


def all_layouts() -> list[Layout]:
    """Every plain three-row layout of every language Gboard covers (one per distinct keyboard: a grid shared by many
    languages is one file), and iOS's where its letters differ from all of Gboard's for that language, named
    '... (iPhone)' (user 2026-11-22). Languages LAYOUTS defines by hand keep those; their other variants are added."""
    langs, territories = cldr_names()
    g_langs = json.loads((GBOARD_DATA / "languages.json").read_text(encoding="utf-8"))
    g_lays = json.loads((GBOARD_DATA / "layouts.json").read_text(encoding="utf-8"))
    i_modes = json.loads((IOS_DATA / "input_modes.json").read_text(encoding="utf-8"))
    i_lays = json.loads((IOS_DATA / "layouts.json").read_text(encoding="utf-8"))
    # one tag per language: the bare code if Gboard has it, else the first
    # one tag per language: the bare code ("el"), else its first regular region ("el-GR"; Gboard's X regions are
    # private variants: "el-XT" had lost Greek its layout)
    tags: dict[str, str] = {}
    for tag in sorted(g_langs, key=lambda t: (len(t.split("-")) > 1, any(p[:1] == "X" for p in t.split("-")[1:]), t)):
        code = tag.split("-")[0].lower()
        tags.setdefault(OLD_CODES.get(code, code), tag)
    letters_only = lambda rows: [[c for c in r if c.isalpha()] for r in rows]
    have = {}  # language -> letter grids it already has (letters only: punctuation keys don't make a new layout)
    first_name: dict[str, str] = {}  # language -> its first layout's name
    named: dict[tuple[str, str], list[list[str]]] = {}  # (language, name) -> that layout's rows
    for lay in LAYOUTS:
        named[(lay.locale, lay.layout)] = lay.rows
        have.setdefault(lay.locale, []).append(letters_only(lay.rows))
        first_name.setdefault(lay.locale, lay.layout)
    files: dict[str, str] = {}  # keyboard content -> file id
    out: list[Layout] = []
    ids = {lay.id for lay in LAYOUTS}

    def add(base: str, language: str, name: str, rows: list[list[str]], accents: dict[str, str], hint: str):
        if letters_only(rows) in have.get(base, []):
            return
        have.setdefault(base, []).append(letters_only(rows))
        # a second layout of the same name: its own letters in brackets ("QWERTY (ñ)"), or "basic" when it only lacks some
        other = named.get((base, name))
        if other is not None:
            extra = sorted({c for r in rows for c in r if c.isalpha()} - {c for r in other for c in r})
            name = f"{name} ({' '.join(extra)})" if extra else f"{name} (basic)"
            n, stem = 2, name
            while (base, name) in named:
                name = f"{stem} {n}"; n += 1
        named.setdefault((base, name), rows)
        first_name.setdefault(base, name)
        # Turkish / Azerbaijani casing (Shift+i = İ) is part of a keyboard's identity: a shared QWERTY file can't have it
        key = json.dumps([rows, accents, base in ("tr", "az")], ensure_ascii=False, sort_keys=True)
        fid = files.get(key)
        if fid is None:
            fid = re.sub(r"[^a-z0-9]+", "-", hint.lower()).strip("-")[:32] or "layout"
            fid = f"{fid}-{base}"
            n = 2
            while fid in ids:
                fid = f"{fid.rsplit('-', 1)[0]}-{base}{n}"; n += 1
            ids.add(fid)
            files[key] = fid
        lay = Layout(fid, base, f"{language} ({name})", name, base.upper()[:3], rows, accents=accents,
                     note="taps" if base in PACKS else "nodict")
        if base == "ka":
            lay = no_capitals(lay)
            # the phonetic layout's Shift plane: iOS's Georgian-Phonetic Capital-Letters (ტ -> თ, წ -> ჭ, რ -> ღ, ს -> შ,
            # ჯ -> ჟ, ზ -> ძ, ც -> ჩ; Gboard's data has them only on long-press, kept too), user 2026-11-23 "implement
            # both layouts". Auto-shift is off for Georgian (FlorisLocale.supportsCapitalization), so only a tap on Shift
            ph = i_lays.get("Georgian-Phonetic", {})
            caps = next((pl["rows"] for pl in ph.get("planes", {}).get("Capital-Letters", []) if pl.get("keylayout") == "iPhone-Letters-Keylayout"), None)
            if caps and letters_only(rows) == letters_only(ph["rows"]):
                for small, big in zip((c for r in ph["rows"] for c in r), (c for r in caps for c in r)):
                    if big != small: lay.uppers[small] = big
        # Turkic dotted / dotless i (Turkish, Azerbaijani, Kirmanjki...): i -> İ, ı -> I (str.upper gave I for both)
        letters = {c for r in rows for c in r}
        # (Turkish and Azerbaijani always: Gboard's Turkish QWERTY has ı on long-press, so no ı key, and Shift+i
        # must still be İ, 2026-11-23)
        if "i" in letters and ("ı" in letters or base in ("tr", "az")):
            lay.uppers.setdefault("i", "İ")
            lay.uppers.setdefault("ı", "I")
        # a letter with no capital of its own (Greek final ς: its capital Σ is σ's) stays itself on shift, or the
        # shifted layer showed Σ twice
        for c in (c for r in rows for c in r):
            if len(c.upper()) == 1 and c.upper() != c and c.upper().lower() != c:
                lay.uppers.setdefault(c, c)
        out.append(lay)

    for base, tag in sorted(tags.items()):
        language = langs.get(base)
        if not language or base in INPUT_METHOD_LANGUAGES:
            continue
        d = g_langs[tag]
        variants = sorted(d["variants"], key=lambda v: v["variant"] != d["default_variant"])
        # the language's script, from its own named layouts (not the generic QWERTY family): a non-Latin language's
        # Latin layouts are transliteration keyboards on Gboard and iOS (Hindi QWERTY types Devanagari there); without
        # transliteration they'd type Latin, so they are left out
        own = {script_of(g_lays.get(v["layout_id"], {}).get("rows")) for v in d["variants"] if v["variant"] not in NAMED}
        own.discard(None)
        latin_ok = base in DIGRAPHIC or not own or "LATIN" in own
        g_lp = {}
        for v in variants:
            L = g_lays.get(v["layout_id"])
            rows = plain_rows(L.get("rows")) if L else None
            if rows is None:
                continue
            if not latin_ok and script_of(rows) == "LATIN":
                continue
            lp = long_press(L.get("longpress"))
            g_lp = g_lp or lp
            add(base, language, layout_name(v["variant"], language, territories, rows, first_name.get(base), base), rows, lp, v["variant"])
        for itag, m in i_modes.items():
            # iOS tags use underscores (he_IL): split on both, or every region-tagged iOS language was missed
            icode = re.split(r"[-_]", itag)[0].lower()
            if OLD_CODES.get(icode, icode) != base:
                continue
            for n in m.get("layouts", []):
                rows = plain_rows(i_lays.get(n, {}).get("rows"))
                if rows is None:
                    continue
                if not latin_ok and script_of(rows) == "LATIN":
                    continue
                rows = [[c.lower() if len(c.lower()) == 1 else c for c in r] for r in rows]
                # an iPhone layout named like Gboard's: "(iPhone)" tells them apart (Gboard's name alone when it has none)
                name = layout_name(n, language, territories, rows, first_name.get(base), base, iphone=True)
                add(base, language, name + " (iPhone)" if base in first_name else name, rows, g_lp, n + "-iphone")
    return out


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
    names = {**cldr_names()[0], **LANGUAGE_NAMES}
    for lay in LAYOUTS + GENERATED:
        rows = ", ".join(q("".join(r)) for r in lay.rows)
        lines.append(f"        LayoutInfo({q(lay.id)}, {q(lay.locale)}, {q(names[lay.locale])}, {q(lay.layout)}, "
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
        # in parts: one listOf of 1469 entries is over the JVM's 64 KB method size
        f"    val all: List<LayoutInfo> = {' + '.join(f'part{i}()' for i in range(0, len(lines), 150))}",
        *[part for i in range(0, len(lines), 150) for part in
          ["", f"    private fun part{i}() = listOf(", *lines[i:i + 150], "    )"]],
        "}",
        "",
    ])


GENERATED: list[Layout] = []
SKIPPED: list[str] = []

if __name__ == "__main__":
    GENERATED = all_layouts()
    punct: dict[str, dict] = {}
    for lay in LAYOUTS + GENERATED:
        page_script, marks = punct.setdefault(lay.locale, native_punct(lay.locale))
        if not lay.symbols and page_script == script_of(lay.rows) != "LATIN":
            lay.symbols = marks
    KOTLIN_OUT.write_text(kotlin_list(), encoding="utf-8")
    print(f"wrote {KOTLIN_OUT}; skipped {len(SKIPPED)} layouts with a repeated letter, a row over 12 or non-BMP letters")
    written = set()
    for lay in LAYOUTS + GENERATED:
        if lay.id in written:
            continue
        written.add(lay.id)
        out = OUT_DIR / f"{lay.id}.xml"
        out.write_text(build(lay), encoding="utf-8")
        print(f"wrote {out}")

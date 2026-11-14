#!/usr/bin/env python3
"""Generate DogBoard's English QWERTY layout (keyboard3 / CLDR Keyboard 3.0 XML).

Key widths are in keyboard3 units (a row is 10 units wide). They follow a 375 dp reference
keyboard where a letter cell is 37.5 dp (1 unit):

    row 3:  shift 48 | gap 8 | 7 letters | gap 8.5 | delete 48
    row 4:  123 46.5 | fn 47 | space 188 | return 93.5

Run from the repository root:
    python3 tools/layouts/gen_qwerty_en.py
"""
from pathlib import Path

OUT = Path("app/src/main/assets/extensions/org.florisboard.k3.foundation/keyboard/qwerty-en.xml")

UNIT = 37.5  # dp per keyboard3 width unit on the reference keyboard


def w(dp: float) -> str:
    return f"{dp / UNIT:.4f}".rstrip("0").rstrip(".")


SHIFT = w(48)
GAP_LEFT = w(8)
GAP_RIGHT = w(8.5)
MODE = w(46.5)       # 123 / ABC
FN = w(47)           # emoji / language key
SPACE = w(188)       # space absorbs the 37.5 dp a dictation key would take
RETURN = w(93.5)
# Email and URL bottom rows: iOS arrangement and widths (email from the 320 pt layout stretched to
# 375, URL from the 375 pt layout); TLD and currency long-press sets as in iOS
EMAIL_KEY = w(40 * 375 / 320)  # 320 pt email row (40 pt keys) stretched to 375
SPACE_EMAIL = w(375 - 46.5 - 47 - 2 * 40 * 375 / 320 - 93.5)  # space + the merged dictation slot
PERIOD_URL = w(63)
SLASH_URL = w(62)
DOTCOM = w(63)
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

ROWS = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]

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


def letter_keys() -> list[str]:
    lines = []
    for letter in "".join(ROWS):
        for case_letter in (letter, letter.upper()):
            lines += key_with_alts(case_letter, case_letter, ACCENTS.get(case_letter, ""))
    return lines


def bottom_row(mode_key: str) -> str:
    return f"{mode_key} fn-key space enter"


# Field-specific bottom rows; the app swaps a "<layer>-<variant>" layer in for base and shift.
FIELD_ROWS = {
    "email": "to-symbol-1 fn-key space-email at-email period-email enter",
    "url": "to-symbol-1 fn-key period-url slash-url dotcom enter",
}


def build() -> str:
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
        f'<key id="at-email" output="@" width="{EMAIL_KEY}" />',
        f'<key id="period-email" output="." width="{EMAIL_KEY}" />',
        f'<key id="period-url" output="." width="{PERIOD_URL}" />',
        f'<key id="slash-url" output="/" width="{SLASH_URL}" />',
        f'<key id="dotcom" output=".com" width="{DOTCOM}" longPressKeyIds="{" ".join("tld-" + t.strip(".").replace(".", "-") for t in TLDS.split())}" />',
        *[f'<key id="tld-{t.strip(".").replace(".", "-")}" output="{t}" />' for t in TLDS.split()],
        f'<key id="fn-key" gap="true" width="{FN}" />',
        f'<key id="gap-left" gap="true" width="{GAP_LEFT}" />',
        f'<key id="gap-right" gap="true" width="{GAP_RIGHT}" />',
        "",
        "<!-- Digits and extra symbols -->",
        *[line for key_id, (out, alts) in SYMBOLS.items() for line in key_with_alts(key_id, out, alts)],
        *[line for key_id, (out, alts) in WIDE_PUNCT.items()
          for line in key_with_alts(key_id, out, alts, PUNCT_WIDE)],
        "",
        "<!-- Letters -->",
        *letter_keys(),
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
    letters = [" ".join(row) for row in ROWS]
    layers = [
        ("base", [letters[0], letters[1], f"base-to-shift gap-left {letters[2]} gap-right bksp", bottom_row("to-symbol-1")]),
        ("shift", [letters[0].upper(), letters[1].upper(),
                   f"shift-to-base gap-left {letters[2].upper()} gap-right bksp", bottom_row("to-symbol-1")]),
        ("symbol-1", [" ".join(SYMBOL_1[0]), " ".join(SYMBOL_1[1]),
                      f"to-symbol-2 gap-left {wide} gap-right bksp", bottom_row("to-base")]),
        ("symbol-2", [" ".join(SYMBOL_2[0]), " ".join(SYMBOL_2[1]),
                      f"to-symbol-1-row3 gap-left {wide} gap-right bksp", bottom_row("to-base")]),
        *[(f"{layer}-{variant}", rows[:3] + [row]) for variant, row in FIELD_ROWS.items()
          for layer, rows in (
              ("base", [letters[0], letters[1], f"base-to-shift gap-left {letters[2]} gap-right bksp", ""]),
              ("shift", [letters[0].upper(), letters[1].upper(),
                         f"shift-to-base gap-left {letters[2].upper()} gap-right bksp", ""]),
          )],
        ("numeric", [
            "plus-numeric 1-numeric 2-numeric 3-numeric percent-numeric",
            "open-paren-numeric 4-numeric 5-numeric 6-numeric space-numeric",
            "close-paren-numeric 7-numeric 8-numeric 9-numeric bksp-numeric",
            "base-numeric comma-numeric symbols-numeric 0-numeric equal-numeric period-numeric enter-numeric",
        ]),
    ]

    out = [
        '<keyboard3 xmlns="https://schemas.unicode.org/cldr/45/keyboard3" conformsTo="45" locale="en">',
        '    <info author="DogBoard" name="English (QWERTY)" layout="QWERTY" indicator="EN" />',
        "    <!-- Generated by tools/layouts/gen_qwerty_en.py; edit the generator, not this file. -->",
        "",
        "    <displays>",
        '        <display keyId="base-to-shift" display="@fl:icon/shift" />',
        '        <display keyId="shift-to-base" display="@fl:icon/shift" />',
        '        <display keyId="to-symbol-1" display="123" />',
        '        <display keyId="to-symbol-1-row3" display="123" />',
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
    OUT.write_text(build(), encoding="utf-8")
    print(f"wrote {OUT}")

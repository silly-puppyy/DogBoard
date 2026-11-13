#!/usr/bin/env python3
"""Generates DogBoard's keyboard themes (Snygg stylesheets) from the design tokens.

    tools/themes/gen_dogboard_themes.py

Writes app/src/main/assets/extensions/io.github.sillypuppyy.dogboard.themes/:
extension.json and stylesheets/<theme>.json. Edit the tokens here, not the JSON.

Design source: the DogBoard design canvas (Foundations: colour roles -> keyboard surfaces; Keyboard
states; Example themes). Colours are Material You roles, so the keyboard follows the wallpaper:
    keyboard ground   surfaceContainer          (dark: surfaceContainerLow)
    letter keys       surfaceContainerLowest    (dark: surfaceContainerHighest), labels onSurface
    function keys     secondaryContainer, labels onSecondaryContainer
    return (action)   primary, label onPrimary; plain return (new line) uses the function tone
    autocorrect chip  primaryContainer, label onPrimaryContainer, 16 sp semibold
    popups            surfaceContainerLowest + elevation (dark: surfaceBright)
    hints             onSurfaceVariant, dividers outlineVariant
Shape: keys and return 10 dp, popups and panel tiles 16 dp, chips full. Type: letters 23 sp,
function words 15 sp / 500, space 14 sp. Shift: off = function tone; on (you pressed it) = letter
tone; auto-capital = function tone (filled glyph); caps lock = primary.
The base sheet is FlorisBoard's Material You day theme (Apache-2.0), so every element the keyboard
draws has a rule; DogBoard's rules replace its colour variables and all key / bar rules.
"""
import copy
import json
import os

BASE = "app/src/main/assets/extensions/org.florisboard.themes.my/stylesheets/floris_day_my.json"
OUT = "app/src/main/assets/extensions/io.github.sillypuppyy.dogboard.themes"

ENTER = "key[output=`@k3:action/enter`]"
DELETE = "key[output=`@k3:action/backspace`]"
SPACE = "key[output=` `]"
ENTER_ACTIONS = ["done", "go", "next", "previous", "search", "send"]  # enteraction values other than "return"
FN_KEY = "key[output=`@fl:action/switch_to_next_subtype`]"
MODE_KEYS = ["key[targetlayer=`symbol-1`]", "key[targetlayer=`symbol-2`]", "key[targetlayer=`numeric`]"]
SHIFT_OFF = "key[targetlayer=`shift`]"
# on the shift layer the shift key switches back to base; the shift state tells how shift was set
SHIFT_MANUAL = "key[targetlayer=`base`][shiftstate=`shifted_manual`]"
SHIFT_AUTO = "key[targetlayer=`base`][shiftstate=`shifted_automatic`]"
SHIFT_LOCK = "key[targetlayer=`base`][shiftstate=`caps_lock`]"


def dyn(scheme: str, role: str) -> str:
    return f"dynamic-{scheme}-color({role})"


def dynamic_roles(scheme: str) -> dict:
    """DogBoard (dynamic colour) roles for 'light' or 'dark'."""
    d = lambda role: dyn(scheme, role)
    dark = scheme == "dark"
    return {
        "--kb-ground": d("surfaceContainerLow" if dark else "surfaceContainer"),
        "--key": d("surfaceContainerHighest" if dark else "surfaceContainerLowest"),
        "--key-pressed": d("surfaceBright" if dark else "surfaceContainerHigh"),
        "--key-label": d("onSurface"),
        "--fn": d("secondaryContainer"),
        "--fn-pressed": d("surfaceVariant" if not dark else "surfaceContainerHighest"),
        "--fn-label": d("onSecondaryContainer"),
        "--action": d("primary"),
        "--action-pressed": d("primaryContainer" if not dark else "inversePrimary"),
        "--action-label": d("onPrimary"),
        "--chip": d("primaryContainer"),
        "--chip-label": d("onPrimaryContainer"),
        "--popup": d("surfaceBright" if dark else "surfaceContainerLowest"),
        "--popup-focus": d("primaryContainer"),
        "--hint": d("onSurfaceVariant"),
        "--divider": d("outlineVariant"),
    }


THEMES = [
    {"id": "dogboard_light", "label": "DogBoard", "isNight": False, "roles": dynamic_roles("light")},
    {"id": "dogboard_dark", "label": "DogBoard (dark)", "isNight": True, "roles": dynamic_roles("dark")},
]

SHAPE_KEY = "rounded-corner(10dp, 10dp, 10dp, 10dp)"
SHAPE_POPUP = "rounded-corner(16dp, 16dp, 16dp, 16dp)"
SHAPE_CHIP = "rounded-corner(50%, 50%, 50%, 50%)"


def stylesheet(base: dict, roles: dict) -> dict:
    sheet = copy.deepcopy(base)
    # FlorisBoard's variable names, mapped to DogBoard roles so panels and dialogs follow along
    defines = dict(sheet["@defines"])
    defines.update(roles)
    defines.update({
        "--primary": roles["--action"],
        "--primary-variant": roles["--action-pressed"],
        "--secondary": roles["--fn"],
        "--secondary-variant": roles["--fn-pressed"],
        "--background": roles["--kb-ground"],
        "--background-variant": roles["--fn"],
        "--surface": roles["--key"],
        "--surface-variant": roles["--key-pressed"],
        "--popup-surface": roles["--popup"],
        "--focused-popup-surface": roles["--popup-focus"],
        "--spacer-color": roles["--divider"],
        "--on-primary": roles["--action-label"],
        "--on-background": roles["--key-label"],
        "--on-surface": roles["--key-label"],
        "--on-surface-variant": roles["--hint"],
        "--shape": SHAPE_KEY,
        "--shape-variant": SHAPE_POPUP,
        "--shape-chip": SHAPE_CHIP,
    })
    sheet["@defines"] = defines
    # drop every key and candidate rule from the base; DogBoard's replace them
    for k in [k for k in sheet if k == "key" or k.startswith(("key:", "key[", "smartbar-candidate-word"))]:
        del sheet[k]
    fn = {"background": "var(--fn)", "foreground": "var(--fn-label)"}
    fn_pressed = {"background": "var(--fn-pressed)", "foreground": "var(--fn-label)"}
    rules = {
        "key": {"background": "var(--key)", "foreground": "var(--key-label)", "font-size": "23sp",
                "shape": "var(--shape)", "shadow-elevation": "1dp", "text-max-lines": "1"},
        "key:pressed": {"background": "var(--key-pressed)", "foreground": "var(--key-label)"},
        SPACE: {"foreground": "var(--hint)", "font-size": "14sp", "text-overflow": "ellipsis"},
        # plain return (new line) is a function key; field actions (send, search, ...) are primary
        ENTER: dict(fn, **{"font-size": "15sp", "font-weight": "600"}),
        ENTER + ":pressed": fn_pressed,
        DELETE: fn, DELETE + ":pressed": fn_pressed,
        FN_KEY: fn, FN_KEY + ":pressed": fn_pressed,
        SHIFT_OFF: fn, SHIFT_OFF + ":pressed": fn_pressed,
        SHIFT_MANUAL: {"background": "var(--key)", "foreground": "var(--key-label)"},
        SHIFT_AUTO: fn,
        SHIFT_LOCK: {"background": "var(--action)", "foreground": "var(--action-label)"},
        "key-popup-box": {"background": "var(--popup)", "foreground": "var(--key-label)", "font-size": "23sp",
                          "shape": "var(--shape-variant)", "shadow-elevation": "2dp"},
        "key-popup-element:focus": {"background": "var(--popup-focus)", "foreground": "var(--chip-label)",
                                    "shape": "var(--shape)"},
        "smartbar-candidate-word": {"background": "transparent", "foreground": "var(--key-label)", "font-size": "16sp",
                                    "margin": "4dp", "padding": "6dp 0dp", "shape": "rectangle()", "text-max-lines": "1",
                                    "text-overflow": "ellipsis"},
        "smartbar-candidate-word[auto-commit=1]": {"background": "var(--chip)", "foreground": "var(--chip-label)",
                                                   "font-weight": "600", "shape": "var(--shape-chip)"},
        "smartbar-candidate-word:pressed": {"background": "var(--key-pressed)", "foreground": "var(--key-label)"},
        "smartbar-candidate-word-secondary-text": {"font-size": "8sp", "margin": "0dp 2dp 0dp 0dp"},
    }
    for action in ENTER_ACTIONS:
        rules[f"{ENTER}[enteraction=`{action}`]"] = {"background": "var(--action)", "foreground": "var(--action-label)"}
        rules[f"{ENTER}[enteraction=`{action}`]:pressed"] = {"background": "var(--action-pressed)", "foreground": "var(--action-label)"}
    for mode in MODE_KEYS:
        rules[mode] = dict(fn, **{"font-size": "15sp", "font-weight": "500"})
        rules[mode + ":pressed"] = fn_pressed
    rules["key[targetlayer=`base`]"] = dict(fn, **{"font-size": "15sp", "font-weight": "500"})  # "ABC"
    # the shift-key rules must come after the ABC rule so they win for the shift key
    for k in (SHIFT_MANUAL, SHIFT_AUTO, SHIFT_LOCK):
        rules[k] = rules.pop(k)
    sheet.update(rules)
    return sheet


def main():
    base = json.load(open(BASE, encoding="utf-8"))
    os.makedirs(os.path.join(OUT, "stylesheets"), exist_ok=True)
    for theme in THEMES:
        with open(os.path.join(OUT, "stylesheets", theme["id"] + ".json"), "w", encoding="utf-8") as f:
            json.dump(stylesheet(base, theme["roles"]), f, indent=2, ensure_ascii=False)
            f.write("\n")
    extension = {
        "$": "ime.extension.theme",
        "meta": {
            "id": "io.github.sillypuppyy.dogboard.themes",
            "version": "0.1.0",
            "title": "DogBoard themes",
            "description": "DogBoard's built-in keyboard themes, following Material You dynamic colour. "
                           "Generated by tools/themes/gen_dogboard_themes.py.",
            "maintainers": ["silly-puppyy"],
            "license": "apache-2.0",
        },
        "themes": [{"id": t["id"], "label": t["label"], "authors": ["silly-puppyy"], "isNight": t["isNight"]} for t in THEMES],
    }
    with open(os.path.join(OUT, "extension.json"), "w", encoding="utf-8") as f:
        json.dump(extension, f, indent=2)
        f.write("\n")
    print(f"{len(THEMES)} themes -> {OUT}")


if __name__ == "__main__":
    main()

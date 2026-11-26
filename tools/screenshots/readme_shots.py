#!/usr/bin/env python3
"""The README's screenshots, taken again on the emulator (tools/screenshots/readme_shots.py).

Needs: the emulator running (1080 x 2424, the dogboard-test AVD), the debug build installed, its languages added
(English + Español + Français on QWERTY, Arabic, Russian, Korean, Hindi, Greek), link previews on. The phone is put in
dark mode with a clean status bar; DogBoard's own theme is put in both slots and follows it. The clipboard shot
CLEARS THE EMULATOR'S CLIPBOARD HISTORY (the app's clipboard_history database) so only the demo clips show.

    tools/screenshots/readme_shots.py [--serial emulator-5554] [--out docs/images] [--only swipe,layout,...]

Writes <out>/<name>.png, 540 px wide: swipe, layout, languages, themes, clipboard.
"""
import argparse
import io
import os
import re
import subprocess
import time

from PIL import Image

PKG = "io.github.sillypuppyy.dogboard.debug"
IME = f"{PKG}/dev.patrickgold.florisboard.FlorisImeService"
ACTIVITY = f"{PKG}/dev.patrickgold.florisboard.app.FlorisAppActivity"
ADB = "/opt/android-sdk/platform-tools/adb"
SERIAL = "emulator-5554"

# where things are on the 1080 x 2424 emulator, DogBoard's default bottom row and toolbar
MESSAGE_FIELD = (400, 2266)  # the SMS app's message field, keyboard closed
EMOJI_KEY = (324, 2200)  # held: Android's keyboard list
TOOLBAR_MENU = (64, 1564)
TOOLBAR_CLIPBOARD = (468, 1564)
KEYBOARD_TOP = 1500  # the crop of a keyboard: toolbar to the gesture bar
# QWERTY's letter centres (EN + ES + FR keyboard, three letter rows at y 1704 / 1872 / 2037)
KEYS = {**{c: (54 + 108 * i, 1704) for i, c in enumerate("qwertyuiop")},
        **{c: (108 + 108 * i, 1872) for i, c in enumerate("asdfghjkl")},
        **{c: (216 + 108 * i, 2037) for i, c in enumerate("zxcvbnm")}}
SWIPE_BEFORE = "Have a "
SWIPE_WORD = "wonderful"  # crosses all three rows, edge to edge
LANGUAGES = [("ar", ["العربية"]), ("ru", ["Русский"]), ("ko", ["한국어"]), ("hi", ["हिन्दी", "हिंदी"]), ("el", ["Ελληνικά"]),
             ("th", ["ไทย"])]
# oldest first (the newest shows on top): a message with a phone number (its own chip), a link inside a message, a link
# with trackers (removed), a short link (expanded, its tracker removed) with a video preview
CLIPS = [
    "Dinner at 7? Call me on +1 415 555 0132",
    "The keyboard I told you about: https://github.com/silly-puppyy/DogBoard",
    "https://github.com/silly-puppyy/DogBoard?utm_source=newsletter&utm_medium=email",
    "https://youtu.be/aqz-KE-bpKQ?si=Hx7mQ2_kLpA9vZ3c",
]


def adb(*args, text=True):
    r = subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, text=text, timeout=120)
    return r.stdout


def sh(cmd):
    return adb("shell", cmd)


def tap(x, y):
    sh(f"input tap {x} {y}")


def hold(x, y, ms=900):
    sh(f"input swipe {x} {y} {x} {y} {ms}")


def screen():
    return Image.open(io.BytesIO(adb("exec-out", "screencap", "-p", text=False)))


BIDI = dict.fromkeys(map(ord, "\u200e\u200f\u202a\u202b\u202c\u202d\u202e\u2066\u2067\u2068\u2069"))


def nodes():
    """The screen's text nodes: (text, centre x, centre y); direction marks dropped (Android wraps Arabic in them)."""
    sh("uiautomator dump /sdcard/ui.xml")
    xml = adb("exec-out", "cat", "/sdcard/ui.xml")
    out = []
    for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        if m.group(1):
            x1, y1, x2, y2 = map(int, m.groups()[1:])
            out.append((m.group(1).translate(BIDI).strip(), (x1 + x2) // 2, (y1 + y2) // 2))
    return out


def tap_text(names, scroll=None, tries=10):
    """Taps the first node whose text is one of [names], scrolling ([scroll] = (x, y1, y2)) until it shows."""
    for i in range(tries):
        for t, x, y in nodes():
            if t in names:
                tap(x, y)
                return True
        if scroll:
            x, y1, y2 = scroll
            # down first, then back up
            # medium speed: a short drag scrolls about two rows without a fling (1.2 s was taken as a hold, no scroll)
            sh(f"input swipe {x} {y1} {x} {y2} 500" if i < tries // 2 else f"input swipe {x} {y2} {x} {y1} 500")
            time.sleep(1)
        else:
            time.sleep(1)
    print("  not found:", names, "; on screen:", [t for t, _, _ in nodes()][:20])
    return False


def settings(page):
    sh("input keyevent BACK")
    sh("input keyevent HOME")
    # -S: a fresh settings activity on that page (one already open stayed on its own page)
    sh(f"am start -W -S -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d 'ui://florisboard/settings/{page}' -n {ACTIVITY}")
    time.sleep(4)
    sh(f"ime set {IME}")  # -S stopped the app, keyboard included: Android falls back to another one


def open_keyboard(number="5550100", clear=True):
    sh("input keyevent HOME")
    sh(f"ime set {IME}")
    sh(f"am start -a android.intent.action.SENDTO -d sms:{number}")
    time.sleep(4)
    tap(*MESSAGE_FIELD)
    time.sleep(3)
    if clear:
        clear_field()


def clear_field():
    sh("input keycombination 113 29")  # ctrl+a
    sh("input keyevent DEL")


def switch_language(names, clear=True):
    if clear:
        clear_field()
    hold(*EMOJI_KEY)
    time.sleep(2)
    ok = tap_text(names, scroll=(540, 1900, 1450), tries=26)
    time.sleep(4)
    return ok


def forget_clip():
    """Android's clipboard set to a space: DogBoard skips blank clips, so no paste chip shows."""
    clear_field()
    sh("input text '%s'")
    sh("input keycombination 113 29")
    sh("input keycombination 113 52")
    clear_field()


def save(img, out, name, crop=None):
    if crop:
        img = img.crop(crop)
    w = 540
    img = img.convert("RGB").resize((w, round(img.height * w / img.width)), Image.LANCZOS)
    path = os.path.join(out, f"{name}.png")
    img.save(path, optimize=True)
    print("  wrote", path, img.size)


def setup():
    sh("cmd uimode night yes")
    sh("settings put global sysui_demo_allowed 1")
    for c in ["enter", "clock -e hhmm 1200", "network -e wifi show -e level 4 -e fully true", "network -e mobile hide",
              "battery -e level 100 -e plugged false", "notifications -e visible false"]:
        sh(f"am broadcast -a com.android.systemui.demo -e command {c}")
    # DogBoard's own theme in both slots, following the phone (set in the saved settings: the Themes page's tiles
    # moved too often to tap reliably); the clipboard history emptied, so no earlier clip shows (paste chip, list)
    sh(f"am force-stop {PKG}")
    prefs = "jetpref_datastore/florisboard-app-prefs.jetpref"
    for key, value in (("keyboard__theme_day", '"dogboard"'), ("keyboard__theme_night", '"dogboard"')):
        sh(f"run-as {PKG} sed -i 's/^s;{key};.*/s;{key};{value}/' {prefs}")
    sh(f"run-as {PKG} sed -i 's/^b;keyboard__theme_follow;.*/b;keyboard__theme_follow;true/' {prefs}")
    for f in ("clipboard_history", "clipboard_history-shm", "clipboard_history-wal"):
        sh(f"run-as {PKG} rm -f databases/{f}")
    # the clipboard's link features (expanding and previews are off by default)
    for key in ("clipboard__expand_short_links", "clipboard__link_previews", "clipboard__strip_trackers", "clipboard__smart_chips"):
        sh(f"run-as {PKG} sh -c \"sed -i '/^b;{key};/d' {prefs}; echo 'b;{key};true' >> {prefs}\"")
    sh(f"ime set {IME}")


def wait_for(text, seconds=15):
    for _ in range(seconds):
        if any(t == text for t, _, _ in nodes()):
            return True
        time.sleep(1)
    return False


def shot_bal(out):
    """The banner's keyboard: "bal" typed, the bar as the design's (Kb:835): “bal” kept, ball to be made, balance."""
    open_keyboard()
    sh("input text 'Throw%sthe%s'")  # mid-sentence, as the design (no capital)
    time.sleep(1)
    for c in "bal":
        tap(*KEYS[c])
        time.sleep(0.4)
    time.sleep(2)
    save(screen(), out, "bal", crop=(0, KEYBOARD_TOP, 1080, 2424))


def shot_swipe(out):
    open_keyboard()
    switch_language(["English + Español + Français"])
    forget_clip()
    sh("input text '" + SWIPE_BEFORE.replace(" ", "%s") + "'")
    time.sleep(2)
    # the whole gesture in one shell, the screenshot taken before the finger lifts (the trail fades after); three
    # points between letters, so the path is a stroke, not jumps
    path = []
    for a, b in zip(SWIPE_WORD, SWIPE_WORD[1:]):
        (xa, ya), (xb, yb) = KEYS[a], KEYS[b]
        path += [(round(xa + (xb - xa) * t / 4), round(ya + (yb - ya) * t / 4)) for t in range(4)]
    path.append(KEYS[SWIPE_WORD[-1]])
    (x0, y0), rest = path[0], path[1:]
    cmd = [f"input motionevent DOWN {x0} {y0}"] + [f"input motionevent MOVE {x} {y}" for x, y in rest]
    cmd += ["screencap -p /sdcard/shot.png", f"input motionevent UP {rest[-1][0]} {rest[-1][1]}"]
    sh("; ".join(cmd))
    time.sleep(1)
    img = Image.open(io.BytesIO(adb("exec-out", "cat", "/sdcard/shot.png", text=False)))
    save(img, out, "swipe")


def shot_layout(out):
    settings("layout")
    sh("input swipe 540 1900 540 700 600")  # to the bottom-row choices, drawn as rows
    time.sleep(2)
    save(screen(), out, "layout")


def shot_languages(out):
    # a conversation never typed in: its field is empty, so nothing is deleted (a deletion leaves an Undo chip in the
    # bar) and each keyboard's bar shows its own sentence-start words
    open_keyboard("5550177", clear=False)
    crops = []
    for tag, names in LANGUAGES:
        if switch_language(names, clear=False):
            img = screen()
            crops.append(img.crop((0, KEYBOARD_TOP, img.width, img.height - 80)))
    switch_language(["English + Español + Français"])
    if not crops:
        return
    # two columns, each keyboard at half width
    cw = crops[0].width // 2
    ch = round(crops[0].height * cw / crops[0].width)
    gap = 12
    rows = (len(crops) + 1) // 2
    sheet = Image.new("RGB", (cw * 2 + gap, rows * ch + (rows - 1) * gap), (0, 0, 0))
    for i, c in enumerate(crops):
        sheet.paste(c.resize((cw, ch), Image.LANCZOS), ((i % 2) * (cw + gap), (i // 2) * (ch + gap)))
    save(sheet, out, "languages")


def shot_themes(out):
    """Two themes picked: Gloss (light, Day) and the Night sky photo (dark, Night), the page coloured by the keyboard
    theme (App colours: Keyboard; purple in dark mode). setup()'s DogBoard theme is put back after."""
    settings("themes")
    wait_for("Which theme")
    tap_text(["Keyboard"])
    for name in ("Gloss", "Night sky"):
        # a tile is picked by its keyboard preview, above its name
        for _ in range(16):
            hit = [(x, y) for t, x, y in nodes() if t == name and y > 700]
            if hit:
                break
            sh("input swipe 540 1900 540 900 500")
            time.sleep(1)
        if hit:
            tap(hit[0][0] + 190, hit[0][1] - 228)  # the name is left-aligned under its preview
            time.sleep(2)
            tap_text(["Keep mine"], tries=2)  # "Use Gloss's sound?"
    settings("themes")
    wait_for("Which theme")
    time.sleep(1)
    save(screen(), out, "themes")
    sh(f"am force-stop {PKG}")
    prefs = "jetpref_datastore/florisboard-app-prefs.jetpref"
    for key, value in (("keyboard__theme_day", '"dogboard"'), ("keyboard__theme_night", '"dogboard"')):
        sh(f"run-as {PKG} sed -i 's/^s;{key};.*/s;{key};{value}/' {prefs}")


def shot_clipboard(out):
    open_keyboard()
    switch_language(["English + Español + Français"])
    # copied through the keyboard (ctrl+x), so DogBoard keeps them and fetches the links' previews
    for clip in CLIPS:
        clear_field()
        sh("input text " + "'" + clip.replace(" ", "%s").replace("'", "") + "'")
        sh("input keycombination 113 29")
        sh("input keycombination 113 52")  # ctrl+x
        time.sleep(1)
    time.sleep(15)  # the expansions and previews (network)
    tap(*TOOLBAR_MENU)
    time.sleep(1.5)
    tap(*TOOLBAR_CLIPBOARD)
    time.sleep(3)
    save(screen(), out, "clipboard")
    sh("input keyevent BACK")


SHOTS = {"bal": shot_bal, "swipe": shot_swipe, "layout": shot_layout, "languages": shot_languages, "themes": shot_themes,
         "clipboard": shot_clipboard}


def main():
    global SERIAL
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=SERIAL)
    ap.add_argument("--out", default="docs/images")
    ap.add_argument("--only", default=",".join(SHOTS))
    a = ap.parse_args()
    SERIAL = a.serial
    os.makedirs(a.out, exist_ok=True)
    setup()
    for name in a.only.split(","):
        print(name)
        SHOTS[name](a.out)


if __name__ == "__main__":
    main()

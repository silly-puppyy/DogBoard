#!/usr/bin/env python3
"""Real people's swipes replayed into keyboard apps on the emulator: DogBoard vs FUTO Keyboard vs Gboard
(tools/swipe/replay/replay.py).

    replay.py calibrate                      # screenshots per keyboard, to read the key grid off
    replay.py run [-n 300] [--kb gboard,futo,dogboard]

User 2026-11-19: "How about others like futo and gboard?" The earlier comparison (2026-11-17) replayed swipes from
our own finger model, fitted to FUTO data, which favours FUTO-trained keyboards. Here: How We Swipe (Leiva et al.
2021, MIT; 1,338 people on a web keyboard), which none of the three is known to train on and ours never did. Each
swipe's points are mapped from its keyboard onto each app's letter keys (How We Swipe key positions learned as in
howweswipe.py; the app's key grid read off a screenshot: the x of q and p, the y of the three letter rows) and sent
with their recorded timing (Replay.java through app_process). The app's output word is read from the debug test
field's log (ImeTestActivity logs its text, tag DogBoardText). A swipe that adds nothing counts as wrong. Case is
ignored. The field is cleared every 15 words (Ctrl+A, Delete), so each word follows a few random earlier ones as
context, the same for every keyboard.
"""
import argparse
import os
import io
import json
import random
import re
import subprocess
import sys
import time
import zipfile

import numpy as np

ADB = ["/opt/android-sdk/platform-tools/adb", "-s", "emulator-5554"]
D = "/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data"
TEST = "io.github.sillypuppyy.dogboard.debug/io.github.sillypuppyy.dogboard.imetest.ImeTestActivity"
# Key grids on the emulator (1080 x 2424, default keyboard height), read off screenshots 2026-11-19:
# x of q and p, y of the top / middle / bottom letter rows, in screen pixels.
KEYBOARDS = {
    "gboard": ("com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME", (57, 1021), (1739, 1893, 2048)),
    "futo": ("org.futo.inputmethod.latin/.LatinIME", (57, 1020), (1733, 1887, 2045)),
    "dogboard": ("io.github.sillypuppyy.dogboard.debug/dev.patrickgold.florisboard.FlorisImeService", (53, 1025), (1754, 1909, 2064)),
}


def adb(*args, **kw):
    return subprocess.run(ADB + list(args), capture_output=True, text=True, **kw).stdout


def hws(n, sentences=False):
    """n How We Swipe swipes: (word, [(cx, cy, t)]) in the dataset layout's 0..1 units (qwerty.json). With
    [sentences], whole sentences in their order (each one's swipes kept together; the field is cleared between them),
    so the keyboard sees the words before as context, as in real typing (2026-11-19: single words after unrelated
    random ones misled the sentence model: "you" -> "to", "be" -> "he")."""
    lay = json.load(open(f"{D}/swipe/futo/swipe-5/layouts/qwerty.json"))
    unit = {k["letter"]: (k["cx"], k["cy"]) for k in lay["keys"]}
    zf = zipfile.ZipFile(f"{D}/swipe/howweswipe/swipelogs.zip")
    names = sorted(x for x in zf.namelist() if x.endswith(".log"))
    random.Random(5).shuffle(names)
    data = []; sent_of = []
    for name in names:
        cur, word, bad = [], None, False
        for r in io.TextIOWrapper(zf.open(name), encoding="utf-8").read().splitlines()[1:]:
            f = r.split()
            if len(f) < 12:
                continue
            ev, t, x, y, w, err = f[4], int(f[1]), float(f[5]), float(f[6]), f[10], f[11] == "1"
            sentence = name + "/" + f[0]
            if ev == "touchstart":
                cur, word, bad = [(x, y, t)], w.lower(), err
            elif cur:
                cur.append((x, y, t))
                if ev == "touchend":
                    if not bad and word.isascii() and word.isalpha() and len(word) >= 2:
                        data.append((word, cur)); sent_of.append(sentence)
                    cur = []
        if len(data) >= max(4 * n, 4000):
            break
    acc = {}
    for word, pts in data:
        for c, p in ((word[0], pts[0]), (word[-1], pts[-1])):
            acc.setdefault(c, []).append(p[:2])
    letters = [c for c in unit if len(acc.get(c, [])) >= 20]
    px = np.array([np.median(np.array(acc[c]), axis=0) for c in letters]); ux = np.array([unit[c] for c in letters])
    fx, fy = np.polyfit(px[:, 0], ux[:, 0], 1), np.polyfit(px[:, 1], ux[:, 1], 1)
    if sentences:  # whole sentences, in order, until n swipes
        order = list(dict.fromkeys(sent_of)); random.Random(6).shuffle(order)
        by = {}
        for (w, p), sid in zip(data, sent_of): by.setdefault(sid, []).append((w, p))
        picked = []
        for sid in order:
            if len(picked) >= n: break
            picked += [(w, p, sid) for w, p in by[sid]]
        data = [(w, p) for w, p, _ in picked]; sent_of = [sid for _, _, sid in picked]
    else:
        idx = list(range(len(data))); random.Random(6).shuffle(idx)
        data = [data[i] for i in idx]; sent_of = [sent_of[i] for i in idx]
    out = []
    for word, pts in data[:n]:
        t0 = pts[0][2]
        out.append((word, [(float(np.polyval(fx, x)), float(np.polyval(fy, y)), t - t0) for x, y, t in pts]))
    return out, unit, sent_of[:len(out)]


def to_screen(unit, xs, ys):
    """0..1 layout units -> screen pixels for a keyboard: x from q and p, y from the three rows."""
    ax = np.polyfit([unit["q"][0], unit["p"][0]], xs, 1)
    rows = sorted({round(v[1], 3) for v in unit.values()})
    ay = np.polyfit(rows, ys, 1)
    return lambda x, y: (float(np.polyval(ax, x)), float(np.polyval(ay, y)))


def field_text():
    lines = adb("logcat", "-d", "-s", "DogBoardText:I").splitlines()
    for l in reversed(lines):
        m = re.search(r"text=\[(.*)\]$", l)
        if m:
            return m.group(1)
    return ""


# The lowest a stroke may go (px): below it is Android's navigation gesture area. 2026-11-19: a sloppy swipe that
# reached the bottom edge was a home gesture; the test app closed and every later swipe went to the launcher's search
# (FUTO 47 right, then 253 empty in a row).
MAX_Y = 2230


def focused():
    return "ImeTestActivity" in adb("shell", "dumpsys", "window", "|", "grep", "mCurrentFocus")


def open_field():
    adb("shell", "am", "start", "-n", TEST)  # never -S: it would also stop DogBoard's keyboard (same package)
    time.sleep(3)
    adb("shell", "input", "tap", "300", "1210")
    time.sleep(1.5)


def clear_field():
    adb("shell", "input", "keycombination", "113", "29")  # Ctrl+A
    adb("shell", "input", "keyevent", "67")               # Delete
    time.sleep(0.4)


def norm(w):
    """Scoring: case and apostrophes ignored (How We Swipe stores "im", "whats"; a keyboard rightly types I'm, what's)."""
    return w.lower().strip(".,!?").replace("'", "").replace("’", "")


def run_keyboard(name, swipes, unit, sent_of=None, kernel=False):
    ime, xs, ys = KEYBOARDS[name]
    adb("shell", "ime", "set", ime)
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(1)
    open_field()
    m0 = to_screen(unit, xs, ys)

    def m(x, y):
        sx, sy = m0(x, y)
        return min(max(sx, 1.0), 1079.0), min(sy, MAX_Y)
    strokes = "\n".join(" ".join(f"{m(x, y)[0]:.1f},{m(x, y)[1]:.1f},{t:.0f}" for x, y, t in pts) for _, pts in swipes)
    open("/tmp/claude-1000/replay-strokes.txt", "w").write(strokes + "\n")
    adb("push", "/tmp/claude-1000/replay-strokes.txt", "/data/local/tmp/strokes.txt")
    clear_field()
    adb("logcat", "-c")
    if kernel:
        # straight into the emulator's touch device (tools/test/taptype stroke lines): a real finger to every keyboard
        w, h = map(int, adb("shell", "wm", "size").split(":")[-1].strip().split("x"))
        def trimmed(pts):
            """Without the rest on the first key (TRIM_REST=1: Gboard may need the finger to move soon after the press)."""
            if os.environ.get("TRIM_REST") != "1":
                return pts
            x0, y0 = m(pts[0][0], pts[0][1])
            k = next((i for i, (x, y, _) in enumerate(pts) if abs(m(x, y)[0] - x0) + abs(m(x, y)[1] - y0) > 12), 0)
            k = max(0, k - 1)
            return [(x, y, t - pts[k][2]) for x, y, t in pts[k:]]
        lines = ["S 0 " + " ".join(f"{int(m(x, y)[0] * 32767 / w)} {int(m(x, y)[1] * 32767 / h)} {t:.0f}" for x, y, t in trimmed(pts))
                 for _, pts in swipes]

        class Kernel:  # Replay.java's handshake, one taptype call per stroke
            def __init__(self):
                self.i = 0
                self.stdin = self
                self.stdout = self
                self.send()

            def send(self):
                if self.i < len(lines):
                    subprocess.run(ADB + ["shell", "/data/local/tmp/taptype /dev/input/event2"], input=lines[self.i] + "\n",
                                   text=True, timeout=60)
                self.i += 1

            def readline(self):
                return "done"

            def write(self, _):
                self.send()

            def flush(self):
                pass

            def close(self):
                pass

            def wait(self, timeout=None):
                pass
        p = Kernel()
    else:
        p = subprocess.Popen(ADB + ["shell", "CLASSPATH=/data/local/tmp/replay.dex app_process / Replay /data/local/tmp/strokes.txt"],
                             stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
    right, rows, lost = 0, [], 0
    before = ""
    for i, (word, _) in enumerate(swipes):
        assert p.stdout.readline().strip() == "done"
        time.sleep(0.7)
        after = field_text()
        got = after[len(before):].strip() if after.startswith(before) else (after.split() or [""])[-1]
        got = got.split()[-1] if got.split() else ""
        if not focused():  # the field lost focus during this swipe: not counted, field reopened
            lost += 1
            rows.append((word, None))
            open_field(); clear_field(); before = field_text()
            p.stdin.write("\n"); p.stdin.flush()
            continue
        ok = norm(got) == word
        right += ok
        rows.append((word, got))
        before = after
        new_sentence = sent_of is not None and i + 1 < len(sent_of) and sent_of[i + 1] != sent_of[i]
        if new_sentence if sent_of is not None else (i + 1) % 15 == 0:
            clear_field(); before = field_text()
        p.stdin.write("\n"); p.stdin.flush()
        if (i + 1) % 50 == 0:
            print(f"  {name}: {i + 1} swipes, {100 * right / (i + 1):.1f} %", flush=True)
    p.stdin.close(); p.wait(timeout=30)
    print(f"  {name}: {lost} swipes not counted (field lost focus)", flush=True)
    return right, rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=("run",))
    ap.add_argument("-n", type=int, default=300)
    ap.add_argument("--kb", default="dogboard,futo,gboard")
    ap.add_argument("--out", default=f"{D}/swipe/replay-hws.json")
    ap.add_argument("--sentences", action="store_true", help="whole sentences in order (context as in real typing)")
    ap.add_argument("--kernel", action="store_true", help="inject through the touch device (tools/test/taptype), not app_process")
    a = ap.parse_args()
    swipes, unit, sent_of = hws(a.n, a.sentences)
    print(f"{len(swipes)} How We Swipe swipes", flush=True)
    res = {}
    for name in a.kb.split(","):
        right, rows = run_keyboard(name, swipes, unit, sent_of if a.sentences else None, kernel=a.kernel)
        counted = sum(g is not None for _, g in rows)
        res[name] = {"top1": 100 * right / max(counted, 1), "counted": counted, "rows": rows}
        print(f"{name}: {100 * right / max(counted, 1):.1f} % ({right} of {counted} counted)", flush=True)
        json.dump(res, open(a.out, "w"), indent=1)
    sys.path.insert(0, "/home/syn/Documents/Code/AI/keyboard/dogboard-batch8/tools")
    import track
    run = track.start("swipe", "en", "replay-hws-apps", layout="qwerty", data="howweswipe",
                      config={"kind": "app-replay", "n": len(swipes), "keyboards": list(res)})
    run.summary.update({f"{k}/top1": v["top1"] for k, v in res.items()})
    run.finish()


if __name__ == "__main__":
    main()

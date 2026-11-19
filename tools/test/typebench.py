"""tools/test/typebench.py (2026-11-19): Type sentences on the emulator by tapping DogBoard's keys (positions from its accessibility nodes), then
summarise DogBoard's own per-word logs: 'gc: N KB allocated this word' and 'neural candidate scores took'."""
import re, subprocess, sys, time, statistics
A = ["/opt/android-sdk/platform-tools/adb", "-s", "emulator-5554"]
def sh(*a): return subprocess.run(A + list(a), capture_output=True, text=True).stdout
def keys():
    sh("shell", "uiautomator", "dump", "--windows", "/sdcard/u.xml")
    x = sh("shell", "cat", "/sdcard/u.xml")
    out = {}
    for n in re.findall(r"<node [^>]*>", x):
        if "dogboard.debug" not in n or 'class="android.widget.Button"' not in n: continue
        d = re.search(r'content-desc="([^"]*)"', n).group(1)
        b = list(map(int, re.findall(r"\d+", re.search(r'bounds="([^"]*)"', n).group(1))))
        out[d.lower()] = ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
    return out
SENTENCES = ["the weather is nice today and we are going to the park",
             "i think this keyboard works better than i expected",
             "can you send me the document before the meeting tomorrow",
             "she said the train was late again this morning",
             "we should get dinner together sometime next week"]
sh("shell", "am", "start", "-n", "io.github.sillypuppyy.dogboard.debug/io.github.sillypuppyy.dogboard.imetest.ImeTestActivity")
time.sleep(3); sh("shell", "input", "tap", "300", "1210"); time.sleep(2)
k = keys(); assert "space" in k and "a" in k, k.keys()
sh("logcat", "-c")
for s in SENTENCES:
    for ch in s + " ":
        x, y = k["space" if ch == " " else ch]
        sh("shell", "input", "tap", str(x), str(y))
log = sh("logcat", "-d", "-s", "DogBoardPerf:I")
kb = [int(m) for m in re.findall(r"gc: (\d+) KB allocated this word", log)]
sc = [int(m) for m in re.findall(r"neural candidate scores took (\d+)ms", log)]
text = re.findall(r"text=\[(.*)\]", sh("logcat", "-d", "-s", "DogBoardText:I"))
print(f"words logged {len(kb)}: KB a word median {statistics.median(kb) if kb else 0}, mean {statistics.mean(kb) if kb else 0:.0f}, max {max(kb) if kb else 0}")
print(f"neural refine ms: median {statistics.median(sc) if sc else 0}, p90 {sorted(sc)[int(len(sc)*.9)] if sc else 0}")
print("typed:", text[-1][:160] if text else "")

#!/usr/bin/env python3
"""Measure how long a keyboard takes to appear after a text field asks for it.

Launches DogBoard's debug-only test screen (ImeTestActivity, a blank text field in its own
process) and reads its `DogBoardPerf` log lines. Works for any installed keyboard: the test
screen detects the keyboard through window insets, not through the keyboard's own code.

    tools/perf/ime_startup.py --serial emulator-5554 --mode cold --runs 5
    tools/perf/ime_startup.py --serial emulator-5554 --ime org.futo.inputmethod.latin/.LatinIME

Marks (all logged by the test screen unless noted):
    requested   the text field asked for the keyboard
    visible     the system first reports keyboard insets (keyboard window is up)
    shown       the keyboard's slide-in animation finished
    keys drawn  DogBoard only: first frame with real keys

Cold: the keyboard's app is force-stopped before each run, so it starts from scratch.
Warm: the test screen is closed and reopened while the keyboard stays alive.
"""
import argparse
import re
import statistics
import subprocess
import sys
import time

ADB = "/opt/android-sdk/platform-tools/adb"
TEST_PACKAGE = "io.github.sillypuppyy.dogboard.debug"
TEST_ACTIVITY = "io.github.sillypuppyy.dogboard.imetest.ImeTestActivity"
DOGBOARD_IME = f"{TEST_PACKAGE}/dev.patrickgold.florisboard.FlorisImeService"
LINE = re.compile(r"^\s*(\d+\.\d+)\s+(\d+)\s+\d+\s+I\s+DogBoardPerf:\s+(.*)$")
MARKS = {
    "visible": "ime visible",
    "shown": "ime shown",
    "keys": "keys drawn",
}


def adb(serial, *args):
    return subprocess.run([ADB, "-s", serial, *args], capture_output=True, text=True).stdout


def perf_lines(serial):
    out = adb(serial, "logcat", "-d", "-v", "epoch", "-s", "DogBoardPerf:I")
    return [(float(m.group(1)), int(m.group(2)), m.group(3)) for m in map(LINE.match, out.splitlines()) if m]


def first(lines, prefix, exclude_pid=None):
    return next((t for t, pid, msg in lines if msg.startswith(prefix) and pid != exclude_pid), None)


def close_test_screen(serial):
    adb(serial, "shell", "input", "keyevent", "KEYCODE_BACK")
    adb(serial, "shell", "input", "keyevent", "KEYCODE_BACK")


def select_ime(serial, ime):
    adb(serial, "shell", "ime", "enable", ime)
    adb(serial, "shell", "ime", "set", ime)
    current = adb(serial, "shell", "settings", "get", "secure", "default_input_method").strip()
    if current != ime:
        raise SystemExit(f"could not select {ime} (active keyboard is {current})")


def kill_process(serial, package):
    """Kill the keyboard's process without changing the selected keyboard (needs adb root)."""
    pids = adb(serial, "shell", "pidof", package).split()
    for pid in pids:
        adb(serial, "shell", "kill", "-9", pid)
    return len(pids)


def run_once(serial, ime, mode, until, timeout_s):
    ime_package = ime.split("/")[0]
    close_test_screen(serial)
    time.sleep(1.5)
    if mode == "cold":
        # kill right before launching, so the keyboard has to start from scratch when the field
        # asks for it (Android restarts a killed keyboard service after about a second)
        adb(serial, "logcat", "-c")
        kill_process(serial, ime_package)
        if ime_package != TEST_PACKAGE:
            kill_process(serial, TEST_PACKAGE + ":imetest")
        adb(serial, "shell", "am", "start", "-n", f"{TEST_PACKAGE}/{TEST_ACTIVITY}")
    else:
        adb(serial, "logcat", "-c")
        adb(serial, "shell", "am", "start", "-n", f"{TEST_PACKAGE}/{TEST_ACTIVITY}")
    deadline = time.time() + timeout_s
    lines = []
    while time.time() < deadline:
        lines = perf_lines(serial)
        requested = first(lines, "test field requests keyboard")
        done = first(lines, MARKS[until])
        if requested is not None and done is not None and done >= requested:
            result = {"total": (done - requested) * 1000.0}
            for key, prefix in MARKS.items():
                t = first(lines, prefix)
                if t is not None and t >= requested:
                    result[key] = (t - requested) * 1000.0
            test_pid = next(pid for _, pid, msg in lines if msg.startswith("test field requests keyboard"))
            compile_line = next((msg for _, pid, msg in lines
                                 if msg.startswith("layout compile took") and pid != test_pid), None)
            if compile_line:
                result["compile"] = float(re.search(r"(\d+)ms", compile_line).group(1))
            return result, lines
        time.sleep(0.2)
    return None, lines


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--ime", default=DOGBOARD_IME, help="keyboard component, package/service")
    ap.add_argument("--mode", choices=["cold", "warm"], default="cold")
    ap.add_argument("--until", choices=list(MARKS), default="shown")
    ap.add_argument("--runs", type=int, default=5)
    ap.add_argument("--timeout", type=float, default=20.0)
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    if args.mode == "cold":
        adb(args.serial, "root")
        time.sleep(2)
    select_ime(args.serial, args.ime)

    if args.mode == "warm":
        run_once(args.serial, args.ime, "cold", args.until, args.timeout)

    results = []
    for i in range(args.runs):
        result, lines = run_once(args.serial, args.ime, args.mode, args.until, args.timeout)
        if result is None:
            print(f"run {i + 1}: TIMEOUT (no '{MARKS[args.until]}' within {args.timeout}s)")
        else:
            active = adb(args.serial, "shell", "settings", "get", "secure", "default_input_method").strip()
            if active != args.ime:
                print(f"run {i + 1}: INVALID (active keyboard changed to {active})")
                continue
            results.append(result)
            print(f"run {i + 1}: " + ", ".join(f"{k} {v:.0f} ms" for k, v in result.items()))
        if args.verbose:
            for t, pid, msg in lines:
                print(f"    {t:.3f} [{pid}] {msg}")

    close_test_screen(args.serial)
    if not results:
        sys.exit(1)
    totals = [r["total"] for r in results]
    print(f"{args.ime} {args.mode} until '{args.until}': median {statistics.median(totals):.0f} ms, "
          f"min {min(totals):.0f}, max {max(totals):.0f} over {len(totals)} runs")


if __name__ == "__main__":
    main()

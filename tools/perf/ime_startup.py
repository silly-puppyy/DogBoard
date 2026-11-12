#!/usr/bin/env python3
"""Measure how long DogBoard takes to show its keys after a text field asks for the keyboard.

Launches DogBoard's debug-only test screen (ImeTestActivity, a blank text field in its own
process) and reads DogBoard's `DogBoardPerf` log lines. Nothing outside DogBoard is touched.

    tools/perf/ime_startup.py --serial emulator-5554 --mode cold --runs 5
    tools/perf/ime_startup.py --serial emulator-5554 --set-ime     # make DogBoard the keyboard first

Cold: DogBoard is force-stopped before each run, so the keyboard service starts from scratch.
Warm: the test screen is closed and reopened while the keyboard service stays alive.
"""
import argparse
import re
import statistics
import subprocess
import sys
import time

ADB = "/opt/android-sdk/platform-tools/adb"
TEST_ACTIVITY = "io.github.sillypuppyy.dogboard.debug.ImeTestActivity"
IME_SERVICE = "dev.patrickgold.florisboard.FlorisImeService"
LINE = re.compile(r"^\s*(\d+\.\d+)\s+\d+\s+\d+\s+I\s+DogBoardPerf:\s+(.*)$")


def adb(serial, *args, check=True):
    return subprocess.run([ADB, "-s", serial, *args], capture_output=True, text=True, check=check).stdout


def perf_lines(serial):
    out = adb(serial, "logcat", "-d", "-v", "epoch", "-s", "DogBoardPerf:I")
    lines = []
    for raw in out.splitlines():
        m = LINE.match(raw)
        if m:
            lines.append((float(m.group(1)), m.group(2)))
    return lines


def first(lines, prefix):
    return next((t for t, msg in lines if msg.startswith(prefix)), None)


def run_once(serial, package, mode, timeout_s):
    if mode == "cold":
        adb(serial, "shell", "am", "force-stop", package)
    else:
        adb(serial, "shell", "input", "keyevent", "KEYCODE_BACK")
        adb(serial, "shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1.0)
    adb(serial, "logcat", "-c")
    adb(serial, "shell", "am", "start", "-n", f"{package}/{TEST_ACTIVITY}")
    done_marker = "keys drawn" if mode == "cold" else "onWindowShown"
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        lines = perf_lines(serial)
        requested = first(lines, "test field requests keyboard")
        done = first(lines, done_marker)
        if requested is not None and done is not None and done >= requested:
            compile_ms = next((int(re.search(r"(\d+)ms", msg).group(1)) for _, msg in lines
                               if msg.startswith("layout compile took")), None)
            return (done - requested) * 1000.0, compile_ms, lines
        time.sleep(0.25)
    return None, None, perf_lines(serial)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--package", default="io.github.sillypuppyy.dogboard.debug")
    ap.add_argument("--mode", choices=["cold", "warm"], default="cold")
    ap.add_argument("--runs", type=int, default=5)
    ap.add_argument("--timeout", type=float, default=20.0)
    ap.add_argument("--set-ime", action="store_true", help="enable and select DogBoard as the keyboard first")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    if args.set_ime:
        ime = f"{args.package}/{IME_SERVICE}"
        adb(args.serial, "shell", "ime", "enable", ime)
        adb(args.serial, "shell", "ime", "set", ime)

    if args.mode == "warm":
        run_once(args.serial, args.package, "cold", args.timeout)  # make sure the service is up

    results = []
    for i in range(args.runs):
        ms, compile_ms, lines = run_once(args.serial, args.package, args.mode, args.timeout)
        if ms is None:
            print(f"run {i + 1}: TIMEOUT (no keyboard within {args.timeout}s)")
            if args.verbose:
                for t, msg in lines:
                    print(f"    {t:.3f} {msg}")
            continue
        results.append(ms)
        extra = f", layout compile {compile_ms} ms" if compile_ms is not None else ""
        print(f"run {i + 1}: {ms:.0f} ms{extra}")
        if args.verbose:
            for t, msg in lines:
                print(f"    {t:.3f} {msg}")

    if not results:
        sys.exit(1)
    print(f"{args.mode}: median {statistics.median(results):.0f} ms, "
          f"min {min(results):.0f}, max {max(results):.0f} over {len(results)} runs")


if __name__ == "__main__":
    main()

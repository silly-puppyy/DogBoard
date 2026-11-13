#!/usr/bin/env python3
"""Runs the design lint over every frame of the debug design gallery (tools/test/design_lint.py).

    tools/test/design_lint.py [--serial emulator-5554] [--frames 0-73] [--package io.github.sillypuppyy.dogboard.debug]

Each frame of DesignGalleryActivity is drawn alone, with the lint on (DesignLint.kt): text wider
than its box, elements outside their container or the keyboard, overlapping caps, caps not where
the spec puts them (spacing), labels off centre. Prints every problem; exit 1 if there are any not listed in design_lint_known.txt.
Needs the debug APK installed.
"""
import argparse
import os
import subprocess
import sys
import time

ADB = "/opt/android-sdk/platform-tools/adb"
ACTIVITY = "io.github.sillypuppyy.dogboard.designgallery.DesignGalleryActivity"


def adb(serial, *args, timeout=30):
    return subprocess.run([ADB, "-s", serial, *args], capture_output=True, text=True, timeout=timeout).stdout


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="emulator-5554")
    ap.add_argument("--package", default="io.github.sillypuppyy.dogboard.debug")
    ap.add_argument("--frames", default="0-73", help="range a-b of frame indices")
    args = ap.parse_args()
    lo, _, hi = args.frames.partition("-")
    problems, missing = [], []
    for i in range(int(lo), int(hi or lo) + 1):
        adb(args.serial, "logcat", "-c")
        adb(args.serial, "shell", "am", "start", "-S", "-n", f"{args.package}/{ACTIVITY}", "--ei", "frame", str(i))
        log = ""
        for _ in range(40):
            time.sleep(0.25)
            log = adb(args.serial, "logcat", "-d", "-s", "DesignLint:*")
            if f"frame {i} done" in log:
                break
        else:
            missing.append(i)
            continue
        for line in log.splitlines():
            if f"frame {i} FAIL" in line:
                problems.append(line.split("DesignLint", 1)[1].lstrip(": "))
            elif f"frame {i} OK" in line:
                print(line.split("DesignLint", 1)[1].lstrip(": "))
    known_file = os.path.join(os.path.dirname(os.path.abspath(__file__)), "design_lint_known.txt")
    known = [l.strip() for l in open(known_file) if l.strip() and not l.startswith("#")]
    accepted = [p for p in problems if any(k in p for k in known)]
    problems = [p for p in problems if p not in accepted]
    for p in accepted:
        print("known (awaiting a design decision):", p)
    for p in problems:
        print(p)
    if missing:
        print(f"no result for frames {missing} (index past the end, or the app crashed)")
    print(f"design lint: {len(problems)} problems, {len(accepted)} known")
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()

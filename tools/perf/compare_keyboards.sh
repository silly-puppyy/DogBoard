#!/usr/bin/env bash
# Compare keyboard startup times on a (rooted) emulator with tools/perf/ime_startup.py.
# Usage: tools/perf/compare_keyboards.sh <serial> [runs]
set -u
SERIAL=${1:?serial}
RUNS=${2:-5}
HERE=$(cd "$(dirname "$0")" && pwd)

KEYBOARDS=(
  "io.github.sillypuppyy.dogboard.bench/dev.patrickgold.florisboard.FlorisImeService"
  "io.github.sillypuppyy.dogboard.debug/dev.patrickgold.florisboard.FlorisImeService"
  "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"
  "org.futo.inputmethod.latin/.LatinIME"
  "helium314.keyboard/.latin.LatinIME"
  "dev.patrickgold.florisboard/.FlorisImeService"
)

for ime in "${KEYBOARDS[@]}"; do
  for mode in cold warm; do
    python3 -I "$HERE/ime_startup.py" --serial "$SERIAL" --ime "$ime" --mode "$mode" --until shown --runs "$RUNS" --timeout 15 | tail -1
  done
  case "$ime" in
    io.github.sillypuppyy.dogboard.*)
      python3 -I "$HERE/ime_startup.py" --serial "$SERIAL" --ime "$ime" --mode cold --until keys --runs "$RUNS" --timeout 15 | tail -1 ;;
  esac
done

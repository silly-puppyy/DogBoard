#!/usr/bin/env bash
# Pre-install smoke test: unit tests, debug build, then startup and typing tests on the emulator.
# Usage: tools/test/smoke.sh [emulator-serial]
# Exit code 0 means the debug APK is safe to install on a phone.
set -euo pipefail
SERIAL=${1:-emulator-5554}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
ADB=/opt/android-sdk/platform-tools/adb
cd "$ROOT"
export PATH=$HOME/.cargo/bin:$PATH

echo "== emulator"
DOGBOARD_EMU_LOG=${DOGBOARD_EMU_LOG:-/tmp/dogboard-emulator.log} tools/test/emulator.sh "$SERIAL"

echo "== clear learned and blocked words from earlier test runs"
"$ADB" -s "$SERIAL" shell run-as io.github.sillypuppyy.dogboard.debug rm -f files/dogboard/user_words.txt files/dogboard/blocked_words.txt || true

echo "== unit tests"
./gradlew :app:testDebugUnitTest --max-workers=6 --console=plain -q

echo "== debug build"
./gradlew :app:assembleDebug --max-workers=6 --console=plain -q
APK=app/build/outputs/apk/debug/app-debug.apk

echo "== install on $SERIAL"
"$ADB" -s "$SERIAL" install -r -t "$APK" | tail -1

echo "== startup (cold, until keys drawn)"
python3 -I tools/perf/ime_startup.py --serial "$SERIAL" --mode cold --until keys --runs 2 --timeout 15 | tail -1

echo "== typing"
python3 -I tools/test/type_test.py --serial "$SERIAL" --history
python3 -I tools/test/type_test.py --serial "$SERIAL" --noise-mm 1.0 --seed 11 --history

echo "== crash check"
if "$ADB" -s "$SERIAL" logcat -d | grep -E "FATAL EXCEPTION|CrashDialogActivity" | grep -q dogboard; then
  echo "FAIL: DogBoard crashed during the smoke test"
  exit 1
fi
echo "SMOKE OK: $APK"

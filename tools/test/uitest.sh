#!/bin/bash
# DogBoard UI tests (tier 2: app/src/androidTest, KeyboardUiTest): typing with real timings, field types, panels,
# screenshots to files/uitest/ on the device: one per test as it ended (FAIL-... when it failed) plus the panels.
#
#   tools/test/uitest.sh local [serial]       build, install on an emulator (default emulator-5554), run, pull screenshots
#   tools/test/uitest.sh testlab [model ...]   build, run on Firebase Test Lab (needs `gcloud auth login` and a
#                                              project: `gcloud config set project <id>`); default: a small mix
#   tools/test/uitest.sh smoke                 one test on one virtual Test Lab device: run this first after any change
#                                              to the pipeline (Spark quota: 5 physical + 10 virtual runs a day)
#   tools/test/uitest.sh models                list Test Lab's Android devices (Samsung, Xiaomi, ...)
#
# Never installs on a phone: a serial that is not an emulator is refused unless DOGBOARD_ALLOW_DEVICE=1. The tests
# FORGET EVERY LEARNED WORD on the device they run on (a clean start each run): never on a phone in use.
set -euo pipefail
cd "$(dirname "$0")/../.."
ADB=/opt/android-sdk/platform-tools/adb
APK=app/build/outputs/apk/debug/app-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
OUT=build/uitest

build() {
  ANDROID_USER_HOME=$HOME/.config/.android taskset -c 0-2 nice ./gradlew --no-daemon --max-workers=2 \
    assembleDebug assembleDebugAndroidTest -q
  pkill -f "[K]otlinCompileDaemon" || true
}

case "${1:-local}" in
  local)
    SERIAL=${2:-emulator-5554}
    if [[ $SERIAL != emulator-* && ${DOGBOARD_ALLOW_DEVICE:-0} != 1 ]]; then
      echo "refusing $SERIAL: not an emulator (DOGBOARD_ALLOW_DEVICE=1 to allow)"; exit 2
    fi
    build
    $ADB -s "$SERIAL" install -r -t "$APK" >/dev/null
    $ADB -s "$SERIAL" install -r -t "$TEST_APK" >/dev/null
    mkdir -p "$OUT/$SERIAL"; rm -rf "$OUT/$SERIAL/uitest"
    $ADB -s "$SERIAL" shell rm -rf /sdcard/Android/data/io.github.sillypuppyy.dogboard.debug/files/uitest
    $ADB -s "$SERIAL" shell am instrument -w -r io.github.sillypuppyy.dogboard.debug.test/androidx.test.runner.AndroidJUnitRunner \
      | tee "$OUT/$SERIAL/instrument.txt" | grep -E "OK \(|FAILURES|Tests run|^Error in" || true
    $ADB -s "$SERIAL" pull /sdcard/Android/data/io.github.sillypuppyy.dogboard.debug/files/uitest "$OUT/$SERIAL/" >/dev/null 2>&1 || true
    echo "screenshots: $OUT/$SERIAL/uitest"
    ;;
  testlab)
    shift
    [[ ${DOGBOARD_NO_BUILD:-0} == 1 ]] || build
    MODELS=("$@")
    # default (catalogue 2026-11-20): Pixel 8, Galaxy S24 (One UI), Xiaomi 14 (HyperOS), Galaxy A10 on Android 10
    # (low end, the oldest supported); Spark's free tier is 5 physical runs a day, so 4 devices = one run a day
    [[ ${#MODELS[@]} -eq 0 ]] && MODELS=("model=shiba,version=36" "model=SC-51E,version=36" "model=houji,version=35" "model=a10,version=29")
    DEVICES=(); for m in "${MODELS[@]}"; do DEVICES+=(--device "$m"); done
    gcloud firebase test android run --type instrumentation --app "$APK" --test "$TEST_APK" \
      "${DEVICES[@]}" --timeout 15m --directories-to-pull /sdcard/Android/data/io.github.sillypuppyy.dogboard.debug/files/uitest
    ;;
  smoke)
    [[ ${DOGBOARD_NO_BUILD:-0} == 1 ]] || build
    gcloud firebase test android run --type instrumentation --app "$APK" --test "$TEST_APK" \
      --device model=MediumPhone.arm,version=34 --timeout 5m \
      --test-targets "class io.github.sillypuppyy.dogboard.uitest.KeyboardUiTest#plainWords"
    ;;
  models)
    gcloud firebase test android models list --format="table(id,brand,name,form,supportedVersionIds.list())"
    ;;
  *) echo "usage: $0 local [serial] | smoke | testlab [model ...] | models"; exit 2 ;;
esac

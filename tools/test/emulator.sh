#!/usr/bin/env bash
# Makes sure the test emulator is running: boots the AVD headless if adb does not see it.
# Usage: tools/test/emulator.sh [serial]   (AVD and images live on the external SSD, see notes/setup.md)
# GPU: the headless emulator segfaults now and then in its GL display code: after ~1 h with
# swiftshader_indirect, ~2.3 h with guest, ~15 min with swangle_indirect. Default guest;
# override with DOGBOARD_EMU_GPU.
set -euo pipefail
SERIAL=${1:-emulator-5554}
ADB=/opt/android-sdk/platform-tools/adb
export ANDROID_AVD_HOME=${ANDROID_AVD_HOME:-/run/media/syn/a1991982-8703-474a-926a-020824310f2e/android-dev/avd}
export ANDROID_SDK_ROOT=/opt/android-sdk
LOG=${DOGBOARD_EMU_LOG:-/tmp/dogboard-emulator.log}

booted() { [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; }

if booted; then exit 0; fi
pkill -f 'qemu-system-x86_64-headless -avd dogboard-test' 2>/dev/null || true
sleep 1
echo "booting emulator (gpu ${DOGBOARD_EMU_GPU:-guest}), log $LOG"
nohup /opt/android-sdk/emulator/emulator -avd dogboard-test -no-window -no-audio -no-boot-anim \
  -no-snapshot-save -gpu "${DOGBOARD_EMU_GPU:-guest}" -memory 4096 -cores 4 > "$LOG" 2>&1 &
for _ in $(seq 1 60); do
  sleep 3
  if booted; then
    "$ADB" -s "$SERIAL" shell settings put secure show_ime_with_hard_keyboard 1
    echo "emulator booted"
    exit 0
  fi
done
echo "emulator did not boot, see $LOG" >&2
exit 1

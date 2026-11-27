#!/usr/bin/env bash
set -euo pipefail

adb install app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant com.ringpass.app android.permission.READ_CONTACTS
adb shell pm grant com.ringpass.app android.permission.READ_PHONE_STATE
adb shell cmd role add-role-holder --user 0 android.app.role.CALL_SCREENING com.ringpass.app
adb shell cmd notification set_dnd alarms
adb shell cmd media_session volume --stream 4 --set 5
adb logcat -c

test_output="$(mktemp)"
adb shell am instrument -w -r -e class com.ringpass.app.CellularCallSmokeTest \
    com.ringpass.app.test/androidx.test.runner.AndroidJUnitRunner >"$test_output" &
test_pid=$!

wait_for_marker() {
    local marker="$1"
    for attempt in {1..30}; do
        if adb logcat -d -s RingPassSmoke:I | grep -F "$marker" >/dev/null; then
            return 0
        fi
        if ! kill -0 "$test_pid" 2>/dev/null; then
            break
        fi
        sleep 1
    done
    cat "$test_output"
    adb logcat -d -s RingPassSmoke:I RingPassScreening:V RingPassAudio:V
    return 1
}

wait_for_marker READY_FOR_CALL
adb emu gsm call 6505550199
wait_for_marker READY_TO_END_CALL
adb emu gsm cancel 6505550199
wait "$test_pid"
cat "$test_output"
grep -F 'OK (1 test)' "$test_output"

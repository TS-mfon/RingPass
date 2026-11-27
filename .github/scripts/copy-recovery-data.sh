#!/usr/bin/env bash
# Run with an authorized ADB connection and both RingPass APKs installed.
set -euo pipefail

old_app=com.ringpass.app
recovery_app=com.ringpass.app.recovery

adb shell run-as "$old_app" test -s databases/ringpass.db
adb shell run-as "$recovery_app" test ! -e databases

adb shell am force-stop "$old_app"
adb shell am force-stop "$recovery_app"

backup_dir="$(mktemp -d)"
stage=""
cleanup() {
    rm -rf -- "$backup_dir"
    if [[ "$stage" =~ ^ringpass-transfer\.[[:alnum:]]+$ ]]; then
        adb shell run-as "$recovery_app" rm -rf -- "$stage" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

stage="$(adb shell run-as "$recovery_app" mktemp -d ringpass-transfer.XXXXXXXX)"
[[ "$stage" =~ ^ringpass-transfer\.[[:alnum:]]+$ ]]
for file in ringpass.db ringpass.db-wal ringpass.db-journal; do
    if ! adb shell run-as "$old_app" test -f "databases/$file"; then
        continue
    fi
    adb exec-out run-as "$old_app" cat "databases/$file" > "$backup_dir/$file"
    adb shell -T "run-as $recovery_app sh -c 'cat > $stage/$file'" < "$backup_dir/$file"
    adb exec-out run-as "$recovery_app" cat "$stage/$file" | cmp "$backup_dir/$file" -
done
test -s "$backup_dir/ringpass.db"
adb shell run-as "$recovery_app" test ! -e databases
adb shell run-as "$recovery_app" mv "$stage" databases
stage=""
echo "RingPass rules copied. Open RingPass Recovery and grant its permissions."

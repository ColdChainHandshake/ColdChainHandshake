# Gate 5 condition "the app survives rotation" as a script (Gate 5 re-check C7), on a phone or emulator from Git
# Bash. Rotates the screen to landscape and back, with the app open on a shipment, and checks from the layout dump
# (never a screenshot: a phone's status bar can show personal data) that the same shipment and the same texts are
# still on screen, and that the process didn't restart.
#
#   S=RZCY820AG0F; source tools/device/rotation_check.sh
#   rotation_check SHIP-123456          # the app open on SHIP-123456 (Transit or Handover)
#
# Prints PASS or FAIL with what differed. Leaves the phone as it found it: auto-rotate and the orientation are put
# back. Needs the `android` CLI on PATH (as drive.sh does).

DEVICE_TOOLS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
A="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}/platform-tools/adb.exe"
PKG="${PKG:-com.coldchain.handshake.debug}"

# The texts on screen, one per line, sorted, without the clock and other times that change by themselves.
_rotation_texts() {
    android layout --device="$S" 2>/dev/null | python -I "$DEVICE_TOOLS/list_screen.py" \
        | sed -E 's/ (\[|None).*$//' | grep -v -E "[0-9]{1,2}:[0-9]{2}" | sort -u
}

_set_rotation() {  # 0 portrait, 1 landscape
    "$A" -s "$S" shell settings put system accelerometer_rotation 0
    "$A" -s "$S" shell settings put system user_rotation "$1"
    sleep 3
}

rotation_check() {
    local shipment="$1"
    if [ -z "$S" ] || [ -z "$shipment" ]; then echo "usage: S=<serial>; rotation_check SHIP-ID"; return 2; fi
    local auto rot pid_before pid_after before after
    auto=$("$A" -s "$S" shell settings get system accelerometer_rotation | tr -d '\r')
    rot=$("$A" -s "$S" shell settings get system user_rotation | tr -d '\r')
    pid_before=$("$A" -s "$S" shell pidof "$PKG" | tr -d '\r')

    _set_rotation 0
    before=$(_rotation_texts)
    _set_rotation 1
    _rotation_texts > /dev/null   # drawn in landscape; read so a crash there shows in the logs
    _set_rotation 0
    after=$(_rotation_texts)
    pid_after=$("$A" -s "$S" shell pidof "$PKG" | tr -d '\r')

    "$A" -s "$S" shell settings put system user_rotation "${rot:-0}"
    "$A" -s "$S" shell settings put system accelerometer_rotation "${auto:-1}"

    local ok=1
    if [ -z "$pid_before" ] || [ "$pid_before" != "$pid_after" ]; then echo "FAIL: the process changed ($pid_before -> $pid_after)"; ok=0; fi
    if ! grep -qF "$shipment" <<<"$after"; then echo "FAIL: $shipment is no longer on screen"; ok=0; fi
    if [ "$before" != "$after" ]; then
        echo "FAIL: the screen differs after rotating back:"
        diff <(echo "$before") <(echo "$after") | head -20
        ok=0
    fi
    [ "$ok" = 1 ] && echo "PASS: $shipment and every text on screen survived landscape and back in the same process"
    [ "$ok" = 1 ]
}

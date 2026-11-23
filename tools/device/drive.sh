# Helpers for driving the debug app on a device or emulator from Git Bash, as used for the Gate 3 rehearsal
# (docs/agent/PROGRESS.md). Source it with S set to a serial from `adb devices`:
#
#   S=emulator-5554; source tools/device/drive.sh
#   fresh_app; sign_in courier@example.test; open_by_id SHIP-123456
#
# `sign_in` reads the code from the local stack's mail catcher (Mailpit, port 54324), so it only works against the
# local stack. For the hosted project, type the code from the real email with `focus_type`.
#
# Two emulator quirks it works around: `adb shell input text` goes nowhere unless the keyboard is really up, so
# `focus_type` taps until it is; and ESCAPE opens Gboard's handwriting panel, so the keyboard is closed with BACK,
# and only while it is showing (BACK with no keyboard leaves the app).
#
# Never use it on a phone someone is using, and don't keep screenshots that show the status bar or the keyboard's
# suggestions: they can show personal data.

DEVICE_TOOLS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
A="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}/platform-tools/adb.exe"
PKG=com.coldchain.handshake.debug

lay() { android layout --device="$S" 2>/dev/null; }
screen() { lay | python -I "$DEVICE_TOOLS/list_screen.py"; }

# tap <text> [nth] [seconds to wait after]
tap() {
    local xy
    xy=$(lay | python -I "$DEVICE_TOOLS/find_element.py" "$1" "${2:-0}") || { echo "TAP FAILED: $1"; return 1; }
    "$A" -s "$S" shell input tap $xy
    sleep "${3:-2}"
}

kb_off() {
    "$A" -s "$S" shell dumpsys input_method | grep -q 'mInputShown=true' && "$A" -s "$S" shell input keyevent KEYCODE_BACK
    sleep 1
}

# focus_type <x> <y> <text>: spaces as %s
focus_type() {
    for _ in 1 2 3 4; do
        "$A" -s "$S" shell input tap "$1" "$2"; sleep 2
        "$A" -s "$S" shell dumpsys input_method | grep -q 'mInputShown=true' && break
        "$A" -s "$S" shell input tap 540 150; sleep 1
    done
    "$A" -s "$S" shell input text "$3"; sleep 1
    kb_off
}

# A new install's state: clears storage (and with it the device ID and its Keystore key), grants the prompts.
fresh_app() {
    "$A" -s "$S" shell pm clear "$PKG" >/dev/null
    for p in POST_NOTIFICATIONS CAMERA; do "$A" -s "$S" shell pm grant "$PKG" "android.permission.$p"; done
    "$A" -s "$S" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    sleep 8
}

# code_for <email>: the newest 6-digit code the local mail catcher holds for it
code_for() {
    curl -s "http://127.0.0.1:54324/api/v1/search?query=to:$1" | python -I -c "
import sys, json, re, urllib.request
messages = json.load(sys.stdin)['messages']
text = json.load(urllib.request.urlopen('http://127.0.0.1:54324/api/v1/message/' + messages[0]['ID']))['Text']
print(re.search(r'Your code: (\d{6})', text).group(1))"
}

# sign_in <email>: an account that already has a profile, on the local stack
sign_in() {
    local xy code
    curl -s -X DELETE http://127.0.0.1:54324/api/v1/messages >/dev/null
    xy=$(lay | python -I "$DEVICE_TOOLS/find_element.py" Email) && focus_type $xy "$1"
    tap 'Send Code' 0 4
    code=$(code_for "$1")
    xy=$(lay | python -I "$DEVICE_TOOLS/find_element.py" Code) && focus_type $xy "$code"
    tap Verify 0 6
}

# open_by_id <shipment id>: through Transit's scanner text box, which a camera-less emulator needs
open_by_id() {
    tap Transit 0 3
    tap 'Scan Consignment QR' 0 4
    focus_type 540 2022 "$1"
    tap 'Apply Code' 0 6
}

# Gate 5 review S1 check, on a phone or emulator from Git Bash: a courier's phone recording with no signal has its
# process killed, and recording must carry on with no signal, then everything reaches the server once it returns.
#
# Only the app's own network is cut (Android 14 and later: the OEM_DENY_3 firewall chain), so the phone itself stays
# online and reachable. Needs the debug build: `run-as` only works on a debuggable app.
#
#   S=RZCY820AG0F; source tools/device/offline_restart.sh
#   app_offline                 # after taking custody on Transit
#   ... wait 50 minutes or more: the access token is then due for refresh (refreshed at 80 % of an hour online) ...
#   room_readings SHIP-123456   # count and newest reading in the phone's own database
#   kill_app; sleep 10; service_state; room_readings SHIP-123456   # again a few minutes later: still growing
#   app_online                  # then check the server: every reading, in order
#
# Never leaves the phone offline: app_online undoes app_offline, and only this app was cut.

DEVICE_TOOLS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
A="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}/platform-tools/adb.exe"
PKG="${PKG:-com.coldchain.handshake.debug}"

app_offline() {
    "$A" -s "$S" shell cmd connectivity set-chain3-enabled true
    "$A" -s "$S" shell cmd connectivity set-package-networking-enabled false "$PKG"
}

app_online() {
    "$A" -s "$S" shell cmd connectivity set-package-networking-enabled true "$PKG"
    "$A" -s "$S" shell cmd connectivity set-chain3-enabled false
}

app_pid() { "$A" -s "$S" shell pidof "$PKG" | tr -d '\r'; }

# A real kill, as Android's low-memory killer does it: the app's own uid may signal its process.
kill_app() {
    local pid
    pid=$(app_pid)
    case "$pid" in ''|*[!0-9]*) echo "no single pid for $PKG: '$pid'"; return 1 ;; esac
    "$A" -s "$S" shell run-as "$PKG" kill -9 "$pid"
    echo "killed $pid at $(date '+%H:%M:%S')"
}

service_state() {
    echo "pid $(app_pid)"
    "$A" -s "$S" shell dumpsys activity services "$PKG" |
        grep -o "isForeground=[a-z]* foregroundId=[0-9]* types=0x[0-9a-f]*" | head -1
}

# room_readings <shipment ID>: the readings this phone holds for it, read from a copy of its database.
room_readings() {
    case "$1" in ''|*[!A-Z0-9-]*) echo "refusing shipment ID: $1"; return 1 ;; esac
    local dir
    dir=$(mktemp -d)
    for f in coldchain_handshake.db coldchain_handshake.db-wal coldchain_handshake.db-shm; do
        "$A" -s "$S" exec-out run-as "$PKG" cat "databases/$f" > "$dir/$f" 2>/dev/null
    done
    python -I "$DEVICE_TOOLS/room_readings.py" "$dir/coldchain_handshake.db" "$1"
    rm -rf "$dir"
}

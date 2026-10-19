#!/bin/bash
# House Control TV from this computer.
#   ./tv.sh [-s DEVICE] build | install | pair | sync | autosync [install|stop|status] | phone | test | status
# DEVICE is the TV's adb address (192.168.1.50:5555) or serial. Default: $HOUSE_TV_DEVICE, else the only device adb sees.
set -euo pipefail
cd "$(dirname "$0")"
DEVICE="${HOUSE_TV_DEVICE:-}"
if [ "${1:-}" = "-s" ]; then DEVICE="$2"; shift 2; fi
ACTION="${1:-status}"; shift || true
APK=android/dist/house-control-tv.apk

die() { echo "house-tv: $*" >&2; exit 1; }
pick_device() {
    if [ -n "$DEVICE" ]; then
        case "$DEVICE" in *:*) adb connect "$DEVICE" >/dev/null 2>&1 || true ;; esac
        return
    fi
    local list; list="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
    [ -n "$list" ] || die "no TV found. Turn on ADB debugging on the TV, then: adb connect <TV IP>:5555"
    [ "$(echo "$list" | wc -l | tr -d ' ')" = "1" ] || die "several devices connected; choose one with -s DEVICE:
$list"
    DEVICE="$list"
}
build() {
    [ -d web/node_modules ] || (cd web && npm ci)
    (cd web && npm run build)
    bash android/build.sh
}

case "$ACTION" in
build) build ;;
install)
    pick_device
    [ -f "$APK" ] || build
    adb -s "$DEVICE" install --no-incremental -r "$APK" ;;
pair)
    # Sends the floor plan saved by House Control on this Mac, and a private key for the phone link and settings sync.
    pick_device
    python3 sync-tv.py --device "$DEVICE"
    echo "Paired. Keep the TV's settings in step with the Mac: ./tv.sh autosync install" ;;
sync)
    python3 sync_mac.py once ;;
autosync)
    python3 sync_mac.py "${1:-status}" ;;
phone)
    python3 tools/open_phone_link.py ;;
test)
    bash test-standalone.sh && python3 -m unittest discover -s tests -p 'test_*.py' ;;
status)
    pick_device
    echo "TV:            $(adb -s "$DEVICE" shell getprop ro.product.manufacturer | tr -d '\r') $(adb -s "$DEVICE" shell getprop ro.product.model | tr -d '\r')"
    echo "House Control: $(adb -s "$DEVICE" shell dumpsys package dev.housecontrol.tv | tr -d '\r' | sed -n 's/^ *versionName=//p' | head -n 1 | grep . || echo 'not installed')"
    echo "Paired Mac:    $([ -f .local/tv-direct.json ] && echo yes || echo 'no (./tv.sh pair)')" ;;
*)
    die "unknown command '$ACTION'. Use build, install, pair, sync, autosync, phone, test or status." ;;
esac

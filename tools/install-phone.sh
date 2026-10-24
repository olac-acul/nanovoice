#!/usr/bin/env bash
# The owner's development builds: build the app and install it on the owner's phone over the app that is already
# there (its data is kept), then let Android compile it ahead of time (the first start is much faster). These builds
# are signed with the DEVELOPMENT key and show "-dev" after the version; what other people get is made by
# tools/release.sh with the release key. Settings live in `.dev-env` (untracked): JAVA_HOME, ANDROID_HOME,
# ANDROID_SERIAL and NANOVOICE_KEYSTORE (the development key).
#
#   tools/install-phone.sh            build, check the signature, install
#   tools/install-phone.sh --check    only say whether the phone is reachable and what is installed
#
# When the phone does not answer on ANDROID_SERIAL it looks for it on USB or wireless debugging and opens port 5555
# again; if it finds no phone it says how to connect one.
#
# Before it installs anything it checks that the APK is signed by the key in tools/dev-cert.sha256: with another
# key Android would refuse to update, and the only way forward would be to uninstall (which erases the app's data).
# This script never uninstalls.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .dev-env ] && . ./.dev-env
die() { echo "install: $*" >&2; exit 1; }

command -v adb > /dev/null || die "adb not found: set ANDROID_HOME in .dev-env"
command -v apksigner > /dev/null || die "apksigner not found: put the Android build-tools on PATH in .dev-env"

how_to_connect() {
  cat >&2 << 'HOW'
install: the phone is not connected. Connect it, then run this again:
  - through the VPN: the VPN (Tailscale) on, and the phone awake and unlocked once;
  - same Wi-Fi as this computer: on the phone, Developer options -> Wireless debugging -> on
    (the first time only, pair this computer: "Pair device with pairing code", then: adb pair <ip>:<pairing port>);
  - or plug in the USB cable and allow debugging on the phone.
HOW
  exit 1
}

# The phone answers on ANDROID_SERIAL (host:5555, through the VPN): fine. Otherwise the fixed port was closed (a
# reboot of the phone does that): find the phone another way (USB, or wireless debugging on this network, whose port
# changes every time), open port 5555 again through it, and connect as usual.
# adb waits a long time for a phone that does not answer (asleep, VPN off): every call that reaches it has a limit.
state() { timeout 5 adb get-state > /dev/null 2>&1; }
reach() { timeout 10 adb connect "$1" > /dev/null 2>&1 || true; }

ensure_phone() {
  local serial="${ANDROID_SERIAL:-}"
  [ -n "$serial" ] || { state && return 0; how_to_connect; }
  state && return 0
  if [[ "$serial" == *:* ]]; then
    echo "install: connecting to $serial..."
    # A connection left "offline" (the phone slept or changed network) is dropped first, or adb keeps it.
    timeout 5 adb disconnect "$serial" > /dev/null 2>&1 || true
    reach "$serial"
    state && return 0
  fi
  echo "install: no answer on $serial; looking for the phone on USB or wireless debugging..."
  # Wireless debugging announces itself on the local network; connecting works when this computer was paired once.
  local found
  found=$(timeout 8 adb mdns services 2> /dev/null | awk '$2 ~ /_adb-tls-connect/ {print $3}' | sort -u || true)
  for address in $found; do reach "$address"; done
  local candidates
  candidates=$(adb devices | awk 'NR > 1 && $2 == "device" {print $1}' | grep -v -x -F "$serial" || true)
  case "$(printf '%s' "$candidates" | grep -c .)" in
    0)
      [ -n "$found" ] && echo "install: wireless debugging found, but this computer is not paired with the phone yet." >&2
      how_to_connect
      ;;
    1) ;;
    *) die "several phones are connected ($(echo $candidates)): set ANDROID_SERIAL to the one to use" ;;
  esac
  if [[ "$serial" != *:5555 ]]; then
    ANDROID_SERIAL="$candidates"
    export ANDROID_SERIAL
    return 0
  fi
  echo "install: phone found at $candidates; opening port 5555 again (until the phone restarts)"
  ANDROID_SERIAL="$candidates" adb tcpip 5555 > /dev/null
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    sleep 1
    reach "$serial"
    state && return 0
  done
  die "port 5555 did not open on the phone; run this again, or set ANDROID_SERIAL=$candidates for this time"
}

ensure_phone
echo "phone: $(adb shell getprop ro.product.model | tr -d '\r')"
info() { adb shell dumpsys package dev.nanovoice 2> /dev/null | tr -d '\r' | sed -n "s/^ *$1=//p" | head -1; }
echo "installed: version $(info versionName), first installed $(info firstInstallTime), updated $(info lastUpdateTime)"
[ "${1:-}" = "--check" ] && exit 0

if [ -n "${NANOVOICE_KEYSTORE:-}" ]; then
  [ -f "$NANOVOICE_KEYSTORE" ] || die "NANOVOICE_KEYSTORE points to a file that does not exist"
  if [ -z "${NANOVOICE_STORE_PASSWORD:-}" ]; then
    read -r -s -p "Development keystore password: " NANOVOICE_STORE_PASSWORD
    echo
  fi
  export NANOVOICE_KEYSTORE NANOVOICE_STORE_PASSWORD
  echo "signing with the development key"
else
  die "NANOVOICE_KEYSTORE is not set (put it in .dev-env): the app on the phone is signed with the development key, and an APK signed with another key cannot replace it"
fi

NANOVOICE_VERSION_SUFFIX=-dev ./gradlew --no-daemon :app:assembleRelease
unset NANOVOICE_STORE_PASSWORD
apk=app/build/outputs/apk/release/app-release.apk
cert=$(apksigner verify --print-certs "$apk" 2> /dev/null | sed -n 's/.*certificate SHA-256 digest: //p' | head -1 | tr 'A-F' 'a-f')
expected=$(tr -d ' :\n' < tools/dev-cert.sha256 | tr 'A-F' 'a-f')
[ "$cert" = "$expected" ] || die "the APK is signed by $cert, not by the key on the phone ($expected): NOT installing"
echo "signature matches the key of the installed app"

adb install -r "$apk"
adb shell cmd package compile -m speed -f dev.nanovoice
echo "now: version $(info versionName), first installed $(info firstInstallTime), updated $(info lastUpdateTime)"
echo "(an unchanged 'first installed' time means the app was updated in place and kept its data)"

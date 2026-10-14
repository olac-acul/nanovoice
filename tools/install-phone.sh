#!/usr/bin/env bash
# Build the release and install it on the phone over the app that is already there (its data is kept), then let
# Android compile it ahead of time (the first start is much faster). Settings live in `.dev-env` (untracked):
# JAVA_HOME, ANDROID_HOME, ANDROID_SERIAL and, to sign with the release key, NANOVOICE_KEYSTORE.
#
#   tools/install-phone.sh            build, check the signature, install
#   tools/install-phone.sh --check    only say whether the phone is reachable and what is installed
#
# Before it installs anything it checks that the APK is signed by the key in tools/release-cert.sha256: with another
# key Android would refuse to update, and the only way forward would be to uninstall (which erases the app's data).
# This script never uninstalls.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .dev-env ] && . ./.dev-env
die() { echo "install: $*" >&2; exit 1; }

command -v adb > /dev/null || die "adb not found: set ANDROID_HOME in .dev-env"
adb get-state > /dev/null 2>&1 || die "no phone reachable through adb (ANDROID_SERIAL=${ANDROID_SERIAL:-unset})"
echo "phone: $(adb shell getprop ro.product.model | tr -d '\r')"
info() { adb shell dumpsys package dev.nanovoice 2> /dev/null | tr -d '\r' | sed -n "s/^ *$1=//p" | head -1; }
echo "installed: version $(info versionName), first installed $(info firstInstallTime), updated $(info lastUpdateTime)"
[ "${1:-}" = "--check" ] && exit 0

if [ -n "${NANOVOICE_KEYSTORE:-}" ]; then
  [ -f "$NANOVOICE_KEYSTORE" ] || die "NANOVOICE_KEYSTORE points to a file that does not exist"
  if [ -z "${NANOVOICE_STORE_PASSWORD:-}" ]; then
    read -r -s -p "Release keystore password: " NANOVOICE_STORE_PASSWORD
    echo
  fi
  export NANOVOICE_KEYSTORE NANOVOICE_STORE_PASSWORD
  echo "signing with the release keystore"
else
  echo "no NANOVOICE_KEYSTORE: signing with the local debug key"
fi

./gradlew --no-daemon :app:assembleRelease
unset NANOVOICE_STORE_PASSWORD
apk=app/build/outputs/apk/release/app-release.apk
cert=$(apksigner verify --print-certs "$apk" 2> /dev/null | sed -n 's/.*certificate SHA-256 digest: //p' | head -1 | tr 'A-F' 'a-f')
expected=$(tr -d ' :\n' < tools/release-cert.sha256 | tr 'A-F' 'a-f')
[ "$cert" = "$expected" ] || die "the APK is signed by $cert, not by the key on the phone ($expected): NOT installing"
echo "signature matches the key of the installed app"

adb install -r "$apk"
adb shell cmd package compile -m speed -f dev.nanovoice
echo "now: version $(info versionName), first installed $(info firstInstallTime), updated $(info lastUpdateTime)"
echo "(an unchanged 'first installed' time means the app was updated in place and kept its data)"

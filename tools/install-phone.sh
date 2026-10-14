#!/bin/sh
# Build the release and install it on the phone, then let Android compile it ahead of time (the first start is
# much faster). Settings live in a personal, untracked file: `.dev-env` (JAVA_HOME, ANDROID_HOME, ANDROID_SERIAL).
#
#   tools/install-phone.sh            install over the app that is already there (keeps its data)
#   tools/install-phone.sh --check    only say whether the phone is reachable and which key signed its app
set -e
cd "$(dirname "$0")/.."
[ -f .dev-env ] && . ./.dev-env
command -v adb > /dev/null || { echo "adb not found: set ANDROID_HOME in .dev-env"; exit 1; }
adb get-state > /dev/null 2>&1 || { echo "no phone reachable through adb (ANDROID_SERIAL=${ANDROID_SERIAL:-unset})"; exit 1; }
echo "phone: $(adb shell getprop ro.product.model | tr -d '\r')"
installed=$(adb shell dumpsys package dev.nanovoice 2> /dev/null | sed -n 's/.*versionName=//p' | head -1 | tr -d '\r')
echo "installed version: ${installed:-none}"
[ "$1" = "--check" ] && exit 0
./gradlew :app:installRelease
adb shell cmd package compile -m speed -f dev.nanovoice
echo "installed and compiled"

#!/usr/bin/env bash
# Make the release key: the one that signs every APK given to other people (GitHub releases, other phones).
# Run it once, in a real terminal: keytool asks the password itself, hidden, twice.
#
#   tools/new-release-key.sh [path]          default path: ~/.config/nanovoice/nanovoice-release.p12
#
# It refuses to replace an existing key: a phone accepts an update only when it is signed by the same key as the app
# it has, so losing or replacing this key means every user has to uninstall. Keep a copy of the file AND its
# password somewhere else (a password manager, an encrypted backup) right after.
#
# The certificate carries only "CN=NanoVoice": it is public, inside every APK. Its SHA-256 is written to
# tools/release-cert.sha256, which tools/release.sh checks before publishing; commit that file.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .dev-env ] && . ./.dev-env
die() { echo "new-release-key: $*" >&2; exit 1; }

keystore="${1:-$HOME/.config/nanovoice/nanovoice-release.p12}"
keytool="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
command -v "$keytool" > /dev/null || die "keytool not found: set JAVA_HOME in .dev-env"
[ -t 0 ] || die "run it in a real terminal: keytool asks the password there"
[ -e "$keystore" ] && die "$keystore already exists: this script never replaces a release key"

mkdir -p "$(dirname "$keystore")"
chmod 700 "$(dirname "$keystore")"
umask 077
echo "Choose the release key's password (at least 12 characters; keytool asks it twice, hidden)."
"$keytool" -genkeypair -keystore "$keystore" -storetype PKCS12 -alias nanovoice \
  -keyalg RSA -keysize 4096 -validity 10950 -dname "CN=NanoVoice"
chmod 600 "$keystore"

echo
echo "Now the same password once more, to read the certificate back:"
sha=$("$keytool" -list -v -keystore "$keystore" -storetype PKCS12 -alias nanovoice \
  | sed -n 's/^[[:space:]]*SHA256: //p' | head -1 | tr -d ':' | tr 'A-F' 'a-f')
[ ${#sha} -eq 64 ] || die "could not read the certificate's SHA-256 (the key is in $keystore)"
echo "$sha" > tools/release-cert.sha256

echo
echo "Release key: $keystore"
echo "Certificate SHA-256: $sha (written to tools/release-cert.sha256: commit it)"
echo "Add to .dev-env:  export NANOVOICE_RELEASE_KEYSTORE=$keystore"
echo "Then make a copy of the file and of its password, kept somewhere else."

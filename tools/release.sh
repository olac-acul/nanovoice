#!/usr/bin/env bash
# Build, sign and publish a pre-release (beta) of the app on GitHub, signing on this machine only.
#
#   tools/release.sh
#
# It signs with the RELEASE key only (made once with tools/new-release-key.sh), never with the development key the
# owner's own phone uses (tools/install-phone.sh): every APK given to someone else is signed by the release key.
#
# What it needs, in `.dev-env` (untracked): JAVA_HOME, ANDROID_HOME, and NANOVOICE_RELEASE_KEYSTORE, the path of the
# release keystore. The keystore password is asked here and handed to Gradle through the environment: it is
# never written to a file or put on a command line.
#
# It refuses to go on unless: the working tree is clean and is the tip of origin/main, the version has no release yet
# and its versionCode is above the last release's, the CI ("android") passed on this very commit, the tests and lint
# pass here too, the APK carries the licence texts and is signed by the key whose SHA-256 is in
# tools/release-cert.sha256, and `gh` can write to this repository.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .dev-env ] && . ./.dev-env
die() { echo "release: $*" >&2; exit 1; }

command -v gh > /dev/null || die "gh is not installed"
command -v apksigner > /dev/null || die "apksigner not found: set ANDROID_HOME in .dev-env"
[ -f tools/release-cert.sha256 ] || die "there is no release key yet: make it with tools/new-release-key.sh"
[ -n "${NANOVOICE_RELEASE_KEYSTORE:-}" ] && [ -f "$NANOVOICE_RELEASE_KEYSTORE" ] || die "set NANOVOICE_RELEASE_KEYSTORE in .dev-env to the release keystore"
[ -z "$(git status --porcelain)" ] || die "there are uncommitted changes"
git fetch -q --tags origin
[ -z "$(git log origin/main..HEAD --oneline)" ] || die "there are commits that are not pushed"
# What is released is what is on main now: not an older commit, not a branch.
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || die "HEAD is not the tip of origin/main: check out main and pull"

version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)
[ -n "$version" ] || die "versionName not found"
tag="v$version"
git rev-parse -q --verify "refs/tags/$tag" > /dev/null && die "$tag already exists: raise versionName and versionCode first"
# Android installs an update only over a lower versionCode: the app would offer it and then fail to install it.
code=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)
last=$(git tag -l 'v*' --sort=-v:refname | head -1)
if [ -n "$last" ]; then
  last_code=$(git show "$last:app/build.gradle.kts" | sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' | head -1)
  [ -n "$code" ] && [ -n "$last_code" ] && [ "$code" -gt "$last_code" ] ||
    die "versionCode $code is not above $last_code of $last: raise it first"
fi
# The CI must have passed on this very commit (not on an earlier one).
ci=$(gh run list --commit "$(git rev-parse HEAD)" --workflow android.yml --json status,conclusion -q '.[0] | "\(.status) \(.conclusion)"' 2> /dev/null || true)
case "$ci" in
  "completed success") ;;
  "") die "the CI has not run on this commit yet: push it and wait" ;;
  completed*) die "the CI failed on this commit ($ci): see 'gh run list'" ;;
  *) die "the CI is still running on this commit: try again when it is done" ;;
esac
case "$(gh repo view --json viewerPermission -q .viewerPermission 2> /dev/null)" in
  ADMIN | MAINTAIN | WRITE) ;;
  *) die "gh cannot write to this repository: run 'gh auth switch' to the account that owns it" ;;
esac

# The tests and lint here too, before the password is asked: a failure costs nothing.
./gradlew --no-daemon -q test lint || die "the tests or lint fail here: not releasing"

read -r -s -p "Release keystore password: " NANOVOICE_STORE_PASSWORD
echo
export NANOVOICE_STORE_PASSWORD
export NANOVOICE_KEYSTORE="$NANOVOICE_RELEASE_KEYSTORE"
unset NANOVOICE_VERSION_SUFFIX

./gradlew --no-daemon :app:assembleRelease
unset NANOVOICE_STORE_PASSWORD

apk=app/build/outputs/apk/release/app-release.apk
[ -f "$apk" ] || die "the build produced no APK"
# The licences of the libraries must go with the app (Impostazioni, Licenze).
unzip -l "$apk" | grep -q 'assets/licenses/NOTICE.txt' || die "the APK has no licence texts (assets/licenses): not publishing"
cert=$(apksigner verify --print-certs "$apk" 2> /dev/null | sed -n 's/.*certificate SHA-256 digest: //p' | head -1 | tr 'A-F' 'a-f')
expected=$(tr -d ' :\n' < tools/release-cert.sha256 | tr 'A-F' 'a-f')
[ "$cert" = "$expected" ] || die "the APK is signed by $cert, expected $expected: not publishing"

mkdir -p build/release
out="build/release/nanovoice-$version.apk"
cp "$apk" "$out"
sum=$(sha256sum "$out" | cut -d' ' -f1)
echo "$sum  nanovoice-$version.apk" > "$out.sha256"

notes="build/release/notes-$version.md"
cat > "$notes" <<NOTES
**Beta $version.** Prova pubblica anticipata: funziona, ma può cambiare senza preavviso.

Firmata con la chiave di rilascio definitiva: le versioni successive si installano sopra questa senza perdere i dati.

Controlli prima di installare:
- SHA-256 dell'APK: \`$sum\`
- SHA-256 del certificato di firma: \`$cert\`
  (\`apksigner verify --print-certs nanovoice-$version.apk\`)

Serve la skill \`add-voice\` di [skills-for-nanoclaw](https://github.com/olac-acul/skills-for-nanoclaw) sul server NanoClaw.
Richiede Android 8 o successivo e un telefono arm64.
NOTES

echo
echo "APK:         $out ($(du -h "$out" | cut -f1))"
echo "APK SHA-256: $sum"
echo "Certificate: $cert (as expected)"
read -r -p "Publish $tag as a pre-release on GitHub? [y/N] " answer
[ "$answer" = "y" ] || { echo "not published; the files are in build/release/"; exit 0; }
gh release create "$tag" "$out" "$out.sha256" --prerelease --target "$(git rev-parse HEAD)" --title "NanoVoice $version (beta)" --notes-file "$notes"
echo "published $tag"

#!/usr/bin/env bash
# Builds the signed SumBuddy release APK for GitHub Releases (docs/releasing.md). Run from anywhere, on the
# maintainer's Mac only: the signing key lives outside the repository (app/build.gradle.kts, "Release signing").
#
#   scripts/release.sh
#
# Steps: checks for a clean tree on main whose commit is already on the public repository (origin), runs the JVM
# tests, builds the signed release, checks with apksigner that the APK is signed with the release certificate (stops
# otherwise), copies it to build/release/sumbuddy-<versionName>.apk and build/release/sumbuddy.apk, writes
# build/release/SHA256SUMS and prints the gh command that publishes them, tagged at exactly that commit.
# It never reads, prints or copies the keystore or its password; Gradle reads them itself.
#
# Environment (optional): JAVA_HOME, ANDROID_HOME, and GRADLE, the command that runs Gradle (default ./gradlew).
set -euo pipefail

# SHA-256 of the certificate releases are signed with (public). With colons, it is the value ANDROID_CERT_SHA256 of
# the friends' page needs for App Links (docs/releasing.md).
EXPECTED_CERT_SHA256="11d2e03bb39f17d8545dd77c073bb2c6f63ca898c42cf42878155bec616f9a8b"
REPO="akhilux-design/sumbuddy"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
read -r -a GRADLE_CMD <<<"${GRADLE:-./gradlew}"

die() {
  echo "release: $*" >&2
  exit 1
}

# 1. A clean tree on main, already pushed to the public repository, so the APK is exactly a commit anyone can see.
branch="$(git rev-parse --abbrev-ref HEAD)"
[ "$branch" = "main" ] || die "on branch '$branch': releases are built from main"
[ -z "$(git status --porcelain)" ] || die "the working tree has changes or untracked files: commit or stash them first"
origin="$(git remote get-url origin 2>/dev/null)" || die "no remote 'origin': releases are made from a clone of $REPO"
case "$origin" in
  *"github.com:$REPO"|*"github.com:$REPO.git"|*"github.com/$REPO"|*"github.com/$REPO.git") ;;
  *) die "origin is $origin, not $REPO: releases are made from a clone of the public repository" ;;
esac
git fetch --quiet origin || die "cannot fetch origin"
[ -n "$(git branch -r --contains HEAD --list 'origin/*')" ] || die "origin does not have this commit yet: push main first"
commit="$(git rev-parse --short HEAD)"
fullCommit="$(git rev-parse HEAD)"

# 2. The version, from app/build.gradle.kts.
versionName="$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)"[[:space:]]*$/\1/p' app/build.gradle.kts | head -n 1)"
versionCode="$(sed -n 's/^[[:space:]]*versionCode = \([0-9][0-9]*\)[[:space:]]*$/\1/p' app/build.gradle.kts | head -n 1)"
[ -n "$versionName" ] && [ -n "$versionCode" ] || die "cannot read versionName and versionCode from app/build.gradle.kts"
[[ "$versionName" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "versionName '$versionName' is not like 1.2.3"
notes="fastlane/metadata/android/en-US/changelogs/$versionCode.txt"
echo "release: SumBuddy $versionName (versionCode $versionCode) from $commit"

# 3. Tests, then the signed release. The old output goes first, so a stale signed APK can never be picked up.
"${GRADLE_CMD[@]}" :parser:test :splits:test :splitwise:test :app:testDebugUnitTest
rm -rf app/build/outputs/apk/release
"${GRADLE_CMD[@]}" :app:assembleRelease
apk="app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$apk" ]; then
  [ -f "app/build/outputs/apk/release/app-release-unsigned.apk" ] &&
    die "the APK is unsigned: the release keystore was not found (app/build.gradle.kts, \"Release signing\")"
  die "no APK at $apk"
fi

# 4. The signature: valid, and made with the release certificate only.
apksigner="$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -n 1)"
[ -n "$apksigner" ] && [ -x "$apksigner" ] || die "apksigner not found under $ANDROID_HOME/build-tools"
certs="$("$apksigner" verify --print-certs "$apk")" || die "apksigner says the signature of $apk is not valid"
digests="$(printf '%s\n' "$certs" | sed -n 's/^Signer #[0-9]* certificate SHA-256 digest: \([0-9a-fA-F]*\)$/\1/p' |
  tr 'A-F' 'a-f' | sort -u)"
[ -n "$digests" ] || die "apksigner printed no certificate SHA-256 for $apk"
if [ "$digests" != "$EXPECTED_CERT_SHA256" ]; then
  echo "release: expected certificate SHA-256 $EXPECTED_CERT_SHA256" >&2
  printf 'release: the APK is signed with %s\n' $digests >&2
  die "wrong signing certificate: nothing was copied"
fi
echo "release: signature checked (certificate SHA-256 $EXPECTED_CERT_SHA256)"

# 5. The files to publish.
out="build/release"
mkdir -p "$out"
rm -f "$out"/sumbuddy*.apk "$out/SHA256SUMS"
cp "$apk" "$out/sumbuddy-$versionName.apk"
cp "$apk" "$out/sumbuddy.apk"
(cd "$out" && shasum -a 256 "sumbuddy-$versionName.apk" sumbuddy.apk >SHA256SUMS)
echo "release: wrote $out/sumbuddy-$versionName.apk, $out/sumbuddy.apk and $out/SHA256SUMS:"
sed 's/^/  /' "$out/SHA256SUMS"

[ -f "$notes" ] || echo "release: warning: $notes is missing; write the release notes there before publishing" >&2

# 6. Publishing is a separate, deliberate step.
cat <<EOF

To publish, from $ROOT:

  gh release create v$versionName \\
    $out/sumbuddy-$versionName.apk $out/sumbuddy.apk $out/SHA256SUMS \\
    --repo $REPO --target $fullCommit --title "SumBuddy $versionName" \\
    --notes-file $notes
EOF

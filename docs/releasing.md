# Releasing SumBuddy

SumBuddy is not on Google Play (decided 2026-11-20). Each release is a signed APK on GitHub Releases in
`akhilux-design/sumbuddy`, which Obtainium and Orion Store read. F-Droid's main repository comes later, with a build
without Google Play services and Firebase, which it does not allow. The newest APK is always at
https://github.com/akhilux-design/sumbuddy/releases/latest/download/sumbuddy.apk , the address the friends' page uses
for "Get SumBuddy" (`APP_DOWNLOAD_URL`, `docs/server-setup.md` 4.3), so the page needs no new deploy for a release.

Releases are signed on the maintainer's Mac only. `app/build.gradle.kts` reads the keystore settings from
`~/.xpense-release/keystore.properties` (or the Gradle property `app.release.keystoreProperties`). Nothing from that
folder goes into the repository, a log or a chat. Anyone else who builds from source gets an unsigned APK.

From the first public commit on, work and releases happen in a clone of `akhilux-design/sumbuddy`, with that public
repository as `origin`. A release is built only from a commit `origin` already has, and its tag is made at exactly
that commit, so the APK always matches source anyone can see.

Debug builds have their own app id, `com.akhilux.xpense.debug` (version name ending in `-debug`). The release key is
the same certificate, so without the suffix a debug APK could install over the public app; with it, the two are
separate apps and a debug APK can never replace a release install.

## Steps

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts` (the first public release is versionCode 2,
   versionName 0.2.0).
2. Write the release notes in `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
3. Commit on `main` and push it to `origin` (the public repository). The tree must be clean, with no untracked
   files.
4. Run `scripts/release.sh`. It:
   - stops unless the tree is clean and on `main`, `origin` is `akhilux-design/sumbuddy`, and `origin` already has
     this commit (`git branch -r --contains HEAD`);
   - runs the JVM tests (`:parser:test :splits:test :splitwise:test :app:testDebugUnitTest`);
   - builds the signed release (`:app:assembleRelease`) and stops if it comes out unsigned;
   - checks with `apksigner` that the signature is valid and that the signing certificate's SHA-256 is
     `11d2e03bb39f17d8545dd77c073bb2c6f63ca898c42cf42878155bec616f9a8b`, and stops otherwise, before copying anything;
   - writes `build/release/sumbuddy-<versionName>.apk`, `build/release/sumbuddy.apk` (the same file) and
     `build/release/SHA256SUMS`;
   - prints the `gh release create v<versionName> ... --target <commit> --notes-file fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`
     command, with `--target` set to the full id of the commit it built (`git rev-parse HEAD`), so the tag lands on
     that commit and not on whatever `main` is later. It publishes nothing itself.

   It never prints the keystore password. `GRADLE="<command>"` replaces `./gradlew` if needed.
5. Read the printed command, then run it. `sumbuddy.apk` must be attached to every release: the `latest/download`
   address above depends on that file name.

## App Links

Friends' links open in the app only when the page's `assetlinks.json` lists the signing certificate. The page deploy
(`docs/server-setup.md` 4.3) needs it in `ANDROID_CERT_SHA256` in colon form:
`11:D2:E0:3B:B3:9F:17:D8:54:5D:D7:7C:07:3B:B2:C6:F6:3C:A8:98:C4:2C:F4:28:78:15:5B:EC:61:6F:9A:8B`.
A new signing key would need a page deploy first, and installed apps could not update to an APK signed with it.

## Where releases are listed

Obtainium, Orion Store and, later, F-Droid: `docs/publishing.md`.

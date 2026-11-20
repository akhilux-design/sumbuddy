# Publishing SumBuddy

Step by step for the maintainer: the public repository, a release, and the places that list the app. Decided
2026-11-20:

- SumBuddy is not on Google Play. It is open source (`GPL-3.0-or-later`) and ships as a signed APK on **GitHub
  Releases**, which **Obtainium** and **Orion Store** read. These three are the targets now.
- **F-Droid**'s main repository comes later, with a `foss` flavour without Google sign-in and Firebase (section 6).
- **IzzyOnDroid** is not a target (section 7).

Store and policy pages change: check each step against the page itself when you do it.

## 0. Before the first public release

These are not in the repository; check each once.

- **Signing key.** Decided 2026-11-20: releases stay signed with the current key, the certificate with SHA-256
  `11d2e03b…9a8b` that `scripts/release.sh` checks ("CN=Android Debug, O=Android, C=US"). Installed apps can only
  update to an APK signed with the same key, so it stays for good. Debug builds have their own app id
  (`com.akhilux.xpense.debug`), so a debug APK, although signed with the same certificate, installs as a separate app
  and can never replace the public one.
- **Google sign-in for everyone.** Google Auth Platform > Audience > Publish app, and brand verification with the home
  page and the privacy policy (`docs/server-setup.md` 7.2). The Branding app name should read SumBuddy, since people
  see it on the sign-in sheet. The policy is `PRIVACY.md`; once the repository is public its address is
  `https://github.com/akhilux-design/sumbuddy/blob/main/PRIVACY.md`.
- **Push and sign-in for the release key.** If the release certificate differs from the one whose Android client is
  in `GOOGLE_ANDROID_CLIENT_IDS` and on the FCM API key, add it (`docs/server-setup.md` 7.1).
- **Friends' page.** Deploy it with `APP_DOWNLOAD_URL=https://github.com/akhilux-design/sumbuddy/releases/latest/download/sumbuddy.apk`
  and the release certificate in `ANDROID_CERT_SHA256` (`docs/server-setup.md` 4.3), so "Get SumBuddy" works and links
  open in the app.

## 1. The public repository

The public repository starts with one clean commit, made from the tracked files only, never by copying the working
folder (that would carry `build/`, `CLAUDE.local.md`, `.claude/`, `local.properties` and other private files). First,
in this folder, `git status --porcelain` must print nothing: anything not committed (for example a design export)
would be left out of the archive.

```bash
git status --porcelain                # must print nothing
mkdir ../sumbuddy-public
git archive --format=tar HEAD | tar -x -C ../sumbuddy-public
cd ../sumbuddy-public
git init -b main
git config user.name "<name to show>"
git config user.email "<id>+<user>@users.noreply.github.com"   # GitHub's noreply address keeps the email private
git add -A
git commit -m "SumBuddy 0.2.0"
gh repo create akhilux-design/sumbuddy --private --source . --push \
  --description "Your month added up from bank SMS on your phone. Private. Splits with friends."
```

The repository starts private: look through it on GitHub once, then make it public (Settings > General > Danger
Zone > Change visibility).

**From the first public commit on, work and releases happen with the public repository as `origin`:** in
`../sumbuddy-public` or another clone of `akhilux-design/sumbuddy`. The old private repository keeps the history
before that commit and gets no new work. `scripts/release.sh` refuses to build unless `origin` is
`akhilux-design/sumbuddy` and already has `HEAD` (`git branch -r --contains HEAD`), and the `gh release create`
command it prints passes `--target` with that commit, so every release tag points at a commit anyone can see.

Then on GitHub:

- Settings > General: Issues on; Wikis off unless wanted.
- Settings > Code security: turn on **Private vulnerability reporting** (`SECURITY.md` points at it).
- About: topics such as `android`, `expense-tracker`, `sms`, `upi`, `india`, `split-bills`, `open-source`.
- Check that `LICENSE` is detected as GPL-3.0 on the repository page (GitHub reads the licence text, which is the
  same for "only" and "or later"; the README says or later).

## 2. A release

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts` (0.2.0 is versionCode 2).
2. Write `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (at most 500 characters) and a section in
   `CHANGELOG.md`.
3. Commit on `main` and push to `origin` (the public repository); the tree must be clean.
4. Run `scripts/release.sh` on the maintainer's Mac (`docs/releasing.md`). It checks that `origin` has the commit,
   runs the tests, builds and signs the release, checks the certificate, and writes
   `build/release/sumbuddy-<versionName>.apk`, `build/release/sumbuddy.apk` and `build/release/SHA256SUMS`. It
   publishes nothing.
5. Run the `gh release create v<versionName> ... --target <commit>` command it prints. The tag must be `v` plus
   `versionName`, so Obtainium and the stores can match tags to versions. `sumbuddy.apk` must be attached to every
   release: the `latest/download/sumbuddy.apk` address on the friends' page and in the README depends on that file
   name.
6. Open <https://github.com/akhilux-design/sumbuddy/releases/latest/download/sumbuddy.apk> and check that it serves the
   new file (compare its SHA-256 with `SHA256SUMS`).

## 3. Obtainium

Nothing to submit. People add the repository URL in Obtainium (Add App > `https://github.com/akhilux-design/sumbuddy`)
and it installs and updates from GitHub Releases. Each release has two identical APKs; Obtainium may ask which one to
use, and either is right. If that confuses people, attach only `sumbuddy.apk` (the friends' page needs that name) and
leave out `sumbuddy-<version>.apk`.

## 4. Permissions

Every permission in the release APK's merged manifest, and why (the same list is in the README). Stores and
reviewers ask for this.

| Permission | Added by | Why |
|---|---|---|
| `READ_SMS` | the app | Reads the bank and UPI SMS already on the phone to find payments, on the phone only. Optional: say no and add payments by hand. |
| `RECEIVE_SMS` | the app | Reads a new bank SMS when it arrives, to show "Payment detected". Optional, as above. |
| `POST_NOTIFICATIONS` | the app | Notifications for a payment found and for changes in your groups (asked for on Android 13 and later). |
| `INTERNET` | the app | Only for sign-in, splitting, Family and Import from Splitwise: the SumBuddy server, Google sign-in and push. |
| `ACCESS_NETWORK_STATE` | the app (also WorkManager, Firebase Messaging) | Syncs groups only when there is a network, and shows when you are offline. |
| `WAKE_LOCK` | WorkManager, Firebase Messaging | Keeps the phone awake for the moment background work runs: reading a new SMS, a group sync, a push message. |
| `RECEIVE_BOOT_COMPLETED` | WorkManager | Puts waiting background work (an SMS still to read, the 12-hour group sync) back in the queue after a restart. |
| `FOREGROUND_SERVICE` | WorkManager | On Android 11 and older, urgent background work (reading SMS) runs as a short foreground service with a quiet "reading" notification that shows no message content. |
| `com.google.android.c2dm.permission.RECEIVE` | Firebase Cloud Messaging | Receives push messages ("group X changed") through Google Play services, only after you sign in. |
| `com.akhilux.xpense.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | AndroidX Core | The app's own signature permission, which keeps its internal broadcast receivers private on older Android versions. No other app can hold it. |

The Credential Manager library would also add `USE_BIOMETRIC` and `USE_FINGERPRINT` (for passkeys). SumBuddy signs
in with Google only, so `app/src/main/AndroidManifest.xml` removes both.

## 5. Orion Store

Orion Store (<https://github.com/RookieEnough/Orion-Store>) is an open-source Android store that lists apps from
their GitHub Releases. Its maintainer reviews each submission for safety and open-source status before it appears.
Submit through the Orion Store app's submission option or the submission form linked from its GitHub repository; if
neither is there, open an issue on that repository. Check its README for the current route first.

**Submission text:**

```
App: SumBuddy
Repository: https://github.com/akhilux-design/sumbuddy
Release APK: https://github.com/akhilux-design/sumbuddy/releases/latest/download/sumbuddy.apk
Package: com.akhilux.xpense
License: GPL-3.0-or-later
Category: Finance
Short description: Your month added up from bank SMS on your phone. Private. Splits with friends.
Description: SumBuddy reads the bank and UPI SMS on your phone, finds the payments and adds up your month. SMS never leave the phone. Optional Google sign-in to split with friends, share a Family month and import from Splitwise. No ads, no analytics. Made for India.
Icon: https://raw.githubusercontent.com/akhilux-design/sumbuddy/main/fastlane/metadata/android/en-US/images/icon.png
Screenshots: fastlane/metadata/android/en-US/images/phoneScreenshots/1.png to 6.png in the repository
Developer: akhilux-design (I am the developer)
```

## 6. F-Droid (later)

F-Droid's main repository builds every app from source on its own servers and accepts only free software. It refuses
any build that contains Google Play services, Firebase or other proprietary libraries, so the app as it is cannot go
there. The plan is a `foss` product flavour:

- **No Google sign-in.** Remove `googleid` and `credentials-play-services-auth` from that flavour. Splitting, Family
  and Import from Splitwise need an account, so the flavour either switches them off (a tracker only) or gets another
  sign-in method on the server (for example an emailed one-time link), which is new server work.
- **No Firebase Cloud Messaging.** Remove `firebase-messaging`. The app already syncs when opened, over the live
  socket while open and every 12 hours, so it works without push, a little less promptly; UnifiedPush could come
  later.
- **Fonts:** already bundled (`app/src/main/res/font`), so nothing to do.
- **No dependency metadata block:** already off (`dependenciesInfo` in `app/build.gradle.kts`).
- **A release build from source:** already works from a clean clone (`gradle.properties` holds the public server's
  address, web client id and link host). The flavour would need no Google client.
- **Signing.** F-Droid signs with its own key unless the build is reproducible, so its APK could not update a GitHub
  install, and App Links on the friends' page would need F-Droid's certificate added to `ANDROID_CERT_SHA256`.
- **AI.** F-Droid's policy on apps made with AI help is still a draft, and neutral; read its current state before
  applying and say plainly how AI coding tools were used.
- Metadata: F-Droid reads the same `fastlane/` folder; the licence is `GPL-3.0-or-later`. The request goes to
  <https://gitlab.com/fdroid/rfp> (Request For Packaging) or as a merge request to <https://gitlab.com/fdroid/fdroiddata>.

## 7. IzzyOnDroid: not a target

Decided 2026-11-20: SumBuddy does not apply to IzzyOnDroid. Its inclusion policy rejects apps made with generative AI
(its AI policy), and SumBuddy was built with the help of AI coding tools. If that policy changes, the fastlane
metadata and the tagged releases are already in the shape it reads.

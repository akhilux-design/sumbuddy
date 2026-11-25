# Publishing SumBuddy

Step by step for the maintainer: the public repository, a release, and the places that list the app. Decided
2026-11-20; the stores were checked again on 2026-11-22:

- SumBuddy is not on Google Play. It is open source (`GPL-3.0-or-later`) and ships as a signed APK on **GitHub
  Releases**, which **Obtainium** and **Orion Store** read. These three are the targets now, and all three work:
  Orion Store has listed SumBuddy since 2026-11-25 (section 5).
- **F-Droid**'s main repository comes later, with a `foss` flavour without Google sign-in and Firebase, and not before
  the project is about 3 months old (section 6).
- **IzzyOnDroid** is not a target (section 7).
- Other catalogues and lists are in section 8. The release certificate's name is an open decision (section 9).

Store and policy pages change: check each step against the page itself when you do it. Facts dated 2026-11-22 were
checked that day against the stores' own pages, code and issue trackers.

## 0. Before the first public release (done)

Done on 2026-11-20 and 2026-11-21, and brand verification on 2026-11-24, except one check on the friends' page. Kept
as a record.

- **Signing key.** Decided 2026-11-20: releases are signed with the current key, the certificate with SHA-256
  `11d2e03b…9a8b` that `scripts/release.sh` checks ("CN=Android Debug, O=Android, C=US"). Installed apps can only
  update to an APK signed with the same key, or with a key rotated from it. Debug builds have their own app id
  (`com.akhilux.xpense.debug`), so a debug APK, although signed with the same certificate, installs as a separate app
  and can never replace the public one. The certificate's name is still an open question (section 9).
- **Google sign-in for everyone.** Published on 2026-11-21 (Google Auth Platform > Audience: In production).
  Branding: app name SumBuddy, home page <https://sumbuddy.akhilux.design/> and privacy policy
  <https://sumbuddy.akhilux.design/PRIVACY.html>. GitHub Pages serves the website from `main` at that address (the
  `CNAME` file; the old `akhilux-design.github.io/sumbuddy/` links redirect there): `index.html` is the home page and
  `PRIVACY.md` is the policy, so the policy there is the one in this repository ("Website" below). Google refused the first address, `akhilux-design.github.io/sumbuddy/`, on
  2026-11-24 because no `github.io` site can be verified by DNS. Since 2026-11-24 `akhilux.design` is a Search Console
  Domain property of the Cloud project's Owner account and a verified GitHub Pages domain (`docs/server-setup.md`
  7.2). Google approved brand verification on 2026-11-24, so its sign-in step shows the name SumBuddy rather than
  the domain (not yet checked on a phone). Any change to Branding (name, logo, URLs, authorized domains) or a new
  scope needs a new review, and the DNS records for `akhilux.design` and `sumbuddy.akhilux.design` must stay.
- **Push and sign-in for the release key.** Nothing to add: the release certificate is the one whose Android client
  is in `GOOGLE_ANDROID_CLIENT_IDS` and on the FCM API key (`docs/server-setup.md` 7.1). A new or rotated key changes
  this (section 9).
- **Friends' page.** It must be deployed with
  `APP_DOWNLOAD_URL=https://github.com/akhilux-design/sumbuddy/releases/latest/download/sumbuddy.apk` and the release
  certificate in `ANDROID_CERT_SHA256` (`docs/server-setup.md` 4.3), so "Get SumBuddy" works and links open in the
  app. Check once on a real link that "Get SumBuddy" opens the GitHub APK.

## 1. The public repository (done)

Done 2026-11-20: `akhilux-design/sumbuddy` started with one clean commit (`a06966c`, "SumBuddy 0.2.0: first public
release"), made from the tracked files with `git archive`, never by copying the working folder. v0.2.0 was released
the same day. The old private repository keeps the history before that commit and gets no new work.

**Work and releases happen with the public repository as `origin`.** `scripts/release.sh` refuses to build unless
`origin` is `akhilux-design/sumbuddy` and already has `HEAD` (`git branch -r --contains HEAD`), and the
`gh release create` command it prints passes `--target` with that commit, so every release tag points at a commit
anyone can see.

On GitHub, check once:

- Settings > General: Issues on; Wikis off unless wanted.
- Settings > Code security: **Private vulnerability reporting** on (`SECURITY.md` points at it).
- About: topics such as `android`, `android-app`, `kotlin-android`, `expense-tracker`, `sms`, `upi`, `india`,
  `split-bills`, `open-source`. Komi Store searches the first three (section 8).
- `LICENSE` is detected as GPL-3.0 on the repository page (GitHub reads the licence text, which is the same for
  "only" and "or later"; the README says or later).

## 2. A release

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts` (0.2.0 is versionCode 2).
2. Write `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (at most 500 characters) and a section in
   `CHANGELOG.md`. If a screen shown in the store screenshots changed, capture it again (below).
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
7. Change the version in the website's hero line ("Version 0.3.2 · ...", `index.html`), commit and push. If a screen
   shown on the website changed, capture it again in both themes and rebuild the screenshots ("Website" below).

**Website** (<https://sumbuddy.akhilux.design/>, GitHub Pages from `main`, legacy Jekyll build). Designed in Figma on
the page "Website" (boards "Website · Dark" and "Website · Light", frames Home and Privacy at 1440 and 360 wide), in
the app's own design system: every UI change goes to that page and to these files together.

- `index.html` home page; `_layouts/policy.html` shows `PRIVACY.md` (set in `_config.yml`); `_includes/site-*.html`
  the head, nav and footer both use. Every other Markdown file keeps GitHub Pages' default look.
- `assets/site/site.css` (hand-written; no raw colours) and `assets/site/policy.js` (fills "On this page"; the policy
  reads fine without it).
- Generated by `node scripts/site-assets.mjs --screens build/previews`: `assets/site/tokens.css` (from `design/spec`),
  the self-hosted fonts and licences, the 3D icons, the app icon and favicons, and `assets/site/screens/*.webp`
  (540 x 1200). The screenshots come from `design/tools/capture.sh <preview> light` and `... dark` on the emulator
  after `design/tools/emulator.sh`; the preview names are in the script. Both themes must come from the same build.
- The home page and the privacy policy make no request to any other host (fonts, icons and images are all served from
  the site) and have no analytics. Other Markdown pages, such as `SECURITY.md`, keep GitHub Pages' default theme, which
  loads a script from cdnjs.
- The privacy layout relies on how `PRIVACY.md` starts: one `#` title, then exactly three paragraphs (Last updated, the
  intro, and one starting with `**In short:**`), then the `##` sections. Keep that shape when editing the policy.
- Keep: `/` and `/PRIVACY.html` must keep answering 200 without a redirect, show the name SumBuddy and link to the
  policy (Google's approved Branding, section 0). Credit wording: "Designed by akhilux · Developed with Claude Code".

**Store screenshots** are `fastlane/metadata/android/en-US/images/phoneScreenshots/1.png` to `8.png`: eight since
2026-11-22, all shown in the README. In order: Home, Review payments, card bills on Home, a card bill, Month, a group
page, Family month, Import from Splitwise. Each is a 1080 x 2400 light-theme capture of a preview with made-up sample
data: `design/tools/capture.sh <preview> light` on the emulator, with the status bar from the demo mode commands in
`design/tools/emulator.sh` (9:41, full battery, Wi-Fi, no notification icons). Keep the file names: Orion's listing
will link to them on `main` (section 5).

## 3. Obtainium

Nothing to submit. People add the repository URL in Obtainium (Add App > `https://github.com/akhilux-design/sumbuddy`)
and it installs and updates from GitHub Releases. Each release has two identical APKs; Obtainium may ask which one to
use, and either is right. If that confuses people, attach only `sumbuddy.apk` (the friends' page needs that name) and
leave out `sumbuddy-<version>.apk`.

**Obtainium's curated list** (<https://apps.obtainium.imranr.dev>, checked 2026-11-22) holds ready-made app configs,
added by pull request to <https://github.com/ImranR98/apps.obtainium.imranr.dev>
(`public/data/apps/simple/com.akhilux.xpense.json`). Its `APP_CRITERIA.md` asks for at least 35 GitHub stars and a
project at least 4 months old. The public repository was created on 2026-11-20, so about 2027-03-20 at the earliest,
and only with 35 stars by then.

**"Add to Obtainium" badge.** It needs no listing. `BADGING.md` in the same repository gives two link forms: a plain
`obtainium://app/<config JSON>` link, or a redirect page on `apps.obtainium.imranr.dev` that opens it. The redirect
page loads that site's analytics and ad script, which sits badly next to SumBuddy's "no ads and no analytics". GitHub's
Markdown keeps only web, mail and a few GitHub link schemes, so an `obtainium://` link is unlikely to be clickable in
the README (not tested). The maintainer decides whether to add a badge; the README's "Add App" steps work without one.

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

Checked 2026-11-22 against the Orion client's code (v2.7.8), its catalogue repository and recent submission issues.
Orion's own docs (Orion-Docs, February 2026) describe an older route; the app and the Orion maintainer's replies are
what count now.

Orion Store (<https://github.com/RookieEnough/Orion-Store>) is an open-source Android store. Its catalogue is
<https://github.com/RookieEnough/Orion-Data>. It lists apps whose source is public on GitHub, GitLab or Codeberg and
whose releases carry APKs. SumBuddy meets this today. There is no written rule on Google libraries or on AI.

**Route: the in-app form only.**

1. Wait until the eight screenshots are on `main`: the form reads them from the repository.
2. Install Orion Store from its GitHub releases. Open its submit form: **Submit app** on the Android tab, under the
   category chips (the Guide's "Submit an app" opens the same form). Choose **From repo**.
3. Paste `https://github.com/akhilux-design/sumbuddy`: the plain repository URL, never `/releases`.
4. Check every field against the table below, and change wrong ones in the form.
5. Generate the issue. Orion opens a pre-filled issue on RookieEnough/Orion-Data, titled
   `App Submission [1 App] - SumBuddy`, with a JSON block. Submit it from the maintainer's own GitHub account (the
   review checks that the submitter owns the repository). Do not edit the title or the JSON on GitHub.

Do not write the issue by hand: the Orion maintainer sends such issues back and asks for a submission from the app
without manual edits (Orion-Data #3130 and #3132, 2026-11-20). Generating an issue starts a cooldown of about 3 hours
in the app, so have everything ready first.

| Field | Value | Why |
|---|---|---|
| Repo URL | `https://github.com/akhilux-design/sumbuddy` | The plain URL; the Orion maintainer asks for this. |
| Name | `SumBuddy` | The form fills in `Sumbuddy`. |
| Category | `Productivity` | Orion has no Finance category; other expense trackers are under Productivity. |
| Author | `akhilux-design` | Filled in from the repository owner. |
| Description | the text below | Replaces the GitHub description the form fills in. |
| Icon URL | `https://raw.githubusercontent.com/akhilux-design/sumbuddy/main/fastlane/metadata/android/en-US/images/icon.png` | A direct PNG link; the Orion maintainer asks for PNG, not webp. |
| Package name | `com.akhilux.xpense` | The form fills in `com.expenses.app`, the `namespace`, because `applicationId` comes from a Gradle property. With the wrong name Orion never finds the installed app and never offers updates. |
| Release keyword | `apk`, the default (leave the switch off) | The Orion maintainer asks for `apk` for Android apps (Orion-Data #3164, 2026-11-21). Both identical APKs then show as two "Universal" variants, which is harmless. |
| Screenshots | `1.png` to `8.png`, filled in from fastlane | At least 3 are needed. Check that the card bill ones are there. |

Description for the form:

```
SumBuddy adds up your month from Indian bank, card and UPI SMS on your phone, shows card bills with what friends owe you of them, and never uploads your SMS. Sign in with Google only if you want to split with friends, settle up by UPI or share a Family month.
```

**Submit once.** Never open a second submission, even if it is slow or a field is wrong: answer and fix things in the
same issue (for example, fix an image in this repository). The Orion maintainer has warned that repeat submitters get
blacklisted. The one exception: if the Orion maintainer closes the issue and asks for a new submission, as happened to
long-open issues in November 2026.

**How long.** The median time from opening to listing was about 12 hours over the 60 most recent approvals (closed 19
to 22 Nov 2026), but some took about 3 days and one about 15 days. The Orion maintainer adds the `approved` label, and
a batch job adds the app to the catalogue and closes the issue.

**Updates and signing.** Orion keeps the GitHub download address of each release asset; it does not re-host or
re-sign APKs. Its mirror reads GitHub Releases every 6 hours and after each approval batch
(`.github/workflows/update_mirror.yml` and `.github/scripts/mirror_generator.py` in Orion-Data), so a new release
reaches Orion users within about 6 hours with nothing to resubmit. Because it is the same signed file, installs from
GitHub, Obtainium, the friends' page and Orion update each other. Later listing changes (icon, keyword, package) go
through an "App Update" issue generated in the app, not a new submission.

**Risks.**

- **AI.** On 2026-11-16 the Orion maintainer removed 41 apps in a commit titled "Remove 41 AI slop apps from the
  catalog" (Orion-Data `b4ee4fd`), after an audit whose criteria are not public. This repository shows its AI use
  openly (README "How this was made", `CLAUDE.md`, a Co-Authored-By line on every commit so far). Expect a possible
  rejection, or removal in a later audit. Saying plainly how the app was made, and keeping it maintained, is the best
  answer; the form does not ask.
- **"Debug build" warning.** Orion's client marks any APK whose certificate subject contains `CN=Android Debug` as a
  debug build and tells users it is signed with Android's public debug key. SumBuddy's key is private, but its
  certificate has that name (section 9), so Orion users will see the warning. A first comment on the issue can explain
  it: the certificate began as the maintainer's own debug key, the key is private and kept off the repository, its
  SHA-256 is in `docs/releasing.md`, and the release APK is not debuggable.

**Listed on 2026-11-25.** The submission (RookieEnough/Orion-Data#3360) was approved and closed at 03:40 UTC, and the
catalogue (`apps.json` in Orion-Data) has SumBuddy under Productivity with package `com.akhilux.xpense`. Done after it:
the README's Download list links Orion Store, the website's Orion card says it is listed and links Orion Store's
releases (Figma page "Website" too), and `SECURITY.md` names Orion as an update route. The "Get it on Orion Store"
badge (Orion's Developer Mode gives the Markdown) is not added.

## 6. F-Droid (later)

Checked 2026-11-22. F-Droid's main repository builds every app from source on its own servers and accepts only free
software. Its Inclusion Policy (<https://f-droid.org/docs/Inclusion_Policy/>) forbids Google Play services and
Firebase, and F-Droid's scanner marks `googleid`, `credentials-play-services-auth` and `firebase-messaging` as
NonFree, so the app as it is cannot go there. The plan is a `foss` product flavour:

- **No Google sign-in.** Remove `googleid` and `credentials-play-services-auth` from that flavour. Splitting, Family
  and Import from Splitwise need an account, so the flavour either switches them off (a tracker only) or gets another
  sign-in method on the server (for example an emailed one-time link), which is new server work.
- **No Firebase Cloud Messaging.** Remove `firebase-messaging`. The app already syncs when opened, over the live
  socket while open and every 12 hours, so it works without push, a little less promptly; UnifiedPush could come
  later.
- **No `app.fcm.*` values.** The published APK is built with four `app.fcm.*` values from the maintainer's
  `~/.gradle/gradle.properties`, which are empty in this repository, so it cannot be rebuilt bit for bit from the
  public source. The foss flavour has no Firebase and must not need them, or neither F-Droid's build nor signature
  copying (below) can match.
- **Fonts:** already bundled (`app/src/main/res/font`), so nothing to do.
- **No dependency metadata block:** already off (`dependenciesInfo` in `app/build.gradle.kts`).
- **A release build from source:** already works from a clean clone (`gradle.properties` holds the public server's
  address, web client id and link host). The flavour would need no Google client.
- **Package name.** fdroidserver finds only literal `applicationId`, `namespace` or `package` values, and ours comes
  from a Gradle property, so it would read the namespace `com.expenses.app`. Set `UpdateCheckName: Ignore` (or write a
  literal `applicationId`), name `com.akhilux.xpense` in the metadata, and check with `fdroid lint` and
  `fdroid checkupdates`.

Rules that apply even with the flavour:

- **About 3 months of active development.** Quality Control point 6 of the Inclusion Policy, added on 2026-10-16
  (<https://gitlab.com/fdroid/fdroid-website/-/merge_requests/1643>), asks for at least 3 months, unless the app has
  proven users, the author already maintains apps on F-Droid, or the app is for one event. Reviewers are closing
  new-app requests on this rule (fdroiddata !58170 on 2026-11-19, !58194 on 2026-11-20). The public history starts on
  2026-11-20, so about 2027-02-20 at the earliest. Whether GitHub downloads count as proven users is not known.
- **Unique value.** Quality Control also asks what an app adds over those already in F-Droid. F-Droid already has
  several SMS expense trackers (Cashiro, PennyWise, UPI Expense Tracker, TU Expense Tracker, Clear SMS) and many split
  apps (Split It Easy, Counted, Quits, RapidSplit, Baluchon, MoneyBuster, Tricky Tripper). Only the combination of SMS
  tracking, splitting and Import from Splitwise may count, so a tracker-only flavour is at real risk of refusal.
- **Application id and domain.** The policy asks for an application id based on a domain the developer controls, and
  refuses an id that copies another party's domain. Check `com.akhilux.xpense` against that before applying.
- **AI.** No adopted policy as of 2026-11-22; the Inclusion Policy does not mention AI. Open discussions in
  <https://gitlab.com/fdroid/admin>: #699 (an interim policy for F-Droid's own work, modelled on Debian's, not about
  apps), #641 (an anti-feature for apps that use or are made with AI; an "AISlop" label for apps made without much
  human input was suggested) and #710 (a proposed "VibeCoded" anti-feature, found through files such as `AGENTS.md`
  and AI lines in commit messages). Reviewers would see `CLAUDE.md` and the Co-Authored-By lines. Say plainly how AI
  coding tools were used (README "How this was made").

**Signing.** By default F-Droid signs with its own key, so its APK could not update a GitHub install, and App Links on
the friends' page would need F-Droid's certificate added to `ANDROID_CERT_SHA256`. The choice is made at the first
inclusion and cannot be switched later. To ship the maintainer's signature instead
(<https://f-droid.org/docs/Reproducible_Builds/>):

- the metadata gets `Binaries:` with a versioned URL, for example
  `https://github.com/akhilux-design/sumbuddy/releases/download/v%v/sumbuddy-foss-%v.apk`, and
  `AllowedAPKSigningKeys: 11d2e03bb39f17d8545dd77c073bb2c6f63ca898c42cf42878155bec616f9a8b`;
- F-Droid builds the APK, copies the maintainer's signature onto it with `apksigcopier`, and publishes the
  maintainer's APK only when the two match. Otherwise that version is skipped;
- so the maintainer's APK must be the same foss flavour, built from a clean tree at the tagged commit;
- `apksigner` from build-tools 35.0.0-rc1 or newer makes APKs that `apksigcopier` cannot verify (fdroiddata #3299);
  the workarounds are `apksigner` 34 or `--alignment-preserved`. Release builds are signed through AGP with
  build-tools 36.1.0 installed, so treat signature copying as untested;
- other known sources of difference: resource shrinking (`isShrinkResources = true`), baseline profiles, R8 and the
  JDK (F-Droid builds with JDK 21 or 25).

**Route.** A merge request to <https://gitlab.com/fdroid/fdroiddata> adding `metadata/com.akhilux.xpense.yml` is the
preferred route; it needs a GitLab account. An issue at <https://gitlab.com/fdroid/rfp> (Request For Packaging) gets
only an automated review. F-Droid reads the same `fastlane/` folder; the licence is `GPL-3.0-or-later`. F-Droid's
build tools know Gradle 9.9.1; whether its build server handles AGP 9.5.1 shows only once CI runs.

## 7. IzzyOnDroid: not a target

Decided 2026-11-20 and checked again on 2026-11-22. SumBuddy does not apply to IzzyOnDroid, for two reasons:

- **AI policy.** Its Inclusion Policy (<https://izzyondroid.org/docs/general/AppInclusionPolicy/#ai-policy>) is
  adopted, not a draft, and is strongly opposed to apps made fully or partly with generative AI. Since 2026-08-26 it
  also says that vibe-coded apps are rejected, that the code itself should contain no LLM output, and that only
  read-only uses (research, debugging, look-ups) are acceptable. Reviewing and testing AI-written code does not change
  this. In its tracker (<https://codeberg.org/IzzyOnDroid/repodata>), none of the 524 requests labelled
  `llm/substantial` had been accepted on 2026-11-22. One declined on 2026-11-20 (#929) is SumBuddy's case: Claude
  wrote most of the code, and the developer directed and tested it.
- **Certificate name.** It does not accept APKs signed with debug certificates
  (<https://izzyondroid.org/about/security/SigningKeys/>), and its scanner flags any certificate whose subject
  contains `CN=Android Debug`, whatever the key. SumBuddy's certificate has that name (section 9).

If its AI policy changes and the certificate changes, the fastlane metadata and the tagged releases are already in the
shape it reads. Its Fastlane page also asks for screenshots no more than twice as tall as they are wide; ours are
1080 x 2400.

## 8. Other catalogues and lists

Checked 2026-11-22. None of these reviews the app the way F-Droid does, and none has a written policy on apps made
with AI help. Each submission is a public post under the maintainer's account, so the maintainer sends it.

- **Open-Source Android Apps** (<https://github.com/pcqpcq/open-source-android-apps>), a curated list: one row in
  `categories/finance.md` by pull request. Open source is its only rule (no star, age, library or AI rule), and it
  merged new-app pull requests within about a day in mid-November 2026 (the newest, from 20 to 22 Nov, were still
  open on 2026-11-22). Possible now. The row has six columns: the name
  linked to the repository, one sentence, `Kotlin`, `GPL-3.0`, stars and a Releases link; a daily job rewrites the
  language, licence and stars from GitHub. A sentence that fits: "A monthly spending tracker made for India that reads
  bank and UPI SMS on the phone and splits shared payments with friends." Their `scripts/check_repo.py --skip-counts`
  checks the format.
- **Komi Store and RepoStore** find GitHub repositories with APK release assets by themselves; there is nothing to
  submit. Komi searches the topics `android`, `android-app` and `kotlin-android`, but its New Releases feed mostly
  needs more than 5 or 10 stars and Trending more than 50, so a new repository will probably not show yet. Set those
  topics (section 1).
- **Zapstore** (<https://zapstore.dev/docs/publish>), a later option. Anyone can publish with its `zsp` tool, straight
  from GitHub Releases, and it keeps our APK certificate, so its installs and GitHub installs update each other. It
  needs the maintainer's own Nostr key and a claim, signed with the release key, that links the two. A new key may
  fail its web-of-trust admission check. Its relay flags Google services.
- **An own F-Droid-format repository, listed in Appiverse** (<https://appiverse.io/fdroid/>), a later option:
  fdroidserver, a repository signing key and HTTPS hosting (for example GitHub Pages). It serves our own signed APKs,
  so installs update each other. Each release needs `fdroid update` and a deploy. Appiverse would probably tag it
  NonFOSS because of the Google libraries.
- **offa/android-foss** (<https://github.com/offa/android-foss>): not now. Its rules ask for no proprietary elements
  and at least 6 months of active development, so it needs the foss flavour, and mid-May 2027 at the earliest.
- **Awesome Android Apps** (<https://github.com/Psyhackological/AAA>): possible by pull request, but its "Free as in
  Freedom" rule may be read against the Google libraries, and merges take days to months.
- **Accrescent** (<https://accrescent.app>): closed to new developers ("not currently accepting new allowlist
  requests"). It also refuses debug certificates, so the certificate's name would matter there too (section 9).

## 9. Open decision: the release certificate's name

The release certificate's subject is `CN=Android Debug, O=Android, C=US`, because it began as the maintainer's own
debug key on the Mac. The key is private and kept off the repository; it is not Android's public test key. Its
SHA-256 is `11d2e03b…9a8b` (`docs/releasing.md`). The name still causes trouble:

- Orion Store shows a "Debug build" warning and tells users the app is signed with Android's public debug key
  (section 5);
- IzzyOnDroid and Accrescent refuse certificates like this (sections 7 and 8).

Options:

1. **Keep it.** Nothing changes for anyone who has the app. Explain the name in each submission, and in the README if
   wanted.
2. **Rotate it** with APK Signature Scheme v3 (`apksigner rotate` and a signing lineage). Existing installs keep
   updating. The old certificate stays in the APK for older Android versions (by default the new one is used from
   Android 13; it can be set to apply from Android 9), so some tools may still show the warning. Whether IzzyOnDroid
   would accept such an APK is unknown.
3. **A new key** with no lineage. Clean, but Android will not update an install signed with the old key: everyone
   must uninstall and install again, and uninstalling deletes the app's data on the phone (backups are off).

For options 2 and 3 the new certificate also needs: an Android client for Google sign-in (its SHA-1) with its id in
the server's `GOOGLE_ANDROID_CLIENT_IDS`, its SHA-1 on the Firebase API key restriction, and its SHA-256 in
`ANDROID_CERT_SHA256` on the friends' page (`docs/server-setup.md` 7.1); then the fingerprint in `scripts/release.sh`
and `docs/releasing.md`. With rotation the old values stay next to the new ones. The maintainer decides.

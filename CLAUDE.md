# Project guide

Android app "SumBuddy" (formerly the working name "Xpense") that tracks monthly expenses from bank SMS read on the
phone and splits shared payments. Internal names keep "xpense" (application id, link host, Worker, D1, Google Cloud
project): see `docs/product.md`. Read `docs/product.md` first: it holds decisions the design does not show.

## Build and run

The path to this folder contains spaces. Quote it in shell commands.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew :parser:test            # SMS parser unit tests (JVM)
./gradlew :app:assembleDebug      # build the app
./gradlew :splits:test            # split and balance maths (JVM)
./gradlew :splitwise:test         # Splitwise export reader (JVM)
```

Server (Cloudflare Worker) and the friends' link page, after `npm install` in each folder:

```bash
cd server && npm test && npm run dev        # local server on 127.0.0.1:8787; the emulator reaches it at 10.0.2.2:8787
cd server/web && npm test
```

Never commit `server/.dev.vars`, service-account JSON files, `google-services.json` or any other secret. Ids that are
not secret (D1 database id, OAuth client ids, link host) may be committed. Setup steps: `docs/server-setup.md`.

Emulator: an AVD named `xpense_pixel` exists. `$ANDROID_HOME/emulator/emulator -avd xpense_pixel`.
Debug builds have the app id `com.akhilux.xpense.debug` (version name ends in `-debug`), so they install next to the
released app `com.akhilux.xpense` and never replace it; use the `.debug` id with `adb` (`design/tools/capture.sh`
does). Debug builds sign in with Dev sign-in against the local server, so they need no Google client.
Use `$ANDROID_HOME/platform-tools/adb`, not the Homebrew one.

## Layout

- `app/` Android app, Kotlin and Jetpack Compose, package `com.expenses.app`.
- `parser/` pure Kotlin module that turns SMS text into payments. No Android dependencies.
- `splits/` pure Kotlin module for split rounding and balances. It must give the same answers as the server (test vectors in `server/test/vectors/`).
- `splitwise/` pure Kotlin module that reads a Splitwise export (CSV) into expenses, payments and balances for Import from Splitwise (`docs/splitwise-import.md` 4). No Android dependencies; the file never leaves the phone.
- `server/` Cloudflare Worker (D1, Durable Objects, FCM push) for sign-in and splitting; `server/web/` is the friends' link page. Contract: `docs/server.md`.
- `design/spec/` exported copy of the Figma design: tokens, typography, components, screens (JSON plus PNG in both themes), flow.
- `index.html`, `_layouts/`, `_includes/`, `assets/site/` the public website, sumbuddy.akhilux.design (GitHub Pages from `main`; Figma page "Website"). `node scripts/site-assets.mjs` rebuilds its generated assets (`docs/publishing.md`, "Website").
- `design/icons/` SVG sources of the icons. Converted drawables are `app/src/main/res/drawable/ic_<name>_line.xml` and `ic_<name>_3d.xml`.
- `docs/product.md` product decisions and version 1 scope.

## Rules

- The design is the source of truth. Match `design/spec` exactly: spacing, radius, colours by token name, text styles.
- Every agreed UI change goes into both the design and the app, in the same piece of work: the Figma file (Dark and
  Light boards), the Flow page and `design/spec/flow.md` whenever a screen, state, branch or path is added or
  changed, the `design/spec` export (JSON and both PNGs), and the code. A state or element the app shows that the
  design does not draw is added to Figma too.
- Use theme tokens, never raw colour values, in screens and components.
- Text is never smaller than 14sp. Touch targets are at least 48dp. State is never shown by colour alone.
- Never use emoji as icons. Rows, tiles and empty states use the 3D icons; the bottom bar and top bar use the line icons.
- SMS content never leaves the phone and is never written to logs.
- The app name appears only in `strings.xml` (`app_name`). Link host comes from `BuildConfig.LINK_HOST`.
- Plus is not in version 1: do not build or show it.
- Versions live in `gradle/libs.versions.toml`. AGP 9 has Kotlin built in: do not apply `org.jetbrains.kotlin.android`.

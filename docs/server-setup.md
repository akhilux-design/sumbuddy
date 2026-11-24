# Run your own server

How to put SumBuddy's split server online on your own accounts, in order. `docs/server.md` is the contract; this file
says which account to make, what to click and where each value goes. Until step 4 everything runs on your computer:
the Worker, its database and the live rooms run in `wrangler dev`, and a debug build of the app talks to it at
`http://10.0.2.2:8787`.

The server is optional. The app tracks your month from SMS without it; only sign-in, splitting with friends, Family
and Import from Splitwise need it.

Free plans and console menus change: check each step against the provider's page when you do it. Some steps cannot
be undone (3.2, 6.3), so read a section to the end before you start it.

**Placeholders.** `server/wrangler.toml`, `server/web/wrangler.toml` and `gradle.properties` in this repository hold
the values of the maintainer's own server (none of them is secret). In your copy, replace each with yours:

| Placeholder | Meaning | Example |
|---|---|---|
| `<worker>` | The Worker's name (`name` in `server/wrangler.toml`) | `sumbuddy-api` |
| `<subdomain>` | Your workers.dev subdomain (1.5) | `my-name` |
| `<db>` | The D1 database name (`database_name` in `server/wrangler.toml`) | `sumbuddy` |
| `<project-id>` | Your Google Cloud project id (2.1) | `sumbuddy-123456` |
| `<package>` | `app.applicationId` in `gradle.properties` | `com.example.sumbuddy` |
| `<name>`, `<host>` | The Pages project name of the friends' link page and its host (3.1, 3.2) | `my-split-links`, `my-split-links.pages.dev` |

The Worker's address is then `https://<worker>.<subdomain>.workers.dev`.

## 0. Local development (no account needed)

From the repository root:

```bash
cd server
npm ci                                # node_modules is git-ignored
cp .dev.vars.example .dev.vars        # local SESSION_SECRET; .dev.vars is git-ignored
npm run db:migrate:local              # creates the local D1 database in .wrangler/ (git-ignored)
npm run dev                           # http://127.0.0.1:8787; the emulator reaches it as http://10.0.2.2:8787
npm test                              # every route, against a local D1 and Durable Object
npm run typecheck
```

- `npm run dev` is the only place `ENVIRONMENT` is `development` (on the command line, `--var
  ENVIRONMENT:development`). That turns on `POST /v1/auth/dev` for requests to `localhost`, `127.0.0.1`, `[::1]` and
  `10.0.2.2` only. Debug builds of the app offer it as "Dev sign-in", so you can try splitting with no Google account.
  It listens on `127.0.0.1`, never `0.0.0.0`, so nothing on the local network can reach it.
- Never run `wrangler dev --remote`: that would use the real database.
- `npm run dev` reads the `[vars]` in `server/wrangler.toml`, real client IDs included. Debug builds have the app
  id `<package>.debug`, so "Continue with Google" works in a debug build against the local server only after you add
  an Android client for `<package>.debug` and your debug key (2.4), on an emulator with a Google account added. Dev
  sign-in needs none of that.
- `npm test` uses its own test values (`server/vitest.config.ts`), not the ones in `wrangler.toml`. It passes the
  same before and after you fill in real values.
- Push is off locally: `server/.dev.vars` has no `FCM_SERVICE_ACCOUNT_JSON`, and the service account key is never
  kept on disk (6.6). The app still syncs when opened, over the live socket and every 12 hours.
- Run the daily cron by hand while `npm run dev` runs: `curl "http://127.0.0.1:8787/cdn-cgi/local/scheduled"`.
- A local database keeps the tables it was made with, because wrangler applies each migration file once. To start
  it again: `rm -rf .wrangler/state && npm run db:migrate:local` (local test data only).
- npm 11 prints "install scripts not yet covered by allowScripts" for `esbuild` and `workerd`. Their binaries come
  as optional packages and work without the scripts; nothing needs approving.

## 1. Cloudflare: account, database, subdomain, secret

Fills: `server/wrangler.toml` `database_id`, the `SESSION_SECRET` secret.

- **1.1 Account** at <https://dash.cloudflare.com/sign-up>, on the Free plan. Workers Free needs no card. Over a free
  limit, requests fail and nothing is billed.
  - Open the verification email Cloudflare sends after sign-up (check spam). Until the address is verified,
    Cloudflare refuses some actions, and wrangler can stop with "Please verify your account's email address and try
    again." To get a new link: dash.cloudflare.com > My Profile > Email Address > Send verification email.
- **1.2 CLI login**: `npx wrangler login` in `server/` (it opens the browser). The login belongs to your user
  account, so the wrangler in `server/web` uses it too.
- **1.3 Database**: `npx wrangler d1 create <db> --location=<hint>`, for example `apac` for India. Put its
  `database_id` and `database_name` in `server/wrangler.toml` `[[d1_databases]]`. The id is not a secret.
- **1.4 Tables** in the real database: `npx wrangler d1 migrations apply <db> --remote`. From then on, never edit an
  applied migration. A later change is a new `server/migrations/NNNN_name.sql`: apply it `--local`, test, then
  `--remote`.
- **1.5 workers.dev subdomain**: dashboard > Workers & Pages > Change next to "Your subdomain". The Worker's address
  goes into `app.apiBaseUrl`, so it is built into every release build, and into the link page (4.3). The subdomain
  covers the whole account and is public. Choose it before your first release build: a later change moves every
  Worker to a new address, and installed apps would lose the server.
- **1.6 Session secret.** It signs access tokens and sign-in nonces. Set it before the first deploy (4.2): 48 random
  bytes, piped so it never lands in a file or the shell history:
  ```bash
  cd server
  openssl rand -base64 48 | npx wrangler secret put SESSION_SECRET
  ```
  Before the first deploy, wrangler offers to create a Worker with that name and answers yes by itself, because the
  secret arrives through the pipe. It creates an empty placeholder Worker and stores the secret. The first deploy
  (4.2) replaces the placeholder and keeps the secret.

  Without the secret every route answers 500. Changing it later is safe: open access tokens stop working and phones
  refresh once (refresh tokens are stored as hashes and stay valid); a sign-in in progress at that moment fails once.

## 2. Google sign-in (Google Cloud project, OAuth clients)

Fills: `GOOGLE_WEB_CLIENT_ID` and `GOOGLE_ANDROID_CLIENT_IDS` in `server/wrangler.toml`, `app.googleWebClientId`.

**Which Google account.** Use one account for every Google step here and in 6 (Firebase), and check the account
picker (top right) in each console before you click. A work or Google Workspace account puts the project under the
company's organization, whose admins control it. Organizations made since May 2024 also block service account keys
by default, and 6.6 needs one.

- **2.1 Project** at <https://console.cloud.google.com/>. Use the same project for Firebase in 6, so there is one
  project to look after.
  - **Billing check.** Nothing in sections 2 and 6 needs billing: OAuth clients, Firebase Spark, Cloud Messaging and
    a service account key all work without it. Google Cloud console > Billing > My projects: the project's row must
    read "Billing is disabled". A project gets your billing account linked by itself when you have exactly one. If
    the row names a billing account: that row's Actions menu > Disable billing. Do not start the Free Trial the
    console offers. If any screen asks for a card, stop.
- **2.2 Google Auth Platform** (APIs & Services > OAuth consent screen): app name, support email, audience
  **External**. Data access: only `openid` and `profile` (the server reads the name and nothing else; email and
  picture are never stored).
  - In the Testing status, only basic scopes are asked for, so any Google account can sign in, but the sheet does
    not show the app name. Publishing and brand verification are in 7.2.
  - The server has no allowlist, so anyone with an app that points at it can make an account. A closed beta would
    need a check on the server.
- **2.3 Web client**: Clients > Create client > **Web application**, any name, no redirect URIs. Its client ID is
  not a secret. It goes in two places:
  - `server/wrangler.toml` `[vars]` `GOOGLE_WEB_CLIENT_ID`;
  - `app.googleWebClientId` in `~/.gradle/gradle.properties` (the app passes it to Credential Manager as the server
    client id).
- **2.4 Android clients**: Clients > Create client > **Android**, one per signing key, with package name =
  `<package>` and the key's SHA-1. Put the Android client IDs, comma separated, in `server/wrangler.toml`
  `GOOGLE_ANDROID_CLIENT_IDS`. The server then accepts ID tokens made for this app only (`azp` check); empty accepts
  any app of the project. Sign-in fails on a phone whose app has no Android client for its package and signature, so
  add one for every build that should sign in: `<package>` with your release key and, only if you want Google
  sign-in in debug builds, `<package>.debug` with your debug key. Then deploy again (4.2).
  - Read a key's SHA-1 with keytool (`JAVA_HOME` must point at a JDK):
    ```bash
    "$JAVA_HOME/bin/keytool" -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android
    "$JAVA_HOME/bin/keytool" -list -v -keystore <your-release-keystore> -alias <alias>
    ```
    Or `./gradlew :app:signingReport` from the repository root, and copy the SHA1 of the variant.
- **2.5 Keep the clients alive.** Google deletes an OAuth client that has had no token request and no settings change
  for six months. It emails the project contacts 30 days before and again after, and a deleted client can be restored
  within 30 days at Clients > Deleted credentials. Keep the contact email one you read.
  - If the Web client is lost for good, nobody can sign in. Make a new one, put its ID in `GOOGLE_WEB_CLIENT_ID` and
    `app.googleWebClientId`, deploy, and ship a new app build: the ID is built into the app.
  - If an Android client is lost, make a new one for that key and update `GOOGLE_ANDROID_CLIENT_IDS`.

## 3. Link page project and link host (Pages)

Do this before the first deploy (4): `npm run deploy` refuses while `LINK_HOST` and `WEB_ORIGIN` hold a placeholder.

Fills: `LINK_HOST` and `WEB_ORIGIN` in `server/wrangler.toml`, `app.linkHost`, the project name the page deploy uses.

- **3.1 Choose the Pages project name** (`<name>`: lowercase letters, digits and dashes).
  - Its host is the address on every link people share (`https://<host>/pay/<token>`). Changing it after a link has
    gone out breaks them all.
  - pages.dev names are shared by every Cloudflare account. If `<name>` is taken, Cloudflare adds a few random
    characters (`<name>-xxxx.pages.dev`). Pick a distinctive name: a new account can create only a few Pages projects
    in its first 48 hours.
- **3.2 Create the project only.** Do not deploy the page yet: that needs the Worker's address (4.3). Run it from
  `server/web`, never from `server/`:
  ```bash
  cd server/web
  npm ci                                # node_modules is git-ignored
  npx wrangler pages project create <name> --production-branch main
  ```
  Success looks like `Successfully created the '<name>' project. It will be available at https://<host>/ once you
  create your first deployment.` Copy `<host>` from that line: it can differ from `<name>.pages.dev`. It is also in
  the Project Domains column of `npx wrangler pages project list`.

  **AI coding agents.** Recent wrangler versions change this command when an environment variable shows an AI agent,
  such as `CLAUDECODE`. It then makes no Pages project: it prints "Delegating to the latest version of Cloudflare
  Pages, now part of Cloudflare Workers" and runs `wrangler deploy` in the current folder, which deploys a Worker
  named `<name>`. So:
  - Run it in a plain terminal, not one opened from an AI tool. This prints nothing when the common markers are
    absent: `env | grep -E 'CLAUDECODE|CODEX_THREAD_ID|OPENCODE|CURSOR_TRACE_ID|GEMINI_CLI|ANTIGRAVITY'`. Wrangler
    knows about twenty tools, so an empty result is not proof; a plain terminal, or the hidden `--force` flag, which
    keeps the command on Pages, is the real safeguard.
  - Once the project exists, `pages deploy` to it is never handed over. So create the project before the first page
    deploy, and give the deploy the exact project name.

  If the output says "Delegating ..." anyway, stop:
  - Delete the Worker `<name>` at once: dashboard > Workers & Pages > `<name>` > Settings > Delete. Run from
    `server/`, it is a copy of the API: bound to the real D1 database, with the daily cron and no `SESSION_SECRET`.
  - List the files changed in the last 15 minutes, from the repository root:
    ```bash
    find server -type f -mmin -15 -not -path '*/node_modules/*' -not -path '*/.wrangler/*' -not -path '*/dist/*'
    ```
    Undo every change there that you did not make yourself: delete new files (for example `wrangler.jsonc` or
    `.assetsignore` in `server/web`), and take wrangler's additions out of changed files (`package.json`,
    `.gitignore`, `server/web/wrangler.toml`). If `package.json` or `package-lock.json` changed, put them back, then
    run `npm ci` in that folder.
  - Then redo the step in a plain terminal, or with `--force`.
- **3.3 Put the host in**, exactly as wrangler printed it.
  - `server/wrangler.toml`: `LINK_HOST = "<host>"`, `WEB_ORIGIN = "https://<host>"`. The Worker builds links from
    `LINK_HOST` and lets the page call it only from `WEB_ORIGIN`.
  - `gradle.properties`: `app.linkHost=<host>`. An `app.linkHost` line in `~/.gradle/gradle.properties` would
    override it: keep the two the same, or leave it out there.
  - `server/web/wrangler.toml`: `name = "<name>"`, so that file names your project.

## 4. Deploy: the Worker, then the link page

**4.1 Before the first deploy.** Needed: 1.6 (`SESSION_SECRET`), 2.3 (`GOOGLE_WEB_CLIENT_ID`) and 3.3 (`LINK_HOST`,
`WEB_ORIGIN`). Push (6) can come later. After a later change to `server/wrangler.toml` (`FCM_PROJECT_ID`,
`GOOGLE_ANDROID_CLIENT_IDS`), run `npm run deploy` again. A `wrangler secret put` needs no redeploy.

- **4.2 Deploy the Worker.** `preview_urls = false` in `server/wrangler.toml` keeps a public address for the current
  version only.
  ```bash
  cd server
  npm test
  npm run typecheck
  npx wrangler d1 migrations list <db> --remote    # what the live database still needs
  npx wrangler d1 migrations apply <db> --remote   # only if it listed one; asks before it applies
  npm run deploy        # runs scripts/check-prod-config.mjs, then scripts/check-remote-migrations.mjs, then wrangler deploy
  ```
  **Every later deploy too: migrations first, then the Worker.** A new Worker can read columns that only a new
  migration adds; deployed before its migration, the routes that read them answer 500. A migration alone is safe
  for the Worker that runs: migrations only add columns and tables. `npm run deploy` refuses while `migrations list`
  names a migration, or when it cannot tell (not signed in to Cloudflare, no network). Before a migration on a
  database with real users, take a D1 Time Travel bookmark (`npx wrangler d1 time-travel info <db>`) so you can
  restore it.

  Check that the address it prints is `https://<worker>.<subdomain>.workers.dev`. Then:
  ```bash
  curl https://<worker>.<subdomain>.workers.dev/v1/health                 # {"ok":true}
  curl -X POST https://<worker>.<subdomain>.workers.dev/v1/auth/dev       # must be 404
  ```
  If curl reports 523 or cannot resolve the host right after the first deploy, wait a minute or two and run it
  again: a new workers.dev address takes a moment.

  Put the address in `~/.gradle/gradle.properties`:
  ```properties
  app.apiBaseUrl=https://<worker>.<subdomain>.workers.dev
  ```
  `check-prod-config.mjs` refuses to deploy while `wrangler.toml` contains `development`, an `[env.*]` section,
  `invocation_logs` other than `false`, or a placeholder (`REPLACE_ME`, the all-zero `database_id`, `xpense.app`).
  Never deploy with `--var ENVIRONMENT:...`; even then the dev sign-in stays off, because it also requires a local
  host name. If `wrangler deploy` refuses the `[[ratelimits]]` blocks, see 5.
- **4.3 Deploy the link page**, after 4.2. The build needs the Worker's address: it writes it into the page's config
  and its Content-Security-Policy. First read the SHA-256 of every key that signs an app which should open the links
  (`keytool ... | grep SHA256`, as in 2.4). Then deploy:
  ```bash
  cd server/web
  PAGES_PROJECT=<name> \
  API_BASE=https://<worker>.<subdomain>.workers.dev \
  ANDROID_PACKAGE=<package> \
  ANDROID_CERT_SHA256=<SHA-256 fingerprints> \
  APP_DOWNLOAD_URL=<https link to your APK> \
  npm run deploy
  ```
  - `PAGES_PROJECT` is the project name from 3.2, not the host. The build refuses an empty or malformed one.
  - `ANDROID_PACKAGE` is `app.applicationId`. A production build refuses to run without it.
  - `ANDROID_CERT_SHA256`: SHA-256 fingerprints (`AA:BB:...`, 32 pairs), comma separated. Do not edit
    `server/web/public/.well-known/assetlinks.json` by hand: the build overwrites it. If the build warns
    "assetlinks.json keeps the placeholder fingerprint", stop: `ANDROID_CERT_SHA256` was empty.
  - `APP_DOWNLOAD_URL`: where people get the app. The pay page's "Get SumBuddy (optional)" and the family invite
    page's "Get SumBuddy" point at it. https only; a production build also refuses a local or placeholder host. The
    maintainer's page uses `https://github.com/akhilux-design/sumbuddy/releases/latest/download/sumbuddy.apk`, which
    always serves the newest release (`docs/releasing.md`). With no download link the button stays hidden.
  - `PLAY_STORE_URL` is still accepted (the build checks that its id matches `ANDROID_PACKAGE`); when both are set,
    `APP_DOWNLOAD_URL` wins. SumBuddy itself is not on Google Play.
  - The name on the page is `app_name` in `app/src/main/res/values/strings.xml` (or the build's `APP_NAME`).
  - The script deploys to the production branch `main`. Ignore the "Take a peek over at https://<hash>.<host>"
    address it prints: it belongs to that one deployment.

  Check in a browser:
  - `https://<host>/` shows the one-line page.
  - `https://<host>/.well-known/assetlinks.json` has `<package>` and your fingerprint(s).
  - `https://<host>/pay/AAAAAAAAAAAAAAAAAAAAAAAA` (an unknown 24-character token) shows "This link no longer
    works". That proves the page reached the Worker. "Could not load this page" means `WEB_ORIGIN` does not match
    the host, or `API_BASE` is wrong.

  "Deploy the page again" below means this same command, with whatever changed.

## 5. Check the free limits

Before the first link goes to a real person, re-read each page and compare with `docs/server.md` 8:

- Workers: <https://developers.cloudflare.com/workers/platform/pricing/> and limits (100,000 requests a day, 10 ms CPU
  per request and per cron run, 50 subrequests).
- D1: <https://developers.cloudflare.com/d1/platform/pricing/> (5 M rows read, 100,000 rows written a day, 500 MB per
  database, 50 queries per invocation).
- Durable Objects: <https://developers.cloudflare.com/durable-objects/platform/pricing/> (SQLite-backed classes on
  Free; WebSocket hibernation).
- **Rate Limiting bindings on Free**: if `wrangler deploy` refuses the `[[ratelimits]]` blocks, delete all of them
  from `server/wrangler.toml` (the tests accept all or none). The Worker then uses its built-in per-isolate limiter
  with the same numbers (`server/src/ratelimit.ts`). Nothing else changes.
- Workers Logs (`[observability]`): keep `invocation_logs = false`.
- Pages: available on all plans.
- Firebase Spark: Cloud Messaging is listed as no-cost, and the plan needs no payment method.

## 6. Push (Firebase, messaging only)

Optional: without push the app works, a little less promptly. It can come after 4.

Fills: `FCM_PROJECT_ID` in `server/wrangler.toml`, the `FCM_SERVICE_ACCOUNT_JSON` secret, the four `app.fcm.*`.

- **6.1 Billing check again** (as in 2.1). Firebase does not let you choose the plan. A project with billing linked
  becomes Blaze (pay-as-you-go); one without stays on Spark (no cost). Billing > My projects must read "Billing is
  disabled" for the project before you go on.
- **6.2 Add Firebase to the Google Cloud project.** At <https://console.firebase.google.com/>, click the button to
  create a new Firebase project, but do not type a name. At the bottom of the page click **Add Firebase to Google
  Cloud project**, select `<project-id>`, then **Open project**. Leave Gemini in Firebase and Google Analytics off,
  then click **Add Firebase**.
  - Typing a name in the first field creates a second, separate project instead.
  - Afterwards, check that the console shows the Spark (no-cost) plan.
  - Adding Firebase turns on the Cloud Messaging and Firebase Installations APIs and makes an API key. Nothing else
    needs enabling.
- **6.3 Register the Android app.** Project settings > General > Add app > Android, package name `<package>`.
  Firebase treats the package name as case-sensitive, and it cannot be changed after registration. No SHA-1 is
  needed in Firebase. Download `google-services.json` only to read four values from it, then delete it (the app does
  not use the `google-services` plugin; the file is git-ignored):

  | `google-services.json` | goes in `~/.gradle/gradle.properties` |
  |---|---|
  | `project_info.project_id` | `app.fcm.projectId` (and `FCM_PROJECT_ID` in `server/wrangler.toml`) |
  | `mobilesdk_app_id` of your app's `client` entry | `app.fcm.applicationId` |
  | `api_key[0].current_key` of your app's `client` entry | `app.fcm.apiKey` |
  | `project_info.project_number` | `app.fcm.senderId` |

  Your app's `client` entry is the one whose `client_info.android_client_info.package_name` is `<package>`. With one
  Android app it is `client[0]`.
- **6.4 Restrict the API key.** Google Cloud > APIs & Services > Credentials. Open the key whose value equals
  `current_key`, usually "Android key (auto created by Firebase)". Leave the "Browser key" alone.
  - Application restrictions: **Android apps**. Add one row per signing key, each with package name `<package>` and
    the key's SHA-1 (2.4).
  - Do not change API restrictions: Firebase has set them. If you ever edit them, keep Firebase Installations API
    (`firebaseinstallations.googleapis.com`) and FCM Registration API (`fcmregistrations.googleapis.com`).
  - If push stops working on a build after this change, that build's signing SHA-1 is missing from the key.
- **6.5 Cloud Messaging API.** Project settings > Cloud Messaging: check that "Firebase Cloud Messaging API (V1)" is
  enabled. Do not enable the legacy API.
- **6.6 Service account for the Worker**, with one role only. Google Cloud > IAM & Admin > Service accounts > Create:
  name `fcm-sender`, role **Firebase Cloud Messaging API Admin**, nothing else. Then click the `fcm-sender` email >
  Keys tab > Add key > Create new key > JSON > Create. The file downloads once. Put it in the secret straight from
  Downloads and delete it:
  ```bash
  cd server
  npx wrangler secret put FCM_SERVICE_ACCOUNT_JSON < ~/Downloads/<file>.json
  rm ~/Downloads/<file>.json      # and empty the bin
  ```
  - Never move or copy the key into the repository. Google does not document the download's file name, and the file
    can be renamed, so `.gitignore` may not catch it. Its `*service-account*.json` line is a best-effort guard only.
  - If key creation is refused with an organization-policy message, the project sits under an organization that
    enforces "Disable service account key creation" (`iam.managed.disableServiceAccountKeyCreation`). An organization
    admin must turn it off for this project.
  - If the key ever leaks: delete it in the console (Keys tab), make a new one, `wrangler secret put` again.
- **6.7 Turn push on.**
  - `server/wrangler.toml`: `FCM_PROJECT_ID = "<project-id>"`, then `npm run deploy` in `server/`.
  - `~/.gradle/gradle.properties`: the four `app.fcm.*` values. Build and install the app again, so it carries them.
  - Push turns on once the deployed Worker has both `FCM_PROJECT_ID` and the `FCM_SERVICE_ACCOUNT_JSON` secret.
    Either can come first.
  - Each signed-in phone sends its token the next time the app starts (`docs/server.md` 9.6).
  - Check: `npx wrangler d1 execute <db> --remote --command "SELECT COUNT(*) AS phones FROM devices"`. The count
    should match the number of phones signed in with a release build.

## 7. Before a release

- **7.1 Release signing key.** For the key that signs your release APK: an Android client for its SHA-1 (2.4), its
  ID in `GOOGLE_ANDROID_CLIENT_IDS` and `npm run deploy`; its SHA-1 on the API key (6.4); its SHA-256 in
  `ANDROID_CERT_SHA256` and the page deployed again (4.3). Keep the keystore and its passwords out of the
  repository (`*.jks` and `*.keystore` are git-ignored). Without these, sign-in, push and App Links fail on every
  install of that APK.
- **7.2 Publish the sign-in app and verify the brand** (Google Auth Platform).
  - Audience > **Publish app**. Only non-sensitive scopes are used, so no review is needed.
  - **Brand verification**, so the app name and logo appear on the Sign in with Google sheet. It needs a public home
    page that says what the app does and a privacy policy (`PRIVACY.md` in this repository is SumBuddy's). Both must
    be on a domain you control by DNS: Google wants a Search Console **Domain** property, verified with a DNS record,
    by an account that is Owner of the Cloud project. A `github.io` address cannot pass (GitHub runs its DNS; Google
    refused it on 2026-11-24). Enter both URLs in Branding and add the domain under Authorized domains.
  - Submit in Verification Center. The automated check usually takes minutes; a manual review usually takes 2-3
    business days.
- **7.3 Last chance for the workers.dev subdomain** (1.5). If you change it, update `app.apiBaseUrl` and deploy the
  page again with the new `API_BASE`.
- **7.4 App Links on a phone.** On an Android 12 or later phone or emulator that is online, with a build signed by a
  key in `ANDROID_CERT_SHA256`:
  ```bash
  adb shell pm set-app-links --package <package> 0 all
  adb shell pm verify-app-links --re-verify <package>
  # wait a few minutes
  adb shell pm get-app-links <package>
  ```
  `<host>` should show `verified`. `none` means the check has not finished: wait and run it again. A code of 1024 or
  more is a verifier error: check the network and the fingerprints.
- **7.5 Release build.** `./gradlew :app:assembleRelease` fails until `app.apiBaseUrl`, `app.googleWebClientId` and
  `app.linkHost` hold real values. `docs/releasing.md` describes the maintainer's release.
- **7.6 Free limits** read again (5).

## 8. Watch it

- **Database size**, the limit that does not reset: `npx wrangler d1 info <db>`. At 400 MB decide between Workers
  Paid (10 GB per database) and a second free database split by group id (`docs/server.md` 8).
- **CPU time**: dashboard > Workers & Pages > `<worker>` > Metrics. The free limit is 10 ms per request; watch
  sign-in (Google key check), the first push of each hour (FCM token signing) and big link pages.
- **Daily limits**: dashboard > Workers & Pages and D1 > Metrics. When a daily limit is hit, requests fail until the
  daily reset (00:00 UTC) and nothing is billed; phones keep their changes and retry.
- **Logs**: Workers Logs show one line per request (method, route template, status, error code, duration) and never
  ids, tokens, names or amounts.
- **OAuth clients**: Google's warning email about an unused client (2.5).

## Where each value goes

| Value | Secret? | Where | Step |
|---|---|---|---|
| D1 `database_id` | no | `server/wrangler.toml` | 1.3 |
| `SESSION_SECRET` | yes | `wrangler secret put` (prod); `server/.dev.vars` (local, git-ignored) | 1.6 |
| `GOOGLE_WEB_CLIENT_ID` | no | `server/wrangler.toml` `[vars]` and `app.googleWebClientId` | 2.3 |
| `GOOGLE_ANDROID_CLIENT_IDS` | no | `server/wrangler.toml` `[vars]`, comma separated | 2.4, 7.1 |
| `app.applicationId` | no | `gradle.properties`; `ANDROID_PACKAGE` of the page deploy | permanent once people install the app |
| `LINK_HOST`, `WEB_ORIGIN` | no | `server/wrangler.toml` `[vars]`; `app.linkHost` | 3.3 |
| Pages project name | no | `PAGES_PROJECT` of the page deploy; `server/web/wrangler.toml` | 3.2, 3.3 |
| Worker address | no | `app.apiBaseUrl`; `API_BASE` of the page deploy | 1.5, 4.2 |
| `FCM_PROJECT_ID` | no | `server/wrangler.toml` `[vars]` and `app.fcm.projectId` | 6.3, 6.7 |
| `FCM_SERVICE_ACCOUNT_JSON` | yes | `wrangler secret put` only; the key file is deleted | 6.6 |
| `app.fcm.applicationId`, `app.fcm.apiKey`, `app.fcm.senderId` | no (API key restricted to the app) | `~/.gradle/gradle.properties` | 6.3 |
| Signing key SHA-1s | no | Google Cloud Android clients (2.4); the FCM API key's Android restriction (6.4) | 2.4, 6.4, 7.1 |
| Signing key SHA-256s | no | `ANDROID_CERT_SHA256` of the page deploy, which writes `assetlinks.json` | 4.3, 7.1 |
| App download URL | no | `APP_DOWNLOAD_URL` of the page deploy | 4.3 |

Your values for the app go in `~/.gradle/gradle.properties` or `ORG_GRADLE_PROJECT_<name>` environment variables,
which override the values committed in `gradle.properties` (the maintainer's public server; the `app.fcm.*` values
are not committed).

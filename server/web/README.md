# Friends' web link page

The page a friend without the app opens from a reminder: `https://<LINK_HOST>/pay/<token>`. Static files for
Cloudflare Pages, plain HTML, CSS and one script. The contract is `docs/server.md` section 7 (data: 4.10).

```bash
cd server/web
npm install
npm test               # node --test + happy-dom: every page state, text safety, headers, tokens, build checks
npm run preview:mock   # every state from test fixtures, no Worker needed; prints the links
npm run preview        # the page against the local Worker (start it in server/ with `npm run dev`)
npm run seed:local     # makes groups and share links on the local Worker, checks each answer, prints the links
```

Open pages at `http://localhost:8788/...` (not 127.0.0.1): the Worker allows that origin in development.

- `public/` is the site. `build.mjs` copies it to `dist/`, writes `tokens.css` from `design/spec`, copies the 3D icons
  from `design/icons/svg` and writes `config.js`.
- Build settings (environment): `API_BASE`, `APP_DOWNLOAD_URL`, `PLAY_STORE_URL`, `APP_NAME`, `ANDROID_PACKAGE`, `ANDROID_CERT_SHA256`,
  and `PAGES_PROJECT` for a deploy. `APP_NAME` defaults to `app_name` in `app/src/main/res/values/strings.xml`, the
  one place the app's name lives.
- `--production` refuses an http, local or placeholder `API_BASE`, needs `ANDROID_PACKAGE`, and refuses a
  `PLAY_STORE_URL` for another package or an `APP_DOWNLOAD_URL` on a local or placeholder host. It does not refuse a
  missing fingerprint: it warns.
- "Get <app name>" (on the pay page and the family invite page) points at `APP_DOWNLOAD_URL` (https only; the APK on
  GitHub Releases) when it is set, else at `PLAY_STORE_URL`. With neither, the button is hidden.
- Fonts in `public/fonts/` come from Fontsource (`npm run fonts`), SIL Open Font License; the licence texts are there.

## Deploy (owner)

The owner's checklist is `docs/server-setup.md` sections 3 and 4. These are the same steps in short. Run every
command here from `server/web`, never from `server/`. Do the steps in this order.

1. Create the Pages project once (setup 3.2):

   ```bash
   cd server/web
   npx wrangler pages project create <name> --production-branch main
   ```

   It must print "Successfully created the '<name>' project. It will be available at https://<host>/". Use that
   `<host>` for `LINK_HOST`, `WEB_ORIGIN = "https://<host>"` and `app.linkHost`. It can differ from
   `<name>.pages.dev` when the name is taken. `PAGES_PROJECT` stays `<name>`.

   Wrangler checks whether an AI agent runs it (`CLAUDECODE` and similar variables). Run by an agent, it does not
   create a Pages project: it prints "Delegating ... Cloudflare Workers" and runs `wrangler deploy` in the current
   folder instead. From `server/` that deploys a copy of the API Worker under the Pages name. Only `--force` keeps
   the command on Pages, and an agent adds `--force` only when the owner approves it. Simplest: run step 1 yourself
   in the Terminal app, not in a terminal opened from Claude or another AI tool. First check that
   `env | grep -E 'CLAUDECODE|CODEX_THREAD_ID|OPENCODE|CURSOR_TRACE_ID|GEMINI_CLI|ANTIGRAVITY'` prints nothing.
   If you see "Delegating", stop, delete the stray Worker (dashboard > Workers & Pages > `<name>` > Settings >
   Delete) and do step 1 again.

2. Put the host in `LINK_HOST`, `WEB_ORIGIN` and `app.linkHost` (setup 3.3), then deploy the Worker (setup 4.2). The
   page needs the Worker's address for `API_BASE`.

3. Deploy the page (setup 4.3):

   ```bash
   cd server/web
   npm ci
   PAGES_PROJECT=<name> \
   API_BASE=https://xpense-api.<subdomain>.workers.dev \
   ANDROID_PACKAGE=<app.applicationId> \
   ANDROID_CERT_SHA256=<release key SHA-256> \
   APP_DOWNLOAD_URL=https://github.com/akhilux-design/sumbuddy/releases/latest/download/sumbuddy.apk \
   npm run deploy
   ```

   `npm run deploy` stops at once when `PAGES_PROJECT` is empty or is not a project name (a host or URL). It then
   builds with `--production`, prints the project, package and fingerprints, and runs
   `wrangler pages deploy dist --project-name "$PAGES_PROJECT" --branch main`. Since the project's production
   branch is `main` (step 1), this is a production deploy whatever git branch you are on. `--branch` also keeps
   wrangler from handing the deploy to Workers.

   - `ANDROID_PACKAGE` is required and must equal `app.applicationId`.
   - `ANDROID_CERT_SHA256` is a comma list of SHA-256 fingerprints (`AA:BB:...`, 32 pairs): the release signing
     key (the certificate `scripts/release.sh` checks). Debug builds have the app id `<app.applicationId>.debug`,
     which `assetlinks.json` does not list, so App Links do not verify for a debug build. Left
     empty, the build keeps the placeholder and prints a warning: App Links do not verify until you deploy again
     with the keys. Do not edit `public/.well-known/assetlinks.json` by hand: the build writes it.
   - `APP_DOWNLOAD_URL` and `PLAY_STORE_URL` are optional; the app is not on Play, so only the first is used. When
     both are set, `APP_DOWNLOAD_URL` wins. With neither, the "Get <app name>" button is hidden.
   - If wrangler asks "The project you specified does not exist ... create it?", stop with Ctrl+C. The name is wrong
     or step 1 was skipped. A project made there asks for a production branch (default `production`), and
     `--branch main` would then only make preview deployments.

4. Open `https://<host>/.well-known/assetlinks.json` and check the package and fingerprints. Open a `/pay/<token>`
   link from a test group and check that it loads. The "Take a peek over at https://<hash>.<host>" address wrangler
   prints belongs to that one deployment: links use `<host>`.

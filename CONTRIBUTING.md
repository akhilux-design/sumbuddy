# Contributing to SumBuddy

Thank you for helping. Bug reports, SMS formats the app gets wrong, and small fixes are the most useful things right
now.

## Before you start

- **Never share real SMS, account numbers, card numbers, UPI IDs or phone numbers**, in an issue, a pull request, a
  test or a screenshot. When a bank's SMS is read wrong, copy its shape and replace every name, digit, reference and
  amount with made-up ones. Every SMS in this repository is synthetic.
- For anything larger than a fix, open an issue first, so we can agree on it before you spend time.
- Security problems: do not open a public issue; see [SECURITY.md](SECURITY.md).

## How the project works

- **The design is the source of truth.** Screens match `design/spec` exactly: spacing, corner radius, colours by
  token name and text styles. `design/spec` is the exported copy of the maintainer's Figma file, which is private. A change to what a screen
  shows needs the design and the app changed together, so please open an issue for UI changes; the maintainer
  updates the design.
- Use theme tokens, never raw colour values, in screens and components. Text is never smaller than 14sp, touch targets
  are at least 48dp, and state is never shown by colour alone. No emoji as icons.
- **SMS content never leaves the phone and is never written to logs.** The server gets only what a person chooses to
  share (`docs/server.md` 10).
- `parser/`, `splits/` and `splitwise/` are plain Kotlin with no Android dependencies. `splits/` must give the same
  answers as the server (`server/test/vectors/`).
- Versions live in `gradle/libs.versions.toml`.
- `docs/product.md` holds the product decisions; read it before changing behaviour.

## Build and test

You need JDK 21 and the Android SDK. From the repository root:

```bash
./gradlew :parser:test :splits:test :splitwise:test :app:testDebugUnitTest :app:lintDebug
(cd server && npm ci && npm test && npm run typecheck)
(cd server/web && npm ci && npm test)
```

A parser fix comes with a test of the made-up SMS it fixes, and with the cases around it that must not change.
To try splitting end to end, run the server locally (`README.md`) and use a debug build, which offers Dev sign-in.
Debug builds have the app id `com.akhilux.xpense.debug`, so they install next to the released app.

## Pull requests

- Keep each pull request to one change, with tests, and say what you checked.
- All tests and lint pass.
- Never commit secrets: `server/.dev.vars`, keystores, `google-services.json` or service-account files
  (`.gitignore` guards most of them).

## Licence of contributions

SumBuddy is licensed under the GNU General Public License, version 3 or (at your option) any later version
(`GPL-3.0-or-later`), with an additional permission under section 7 for the Google Play services, Firebase and Google
Identity libraries ([README.md](README.md#licence)). By submitting a
contribution, you agree that it is licensed under the same terms, including that additional permission, and that you
have the right to license it so.

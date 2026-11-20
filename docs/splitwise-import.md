<!-- Spec for Import from Splitwise. Written 2026-11-18 from a survey of the code, docs and design. -->
# Import from Splitwise: implementation spec

Status: built and live (2026-11-20). The server route and its rate limit, the `:splitwise` module and the app (Room 5, sync, both screens, both entry points) are built, reviewed and tested; the Worker with the import route was deployed on 2026-11-20 (version `3db84b59`, docs/server-setup.md 4.2). Where the build differs from this spec, the text below says "As built" and docs/server.md has the full notes ("Build notes, Splitwise import", "Review fixes, Splitwise import", "Build notes, import rate limit", "Build notes, Splitwise import on the phone" and "Review fixes, Splitwise import on the phone"). Written 2026-11-18 from `docs/product.md`, `docs/server.md` (3, 3.1, 4, 5, 6, 8, 9, 12, 13, build notes), `server/src`, `server/migrations`, `server/test`, the app under `app/src/main/kotlin/com/expenses/app` and `design/spec`. This spec covers the server, the app and the design (Figma boards, Flow page, `flow.md`, the export), so it can be built as one piece of work, as CLAUDE.md requires.

## 0. What ships

- Both entry points return. **New group** gets "Import from Splitwise instead" (Ghost Medium under Create group, design 26). **You** gets a "More" section with "Import from Splitwise · Bring groups and balances" (Info Row, Icon/repeat 3D, in a List card, design 04-you/00). Plus stays hidden. Groups (empty) still does not offer import (product.md, 2026-11-18).
- **Import from Splitwise** (28 to 30, plus the new states in section 7). The person picks the group's `.csv` with the Storage Access Framework. The phone reads it in memory with the new pure Kotlin module `:splitwise`. The group name comes from the file name and can be edited. The person says which column is them. Import then writes the group to Room and the outbox in one transaction, so it is offline-first. The screen shows progress until the server has everything.
- **Server.** Group creation uses the existing `POST /v1/groups`. One new route, `POST /v1/groups/{gid}/import`, writes up to 50 expenses or settlements per request in one D1 batch. Each item still gets its own version and change row, so pulls, the version counter and the outbox are unchanged. Delete group gets one rule change (3.3). **No D1 migration.**
- **Group imported** (31, changed). "Share invite links" sends one message, usually to the group's WhatsApp chat, with each friend's own pay/invite link (the existing per-place links of 4.10). Then the new group's page opens.

## 1. The Splitwise export: research

### 1.1 Sources

- Splitwise help, "How can I double check my balances?" (kb.splitwise.com/balances-and-expenses/how-can-i-double-check-my-balances, redirected from the old article 88333):
  - On mobile: open the group or friendship and tap **Export** at the end of the header menu. On the web: gear icon, then **Export Spreadsheet**.
  - "The export shows each transaction as a row." Each person's column shows how that row changed their balance: "payments and paid expenses are positive, borrowed amounts and received payments are negative".
  - A "Cost" column holds the total of each transaction.
  - The same export exists for a friendship (two people).
- Spliit issue #22 (github.com/spliit-app/spliit/issues/22):
  - Header `Date, Description, Category, Cost, Currency, <one column per user>`. Each user value is "paid minus owed".
  - The last row holds each user's total, which is the column sum.
  - Comments say payments carry the category "Payment" ("Zahlung" in German), so exports are localised. People removed empty lines and the last line before importing.
- Spliit issue #342 (github.com/spliit-app/spliit/issues/342) has a full sample, including:
  - `2025-05-17 | Bob paid Carol | Payment | 10 | USD | 0 | 10 | -10 | 0`: the payer of a settle-up is positive.
  - `Income | General | 30 | USD | -20 | 10 | 10 | 0`: two positives.
  - `Self-payment | General | 100 | USD | 0 | 0 | 0 | 0`: all zero.
  - `2025-05-17 | Total balance |  |  | USD | -10 | 0 | 10 | 0`: the total row carries the last date, the description "Total balance", blank Category and Cost, and the currency.
- TheFacc/Splitwise-CSV-Analytics README:
  - Sample `2024-06-25,Grocery run,Groceries,98.50,USD,55.03,0.00,-42.69,-12.34`, which sums to exactly 0.
  - The file does not say who paid. The project assumes one payer, and takes the payer's positive value minus the cost as their share.
- Cospend issue #203 (github.com/julien-nc/cospend-nc/issues/203):
  - Sample `2022-07-05,desc,cat1,8.20,EUR,-2.73,5.47,-2.74`, with uneven cents.
  - Payer inference: the one positive member paid the Cost. If the Cost equals that positive value, the payer is not in the split.
- ChristopherJohnston gist a6e69bd8894f20ecfe127fa4149bd013:
  - Uses ISO dates ("2020-10-02") and amounts in decimals ("208.01").
  - Its payment row is `Participant A. pays Participant B. | Payment | 208.01 | GBP | 208.01 | -208.01`.
- splitwise-to-ynab sample (npm, quoted in search results): `3/31/19,Total balance, , ,USD,-19,19`.
  - The blank cells of the total row are single spaces.
  - That file's dates were M/D/YY, most likely after Excel re-saved it. Every first-hand sample from 2020 to 2025 uses `YYYY-MM-DD`.
- dev.splitwise.com: cost is a decimal with at most 2 places. Currency codes are mostly ISO 4217. Categories are subcategories with an "Other" per parent, and "General" is the default.
- Cloudflare D1 limits (developers.cloudflare.com/d1/platform/limits):
  - 50 queries per invocation on Free, and a batch counts as one.
  - No fixed statement cap per batch.
  - 100 bound parameters and 100 KB per statement, 30 s per query.

### 1.2 File layout the parser accepts

- **Encoding.** UTF-8. A leading BOM (U+FEFF) is dropped. Lines end in CRLF, LF or CR.
- **CSV.** RFC 4180: comma-separated. A field that starts with `"` is quoted, `""` inside it is a quote, and commas and line breaks inside quotes belong to the field. An unterminated quote makes the file unreadable.
- **Cells.** Spaces and tabs at both ends of every cell are trimmed, since the total row's blank cells are a single space. Records whose cells are all blank are skipped, both blank lines and `, , ,`.
- **Header.** The first non-blank record.
  - Its first five cells, trimmed and lower-cased, must be exactly `date, description, category, cost, currency`.
  - The cells after them are the people, in file order. Blank cells at the end are dropped. A blank cell between two names makes the file unreadable.
  - Only English headers are accepted, so a translated export reads as "not a Splitwise export" (risk).
- **Data rows.** A row needs at least 5 + people cells. Extra cells at the end must be blank.
- **Total balance row.** Description equals `Total balance` (any case) and Cost is blank. It is never imported. Its values are a checksum per currency (1.4).
- **Date.** `YYYY-MM-DD`, Splitwise's own format. A trailing time after `T` or a space is ignored.
  - For files re-saved in Excel, `D/M/YYYY`, `M/D/YYYY` and 2-digit years (20yy) are also accepted, with one order per file.
  - Day-first if any first number is above 12. Otherwise month-first if any second number is above 12. Otherwise day-first (India).
  - Anything else makes the file unreadable.
- **Money (Cost and each person's value).** An optional sign, digits and at most 2 decimals, converted to paise with integer string arithmetic and never with floating point.
  - Grouping commas are allowed only in the patterns `^-?\d{1,3}(,\d{2,3})+(\.\d{1,2})?$`, for Indian or Western grouping after an Excel save.
  - `-0.00` is 0.
  - A row in INR with a value that does not parse makes the file unreadable. Values in other currencies are only counted, never parsed strictly.
- **Currency.** The cell is upper-cased. A blank cell makes the file unreadable.

### 1.3 Recovering payer and shares (per INR row)

Write `n_p` for person p's value in paise and `C` for the Cost. Each row is checked and classified in this order:

1. **Σ n_p ≠ 0** (to the paisa): the file does not add up. Splitwise rows are exact.
2. **All `n_p` = 0**: the row changes no balance (one person paid only for themselves). It is left out silently and counted as `noEffectRows`.
3. **`C` ≤ 0** with some `n_p` ≠ 0: the file does not add up.
4. **Payment.** Category is `Payment` (any case) and the row has exactly one positive `+a`, one negative `-a`, and `a == C`. It becomes a **settlement** `from` = the positive person (who handed over money), `to` = the negative person, amount `a`. A Payment row that does not fit goes through rules 5 and 6, which still keeps balances right.
5. **One positive person P.** One expense paid by P.
   - `amount = max(C, n_P)`. P's share is `amount − n_P` (0 when P was not in the split, as in "paid for others").
   - Every negative person i has share `−n_i`. Shares of 0 are not listed.
   - The shares add up to `amount`, and the nets come out exactly as in the file.
6. **Several positive people** (several payers, or an "income" row).
   - The real split cannot be recovered from nets alone, so the row becomes one expense per positive person j, in column order: paid by j, `amount = n_j`.
   - Shares are filled greedily from the negative people in column order: each takes `min(need, remaining debt)`.
   - Nets are reproduced exactly. The Cost is not kept (the parts add up to Σ positives ≤ C). Each part keeps the row's description, category and date.
   - On the phone, the person's own share of such a row is right when they only owed. When they were one of the payers, it shows as 0 (risk).

### 1.4 Rows and files that cannot come across, and limits

| Case | Phone behaviour |
|---|---|
| Rows not in INR | Left out, counted per currency, and named on 29 ("3 expenses in other currencies stay out. Only rupees come across."). This matches Splitwise, which keeps a separate balance per currency unless converted. No conversion: zero cost, and foreign currencies are out of scope (server.md 12). |
| No INR rows at all (including an empty export) | Refused: "Nothing to bring across". |
| A row whose nets do not sum to 0, or Cost ≤ 0 with nets | Refused: "This file does not add up". |
| Only INR, exactly one INR Total balance row, and a column whose sum of imported values differs from it | Refused: "This file does not add up". The check is skipped for files with several currencies (the shape of their total rows is unknown, so this is a risk). |
| Fewer than 2 people | Refused: "Nobody else is in this file". |
| More than 50 people (server cap, members of 4.6) | Refused: "This group is too big". |
| More than 5,000 imported rows, or more than 5,000 server items after rule 6 | Refused: "This group is too big". Free-tier budget is in 3.5. |
| File over 5 MB (read at most 5 MB + 1 byte) | Refused: "This group is too big". |
| An amount above ₹1 crore (10^9 paise, 4.7) | Refused: "An expense is too big". |
| Date after today (Splitwise allows planned expenses) | Imported dated **today** (India). The server refuses dates after tomorrow. |
| Date before 2000-01-01 | Imported dated 2000-01-01. |
| Description | `GroupText.clean(text, 80)`. When blank, the category name is used, as `addGroupExpense` does. |
| Person name (header cell) | `GroupText.clean(name, 60)`. When blank, the file is unreadable. Duplicate names are allowed; columns are told apart by index. |

### 1.5 Group name

The CSV holds no group name: the columns are as listed, and no source shows one. Splitwise's download file name is not documented, and no source confirms a pattern. The design draws `flat-302-export.csv` becoming "Import Flat 302", and a third-party gist uses `test-group_export.csv`.

So the name is taken from the document's `OpenableColumns.DISPLAY_NAME` with `GroupNameFromFile.of(displayName)`:

1. Drop the extension `.csv` (any case) and a copy suffix ` (1)`, `(2)` and so on.
2. Drop a trailing `[ _-]export` (any case), then a trailing `[ _-]\d{4}-\d{2}-\d{2}`, then a trailing `[ _-]export` again.
3. Turn `_` and `-` into spaces and collapse runs of spaces.
4. If the result has no capital letter, capitalise the first letter of each word ("flat 302" becomes "Flat 302").
5. Apply `GroupText.clean(_, 40)`.
6. If the result is empty, or one of `export`, `splitwise`, `expenses`, `download`, `file` (any case), it becomes "": the field is blank and Import stays off until a name is typed.

29 shows the name in a **Group name** field, the same Input as New group. The button reads "Import {name}".

### 1.6 "Which one is you?"

The file has names only, so the person must say which column is them.

- 29 lists every person as an **Option Card**: Icon/user 3D, the name as title, and a subtitle from their INR balance (sum of imported values): "Is owed ₹2,350", "Owes ₹1,150" or "All settled".
- One card is preselected only on a unique match with the signed-in profile name, compared trimmed, lower-cased, with spaces collapsed:
  - first an exact full-name match,
  - otherwise a unique match of the first word.
- With no match nothing is selected, and Import is disabled until one is picked.
- The chosen column becomes the person's own member (`myMemberId`). That column's Splitwise name is dropped, because the server uses the user's own name.
- Everyone else becomes a **friend without the app**: name only, phone null, `NewPerson.Friend(name, null)`. They are never matched to the person's existing friends or app users. The "Names only" Info Row already says "The file has no phone numbers. Invite friends with a link after." Each friend can later take their place through their own link (4.10 invite accept).

## 2. Why the existing API is not enough

For a 1,000-row file through today's routes:

- **Requests.** One `PUT .../expenses/{eid}` or `PUT .../settlements/{sid}` per row: 1,000 Worker requests and 1,000 Durable Object notifies. `RL_WRITE` allows 30 per 60 s, so this takes at least 34 minutes.
- **Writes.** About 9,000 D1 written rows.
- **Correctness.** A Splitwise payment between two *other* people cannot be recorded at all: `putSettlement` answers `403 not_allowed` ("Only the payer or the receiver records a settlement"). Balances would come out wrong.

Hence one batch route below. It reuses the existing mutation fragments (bump, entity statements, guard) per item, so the version counter, change rows, pulls (pages of 100 versions, so at most about 100 entities a page) and the outbox all stay as they are.

## 3. Server

### 3.1 `POST /v1/groups/{gid}/import` (new)

Router entry: `{ method: "POST", path: `${g}/import`, handler: imports.importItems, auth: true, write: true, replay: imports.replayImport }`. That means a bearer token, `X-Installation-Id`, a required `Idempotency-Key`, `RL_USER` and `RL_WRITE`, and the existing 32 KB body cap (`413 too_large`).

Request:

```json
{
  "items": [
    { "kind": "expense", "id": "<uuid v4>", "description": "Rent", "category": "RENT_AND_BILLS",
      "amountPaise": 3000000, "occurredOn": "2025-04-01", "paidByMemberId": "<uuid>", "splitMethod": "EQUAL",
      "shares": [ { "memberId": "<uuid>", "sharePaise": 1000000, "input": null } ] },
    { "kind": "settlement", "id": "<uuid v4>", "fromMemberId": "<uuid>", "toMemberId": "<uuid>",
      "amountPaise": 50000, "occurredOn": "2025-04-03" }
  ]
}
```

Response `200`:

```json
{ "applied": true, "firstVersion": 2, "lastVersion": 51 }
```

- Item `i` (0-based, body order) is written at version `firstVersion + i`, with its own change row (`expense`/`create` or `settlement`/`create`, actor = the caller's member).
- When every item id already exists in this group (an old retry after its key was purged), the answer is `200 { "applied": false, "firstVersion": null, "lastVersion": null }` and nothing is written.

Checks, in the order their answers are given. **As built (docs/server.md 4.17, after review 2026-11-18), check 12
reads right after check 8 and gives its `applied: false` answer there, before checks 9 to 11:** a part applied long
ago and retried after its key was purged must get `applied: false` even if a friend has joined, a named member was
removed or the group reached the cap since, because any refusal makes the phone delete the group (5.4). Its `409
exists_different` still comes last. The table keeps the order as first written:

| # | Check | Answer |
|---|---|---|
| 1 | caller is an active member of a live group (`requireMember`) | `403 not_member` (+`reason`), `410 group_deleted` |
| 2 | `group.kind = 'group'` | `403 not_allowed` |
| 3 | caller is the owner (`owner_user_id`) | `403 not_owner` |
| 4 | body is an object; `items` is an array of 1–50 | `400 bad_request`, field `items` |
| 5 | each item: `kind` is `expense` or `settlement` | `400`, `items[i].kind` |
| 6 | `id` is a lowercase UUID **v4** (`isUuidV4`), unique in the body | `400`, `items[i].id` |
| 7 | expense: every rule of 4.7 through a refactored `parseExpenseFields(raw, prefix, now)` shared with `putExpense`. Text 1–80 with no control or bidi characters, category, amount 1..10^9, date 2000-01-01..tomorrow (India), shares 1–50 with unique members, sum = amount, inputs per method, 6.1 recompute (`400 shares_mismatch`). `auto` absent or false, `baseVersion` absent or null. | `400 bad_request` / `shares_mismatch`, field prefixed `items[i].` |
| 8 | settlement: `fromMemberId`, `toMemberId` are UUIDs and differ; `amountPaise` 1..10^9; `occurredOn` as 4.7 | `400`, `items[i].…` |
| 9 | nobody else in the group is on the app: no active member with `user_id` not null and ≠ caller | `403 not_allowed` ("Import only fills a group nobody else has joined") |
| 10 | every member id named (payers, sharers, from, to) is an active member of `gid`: one `membersByIds` read, at most 50 ids | `400 bad_request`, the first failing field |
| 11 | `group.version + items.length ≤ 6000` | `409 limit_reached` |
| 12 | ids: read `SELECT id, group_id FROM expenses WHERE id IN (…)` and the same on `settlements` (≤ 50 params each). None exists: write. All exist in `gid`: `200 applied:false`. Otherwise: | `409 exists_different` |

The batch is one `ctx.db.batch`, run in order and atomic:

```
for each item i (body order):
  bump(db, gid, now)
  expense:    expenseStmts(db, {..., actor: myMemberId, auto: false}, { create: true })   // new flag: skip the
              // "DELETE FROM expense_shares" of an update; shares inserted in chunks of 25 rows of 4 values
  settlement: insertSettlementStmt(db, { gid, sid: id, from, to, amountPaise, method: "OTHER",
              matchedFromSms: false, occurredOn, recordedBy: myMemberId, confirmedAt: now, now })
  guard(db, { gid, kind, op: "create", entityId: id, actor: myMemberId, now,
              rule: i == 0 ? importRule(allMemberIds, callerUserId) : undefined })
idem.stmtGroupVersion(200, gid)   // new IdemWriter method, last statement
```

- `importRule` (new, in `db/mutation.ts`), with parameters from `?7`. The total stays ≤ 57 parameters, since a group has at most 50 members:

  ```sql
  (SELECT COUNT(*) FROM members mx WHERE mx.id IN (?7, …) AND mx.group_id = ?1 AND mx.removed_at IS NULL) = <n>
  AND (SELECT owner_user_id FROM groups WHERE id = ?1) = ?<k>
  AND NOT EXISTS (SELECT 1 FROM members my WHERE my.group_id = ?1 AND my.user_id IS NOT NULL
                  AND my.user_id <> ?<k> AND my.removed_at IS NULL)
  ```

  D1 runs one batch at a time, so checking it at the first guard covers the whole batch. A friend who accepts a link, or an ownership change, between the handler's checks and the batch fails the guard and rolls everything back.
- `stmtGroupVersion` is `INSERT INTO idempotency_keys (user_id, key, request_hash, status, entity_id, created_at) VALUES (?1, ?2, ?3, ?4, (SELECT CAST(version AS TEXT) FROM groups WHERE id = ?5), ?6)`. It stores `lastVersion` for replays.
- `firstVersion = guardVersion(results, firstGuardIndex)` and `lastVersion = guardVersion(results, lastGuardIndex)`. The bumps of one batch are contiguous.
- **Guard failure.** Run checks 1, 3, 9 and 10 again, in that order, and answer with the first that fails. If none fails, `503 internal` and the phone retries.
- **Primary-key failure on `expenses.id` or `settlements.id`** (two copies with different keys at once): run check 12 again and answer as it says.
- **`IdempotencyRace`**: handled by the router as a replay, as today.
- **After the batch.** `after(ctx, { gid, version: lastVersion })`: one room notify and a NORMAL `changed` push. Only the caller's other phones receive it, since nobody else is on the app.
- **Replay** (same key, same body hash): `200 { applied: true, firstVersion: last − items.length + 1, lastVersion: last }`, from `idempotency_keys.entity_id`. There is no access check, because the answer holds no group content, like reminders and deletes (4.3). The same key with another body gives `422 idempotency_key_reused`.
- Nothing else changes: change ops stay `create`, the `group_cache` keys are untouched (they are recomputed by version), and so are push priority and pulls.
- **Logging.** The route template `/v1/groups/:gid/import` only (`log.ts` already does this).

### 3.2 Item limits (phone and server agree)

- Phone:
  - **≤ 50 items** per request.
  - Encoded body **≤ 30,000 bytes** (UTF-8), leaving room under 32 KB.
  - Items in date order, then file order.
  - ≤ 5,000 server items per file.
- Server:
  - `version + items ≤ 6000` per group: room for the group create and a few member changes. The 20,000-expense cap of 4.7 is never reached through import.

### 3.3 Delete group: rule change

`DELETE /v1/groups/{gid}` (4.6): the owner may delete when **every net is 0, or no other active member is on the app**. The second case is new.

- In `routes/groups.ts` `deleteGroup`, the `settled(...)` check becomes `if (nets not all 0 && othersOnApp(access)) throw notSettled()`, reading members inside the check. The guard stays `versionRule(checked)`: an invite accept bumps the version, so a join in between fails the guard and the check runs again.
- **Why:**
  - An import made with the wrong file or the wrong "which one is you" can otherwise never be removed: its balances are not 0 and nobody else can settle them.
  - The phone's clean-up of a refused import (5.4) needs it.
  - Friends without the app only see the group through links, which the delete revokes as today.
- This is a product decision (decisions, 12).

### 3.4 Migration

**None.** No new table, column, change op or CHECK. The live database (0001, 0002) serves the new route as it is. The deploy order stays as today: `npm run deploy` checks that remote migrations are applied (`check-remote-migrations.mjs`).

### 3.5 Free-tier budget (section 8)

1,000 INR rows (900 expenses averaging 3 shares, 100 payments) in a 3-person group:

| Item | Worker req | DO req | D1 rows read | D1 rows written |
|---|---|---|---|---|
| Group create (`POST /v1/groups`) | 1 | 1 | ~10 | ~10 |
| Import, 20 requests of 50 items | 20 | 20 | ~6,000 (guards ~5/item, checks) | 900×7 (bump, row, version index, 3 shares, change) + 100×4 + 20 keys ≈ **6,700** |
| Pull back from 0 (10 pages of 100 versions) + `/me/groups` | 11 | | ~5,000 | |
| Links on 31 (2 friends) | 2 | | ~10 | 2 |
| **Total** | **~34** | **~21** | **~11,000 (0.2% of 5 M)** | **~6,700 (6.7% of 100,000)** |

- The time is about 3 to 4 minutes for the 20 requests: since 2026-11-19 an import request counts ceil(items / 10)
  against `RL_WRITE` (30 per 60 s, docs/server.md 4.15 and open point 13.20), so about 6 parts of 50 go through a
  minute and the phone waits each `429`'s `Retry-After` (5.4). 5,000 rows take about 17 minutes.
- The largest allowed file (5,000 rows of 3 people) writes about 33,000 rows, a third of a day. The cap exists for that.
- Storage is about 1 KB per expense, so about 1 MB per 1,000 rows (500 MB per database).
- CPU per import request: parse ≤ 32 KB, validate ≤ 50 items (including `computeShares`), build about 200 statements. Expected 1–3 ms against the 10 ms cap. Check in the dashboard after deploy.

### 3.6 Code layout

- `server/src/routes/imports.ts` (new): `importItems`, `replayImport`.
- `server/src/routes/ledger.ts`: extract `parseExpenseFields(raw: unknown, prefix: string, now: number)` from `parseExpense` (field names get the prefix). `putExpense` keeps its behaviour.
- `server/src/db/ledger.ts`: `expenseStmts(db, w, opts?: { create?: boolean })`.
- `server/src/db/mutation.ts`: `importRule(ids, callerUserId)` and `IdemWriter.stmtGroupVersion(status, gid)`.
- `server/src/routes/groups.ts`: the delete rule.
- `server/src/router.ts`: the route.

### 3.7 Server tests (vitest)

New `server/test/import.test.ts`:

1. The owner of a new group with two friends without the app imports 3 expenses (EQUAL, AMOUNTS, payer not in the split) and 1 settlement *between the two friends*.
   - Answers `200` with contiguous versions.
   - `GET /changes?since=0` returns them with those versions and change rows `expense/create` ×3 and `settlement/create` ×1, actor = owner.
   - The settlement has `method: OTHER`, `confirmedAt` set and `recordedByMemberId` = owner.
   - `GET /balances` equals the expected nets (one of `balances.json`'s style cases).
2. Replay with the same key: same answer, row counts unchanged. Same key with another body: `422`.
3. After deleting the key row by SQL (purge): resending gives `200 applied:false`. One id already present plus new ones gives `409 exists_different`.
4. Validation:
   - 0 or 51 items.
   - Unknown kind.
   - Duplicate ids; a v1 UUID id.
   - Shares not summing.
   - EQUAL with wrong shares (`shares_mismatch`, field `items[0].shares`).
   - `auto: true`.
   - A date two days ahead.
   - Settlement from == to.
   - A member of another group; a removed member.
   - Body over 32 KB (`413`).
5. Authorization:
   - Non-member `403 not_member`.
   - A member who is not the owner `403 not_owner` (set up with `befriend`).
   - A direct group `403 not_allowed`.
   - A group where a friend accepted a link `403 not_allowed`.
   - Deleted group `410`.
   - No token `401`.
6. Race: `testHooks.beforeBatch` accepts an invite for one friend's place before the batch runs. The batch rolls back (no rows, version unchanged) and the answer is `403 not_allowed`.
7. Cap: set `groups.version = 5990` by SQL and import 11 items: `409 limit_reached`.
8. Pages: import 150 items in 3 requests. `changes?since=0` gives pages of ≤ 100 versions, `hasMore` once.
9. Cost: one request of 50 expenses with 3 shares writes ≤ 50×7 + 5 rows and reads < 600 rows. Measure with `testHooks.onDb` and the D1 `meta` counts, as `cron.test.ts` and `me.test.ts` do.
10. Fan-out: one room notify per request (`room.test.ts` style spy).
11. `logging.test.ts`: add an import flow to the console spy. No description, name, amount or id appears.

`server/test/groups.test.ts`: add "delete: the owner deletes with balances not settled while nobody else is on the app (204), and gets 409 once a friend joins". The existing delete test (two app users) is unchanged and still passes.

## 4. Pure Kotlin module `:splitwise`

`settings.gradle.kts`: `include(":splitwise")`. `splitwise/build.gradle.kts` is a copy of `splits/build.gradle.kts` (kotlin.jvm, JVM 17, junit), with `implementation(project(":splits"))` for `SplitMath.MAX_AMOUNT_MINOR` and `MAX_PARTICIPANTS`, and `Balances` in tests. No Android dependencies. `app/build.gradle.kts`: `implementation(project(":splitwise"))`. CLAUDE.md "Layout" gains a line for `splitwise/`.

Package `com.expenses.splitwise`:

```kotlin
object SplitwiseCsv {
    const val MAX_BYTES: Int = 5 * 1024 * 1024
    const val MAX_PEOPLE: Int = 50
    const val MAX_ROWS: Int = 5_000            // imported INR rows, and server items after rule 6
    /** Parses Splitwise's per-group "Export as spreadsheet" file (docs/server.md 4.17). Never logs. */
    fun parse(bytes: ByteArray, truncated: Boolean = false): SplitwiseResult
}

sealed interface SplitwiseResult {
    data class Ok(val file: SplitwiseFile) : SplitwiseResult
    data class Refused(val reason: Refusal) : SplitwiseResult
}

enum class Refusal { NOT_SPLITWISE, DOES_NOT_ADD_UP, TOO_LARGE_FILE, TOO_MANY_PEOPLE, TOO_MANY_ROWS,
    TOO_LARGE_AMOUNT, NOTHING_IN_RUPEES, ONE_PERSON }

data class SplitwiseFile(
    val people: List<String>,                 // header names, file order, cleaned
    val expenses: List<SwExpense>,            // INR expense rows (rule 5/6), file order
    val payments: List<SwPayment>,            // INR Payment rows (rule 4)
    val balancesPaise: List<Long>,            // per person: sum of imported INR values
    val leftOutByCurrency: Map<String, Int>,  // rows not in INR, by currency code
    val noEffectRows: Int,
) { val itemCount: Int get() = expenses.sumOf { it.parts.size } + payments.size }

data class SwExpense(val row: Int, val date: java.time.LocalDate, val description: String, val category: String,
                     val parts: List<SwPart>)
data class SwPart(val payer: Int, val amountPaise: Long, val sharesPaise: Map<Int, Long>) // person index -> share > 0
data class SwPayment(val row: Int, val date: java.time.LocalDate, val from: Int, val to: Int, val amountPaise: Long)
```

Rules are those of 1.2 to 1.4. Files: `Csv.kt` (RFC 4180 reader), `SplitwiseCsv.kt`, `SplitwiseFile.kt`, `Money.kt` (paise parser) and `Dates.kt` (file-level order detection). Dates are returned as in the file; clamping happens in the app (5.2).

Tests (`splitwise/src/test`, fixtures in `src/test/resources/*.csv`, all synthetic):

- The spliit #342 sample, converted to INR:
  - "Altruist payment": payer not in the split.
  - "By percentage": one payer.
  - "Income" and a two-payer row: parts, with nets kept.
  - "Bob paid Carol": a payment from Bob to Carol.
  - "Self-payment": no effect.
  - The total row ignored.
- BOM with CRLF; blank lines after the header and before the total; total cells written as a single space; a trailing comma.
- Quoted descriptions with commas, `""` and a line break; an unterminated quote gives `NOT_SPLITWISE`.
- Money: `1,234.50` quoted, `-0.00`, `98.5`. In INR, `33.333` gives `NOT_SPLITWISE`.
- Dates: ISO; ISO with time; `25/12/2025` (day-first); `12/25/25` (month-first); all ≤ 12 gives day-first; `2025-02-30` gives `NOT_SPLITWISE`.
- Currency:
  - USD rows counted in `leftOutByCurrency`.
  - Only USD gives `NOTHING_IN_RUPEES`; a header plus a total row only also gives `NOTHING_IN_RUPEES`.
  - A single-currency total that disagrees gives `DOES_NOT_ADD_UP`; with two currencies the total check is skipped.
- A row not summing to 0 gives `DOES_NOT_ADD_UP`.
- Limits: 1 person `ONE_PERSON`; 51 people `TOO_MANY_PEOPLE`; 5,001 rows `TOO_MANY_ROWS`; 5,000 rows that split into more than 5,000 items `TOO_MANY_ROWS`; `truncated = true` gives `TOO_LARGE_FILE`; a row over ₹1 crore gives `TOO_LARGE_AMOUNT`.
- A PDF header (`%PDF-1.7`), random bytes and the German header `Datum,Beschreibung,…` give `NOT_SPLITWISE`.
- Property test with a seeded random generator of valid files: for every file, `Balances.nets` over all parts and payments (`:splits`) equals `balancesPaise` in each column. Every part's shares add up to its amount, and each share is > 0.

## 5. App

### 5.1 Room (version 5, `AutoMigration(4, 5)`, new table only)

```kotlin
/** A Splitwise import made on this phone (docs/server.md 9.3). The file itself is never kept. */
@Entity(tableName = "grp_imports", indices = [Index("fileHash")])
data class ImportEntity(
    @PrimaryKey val groupId: String,
    val fileHash: String,     // hex SHA-256 of the file's bytes, for "already here"; stays on the phone
    val groupName: String,    // as built: the name the group was imported with, for Import failed (140) once the group is gone
    val expenseCount: Int,    // Splitwise expense rows imported ("46 past expenses")
    val itemCount: Int,       // expenses (parts) and payments sent to the server
    val sentItems: Int,
    val state: ImportState,   // SENDING, DONE, FAILED (stored by name)
    val createdAt: Instant,
)
```

- No foreign key: a FAILED row must outlive the group it removed.
- `EntityKind` gains `IMPORT` (appended; never rename constants).
- `GroupExtrasDao` gains:
  - `upsertImport`, `importOf(groupId)`, `observeImport(groupId)`
  - `latestImportByHash(hash)`: state ≠ FAILED, newest first
  - `addSentItems(groupId, n)`, `setImportState(groupId, state)`, `deleteAllImports()`
- `OutboxDao` gains `observeStatusForGroup(groupId): Flow<List<OutboxStatus>>`, a projection `seq, entityKind, op, attempts, failures, lastErrorCode` that does not load bodies.
- `DatabaseMigrationTest` adds 4 → 5. `app/schemas/.../5.json` is exported by the build.

### 5.2 `com.expenses.app.groups.splitwise` (app, JVM-testable)

As built, the helpers that need nothing of the app live in the `:splitwise` module (`com.expenses.splitwise`), next
to the reader of section 4, so they are tested on the JVM without Robolectric: `GroupNameFromFile.of`,
`SplitwisePeople.preselect`, `SplitwiseText.clean` (the same rules as the app's `GroupText.clean` and the server's
text check, a text of only U+FEFF included), `SplitwiseCategories.map` (the table below, to the module's own
`SwCategory`) and `ImportParts` (`clampDate`, `items`, `chunk`: at most 50 items and 30,000 bytes a part). The app
package keeps what needs the app's types: `ImportCategories.toCategory` (`SwCategory` or the keyword guess to
`Category`), `ImportPlanner` and `ImportBodies`. Where this spec writes `GroupText.clean` for a file's text, the
code calls `SplitwiseText.clean`.

- `GroupNameFromFile.of(displayName): String` (1.5; as built in `:splitwise`).
- `ImportCategories.toCategory(raw: String?, description: String): Category` (as built; the table itself is `SplitwiseCategories.map` in `:splitwise`), matching the trimmed lower-case text:

  | Category | Splitwise text |
  |---|---|
  | GROCERIES | `groceries` |
  | FOOD | `dining out`, `liquor`, `food and drink…` |
  | TRAVEL | `taxi`, `bus/train`, `plane`, `car`, `gas/fuel`, `parking`, `hotel`, `bicycle`, `transportation…` |
  | RENT_AND_BILLS | `rent`, `mortgage`, `electricity`, `heat/gas`, `water`, `tv/phone/internet`, `trash`, `cleaning`, `maintenance`, `services`, `insurance`, `utilities…`, `home - other` |
  | SHOPPING | `clothing`, `electronics`, `furniture`, `gifts`, `household supplies` |
  | FUN | `games`, `movies`, `music`, `sports`, `entertainment…` |
  | OTHER | `childcare`, `education`, `medical expenses`, `taxes`, `pets`, `life…` |
  | guessed | `general` or blank: `CategoryGuesser.guess(description)`, which gives OTHER when nothing matches |

  Unknown text is also guessed from the description.
- `ImportPlanner.plan(file, me: Int, groupName: String, user: UserDto, today: LocalDate, now: Instant, newId: () -> String): ImportPlan`:
  - New ids are v4 (`UUID.randomUUID()`): `groupId`, `myMemberId` for column `me`, a member id per other column. Members are stored in column order (avatar colours).
  - Each `SwPart` becomes a `GroupExpenseEntity`:
    - `description` = cleaned text, or the category name when blank
    - `category` from `toCategory`
    - `amountMinor`, and `occurredOn` = the date clamped to [2000-01-01, today]
    - `paidByMemberId`; `auto = false`; `createdBy` and `updatedBy` = `myMemberId`
    - `version = 0`, `syncState = PENDING`
  - Shares are `ExpenseShareEntity` for shares > 0. **Split method:** EQUAL when `SplitMath.shares(EQUAL, amount, participants.associateWith { null })` equals the shares exactly (input null). Otherwise AMOUNTS (input = share).
  - Each `SwPayment` becomes a `SettlementEntity`: method OTHER, `matchedFromSms = false`, `recordedByMemberId = myMemberId`, `confirmedAt = now`, `version 0`, PENDING.
  - Items are sorted by `occurredOn`, then file row, and chunked greedily. A new chunk starts when adding an item would make 51 items or more than 30,000 bytes of the encoded body (`ApiJson`, UTF-8).
  - The body of each part is built by `ImportBodies.part(items)` as `JsonObject`s, like `GroupBodies`. kotlinx polymorphism is avoided.
  - The plan returns members (`MemberEntity` + `MemberBody.Friend(id, name, null)`), entities, `parts: List<ImportPart(partId, body: JsonObject, itemIds: List<Pair<EntityKind, String>>)>`, `expenseCount = file.expenses.size` and `itemCount`.

### 5.3 `GroupsRepository`

- `suspend fun importSplitwise(file: SplitwiseFile, me: Int, groupName: String, fileHash: String): String`
  - Signed in only; the name must be non-blank after `GroupText.clean(_, 40)`.
  - Builds the plan, then in **one** `transactions.run`:
    1. `upsertGroup(GroupEntity(kind GROUP, ownerUserId = me.id, ownerMemberId = myMemberId, version 0, latestVersion 0, PENDING))`
    2. `upsertMembers(...)`
    3. upsert every expense with its shares, and every settlement
    4. `mirrors.sync(id)` for every expense (9.4: a GROUP mirror of the person's share when someone else paid, or the person's own SMS or typed payment linked by `linkRestored`, else a mirror)
    5. `outbox.append(gid, GROUP, gid, CREATE, "POST", "/v1/groups", GroupBodies.create(...), null)`
    6. per part, `outbox.append(gid, IMPORT, partId, CREATE, "POST", "/v1/groups/$gid/import", body, null)`
    7. `extras.upsertImport(ImportEntity(gid, fileHash, groupName, expenseCount, itemCount, 0, SENDING, now))`
  - Then `scheduler.requestSync()`. Returns `gid`.
- `fun observeImport(groupId): Flow<ImportProgress?>` combines the import row, the group name and the outbox status into `ImportProgress(groupId, groupName, sent, total, state, problem)`. `problem` is true when an entry of this group has `failures > 0` and `lastErrorCode` is not `offline`.
- `suspend fun importedFile(hash): PastImport?`: the latest non-FAILED import of that hash whose group is still on the phone. As built it returns `PastImport(groupId, groupName, on, expenseCount)` and not the row: the group's current name (it may have been renamed since) and the day of the import in India time, which is all 137 shows.
- As built, Delete group and Leave group in Group settings also forget the group's import row (`LocalGroupData.removeOwnGroup`), whether its parts were all sent or still waited: the waiting parts go with the group, so a row left at SENDING could never finish. A FAILED row still outlives its group (5.4).
- `shareLink(groupId, memberId)`: while the group has an IMPORT outbox entry, return `ApiResult.Error(ApiError(409, "import_pending", fromServer = false))` with no request. A friend who joined mid-import would make the remaining parts fail (rule 9). Remind already shows its Problem banner for such errors.

### 5.4 `SyncEngine`, `LocalGroupData` (docs/server.md 5.1, 5.2)

- **Sending.** IMPORT entries go through the existing loop: order, keys, 429 waits, parking after 5 server errors (which blocks only this group), and network back-off. They are never coalesced (only EXPENSE entries are).
- **Accepted** (`applyAccepted`, `EntityKind.IMPORT`):
  1. Decode `ImportResponse(applied: Boolean, firstVersion: Long?, lastVersion: Long?)` and the entry's own body (`items[].kind`, `items[].id`).
  2. For each item `i` without another waiting entry for that id: set `syncState = SYNCED`, and set `version = firstVersion + i` when `applied`.
  3. `extras.addSentItems(gid, items.size)`.
  4. When no other IMPORT entry for `gid` is waiting, `setImportState(gid, DONE)`.
- **Refused** (`reject`):
  - `not_member` and `group_deleted`: as today, plus `setImportState(gid, FAILED)`.
  - Any other code (`400`, `403 not_allowed`/`not_owner`, `409`, `413`, `422`): `failImport(gid)`, in one transaction:
    1. `setImportState(gid, FAILED)`
    2. `local.removeGroup(gid, forgetSplits = true)`: mirrors go, and linked payments go back to their remembered status (`grp_link_priors`)
    3. `outbox.append(gid, GROUP, gid, DELETE, "DELETE", "/v1/groups/$gid", null, null)`, which is allowed by 3.3
  - A refused **group create** of an import (existing rollback) also sets FAILED.
- **Pull.** `pullLocked` skips every server group that has a waiting `GROUP`/`DELETE` entry: it is neither re-added nor pulled. Otherwise the partial copy would come back before the delete is sent. If that DELETE is refused (`409 balances_not_settled`, meaning someone joined in between), the entry is dropped as today and the group returns on the next pull.
- **`LocalGroupData.restoreNeverSent`.** For IMPORT/CREATE entries, decode the item ids and `mirrors.forget(id)` for each expense whose stored `version` is 0. This covers sign-out, another account signing in, and group removal with parts unsent. `clearAll` also runs `extras.deleteAllImports()`.
- **LiveRoom** is unchanged: it connects once the group create has left the outbox.

### 5.5 Personal rows (docs/server.md 9.4)

Imported expenses follow 9.4 like any group expense:

- Someone else paid and the person has a share: a GROUP mirror counts that share in its month.
- The person paid: their one SMS or typed debit of the same amount within a day is linked (`linkRestored`), else a mirror counts their share.

This is decision 11. Rows dated before tracking began still get personal rows, as a join from version 0 does today.

### 5.6 Navigation (`ui/nav/Routes.kt`)

```kotlin
/** Import from Splitwise (design 02-everyday/28-30, 133-140). From New group and You. Signed in only (SignInGate). */
@Serializable data object ImportSplitwise

/** Group imported (design 02-everyday/31, 141), right after an import. */
@Serializable data class GroupImported(val groupId: String)
```

- `splitwiseDestinations(navController)` is registered in `AppNavHost`.
- `ImportSplitwise` is wrapped in `SignInGate` with the default `SignIn()`. Signed out from You, Sign in opens first and Import comes back.
- **New group.** The Ghost Medium "Import from Splitwise instead" shows only when `NewGroup` has no prefilled people or friends, which means it was opened from the Groups tab. From Choose people's "Make a group" it is hidden (new frame 142). Tap: `navigate(ImportSplitwise)`.
- **You.** `YouEvent.OpenImport` navigates to `ImportSplitwise`.
- **On DONE**, navigate to `GroupImported(gid)`, popping `ImportSplitwise` and a `NewGroup` under it.
- **From `GroupImported`** (Later, Back, or after the share sheet starts), and from "Open {group}" on the importing and already-here states: open `GroupPage(gid)` on top of the **Groups tab**, so Back from the new group's page lands on Groups.
- **Back during importing** does the same as "Open {group}". The import goes on in the background.

### 5.7 Import from Splitwise screen (`ui/screens/splitwise/`)

Files:

- `ImportSplitwiseScreen.kt`, `ImportSplitwiseUiState.kt`, `ImportSplitwiseViewModel.kt`, `SplitwiseNav.kt`
- `PickedFile.kt`: `suspend fun readPicked(resolver, uri): PickedFile`. It reads `DISPLAY_NAME` and up to `MAX_BYTES + 1` bytes on IO, and `truncated` is true when that limit was passed.
- Debug previews of every state in `app/src/debug/.../preview/` (unique names).

The file picker is `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument())` with `arrayOf("*/*")`, so any file can be picked and 30 can say it is not an export. **No storage permission and no persistable grant**: the file is read once at once, and the Uri is not kept.

The ViewModel takes `readFile`, `groups`, `profileName`, `online: Flow<Boolean>`, `SavedStateHandle` (only `importingGroupId`, so a started import is followed after process death) and dispatchers. State:

```kotlin
sealed interface ImportUiState {
    data object NoFile : ImportUiState                                   // 28
    data class Reading(val fileName: String) : ImportUiState             // 135
    data class Chosen(val fileName: String, val expenseCount: Int, val people: List<PersonOption>,
                      val selected: Int?, val groupName: String, val leftOut: Int,
                      val importing: Boolean) : ImportUiState            // 29, 133, 134
    data class CannotImport(val fileName: String, val reason: Refusal) : ImportUiState   // 30, 136
    data class AlreadyHere(val fileName: String, val expenseCount: Int, val peopleCount: Int,
                           val groupId: String, val groupName: String, val on: LocalDate) : ImportUiState // 137
    data class Importing(val groupId: String, val groupName: String, val sent: Int, val total: Int,
                         val banner: ImportBanner) : ImportUiState       // 138, 139
    data class Failed(val groupName: String) : ImportUiState             // 140
}
data class PersonOption(val name: String, val balanceMinor: Long)
enum class ImportBanner { None, Offline, Problem }
```

Behaviour:

- **Choose file, Choose another file, Choose a different file** open the picker.
- **A file picked** goes to `Reading`, then `SplitwiseCsv.parse` on `Default`, then the SHA-256 is computed. As built, `Reading` is set the moment the picker returns, before the file is read (a file kept in Drive is fetched first, which can take seconds), so "Choose file" is off for the whole read. Its file name is empty until the provider gives it (`pickedName`, asked before the read); the Caption's line keeps its place meanwhile, so the area stays 95 high. A second pick during a read replaces the first, whose result is dropped.
  - Refused: `CannotImport`.
  - Same hash already imported (`importedFile(hash)`): `AlreadyHere`.
  - Otherwise `Chosen`, with the name from 1.5, preselection from 1.6, and `leftOut = Σ leftOutByCurrency`.
- **Import** is enabled when `selected != null && clean(name) not empty && !importing`. It runs `importSplitwise` on IO, then shows `Importing(sent 0)` and observes `observeImport` together with `online`:
  - `state == DONE`: effect `OpenImported(gid)`
  - `FAILED`: `Failed`
  - offline: banner `Offline`
  - `problem`: banner `Problem`
- **Close** on `Failed` leaves the screen.

Layout (tokens and components as the design; `PageFrame` for the states with a top bar):

- **28 `NoFile`.**
  - Top bar "Import from Splitwise".
  - Display/L headline, then the Body/M intro.
  - Drop area: `SurfaceCard`, padding 24/16, gap 4, radius 16, `color/bg/surface`, holding Title "No file chosen yet" and Caption "Pick the .csv file you exported from Splitwise.".
  - Three Info Rows with Icon/box, Icon/folder and Icon/link (3D).
  - Footer: Primary Large "Choose file".
- **135 `Reading`.** Same as 28. The drop area shows Title "Reading the file" and Caption {file name}. "Choose file" is disabled (opacity 0.4).
- **29 `Chosen`.** The headline and intro, then:
  - File Setting Row: Icon/file 3D, title {file name}, subtitle "{n} expenses · {p} people".
  - [134 only] a Result note (the Privacy promise `Note` frame, Body/M `color/text/primary`): "{k} expenses in other currencies stay out. Only rupees come across."
  - `AppInput` "Group name", with the "That does not look right." helper when blank.
  - `SectionLabel` "Which one is you?".
  - One `OptionCard` per person: Icon/user, name, "Is owed ₹2,350" / "Owes ₹1,150" / "All settled". They form a radio group (`selectableGroup`; OptionCard's radio).
  - The three Info Rows.
  - Footer: Primary Large "Import {name}" (disabled at 0.4 while nothing is chosen or the name is blank: 133), Ghost Medium "Choose a different file".
- **30 / 136 `CannotImport`.** The drop area shows the title (Title style) and body (Caption, `color/status/negative`) of the table below. The three Info Rows stay. Footer: Primary Large "Choose another file".
- **137 `AlreadyHere`.** The headline and intro, the File Setting Row, and the Note "This file already came across as {group} on {d MMM}.". Footer: Primary Large "Open {group}", Ghost Medium "Choose a different file".
- **138 / 139 `Importing`.** No top bar.
  - [139] Banner `Notice` (Icon/wifi-off) at the top of the body: "You're offline" / "Check your connection and try again. Nothing is lost.". The Problem alternative is Icon/alert with "Could not do that now" / "Try again in a moment.".
  - `StickerTile(AppIcon.Box, Brand)`.
  - Display/L "Bringing {group} across".
  - Body/M "Balances and past expenses are being saved. You can leave this screen. It finishes by itself."
  - Progress bar plus Caption "{sent} of {total} sent". `ScanProgressBar` is extracted from `ScanningScreen` into `ui/components/ProgressBar.kt` and reused by both.
  - Footer: Ghost Medium "Open {group}".
- **140 `Failed`.** No top bar. `StickerTile(AppIcon.Alert, Yellow)`, Display/L "{group} did not come across", Body/L "Part of the file could not be saved, so none of it was kept. Export the group from Splitwise again and try once more.". Footer: Primary Large "Choose another file", Ghost Medium "Close".

`CannotImport` titles and bodies:

| Refusal | Title | Body |
|---|---|---|
| NOT_SPLITWISE | That is not a Splitwise export | {file} cannot be read. Pick the .csv file that Splitwise made. |
| DOES_NOT_ADD_UP | This file does not add up | {file} was changed after Splitwise made it. Export the group again and pick the new file. |
| TOO_LARGE_FILE | This group is too big | {file} is over 5 MB. Only smaller groups can come across. |
| TOO_MANY_PEOPLE | This group is too big | {file} has more than 50 people. Only groups of up to 50 people can come across. |
| TOO_MANY_ROWS | This group is too big | {file} has more than 5,000 expenses. Only groups with up to 5,000 can come across. |
| TOO_LARGE_AMOUNT | An expense is too big | {file} has an expense over ₹1 crore, the most one expense can be here. |
| NOTHING_IN_RUPEES | Nothing to bring across | {file} has no expenses in rupees. Only rupees can come across. |
| ONE_PERSON | Nobody else is in this file | {file} has only one person. Pick the export of a group you share. |

### 5.8 Group imported (31) and the invite

Files: `GroupImportedScreen.kt`, `GroupImportedViewModel.kt`.

Data:

- Group name.
- `expenseCount` from `grp_imports`.
- Friends: members ≠ me, not on the app, not removed, in stored order.

Layout:

- No top bar. `StickerTile(CheckCircle, Brand)`.
- Display/L "{group} is in".
- Body/L "Balances and {n} past expenses came across.", or "Balances came across." when n = 0.
- Card (`SurfaceCard`, padding 12/16, gap 2):
  - Label "Invite links" ("Invite link" for one friend).
  - Title in `color/text/brand`: the names. "Priya and Kiran", "Priya, Kiran and Meera", or "Priya, Kiran and 3 more".
  - Caption: "One message with a link for each person.", or for one friend "One message with the link for Priya.".
- Info Row Icon/user-plus: "Friends can join or just pay" / "Nobody needs the app to see what they owe."
- Footer: Primary Large "Share invite links" ("Share invite link" for one), Ghost Medium "Later".

Share:

- For each friend in order, call `groups.shareLink(gid, memberId)`. This reuses the per-place links: id and token made on the phone, `PUT .../links/{linkId}`, reused within 60 days.
- On a 429, wait `retryAfterSeconds` (≤ 60 s, as `SyncEngine` does) once per link, with the button disabled meanwhile.
- Offline: Offline banner (141). Any other error: Problem banner.
- When every link is made, build the text and call `RemindShare.send(context, text)`: WhatsApp, else WhatsApp Business, else the share sheet, so the person picks the group's chat. Then open the group page (5.6).

The message (strings; the app name only through `app_name`):

```
Flat 302 is on SumBuddy now, with our balances from Splitwise.
Open the link with your name to see what you owe and pay. No app needed.
Priya Nair: https://<LINK_HOST>/pay/<token>
Kiran Rao: https://<LINK_HOST>/pay/<token>
```

Each link opens that friend's web page (7), or the app's Join group for that place (4.10). The owner's 7-day "{name} joined · Not {name}? Undo" covers a wrong tap in a shared chat.

### 5.9 Entry points un-hidden

- **`NewGroupScreen`.** Add `showImport: Boolean` and `NewGroupEvent.ImportFromSplitwise`. The Ghost Medium "Import from Splitwise instead" goes under Create group in the footer. Update its KDoc (it currently says the button is left out).
- **`YouScreen`.** After Privacy, add `SectionLabel("More")` and a `SurfaceCard { InfoRow("Import from Splitwise", "Bring groups and balances", AppIcon.Repeat, onClick = OpenImport) }`. Plus stays out. Update the KDoc of `YouScreen` and `YouUiState`, and the comment in `strings_you.xml`.
- **`strings_groups.xml`.** Delete the unused `groups_import_title` and `groups_import_body` and their comment: Groups (empty) no longer offers import. Update the KDoc of `GroupsScreen.kt` (lines 251–252).

### 5.10 Group settings delete row (3.3)

- `GroupSettingsUiState.DeleteRow.Owner(canDelete: Boolean, nobodyElseJoined: Boolean)`.
- `GroupSettingsViewModel`: `canDelete = allNetsZero || nobodyElseJoined`, where `nobodyElseJoined` means no member other than me is `onApp && !removed`.
- The subtitle is "Removes it for everyone. Nobody else has joined it yet." when `!allNetsZero && nobodyElseJoined`, else the current "Removes it for everyone. Balances must be settled first.".
- The confirm (93) is unchanged.

### 5.11 Strings

**New `strings_import.xml`** (prefix `import_`, as `BuildRulesTest` requires):

Entry, choose and file states:

- `import_title` "Import from Splitwise"
- `import_headline` "Bring a group from Splitwise"
- `import_intro` "In Splitwise, open the group and choose Export as spreadsheet. Then pick that file here."
- `import_no_file_title` "No file chosen yet"
- `import_no_file_body` "Pick the .csv file you exported from Splitwise."
- `import_what_title` "What comes across"
- `import_what_body` "Expenses, who paid, and what each person owes."
- `import_one_file_title` "One file, one group"
- `import_one_file_body` "Export each group separately in Splitwise."
- `import_names_title` "Names only"
- `import_names_body` "The file has no phone numbers. Invite friends with a link after."
- `import_choose_file` "Choose file"
- `import_reading_title` "Reading the file"
- plurals `import_expenses` "%1$s expense" / "%1$s expenses"
- plurals `import_people` "%1$s person" / "%1$s people"
- `import_file_counts` "%1$s · %2$s"
- `import_group_name` "Group name"
- `import_group_name_error` "That does not look right."
- `import_which_one` "Which one is you?"
- `import_person_owed` "Is owed %1$s"
- `import_person_owes` "Owes %1$s"
- `import_person_settled` "All settled"
- plurals `import_left_out` "%1$s expense in another currency stays out. Only rupees come across." / "%1$s expenses in other currencies stay out. Only rupees come across."
- `import_action` "Import %1$s"
- `import_action_no_name` "Import group"
- `import_choose_different` "Choose a different file"
- `import_choose_another` "Choose another file"

Cannot import (titles and bodies):

- `import_wrong_title` / `import_wrong_body`
- `import_changed_title` / `import_changed_body`
- `import_too_big_title`, with the bodies `import_too_large_file_body`, `import_too_many_people_body` and `import_too_many_rows_body`
- `import_amount_title` / `import_amount_body`
- `import_no_rupees_title` / `import_no_rupees_body`
- `import_one_person_title` / `import_one_person_body`

The texts are in 5.7. `%1$s` is the file name.

Already here:

- `import_already_note` "This file already came across as %1$s on %2$s."
- `import_open_group` "Open %1$s"

Importing and failed:

- `import_sending_title` "Bringing %1$s across"
- `import_sending_body` "Balances and past expenses are being saved. You can leave this screen. It finishes by itself."
- `import_sending_progress` "%1$s of %2$s sent"
- `import_offline_title` "You're offline"
- `import_offline_body` "Check your connection and try again. Nothing is lost."
- `import_problem_title` "Could not do that now"
- `import_problem_body` "Try again in a moment."
- `import_failed_title` "%1$s did not come across"
- `import_failed_body` "Part of the file could not be saved, so none of it was kept. Export the group from Splitwise again and try once more."
- `import_close` "Close"

Group imported:

- `import_done_title` "%1$s is in"
- plurals `import_done_body` "Balances and %1$s past expense came across." / "Balances and %1$s past expenses came across."
- `import_done_body_none` "Balances came across."
- plurals `import_done_links_label` "Invite link" / "Invite links"
- `import_names_two` "%1$s and %2$s"
- `import_names_three` "%1$s, %2$s and %3$s"
- plurals `import_names_more` "%1$s, %2$s and %3$d more"
- `import_done_links_note_one` "One message with the link for %1$s."
- `import_done_links_note` "One message with a link for each person."
- `import_done_info_title` "Friends can join or just pay"
- `import_done_info_body` "Nobody needs the app to see what they owe."
- plurals `import_done_share` "Share invite link" / "Share invite links"
- `import_done_later` "Later"

The message (5.8):

- `import_message_intro` "%1$s is on %2$s now, with our balances from Splitwise." (%2$s is `app_name`)
- `import_message_how` "Open the link with your name to see what you owe and pay. No app needed."
- `import_message_line` "%1$s: %2$s"

**Other files:**

- `strings_groups.xml`: `groups_new_group_import` "Import from Splitwise instead".
- `strings_you.xml`: `you_section_more` "More", `you_import_title` "Import from Splitwise", `you_import_body` "Bring groups and balances".
- `strings_grouppage.xml`: `grouppage_settings_delete_body_alone` "Removes it for everyone. Nobody else has joined it yet."

### 5.12 App tests (JVM / Robolectric)

New tests:

- `groups/splitwise/GroupNameFromFileTest`:
  - `flat-302-export.csv` → "Flat 302"
  - `flat-302_2026-11-18_export (1).csv` → "Flat 302"
  - `Goa Trip_export.csv` → "Goa Trip"
  - `export.csv` → ""
  - names over 40 characters are clipped
- `SplitwiseCategoriesTest`: every row of the table, "General" with "Swiggy" → FOOD, unknown → OTHER.
- `ImportPlannerTest`:
  - ids are v4; column `me` becomes `myMemberId`; friends have no phone
  - EQUAL detected, else AMOUNTS
  - a future date becomes today
  - a blank description becomes the category name
  - settlement fields
  - chunks of ≤ 50 items and ≤ 30,000 bytes, in date order
  - two runs give the same nets as `file.balancesPaise` (`GroupBalance`)
- `GroupsRepositoryTest`:
  - `importSplitwise` writes the group, members, PENDING rows and outbox order (create first, then parts)
  - mirrors: someone else paid gives a mirror with my share; I paid with a matching SMS row links it; I paid with none gives a mirror
  - the `grp_imports` row
  - `shareLink` refused while parts wait
- `SyncEngineTest`:
  - accepted parts mark rows SYNCED with `firstVersion + i`, add `sentItems` and end in DONE
  - `applied:false` keeps version 0
  - a refused part removes the group, forgets mirrors, puts a waiting payment back to waiting, queues the DELETE, makes the next pull skip the group while it waits, and sets FAILED
  - a 429 waits; offline keeps entries; a stuck part blocks only its group
  - sign-out with parts unsent forgets their mirrors
- `DatabaseMigrationTest`: 4 → 5.
- `ui/screens/splitwise/ImportSplitwiseViewModelTest`:
  - every state and transition, including preselection (exact match, first-word match, no match)
  - each Refusal mapped to its texts
  - AlreadyHere by hash
  - Import disabled rules
  - Importing to the DONE effect, offline banner, problem banner, Failed
  - process death with `importingGroupId`
- `ImportSplitwiseScreenTest` (Compose, Robolectric):
  - the texts of 28, 29, 30, 133 to 140
  - Option Cards expose the radio role and selected state
  - disabled buttons are not clickable
  - no text under 14sp (theme)
- `GroupImportedViewModelTest`: one link per friend in order; the message text; rate-limit wait; offline banner; navigation after share.
- `LiveServerTest` (optional, against the local Worker): import a 2-person file end to end and pull it back on a second "phone".

Existing tests that change:

- `YouScreenTest.plusSeeTheCodeAndSplitwiseStayOut` becomes `plusAndSeeTheCodeStayOut` (Import from Splitwise now shows under More), plus a new test that the row emits `OpenImport`.
- `GroupsScreenTest`: comment only (Groups empty still has no import line).
- `NewGroupNavigationTest`: the button opens Import and is hidden when prefilled.
- `GroupSettingsViewModelTest`: delete when nobody else joined, with the new subtitle.

## 6. Privacy

- The file is read once through the Storage Access Framework (no storage permission), into memory only.
- It is never written to disk, logged or sent. The display name is shown on screen and used for the group name field only.
- The SHA-256 of the bytes stays in `grp_imports` on the phone, for "already here".
- What reaches the server is exactly what any group holds:
  - the group name, and the friends' names (no phones)
  - the descriptions, categories, amounts and dates of expenses and payments, and the shares
- The categories are mapped and the descriptions cleaned on the phone.
- The SMS inbox and personal expenses are untouched, apart from the 9.4 mirrors on the phone.
- Server logs hold the route template only.
- Splitwise descriptions become visible on each friend's link page (4.10). They were already shared in Splitwise with the same people.

## 7. Docs and design to update in the same work

- **`docs/server.md`:**
  - 3: the migration table gains "no migration for import".
  - 4.2: Import is owner-only while nobody else is on the app.
  - 4.6: delete rule (3.3).
  - New 4.17 (this route).
  - 4.15: note on `RL_WRITE` and the 50-item cap.
  - 5.1: IMPORT entries, not coalesced; failed import.
  - 5.2: skip groups with a waiting delete.
  - 8: budget row.
  - 9.1: the `:splitwise` module and `groups.splitwise` package.
  - 9.3: Room 5, `grp_imports`, `EntityKind.IMPORT`.
  - 9.4: imported expenses.
  - 10: file privacy line.
  - 12: drop "Splitwise import: not built".
  - 13: new open points (the "Join as {name}" wording on Join group, a history view for old group expenses).
  - Build notes.
- **`docs/product.md`:** in Version 1 scope, replace "Splitwise import is not built yet…" with "Splitwise import is built (from New group opened on Groups, and You > More). Groups (empty) does not offer it." Add the decisions of this spec under "Splitwise import (decided …)".
- **`docs/architecture.md`:** the routes `ImportSplitwise` and `GroupImported`, and the new module.
- **`CLAUDE.md`:** a "Layout" line for `splitwise/`.
- **`design/spec/flow.md`:**
  - Rewrite "Branch: If moving from Splitwise" (see design_additions).
  - Add the branches "If the file cannot come across", "If the file came across before", "If offline while importing", "If it could not be saved", and "If nobody else has joined" under "To manage a group".
  - The existing "Branch: If the file is not a Splitwise export" stays.
- **`design/spec/README.md`:** a "Splitwise import added" section listing new frames 133 to 143, frames 29 and 31 changed in place, and the alternative wordings drawn once (136, 139, 141).
- **Figma:** Dark and Light boards plus the Flow page. Then re-export JSON and both PNGs with `design/tools/dump.js`.

## 8. Build order

1. `:splitwise` module and tests. Pure, and it unblocks the rest.
2. Server route, delete rule and vitest.
3. Room 5, planner, repository, SyncEngine and LocalGroupData with their tests.
4. Screens, navigation, entry points and strings.
5. Figma, flow.md, README and export.
6. Docs.
7. Run:
   - `./gradlew :splitwise:test :splits:test :app:testDebugUnitTest :app:assembleDebug`
   - `cd server && npm test`
   - an emulator run against `10.0.2.2:8787` with a hand-made 1,000-row file

## Decisions taken (2026-11-18)
The lead accepted every recommendation below. Lead note: Room version 5 (built first).
1. **Where does the group's name come from?** File name, cleaned (flat-302-export.csv becomes Flat 302) and shown in an editable Group name field. Splitwise does not put the group name in the CSV, and its download file name is not documented. Files forwarded on WhatsApp are often renamed to things like 'Download (1)', so the person must be able to fix the name. The field is the same Input as New group.
2. **How does the person say which column is them?** Option Cards on 29, with each person's balance as the subtitle. One is preselected only when exactly one name matches the profile name. Import stays disabled until one is chosen. A wrong pick swaps the person with a friend for good, so it must be seen and confirmed. One screen is easier than two steps, and the balance subtitle ('Is owed ₹2,350') helps people recognise themselves.
3. **What do the other members become?** Friends without the app, names only (no phone). Each one later joins their place through their own link. Matching by name is unreliable and could merge strangers. There is no lookup of users (server.md 10), and the design's 'Names only' row already promises this.
4. **What happens to expenses in other currencies?** Leave them out, with a note on 29 such as '3 expenses in other currencies stay out. Only rupees come across.' A file with no rupee rows is refused ('Nothing to bring across'). The server is INR only and conversion is out of scope at zero cost. Splitwise itself keeps a separate balance per currency, so the rupee balances still match Splitwise. Refusing the whole file over one stray USD row would block ordinary flat and trip groups.
5. **What happens to rows that do not add up (edited files)?** Refuse the whole file and ask for a fresh export. This applies when a row's values do not sum to zero, or when a rupee-only file's Total balance row disagrees with its columns. Splitwise rows are exact to the paisa, so a mismatch means the file was edited, usually in Excel. Skipping rows would bring wrong balances across silently, and that is worse than asking for a fresh export.
6. **How is a Splitwise expense with several payers brought across?** One expense per positive person, each paid by that person for their net amount, with shares filled from the people who owe. The export holds nets only, so the real split cannot be recovered. This keeps every balance exact and invents no payments that never happened. Only the cost and the payers' own shares of such rows are lost, and these rows are rare.
7. **How do Splitwise 'Payment' rows come across?** As confirmed settlements with method Other, recorded by the importer, between any two people, including two friends. They are repayments, and treating them as expenses would distort months. The new import route allows a settlement between two other people only while nobody else is on the app, so nobody can make up payments in a live group.
8. **What limits apply to one import?** 50 people (the group cap), 5,000 rupee rows or server items, a 5 MB file, and amounts up to ₹1 crore. Most flat and trip groups are well under 2,000 rows. 5,000 rows of 3 people write about a third of a day's free D1 writes, which is the most one import should take.
9. **How does the rest of the group get invited after an import (31)?** One message with every friend's own link ('Priya Nair: https://…'), shared through WhatsApp to the group's chat. 31's card becomes 'Invite links / Priya and Kiran / One message with a link for each person.'. Almost every Splitwise group in India already has a WhatsApp group, so one message is the least work. It reuses the per-place links, web page and Join group that exist today, with no new server feature. The undo-join banner covers a wrong tap for 7 days. A group-wide link would need a new link kind, a name picker on the web and in the app, and carries the same claim risk.
10. **Can people send links before the import has finished?** No. While import parts wait in the outbox, links for that group are not made, and the screen shows the Problem banner if asked. A friend who joins mid-import would make the remaining parts fail, because the import route only fills a group nobody else has joined.
11. **Do imported expenses count in the person's months?** Yes, like any split. The person's share counts, and their own payments link to a matching SMS or typed payment. It reuses the existing rules unchanged, and a person who joins a group by link already gets the same. A cut-off date would need a new rule that also changes joins and restores. Risk: Splitwise payments that do not match an SMS payment within a day can count twice.
12. **How can a mistaken import be undone?** Relax Delete group: the owner can delete whenever nobody else in the group is on the app. Group settings then shows the row enabled with 'Removes it for everyone. Nobody else has joined it yet.'. Without it, an import of the wrong file or with the wrong 'me' can never be removed, because nobody can settle it. The phone's clean-up of a refused import also needs it. It needs no migration, and only the person's own record is affected; friends without the app lose only their link page.
13. **Is the import offline-first?** Offline-first. The whole group is written to Room and the outbox in one transaction. 'Bringing Flat 302 across' shows progress and 'Open Flat 302', and offline it says so and finishes by itself later. This matches the app's rule that every write lands in Room first, and it reuses the outbox's retries, rate-limit waits and parking. Nothing is lost on a flaky mobile network.
14. **What happens when the same file is picked again?** Show the 'already here' state: 'This file already came across as Flat 302 on 18 Nov.' with Open Flat 302 or Choose a different file. It is detected by a hash of the file kept on the phone. Two identical groups would double every friend's link page and confuse everyone. The hash never leaves the phone.
15. **Is 'Import from Splitwise instead' shown on New group opened from a split (Choose people > Make a group)?** Hidden when New group comes prefilled from a split, and shown when it is opened from the Groups tab. From a split, the person is in the middle of splitting a payment, and an import there would break that flow's return. This is drawn as frame 142.
16. **How are Splitwise categories mapped?** A fixed table (Groceries, Dining out to Food, Rent and utilities to Rent and bills, and so on). 'General', the default, and unknown names use the app's CategoryGuesser on the description. Most Splitwise rows are 'General', and guessing from 'Swiggy' or 'Blinkit' gives useful month views at no cost.
17. **What about expenses dated in the future in Splitwise?** Dated today (India time). The server refuses dates after tomorrow, and keeping the balance right matters more than the date.
18. **A Splitwise friendship export (two people, not a group): what does it become?** A named group of two, named from the file and renamable. 'One file is one group' stays true, with no special case. A direct split with a friend without the app works no differently for balances.
19. **Should files re-saved in Excel (dates like 25/12/2025, amounts like 1,234.50) be accepted?** Yes: ISO dates, plus D/M/YYYY or M/D/YY with the order detected per file (day-first when unclear), and quoted grouped numbers. Many people open the file in Excel or Sheets before sending it on. A rejected file with no hint of why is a dead end, and balances do not depend on dates.

## Design additions (Figma Dark and Light boards, Flow page, flow.md, design/spec export)
### Import from Splitwise (file chosen), 02-everyday/29, changed in place
- Based on: 02-everyday/29-import-from-splitwise-file-chosen; Input (State=Default) from 26-new-group; Option Card from 01-onboarding/08-how-far-back
- Content: Body, top to bottom (gap 12):
- Display/L 'Bring a group from Splitwise'
- Body/M 'In Splitwise, open the group and choose Export as spreadsheet. Then pick that file here.'
- File: Setting Row, Icon/file 3D, Title 'flat-302-export.csv', Subtitle '46 expenses · 3 people'
- Input State=Default, Label 'Group name', Value 'Flat 302'
- Label (Label style, color/text/secondary) 'Which one is you?'
- Three Option Cards with Icon/user 3D, 8 apart:
  - 'Arjun Mehta' / 'Is owed ₹2,350', Selected=True
  - 'Priya Nair' / 'Owes ₹1,150', Selected=False
  - 'Kiran Rao' / 'Owes ₹1,200', Selected=False
- The three Info Rows as now: 'What comes across', 'One file, one group', 'Names only'
The body scrolls and clips under the footer.
Footer unchanged: Primary Large 'Import Flat 302', Ghost Medium 'Choose a different file'.
- flow.md: 04 Groups and friends > Branch: If moving from Splitwise, step 2 'SCREEN NEW Which one is you? - Pick your own name. The group name comes from the file name and can be changed.'

### Import from Splitwise (which one is you), 02-everyday/133, new
- Based on: The changed 02-everyday/29
- Content: Same as 29, except:
- No Option Card is selected.
- Primary Large 'Import Flat 302' is the disabled Primary (opacity 0.4, as on 04-you/12).
This is the state when no name matches the person's own.
- flow.md: Branch: If moving from Splitwise, step 2 note: 'Nothing is chosen when no name matches yours. Import stays off until you choose.'

### Import from Splitwise (other currencies), 02-everyday/134, new
- Based on: The changed 02-everyday/29; the Privacy promise 'Note' frame (Result note)
- Content: Same as 29, with a Result note directly under the File row: Note frame, Body/M color/text/primary, '3 expenses in other currencies stay out. Only rupees come across.'
The File row subtitle stays '46 expenses · 3 people'.
- flow.md: New 'Branch: If some expenses are not in rupees' - 1. SCREEN NEW Other currencies stay out - The file's rupee expenses come across. A note says how many stay out.

### Import from Splitwise (reading), 02-everyday/135, new
- Based on: 02-everyday/28-import-from-splitwise
- Content: Same as 28, except:
- The Drop area shows Title 'Reading the file' and Caption (color/text/secondary) 'flat-302-export.csv'.
- Footer: Primary Large 'Choose file' disabled (opacity 0.4).
- flow.md: Branch: If moving from Splitwise, step 1 note: 'Reading the file' shows while the file is read on the phone.

### Import from Splitwise (cannot import), 02-everyday/136, new
- Based on: 02-everyday/30-import-from-splitwise-wrong-file
- Content: Same frame as 30. The Drop area shows:
- Title 'This file does not add up'
- Caption (color/status/negative) 'flat-302-export.csv was changed after Splitwise made it. Export the group again and pick the new file.'
Footer: Primary Large 'Choose another file'.
Alternatives drawn once (listed in README):
- 'This group is too big' / 'flat-302-export.csv is over 5 MB. Only smaller groups can come across.'
- 'This group is too big' / '… has more than 50 people. Only groups of up to 50 people can come across.'
- 'This group is too big' / '… has more than 5,000 expenses. Only groups with up to 5,000 can come across.'
- 'An expense is too big' / '… has an expense over ₹1 crore, the most one expense can be here.'
- 'Nothing to bring across' / '… has no expenses in rupees. Only rupees can come across.'
- 'Nobody else is in this file' / '… has only one person. Pick the export of a group you share.'
- flow.md: New 'Branch: If the file cannot come across' - 1. SCREEN NEW Cannot import - It does not add up (changed after export), it is too big, it has nothing in rupees, or only one person. Choose another file.

### Import from Splitwise (already here), 02-everyday/137, new
- Based on: The changed 02-everyday/29; the Note frame
- Content: Body:
- Display/L 'Bring a group from Splitwise'
- Body/M intro as on 29
- File Setting Row: Icon/file 3D, 'flat-302-export.csv' / '46 expenses · 3 people'
- Note (Body/M color/text/primary): 'This file already came across as Flat 302 on 18 Nov.'
- The three Info Rows
Footer: Primary Large 'Open Flat 302', Ghost Medium 'Choose a different file'.
- flow.md: New 'Branch: If the file came across before' - 1. SCREEN NEW Already here - Open the group, or choose a different file.

### Importing, 02-everyday/138, new
- Based on: 01-onboarding/09-scanning-sms (Sticker tile, Display/L, Body/M, Progress block)
- Content: No Top Bar. Body padding [40,16,40,16], gap 12:
- Sticker tile, color/brand/soft, Icon/box 3D
- Display/L 'Bringing Flat 302 across'
- Body/M color/text/secondary 'Balances and past expenses are being saved. You can leave this screen. It finishes by itself.'
- Progress block: bar color/bg/surface-raised with the Done fill color/brand/primary at about 43%, and Caption '20 of 46 sent'
Footer: Ghost Medium 'Open Flat 302'.
- flow.md: Branch: If moving from Splitwise, new step 4 'SCREEN NEW Bringing it across - Progress. You can leave; it finishes by itself.'

### Importing (offline), 02-everyday/139, new
- Based on: 02-everyday/138; the Offline banner frame from 03-home-offline
- Content: Same as 138, with the Offline banner (color/accent/yellow-soft, Icon/wifi-off) at the top of the body: 'You're offline' / 'Check your connection and try again. Nothing is lost.' The progress stays where it stopped ('20 of 46 sent').
Alternative drawn once (README): the Problem banner (Icon/alert) 'Could not do that now' / 'Try again in a moment.'
- flow.md: New 'Branch: If offline while importing' - 1. SCREEN NEW Importing (offline) - The group is already on this phone. It finishes sending when you are back online.

### Import failed, 02-everyday/140, new
- Based on: 02-everyday/31-group-imported layout (Sticker tile, Display/L, Body/L, Footer)
- Content: No Top Bar.
- Sticker tile, color/accent/yellow-soft (Sticker Yellow), Icon/alert 3D
- Display/L 'Flat 302 did not come across'
- Body/L color/text/secondary 'Part of the file could not be saved, so none of it was kept. Export the group from Splitwise again and try once more.'
Footer: Primary Large 'Choose another file', Ghost Medium 'Close'.
- flow.md: New 'Branch: If it could not be saved' - 1. OUTCOME NEW Did not come across - The group is removed everywhere. Export again and try once more.

### Group imported, 02-everyday/31, changed in place
- Based on: 02-everyday/31-group-imported
- Content: The 'Invite link' card becomes:
- Label 'Invite links'
- Title (color/text/brand) 'Priya and Kiran'
- Caption (color/text/secondary) 'One message with a link for each person.'
Padding 12/16, gap 2; the card grows by the caption line.
Primary Large label: 'Share invite links'.
Unchanged: the Sticker tile (check-circle), 'Flat 302 is in', 'Balances and 46 past expenses came across.', the Info Row 'Friends can join or just pay' / 'Nobody needs the app to see what they owe.', and Ghost Medium 'Later'.
README notes:
- One friend: 'Invite link' / 'Priya' / 'One message with the link for Priya.' / 'Share invite link'
- Four or more: 'Priya, Kiran and 3 more'
- flow.md: Branch: If moving from Splitwise, step 5 'OUTCOME NEW Invite the group - One message with a link for each person, for the group's chat, brings the rest across.'

### Group imported (offline), 02-everyday/141, new
- Based on: The changed 02-everyday/31; the Offline banner frame
- Content: Same as the changed 31, with the Offline banner at the top of the body: 'You're offline' / 'Check your connection and try again. Nothing is lost.'
Alternative drawn once (README): the Problem banner 'Could not do that now' / 'Try again in a moment.'
- flow.md: Branch: If moving from Splitwise, step 5 note: 'Links need the internet; offline, a banner says so.'

### New group (from a split), 02-everyday/142, new
- Based on: 02-everyday/26-new-group, with the people of 02-everyday/48-choose-people-several-friends
- Content: Same as 26:
- Group name Input empty and focused.
- Rahul Menon, Priya Nair and Kiran Rao switched on (filled in from Choose people).
- The 'Add someone' row and the Info Row as on 26.
- Footer: only Primary Large 'Create group'. 'Import from Splitwise instead' is not shown.
- flow.md: 03 split lane, 'Make a group' branch note: 'New group from a split has no Import from Splitwise.'

### Group settings (nobody else joined), 02-everyday/143, new
- Based on: 02-everyday/25-group-settings
- Content: Owner Arjun Mehta (Owner tag); Priya Nair and Kiran Rao 'Not on SumBuddy'.
The Leaving section's Delete group Info Row (Icon/trash 3D) is tappable, with Subtitle 'Removes it for everyone. Nobody else has joined it yet.'
The confirm on tap is 93 as drawn.
- flow.md: 04 > Branch: To manage a group, add: 'SCREEN NEW Delete before anyone joins - While nobody else is on the app, the owner can delete the group without settling first.'

### Flow page and flow.md: Branch: If moving from Splitwise, rewritten
- Based on: Flow page lane 04, existing branch cards
- Content: 1. SCREEN NEW Import from Splitwise - From New group (Import from Splitwise instead) and You (More). Upload the group's Splitwise export file. One file is one group.
2. SCREEN NEW Which one is you? - Pick your own name. The group name comes from the file name and can be changed.
3. AUTOMATIC NEW Recreate the group - Members (names only), past expenses, payments and balances are copied from the file. Rupees only.
4. SCREEN NEW Bringing it across - Progress. You can leave; it finishes by itself.
5. OUTCOME NEW Invite the group - One message with a link for each person brings the rest of the group across.
- flow.md: Replaces the three-step branch in design/spec/flow.md lines 387-391, with the new branches listed in the other additions. The existing 'Branch: If the file is not a Splitwise export' stays.

## Risks
- Splitwise's export format has no official specification. The layout comes from Splitwise's help article (signs and the Cost column) and community samples from 2020 to 2025 (Spliit #22 and #342, TheFacc, Cospend #203, a gist). Before release, check it against a real export made from the Splitwise Android app and the web, both from an Indian account.
- Only English headers are accepted. Exports from a Splitwise account set to another language (for example 'Zahlung' for Payment, translated column names) show 'That is not a Splitwise export'.
- Splitwise's download file name is unconfirmed (the design assumes flat-302-export.csv). The cleaned name may be wrong, which is why the Group name field is editable.
- The shape of the 'Total balance' row in a file with several currencies is unknown (one row per currency, or one converted row). The checksum runs only on rupee-only files with one total row. If a real single-currency total ever differs in a valid export (for example it includes deleted or converted rows), valid files would be refused as 'does not add up'.
- The Splitwise category names in the mapping table are from memory of the app and API. Unknown names fall back to guessing from the description, so wrong or missing names only lower category quality.
- Rows with several payers are rebuilt as one expense per payer from nets. Balances are exact, but the cost is lost, and a payer's own share shows as 0 in their month.
- Personal months: imported expenses follow 9.4. Splitwise expenses the person paid that do not match one SMS payment of the same amount within a day become a mirror of their share, while the SMS payment may already count in full, so a month can count it twice. Months from before tracking began fill with shares only.
- Sharing every friend's link in one group chat lets anyone in the chat claim someone else's place. Join group (32) does not say whose place it is. The owner's 7-day undo is the only guard. Consider adding 'Join as {name}' to Join group later (open point).
- Making links is one PUT per friend and counts against RL_WRITE (30 per 60 s). Groups of more than about 25 friends will wait up to a minute on 31.
- Free tier: a 5,000-row import writes about 33,000 D1 rows, a third of a day. A malicious signed-in client could repeat imports across new groups and use up the day's writes. Like the existing routes, rate limits stop a looping client, not a determined attacker.
- CPU: an import request of 50 items with about 200 statements is expected at 1-3 ms. Check it against the 10 ms Free limit in the Workers dashboard after deploy.
- D1 has no documented statement cap per batch. If Cloudflare introduces one, lower the 50-item part size (phone constant and server check) together.
- Deleting with balances not settled while nobody else is on the app changes an existing rule (server 4.6, Group settings copy). It also applies to ordinary groups that only have friends without the app. It is a product decision and needs the owner's yes.
- When a part is refused, the phone removes the group and queues DELETE /v1/groups/{gid}. If someone joined meanwhile, the delete gets 409, the entry is dropped, and the partial group comes back on the next pull. It can be rare, because links are blocked while parts wait.
- A refused part should only happen through a validation mismatch between phone and server. Keep ImportPlanner's checks identical to parseExpenseFields (text clean, 80 and 60 characters, dates, sums), or every such import will be removed.
- After an import, the Group page may show its loading placeholders for a few seconds while the phone pulls the whole history back from version 0 (existing LOADING rule for a new group).
- Expedited WorkManager jobs have runtime quotas. A big import (100 parts plus 429 waits) can be stopped part way and resumed by the next sync. Progress then pauses, which is correct but slower.
- The Group page lists only this month's expenses, and no screen shows a group's older history. 'Balances and 46 past expenses came across' is true, but most of those expenses are not visible anywhere except in the person's own Activity mirrors. This is an open point for product.
- The EQUAL label depends on random member ids: when Splitwise gave the extra paisa to a different person than SplitMath would, the expense shows 'by amounts' instead of 'equally'.
- Excel re-saved files: day and month order is detected per file and is day-first when unclear, so dates in tiny files could be swapped (balances are unaffected). Other Excel changes (currency symbols in cells, 0 decimals) make the file unreadable.
- Sign-out while parts wait loses them, after the existing 'N changes are not sent yet' warning. One part counts as one change, so the number is smaller than the rows lost.
- Pull skipping groups with a pending DELETE changes 5.2. Make sure an ordinary group never gets a queued DELETE: leave, remove and delete stay online-only for the person.
- Settled: docs/server.md section 3 now lists migration 0002 as applied on 2026-11-18 (docs/server-setup.md 4.2), which is what the live database has. This spec needs no migration.

## Files expected to change

Written before the build. As built, `GroupNameFromFile`, `SplitwiseCategories` and their tests are in `splitwise/`
(5.2), the app has `ImportCategories` in their place, and the import screens' package also holds `PickedFile.kt`.
- `server/src/routes/imports.ts (new)`
- `server/src/router.ts`
- `server/src/routes/ledger.ts`
- `server/src/db/ledger.ts`
- `server/src/db/mutation.ts`
- `server/src/routes/groups.ts`
- `server/test/import.test.ts (new)`
- `server/test/groups.test.ts`
- `server/test/logging.test.ts`
- `server/test/helpers.ts`
- `settings.gradle.kts`
- `splitwise/build.gradle.kts (new)`
- `splitwise/src/main/kotlin/com/expenses/splitwise/Csv.kt (new)`
- `splitwise/src/main/kotlin/com/expenses/splitwise/SplitwiseCsv.kt (new)`
- `splitwise/src/main/kotlin/com/expenses/splitwise/SplitwiseFile.kt (new)`
- `splitwise/src/main/kotlin/com/expenses/splitwise/Money.kt (new)`
- `splitwise/src/main/kotlin/com/expenses/splitwise/Dates.kt (new)`
- `splitwise/src/test/kotlin/com/expenses/splitwise/SplitwiseCsvTest.kt (new)`
- `splitwise/src/test/resources/*.csv (new fixtures)`
- `app/build.gradle.kts`
- `app/src/main/kotlin/com/expenses/app/groups/splitwise/GroupNameFromFile.kt (new)`
- `app/src/main/kotlin/com/expenses/app/groups/splitwise/SplitwiseCategories.kt (new)`
- `app/src/main/kotlin/com/expenses/app/groups/splitwise/ImportPlanner.kt (new)`
- `app/src/main/kotlin/com/expenses/app/groups/splitwise/ImportBodies.kt (new)`
- `app/src/main/kotlin/com/expenses/app/groups/data/GroupEntities.kt`
- `app/src/main/kotlin/com/expenses/app/groups/data/GroupDaos.kt`
- `app/src/main/kotlin/com/expenses/app/data/db/AppDatabase.kt`
- `app/schemas/com.expenses.app.data.db.AppDatabase/5.json (generated)`
- `app/src/main/kotlin/com/expenses/app/groups/data/GroupsRepository.kt`
- `app/src/main/kotlin/com/expenses/app/groups/api/Dtos.kt`
- `app/src/main/kotlin/com/expenses/app/groups/sync/SyncEngine.kt`
- `app/src/main/kotlin/com/expenses/app/groups/sync/LocalGroupData.kt`
- `app/src/main/kotlin/com/expenses/app/ui/nav/Routes.kt`
- `app/src/main/kotlin/com/expenses/app/ui/nav/AppNavHost.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/splitwise/ImportSplitwiseScreen.kt (new)`
- `app/src/main/kotlin/com/expenses/app/ui/screens/splitwise/ImportSplitwiseUiState.kt (new)`
- `app/src/main/kotlin/com/expenses/app/ui/screens/splitwise/ImportSplitwiseViewModel.kt (new)`
- `app/src/main/kotlin/com/expenses/app/ui/screens/splitwise/PickedFile.kt (new)`
- `app/src/main/kotlin/com/expenses/app/ui/screens/splitwise/GroupImportedScreen.kt (new)`
- `app/src/main/kotlin/com/expenses/app/ui/screens/splitwise/GroupImportedViewModel.kt (new)`
- `app/src/main/kotlin/com/expenses/app/ui/screens/splitwise/SplitwiseNav.kt (new)`
- `app/src/main/kotlin/com/expenses/app/ui/components/ProgressBar.kt (new, extracted from ScanningScreen)`
- `app/src/main/kotlin/com/expenses/app/ui/screens/onboarding/ScanningScreen.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/groups/NewGroupScreen.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/groups/GroupsNav.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/groups/GroupsScreen.kt (KDoc)`
- `app/src/main/kotlin/com/expenses/app/ui/screens/you/YouScreen.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/you/YouUiState.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/you/YouNav.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/grouppage/GroupSettingsUiState.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/grouppage/GroupSettingsViewModel.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/grouppage/GroupSettingsScreen.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/grouppage/GroupPageSamples.kt`
- `app/src/main/res/values/strings_import.xml (new)`
- `app/src/main/res/values/strings_groups.xml`
- `app/src/main/res/values/strings_you.xml`
- `app/src/main/res/values/strings_grouppage.xml`
- `app/src/debug/kotlin/com/expenses/app/preview/ (Splitwise previews, new)`
- `app/src/test/kotlin/com/expenses/app/groups/splitwise/GroupNameFromFileTest.kt (new)`
- `app/src/test/kotlin/com/expenses/app/groups/splitwise/SplitwiseCategoriesTest.kt (new)`
- `app/src/test/kotlin/com/expenses/app/groups/splitwise/ImportPlannerTest.kt (new)`
- `app/src/test/kotlin/com/expenses/app/groups/GroupsRepositoryTest.kt`
- `app/src/test/kotlin/com/expenses/app/groups/SyncEngineTest.kt`
- `app/src/test/kotlin/com/expenses/app/data/db/DatabaseMigrationTest.kt`
- `app/src/test/kotlin/com/expenses/app/ui/screens/splitwise/ImportSplitwiseViewModelTest.kt (new)`
- `app/src/test/kotlin/com/expenses/app/ui/screens/splitwise/ImportSplitwiseScreenTest.kt (new)`
- `app/src/test/kotlin/com/expenses/app/ui/screens/splitwise/GroupImportedViewModelTest.kt (new)`
- `app/src/test/kotlin/com/expenses/app/ui/screens/you/YouScreenTest.kt`
- `app/src/test/kotlin/com/expenses/app/ui/screens/groups/GroupsScreenTest.kt`
- `app/src/test/kotlin/com/expenses/app/ui/screens/groups/NewGroupNavigationTest.kt`
- `app/src/test/kotlin/com/expenses/app/ui/screens/grouppage/GroupSettingsViewModelTest.kt`
- `app/src/test/kotlin/com/expenses/app/groups/LiveServerTest.kt (optional end-to-end)`
- `docs/server.md`
- `docs/product.md`
- `docs/architecture.md`
- `CLAUDE.md (Layout: splitwise/)`
- `design/spec/flow.md`
- `design/spec/README.md`
- `design/spec/screens/02-everyday/29-import-from-splitwise-file-chosen.json/.dark.png/.light.png`
- `design/spec/screens/02-everyday/31-group-imported.json/.dark.png/.light.png`
- `design/spec/screens/02-everyday/133-143 (new .json/.dark.png/.light.png)`
- `Figma file eb0tjEItIpuHkVkKloxqgC: Dark and Light boards, Flow page`

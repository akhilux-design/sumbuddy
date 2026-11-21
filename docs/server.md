# Server contract (splitting)

The contract every builder of the split feature follows: the Cloudflare server (`server/`), the friends' web link
(`server/web/`), the shared split maths (`shared/vectors/`, `:splits`) and the Android data and sync layer. When something
here changes, change this file in the same commit. Read `docs/product.md` ("Server (decided 2026-11-17)") and
`design/spec/flow.md` first; `docs/backend-options.md` explains why this stack.

Written 2026-11-17, before any account existed, and built and tested locally (wrangler dev, miniflare,
`@cloudflare/vitest-plugin`, local D1). Since then the Cloudflare account, the D1 database, the Google OAuth clients
and the Firebase project exist and the Worker is live: `docs/server-setup.md` says what is done, what is deployed and
which migrations the live database has. The real ids and hosts (none of them secret) are in `server/wrangler.toml`
and the app's Gradle properties; secrets are only in Cloudflare (`wrangler secret put`) and in the untracked
`server/.dev.vars`, never in this file or the repository. Where this file shows a placeholder (section 11), read the
value from `server/wrangler.toml`.

## 1. What the design needs from the server

Read from `design/spec/screens/02-everyday` (Groups tab, split screens), `03-getting-paid-back`, `04-you`,
`06-family`, `01-onboarding` (Sign in) and `flow.md`.

| Screen / flow card | Needs from the server |
|---|---|
| 01 Sign in, Sign in (could not sign in), Welcome "I already have an account", You (not signed in), Groups (not signed in) | Google sign-in, own session. Failure leaves the phone unchanged. |
| 01 Your name (after sign-in), 04 Account | Profile: name (required), phone (optional, unverified, seen only by people in the user's groups). Sign out. Delete account. |
| Flow 01 "Groups and balances restored" | Everything group-related lives in the account and comes back on a new phone (full pull from version 0). |
| 02/20 Groups | My groups with my net per group, "OVERALL YOU ARE OWED", Friends (per-person totals across groups). Computed on the phone from synced data. |
| 02/21-23 Groups empty, loading, Could not load | First sync of a group can be slow or fail; "Nothing is lost" (outbox). |
| 02/24 Group page | Members, balances to me ("owes you", "all settled"), this month's expenses with payer, date, split method ("equally") and "splits itself". Settle up, Remind. |
| 02/25 Group settings | Rename; people "On SumBuddy" / "Not on SumBuddy"; add someone by name and phone; remove; leave once my balance is 0; delete (removes for everyone; the owner only, when every net is 0 or nobody else in the group is on the app, 4.6; 02/143 is the second case, how a wrong Splitwise import is removed). |
| 02/26 New group, 02/27 Add friend | Create group with app users I already know and friends without the app (name + phone). |
| 02/32 Join group | Invite preview before joining: inviter, group, people, "You owe ₹1,150 so far", "5 expenses in November". Accept. |
| 02/09-13 Split it, Choose people, Split. Sorted., Undo | Group expense with payer and per-member shares; methods Equally, Amounts, %, Shares; single friends outside a group (direct split); delete for Undo. Friends are notified. |
| 02/15 Add expense | Expense paid by someone else ("Paid by Rahul"). |
| 02/14 Recurring split offer, "Do this every time" | Phone only (a local rule). The server stores only the `auto` flag shown as "splits itself". |
| 02/00, 03 Home, Home offline | "Owed to you" / "You owe"; offline: own expenses work, splits wait and sync. |
| 02/16 Activity | "From splits", "your share of ₹450", "Splits" filter: the phone's own month data (see 9.4). |
| 03/00 Settle up | Pair balance ("You owe Rahul ₹1,200 · 4 expenses · Flat 302"); the payee's UPI ID for Pay by UPI. |
| 03/01 Mark as paid, 03/02 Money received, 03/03 All square | Settlements (cash, bank transfer or other, UPI), recorded by either party, confirmed by the other; SMS matching happens on the phone. Both people notified. |
| 03/04 Card bill view | Phone only (card and bill come from SMS). Uses group balances. |
| 03/05 Remind on WhatsApp | A per-friend link (`https://<LINK_HOST>/pay/<token>`). The message goes from the user's own WhatsApp. |
| 03/06 Friend web page | Public read-only page: sender, the friend's share, the expense breakdown, Pay by UPI. No sign-in. |
| 06/00 Set up Family, 06/01 Join Family, 06/02 Home (family month), 06/17-24 Family settings, 06/28 web invite | A family (4.18): a group of kind `family` with one owner and at most 2 active members (the invite's placeholder included), one live family per user. Made online with a placeholder whose friend's link (7 days) is the invite; joined with that link. Family expenses are each person's own payments (one share, the payer's, full amount), so nobody owes anybody: no balances, settle-up or reminders. Leave, removal and account deletion take that person's family expenses out. The friends' page shows a family invite (7). |

## 2. Decisions

1. **Zero running cost.** Workers (API), D1 (SQL), one Durable Object per group (live room, WebSocket hibernation),
   Pages (web link on `*.pages.dev`), FCM HTTP v1 (push, free Firebase project used for messaging only).
2. **Sign-in = Google.** Android Credential Manager gets a Google ID token; the Worker verifies it against Google's
   JWKS and issues its own access token (24 h; every request still checks the session row, 4.4) and refresh token
   (60 days, rotated). No phone OTP.
3. **The phone is the source of its own truth; the server holds only what is shared.** SMS text, personal (unsplit)
   expenses, accounts, cards, card bills, notes, categories rules and split rules never leave the phone. The server
   gets a group expense only when the user puts it in a group (Split, Add expense with Split, or a recurring rule the
   user turned on).
4. **Offline first.** Every write happens in Room first, goes into an outbox with a client-made id and an
   idempotency key, and is sent later. Reads come from Room only.
5. **One version counter per group.** Every accepted mutation bumps the group's version by one; every row it touches
   carries that version. Phones pull "everything in this group with version > N".
6. **Last writer wins** per expense, by server arrival order. Deletes are tombstones and win over later edits.
7. **Balances are not stored.** Server and phone compute them from expenses and settlements with the same integer
   algorithm, checked by shared test vectors (section 6).
8. **Push carries no content.** FCM messages are data-only ("group X changed to version N"); the phone syncs and
   writes the notification text itself. Nothing about expenses passes through Google.
9. **A family is a group of kind `family`** (decided 2026-11-18, docs/family.md decision 1; 4.18). It reuses groups,
   members, links, the version counter, pulls, the outbox and push. A new kind, not a flag on `group`: every existing
   check that tests `kind === 'group'` (or `!== 'group'`) refuses a family until a route opens it on purpose, so a
   missed check cannot treat a family as a splitting group (balances, settlements, the Groups list, the link page).
   0001's CHECK did not allow it, hence the table rebuild in 0003 (3).

## 3. Data model (D1)

Migrations live in `server/migrations/NNNN_name.sql`, applied with `wrangler d1 migrations apply xpense --local`
(and `--remote` later). Never edit an applied migration; add a new one. `0001_init.sql` was still changed once on
2026-11-17 (review notes 2, item 6), because it had been applied only to local databases; it is frozen from its first
`--remote` apply. The first file is `server/migrations/0001_init.sql`, exactly:

```sql
-- Conventions: ids are lowercase UUID strings; times are epoch milliseconds (INTEGER); dates are 'YYYY-MM-DD'
-- (India date); money is INTEGER paise; booleans are INTEGER 0/1. Every group-owned row carries `version`,
-- the group version of the mutation that last touched it.

CREATE TABLE users (
  id          TEXT PRIMARY KEY,             -- server-made UUID v4
  google_sub  TEXT UNIQUE,                  -- Google account id; NULL only for development users
  dev_key     TEXT UNIQUE,                  -- development sign-in only; always NULL in production
  name        TEXT NOT NULL,
  phone       TEXT,                         -- E.164, optional, not verified
  upi_id      TEXT,                         -- optional, for Pay by UPI
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL,
  CHECK (google_sub IS NOT NULL OR dev_key IS NOT NULL)
);

CREATE TABLE sessions (
  id                 TEXT PRIMARY KEY,
  user_id            TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  refresh_hash       TEXT NOT NULL UNIQUE,  -- hex SHA-256 of the current refresh token
  prev_refresh_hash  TEXT,                  -- the token it replaced, for the 10 min lost-answer window (4.4)
  rotated_at         INTEGER,
  nonce_hash         TEXT UNIQUE,           -- hex SHA-256 of the sign-in nonce, so a nonce signs in once; NULL for dev
  created_at         INTEGER NOT NULL,
  expires_at         INTEGER NOT NULL,      -- sliding: now + 60 days at each refresh
  revoked_at         INTEGER
);
CREATE INDEX sessions_user ON sessions(user_id);
CREATE INDEX sessions_prev ON sessions(prev_refresh_hash) WHERE prev_refresh_hash IS NOT NULL;

CREATE TABLE devices (
  installation_id  TEXT PRIMARY KEY,        -- random UUID made once per app install
  user_id          TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  fcm_token        TEXT NOT NULL UNIQUE,
  updated_at       INTEGER NOT NULL
);
CREATE INDEX devices_user ON devices(user_id);

CREATE TABLE groups (
  id             TEXT PRIMARY KEY,          -- client-made UUID (deterministic for direct groups, 4.6)
  kind           TEXT NOT NULL CHECK (kind IN ('group', 'direct')),
  name           TEXT,                      -- NULL for kind 'direct'
  owner_user_id  TEXT REFERENCES users(id) ON DELETE SET NULL,
  version        INTEGER NOT NULL DEFAULT 0,
  created_at     INTEGER NOT NULL,
  updated_at     INTEGER NOT NULL,
  deleted_at     INTEGER,                   -- tombstone; child rows are purged later by the cron (4.13)
  purged_at      INTEGER,                   -- set by the cron once no child row is left
  CHECK ((kind = 'group' AND name IS NOT NULL) OR (kind = 'direct' AND name IS NULL))
);
-- Deleting a user sets owner_user_id to NULL (and DELETE /v1/me passes ownership): without this index each of
-- those reads every group. Written only when a group is made or changes owner, never on a version bump.
CREATE INDEX groups_owner ON groups(owner_user_id) WHERE owner_user_id IS NOT NULL;
-- The daily purge finds tombstoned groups without reading every group (written only on delete and purge).
CREATE INDEX groups_to_purge ON groups(deleted_at, id) WHERE deleted_at IS NOT NULL AND purged_at IS NULL;

CREATE TABLE members (
  id                  TEXT PRIMARY KEY,     -- client-made UUID (deterministic for app users in direct groups)
  group_id            TEXT NOT NULL REFERENCES groups(id),
  user_id             TEXT REFERENCES users(id) ON DELETE SET NULL,  -- NULL = friend without the app
  name                TEXT NOT NULL,        -- shown when user_id is NULL; for app users the user's name wins
  phone               TEXT,                 -- friends without the app only
  added_by_member_id  TEXT,
  joined_at           INTEGER,              -- when user_id was set (created with a user, or link accepted)
  last_reminded_at    INTEGER,
  version             INTEGER NOT NULL,
  created_at          INTEGER NOT NULL,
  removed_at          INTEGER
);
CREATE INDEX members_group_version ON members(group_id, version);
CREATE INDEX members_user ON members(user_id) WHERE user_id IS NOT NULL;
CREATE UNIQUE INDEX members_one_per_user ON members(group_id, user_id)
  WHERE user_id IS NOT NULL AND removed_at IS NULL;

-- members_no_fk: the member columns of expenses, expense_shares and settlements carry no REFERENCES members(id).
-- With one, every member row the cron purges makes SQLite look for children in those tables, and none of these
-- columns is indexed, so each deleted member would read every expense, share and settlement in the database.
-- Indexing them instead would add written rows to every expense. Members are never deleted while their group
-- lives, and the 3.1 guard checks that every member a write names is an active member of that group.
CREATE TABLE expenses (
  id                    TEXT PRIMARY KEY,   -- client-made UUID
  group_id              TEXT NOT NULL REFERENCES groups(id),
  description           TEXT NOT NULL,
  category              TEXT NOT NULL CHECK (category IN
                          ('FOOD','GROCERIES','TRAVEL','RENT_AND_BILLS','SHOPPING','FUN','OTHER')),
  amount_paise          INTEGER NOT NULL CHECK (amount_paise > 0),
  currency              TEXT NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
  occurred_on           TEXT NOT NULL,
  paid_by_member_id     TEXT NOT NULL,      -- a member of the group (no REFERENCES: see members_no_fk above)
  split_method          TEXT NOT NULL CHECK (split_method IN ('EQUAL','AMOUNTS','PERCENT','SHARES')),
  auto                  INTEGER NOT NULL DEFAULT 0,   -- "splits itself"
  created_by_member_id  TEXT NOT NULL,
  updated_by_member_id  TEXT NOT NULL,
  version               INTEGER NOT NULL,
  created_at            INTEGER NOT NULL,
  updated_at            INTEGER NOT NULL,
  deleted_at            INTEGER
) WITHOUT ROWID;                            -- no separate primary-key index: one written row less per create
CREATE INDEX expenses_group_version ON expenses(group_id, version);

CREATE TABLE expense_shares (
  expense_id   TEXT NOT NULL REFERENCES expenses(id),
  member_id    TEXT NOT NULL,               -- a member of the expense's group
  share_paise  INTEGER NOT NULL CHECK (share_paise >= 0),
  input        INTEGER,                     -- EQUAL: NULL; AMOUNTS: paise; PERCENT: basis points; SHARES: weight
  PRIMARY KEY (expense_id, member_id)
) WITHOUT ROWID;

CREATE TABLE settlements (
  id                    TEXT PRIMARY KEY,   -- client-made UUID
  group_id              TEXT NOT NULL REFERENCES groups(id),
  from_member_id        TEXT NOT NULL,      -- who paid (a member of the group)
  to_member_id          TEXT NOT NULL,      -- who received (a member of the group)
  amount_paise          INTEGER NOT NULL CHECK (amount_paise > 0),
  method                TEXT NOT NULL CHECK (method IN ('UPI','CASH','OTHER')),
  matched_from_sms      INTEGER NOT NULL DEFAULT 0,
  occurred_on           TEXT NOT NULL,
  recorded_by_member_id TEXT NOT NULL,
  confirmed_at          INTEGER,
  version               INTEGER NOT NULL,
  created_at            INTEGER NOT NULL,
  deleted_at            INTEGER,
  CHECK (from_member_id <> to_member_id)
) WITHOUT ROWID;
CREATE INDEX settlements_group_version ON settlements(group_id, version);

-- Activity / change log: one row per accepted mutation. Pulls read entity rows by version; this log tells the
-- phone what happened (for notification text) and who did it.
CREATE TABLE changes (
  group_id         TEXT NOT NULL,
  version          INTEGER NOT NULL,
  kind             TEXT NOT NULL CHECK (kind IN ('group','member','expense','settlement')),
  op               TEXT NOT NULL CHECK (op IN ('create','update','delete','join','leave','remove','confirm')),
  entity_id        TEXT NOT NULL,
  actor_member_id  TEXT,
  created_at       INTEGER NOT NULL,
  PRIMARY KEY (group_id, version)
) WITHOUT ROWID;

-- Share links: one live link per (member, creator) as the phone keeps reusing its token. Only the hash is stored.
CREATE TABLE links (
  id                    TEXT PRIMARY KEY,   -- client-made UUID (the phone makes id and token, 4.10)
  token_hash            TEXT NOT NULL UNIQUE,  -- hex SHA-256 of the token
  group_id              TEXT NOT NULL REFERENCES groups(id),
  member_id             TEXT NOT NULL REFERENCES members(id),  -- the friend the link is for
  created_by_member_id  TEXT NOT NULL,
  created_at            INTEGER NOT NULL,
  expires_at            INTEGER NOT NULL,   -- created_at + 90 days
  revoked_at            INTEGER
);
CREATE INDEX links_member ON links(member_id);
CREATE INDEX links_group ON links(group_id);   -- group delete, account deletion and the cron purge revoke or delete by group

-- Derived data only (balances, the public link page, the invite preview), keyed by the group version it was
-- computed at. A row whose version is not the group's current version is ignored and recomputed (4.16).
CREATE TABLE group_cache (
  group_id   TEXT NOT NULL,
  cache_key  TEXT NOT NULL,                 -- 'balances', 'link:<linkId>' or 'invite:<memberId>:<YYYY-MM>'
  version    INTEGER NOT NULL,
  body       TEXT NOT NULL,                 -- JSON
  PRIMARY KEY (group_id, cache_key)
) WITHOUT ROWID;

CREATE TABLE idempotency_keys (
  user_id       TEXT NOT NULL,
  key           TEXT NOT NULL,              -- the Idempotency-Key header (the outbox entry's UUID)
  request_hash  TEXT NOT NULL,              -- hex SHA-256 of method + path + canonical JSON body
  status        INTEGER NOT NULL,           -- HTTP status of the first answer
  entity_id     TEXT,
  created_at    INTEGER NOT NULL,
  PRIMARY KEY (user_id, key)
) WITHOUT ROWID;
```

Migrations, in order (`wrangler d1 migrations apply` records each one it ran in the `d1_migrations` table):

| File | What it does | Live database |
|---|---|---|
| `0001_init.sql` | the schema above | applied 2026-11-17; frozen |
| `0002_undo_join.sql` | three nullable columns on `members` for undoing a join by link (4.6), and the table `access_ended` (why a person lost a group, 4.5) | applied 2026-11-18 (docs/server-setup.md 4.2); frozen |
| `0003_family.sql` | rebuilds `groups` so `kind` may be `'family'` (and a family must have a name); same columns, rows and indexes (4.18) | **not applied yet**: owner steps in "Build notes, family" |

Import from Splitwise (4.17) needs **no migration**: no new table, column, change op or CHECK. Its rows are ordinary
expenses, shares, settlements and change rows.

`server/migrations/0002_undo_join.sql` (after its header comment), exactly:

```sql
ALTER TABLE members ADD COLUMN join_link_id TEXT;     -- the link accepted to take this place; NULL = not joined by link
ALTER TABLE members ADD COLUMN join_prev_name TEXT;   -- members.name just before that join (the friend's name)
ALTER TABLE members ADD COLUMN join_prev_phone TEXT;  -- members.phone just before that join (accept clears phone)

-- access_ended: why a person is no longer in a group, so their phone can tell an undone join (forget the place's
-- splits) from a leave, a removal or a deletion (keep them; docs/server.md 9.4). One row per (user, group), the latest
-- reason winning. Written in the same batch as the leave, the removal, the undo-join (one row) or the group delete
-- (one row per member on the app). Read by GET /v1/me/groups (`lost`) and by the `not_member` answer (`reason`), only
-- for groups the user is not active in again. Deleted by the daily cron 90 days after `at`, and with the user
-- (ON DELETE CASCADE: the primary key starts with user_id, so that delete reads only the user's rows). No REFERENCES
-- groups(id): the cron purges deleted groups without looking here; a row outlives its group by at most 90 days.
CREATE TABLE access_ended (
  user_id   TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  group_id  TEXT NOT NULL,
  reason    TEXT NOT NULL CHECK (reason IN ('join_undone', 'removed', 'left', 'deleted')),
  at        INTEGER NOT NULL,       -- when access ended (epoch ms)
  PRIMARY KEY (user_id, group_id)
) WITHOUT ROWID;
```

Order matters. The Worker built with 0002 names `join_link_id` in every member read and writes `access_ended` in every
leave, removal, undo-join and group delete, so deployed before 0002 is applied it fails every group route. 0002 alone
changes nothing for the Worker that runs now (it adds nullable columns and a table nobody reads yet, and rewrites no
row). So: apply 0002 `--remote` first, then deploy. 0002 was amended on 2026-11-18 (the table) before any `--remote`
apply; it is frozen from that apply, like 0001.

`server/migrations/0003_family.sql` (after its header comment), exactly:

```sql
DROP TABLE IF EXISTS groups_new;   -- a leftover from a failed earlier attempt, never real data

PRAGMA defer_foreign_keys = on;

CREATE TABLE groups_new (
  id             TEXT PRIMARY KEY,
  kind           TEXT NOT NULL CHECK (kind IN ('group', 'direct', 'family')),
  name           TEXT,
  owner_user_id  TEXT REFERENCES users(id) ON DELETE SET NULL,
  version        INTEGER NOT NULL DEFAULT 0,
  created_at     INTEGER NOT NULL,
  updated_at     INTEGER NOT NULL,
  deleted_at     INTEGER,
  purged_at      INTEGER,
  CHECK ((kind IN ('group', 'family') AND name IS NOT NULL) OR (kind = 'direct' AND name IS NULL))
);

INSERT INTO groups_new (id, kind, name, owner_user_id, version, created_at, updated_at, deleted_at, purged_at)
  SELECT id, kind, name, owner_user_id, version, created_at, updated_at, deleted_at, purged_at FROM groups;

DROP TABLE groups;
ALTER TABLE groups_new RENAME TO groups;

CREATE INDEX groups_owner ON groups(owner_user_id) WHERE owner_user_id IS NOT NULL;
CREATE INDEX groups_to_purge ON groups(deleted_at, id) WHERE deleted_at IS NOT NULL AND purged_at IS NULL;

PRAGMA defer_foreign_keys = off;
```

Why a rebuild: 0001's CHECK on `groups.kind` allows only `'group'` and `'direct'`, and SQLite cannot alter a CHECK in
place. The order is the one Cloudflare documents for D1 (create, copy, drop, rename, inside `PRAGMA
defer_foreign_keys`); D1 runs the whole file as one transaction. `members`, `expenses`, `settlements` and `links`
reference `groups(id)` with a plain `REFERENCES` (no `ON DELETE CASCADE` or `SET NULL`, which D1 runs even with
deferred foreign keys), so the implicit delete of `DROP TABLE groups` removes no child row; it only counts
foreign-key violations, and once `groups_new` is renamed every child has its parent again. The copy happens before
the drop, so that count is still above 0 at the end: **the last `PRAGMA defer_foreign_keys = off` is required**, it
discards the count before the commit. Without it the commit fails and the file rolls back (checked: see "Build notes,
family" 3). Columns, rows, the owner's foreign key and both partial indexes are kept exactly; `access_ended` and
`group_cache` name a group without a foreign key and are not touched. No other schema change: "one live family per
user" and "at most 2 active members" are enforced by the handlers and the 3.1 guard (`members` has no kind column an
index could use), and `changes.kind` / `op` already cover everything a family writes.

Order: 0003 alone changes nothing for the Worker that runs now (same columns, same rows). The Worker built with it
writes `kind = 'family'`, which the old CHECK refuses. So: take a D1 Time Travel bookmark, rehearse on a throwaway
remote database, apply 0003 `--remote`, then deploy (`npm run deploy` refuses while it is not applied); ship the app
build with Family at the same time (an older build reads `family` as a named group).

Notes:

- No balance columns. Balances are computed when needed (section 6) and cached by group version in `group_cache`
  (4.16); writes never maintain a running total, so there is nothing to drift.
- `changes` and entity rows are kept for the life of the group, so a phone can always pull from version 0. Deleting a
  group sets the tombstone (`deleted_at`) and revokes its links in one batch; every route then answers
  `410 group_deleted`. Its members, expenses, shares, settlements, changes and cache rows are purged afterwards by the
  daily cron in small chunks (4.13): deleting a big group at once could use more than the day's 100,000 written rows
  on its own (each deleted row and each index entry counts).
- A member is never hard-deleted while the group lives: removed members keep `removed_at` so old expenses still show
  their name.
- No foreign keys from the member columns of `expenses`, `expense_shares` and `settlements` to `members` (the SQL
  comment `members_no_fk`). With them, every member row the cron purges made SQLite look for children in those
  tables, whose member columns have no index: one purged member read every expense, share and settlement of every
  group (24,000 rows for a 3-member group next to a 2,000-expense group). Indexing those columns instead would add a
  written row to every share. The 3.1 guard checks that each member a write names exists in the group and is active.
- Indexes kept for rare writes only: `groups_owner` (deleting a user, `ON DELETE SET NULL`, and DELETE /v1/me's
  ownership step would otherwise read every group), `groups_to_purge` (the cron's lookup of tombstoned groups),
  `links_group` (group delete, account deletion and the purge revoke or delete links by group). A version bump does
  not touch them.
- The `join_*` columns (0002) are written by an invite accept, cleared by undo-join and by account deletion, and never
  returned by any route. No index: they are read with the member row. No `REFERENCES links(id)`: the cron deletes old
  links, and a foreign key would make each delete look for members by an unindexed column (as `members_no_fk`).
- `groups` after 0003 is the same table with a wider CHECK: `kind` is `group`, `direct` or `family`, and a `group` or
  a `family` has a name. A family's other rows are ordinary members, expenses, shares, changes, links and
  `access_ended` rows; it never has settlements and its balances are never computed (4.18).
- `access_ended` (0002) is the only thing the server keeps about a group a person is out of: the group id, why
  (`join_undone`, `removed`, `left`, `deleted`) and when, for 90 days. Without it the phone could not tell an undone
  join from a removal or a deletion (they look the same from outside the group), and forgot the person's splits for
  all three (9.4). Cost: one written row per leave, removal or undo-join, one per member on the app per group delete;
  one primary-key range read of the caller's own rows per `GET /v1/me/groups` (usually none), and one row read on a
  `not_member` answer. The cron's delete scans the table, which holds the last 90 days of losses only.

### 3.1 Writing a mutation

Every mutation is one `env.DB.batch([...])` (atomic, run in order; D1 counts a whole batch as one query against the
50-per-invocation limit):

1. `UPDATE groups SET version = version + 1, updated_at = ?now WHERE id = ?gid AND deleted_at IS NULL`
2. the entity statements, with `version = (SELECT version FROM groups WHERE id = ?gid)`. Updates and deletes also
   match `group_id = ?gid` (an expense upsert is `ON CONFLICT(id) DO UPDATE ... WHERE expenses.group_id =
   excluded.group_id AND expenses.deleted_at IS NULL`), so a path id from another group, or a tombstone, is never
   changed.
3. the **guard**, which is also the change row:
   `INSERT INTO changes (group_id, version, kind, op, entity_id, actor_member_id, created_at) VALUES (?gid,
   (SELECT version FROM groups WHERE id = ?gid AND deleted_at IS NULL), ?kind, ?op, (SELECT id FROM <entity table>
   WHERE id = ?eid AND group_id = ?gid AND version = (SELECT version FROM groups WHERE id = ?gid) AND <rule>), ...)`.
   `version` and `entity_id` are NOT NULL, so when the group is gone, the entity was not written at this version, or
   the rule no longer holds, the insert fails and the whole batch rolls back.
4. `INSERT INTO idempotency_keys (...)`

The `<rule>` per mutation:

- Expense or settlement create/update: `(SELECT COUNT(*) FROM members WHERE id IN (?ids) AND group_id = ?gid AND
  removed_at IS NULL) = <number of ?ids>`, where `?ids` are the distinct payer, share and from/to member ids that the
  stored row did not already have (at most 51 parameters). A member removed between the handler's checks and the batch
  cannot gain a balance, and an id that is not a member row of the group at all fails too (the stand-in for the
  foreign keys those columns do not have, 3).
- Leave, remove member, delete group (the balance rules): `(SELECT version FROM groups WHERE id = ?gid) = ?checked + 1`,
  where `?checked` is the version the zero-balance check used. Any write in between fails the batch; the handler
  checks again once, then answers `409 balances_not_settled` or applies it. Delete group writes its guard before the
  statement that sets `deleted_at`.
- Undo a join by link (4.6): `user_id IS NULL AND removed_at IS NULL AND (SELECT owner_user_id FROM groups WHERE id =
  ?gid) = ?caller`. The member update itself matches only the join the handler checked (same user, same link, still
  active, joined less than 7 days ago). So a leave, an account deletion, a second undo or an ownership change in
  between fails the batch; the handler then runs its checks again to pick the answer.
- Import (4.17), on the first item's guard only (`importRule`): the expense/settlement member rule above for every
  member the items name, plus `(SELECT owner_user_id FROM groups WHERE id = ?gid) = ?caller AND NOT EXISTS (an active
  member of ?gid with a user_id other than ?caller)`. D1 runs one batch at a time, so one check covers the batch.

- Family (4.18). Create: the change row's `entity_id` is `(SELECT ?gid WHERE NOT EXISTS (an active member row of
  ?caller in a live family other than ?gid))`, so a second family made at the same moment on another phone fails the
  batch. Accept a family link: `user_id = ?caller AND` the same `NOT EXISTS`. Add a member to a family:
  `(SELECT COUNT(*) FROM members WHERE group_id = ?gid AND removed_at IS NULL) <= 2` (a group: `<= 50`). Leave a
  family: `removed_at IS NOT NULL` (no balance, so no version rule). Remove from a family: `removed_at IS NOT NULL AND
  user_id = ?seen` (the person the checks saw) or `AND user_id IS NULL` (the placeholder); a place taken through the
  link in between fails it and the checks run once more.

An import is the one batch with several mutations: steps 1 to 3 repeated for each of its up to 50 items (each its own
version and change row), and step 4 once at the end.

D1 runs one batch at a time per database, so versions are gap-free and strictly increasing per group. A failed guard
shows as a `NOT NULL constraint failed: changes.version` or `changes.entity_id` error; the handler reads once to pick
the answer (`410 group_deleted`, `410 expense_deleted`, `404 not_found` for an id that lives in another group,
`400 bad_request` for a member who is no longer active). The handler's own checks before the batch only choose the
error message; the guard is what keeps the data right. If step 4 hits its primary key (two copies of the same request
at once), the batch rolls back and the handler answers as a replay (section 4.3).

Creating a group is the one exception to step 1: the batch inserts the `groups` row with `version = 1`, its members
with `version = 1` and the change `(gid, 1, 'group', 'create', ...)`. An existing id fails the batch on the primary
key, and the handler then answers as in 4.6 (replay or `409 exists_different`).

D1 free-plan limits to respect: at most 50 queries per Worker invocation (a batch counts as one), at most 100 bound
parameters per statement, at most 500 MB per database (section 8). Insert shares and members with multi-row
`INSERT ... VALUES (...), (...)` in chunks of at most 25 rows of 4 values (members: chunks of 10). With the 50-member
cap a mutation stays under 15 statements; an import of 50 items is about 200 (D1 has no documented statement cap per
batch; if one appears, lower the 50-item part on the phone and the server together). Never loop over a user's groups issuing one batch each (a user may be in
300): changes that touch every group of a user are set-based statements in one batch (4.5).

## 4. API

Base URL: `API_BASE_URL` (debug app: `http://10.0.2.2:8787`; production: the Worker's address). All routes are under
`/v1`. JSON only, UTF-8, camelCase. Request bodies over 32 KB get `413 too_large`.

### 4.1 Common rules

- **Types.** Ids: lowercase UUID strings (`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`).
  Money: `...Paise` integers. Times: `...At` epoch millis. Dates: `...On` `YYYY-MM-DD` in India time (UTC+05:30).
- **Headers on authenticated routes.** `Authorization: Bearer <accessToken>`, `X-Installation-Id: <uuid>` (that
  install's id: used to skip pushing to the phone that made a change).
- **Idempotency.** Every `POST`, `PUT`, `PATCH` and `DELETE` except `/v1/auth/*`, `/v1/devices/*` and the link
  `PUT` (both idempotent by their own ids) must carry `Idempotency-Key: <uuid>`; missing gives `400 bad_request`
  (field `Idempotency-Key`). See 4.3.
- **Responses** carry `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`. No CORS headers except on
  `GET /v1/links/{token}` (4.10).
- **Errors** are `{"error": {"code": "...", "message": "...", "field": "...", "reason": "..."}}`. `message` is fixed
  English text for developers, never containing user data. `field` only for validation errors (a JSON path like
  `shares[2].input`). `reason` only on `not_member`, when the caller was in the group in the last 90 days and is not
  now: `join_undone`, `removed`, `left` or `deleted` (0002 `access_ended`, 4.5 `lost`).

| Status | `code` | When | Phone does |
|---|---|---|---|
| 400 | `bad_request` | validation failed | drop the outbox entry, roll the entity back (5.1), pull the group |
| 400 | `shares_mismatch` | shares do not match the method's computation (6.1) | same as above |
| 401 | `unauthorized` / `token_expired` | missing or bad / expired access token | refresh once, retry |
| 401 | `refresh_invalid` | refresh token unknown, expired or revoked | needs sign-in; local group data and outbox kept (9.2) |
| 401 | `google_token_invalid`, `nonce_invalid` | sign-in rejected | "Could not sign in" |
| 403 | `not_member` | caller is not an active member of the group; `reason` says why when the server knows | remove the group locally (5.2); forget its splits only for `reason: join_undone` (9.4) |
| 403 | `not_owner`, `not_allowed` | role rule failed (4.2) | drop entry, roll back, pull |
| 404 | `not_found`, `link_not_found` | unknown id, or link revoked or expired | drop entry, roll back / show "link no longer works" |
| 409 | `already_member`, `already_claimed`, `member_on_app`, `balances_not_settled`, `limit_reached`, `exists_different`, `not_joined_by_link`, `undo_expired` | state rule failed | drop entry, roll back, pull |
| 409 | `already_in_family` | create a family or accept a family link while active in another live family (4.18) | never queued (both are online-only): show "You're already in a family"; after a create, sync and open the family |
| 410 | `group_deleted`, `expense_deleted` | target is a tombstone | drop entry, apply the tombstone |
| 413 | `too_large` | body over 32 KB | drop entry, roll back |
| 422 | `idempotency_key_reused` | same key, different request | bug: drop entry, roll back, report locally |
| 429 | `rate_limited` (JSON) | over a limit; `Retry-After` seconds given | wait `Retry-After`, then go on (not exponential) |
| 500 / 503 / non-JSON (any status) | `internal` or Cloudflare's own page (daily free limit reached, which can be a 429 without our JSON) | | keep the entry, exponential back-off |

"Roll back" means: put the entity back as it was before the user's change and undo the linked personal-row change
(5.1, 9.4), and tell the user that the change could not be saved (copy not designed, 13).

### 4.2 Authorization

An **active member** of group G is a `members` row in G with `user_id = caller` and `removed_at IS NULL`, and G not
deleted. Every `/v1/groups/{gid}/...` route first runs one query that returns the caller's member id in G (or none);
none gives `403 not_member`, with the `reason` the caller lost G when `access_ended` has one (one more read, on that
answer only; 4.1). A deleted group gives `410 group_deleted`. Path ids that are not in G give `404 not_found`.

| Route | Who may call |
|---|---|
| Group rename, add member, edit or remove a friend without the app, expense create/update/delete, share link create/revoke, reminders | any active member |
| Remove a member who is on the app | the group owner |
| Undo a join by link (4.6) | the group owner, within 7 days of the join, never on their own place |
| Delete group | the owner, when every net is 0 or nobody else in the group is on the app (4.6) |
| Import from Splitwise (4.17) | the group owner (kind `group`), while nobody else in the group is on the app; settlements between any two members |
| Leave group | any active member whose net is 0 |
| Record a settlement | an active member who is `from` or `to` of it |
| Confirm a settlement | the party that did not record it, if on the app (4.8) |
| Delete a settlement | its recorder, `from` or `to` (an imported payment between two friends: the importing owner, its recorder, 4.17) |
| `GET /v1/invites/{token}`, accept | any signed-in user holding the token |
| `GET /v1/links/{token}` | anyone holding the token (no sign-in) |

Families (4.18):

| Family route | Who may call |
|---|---|
| Create | any signed-in user not active in another live family |
| Rename | any active member |
| Add the placeholder, a link for the placeholder, cancel the invite (remove the placeholder) | any active member (the family not full) |
| Remove the partner on the app | the owner |
| Leave | any active member (no balance rule) |
| Expense create, update, delete | the payer, own member only |
| Delete, undo-join, settlements, reminders, balances, edit a member, import | nobody (`403 not_allowed`) |
| `GET /v1/invites/{token}`, accept | a signed-in holder of a live token who is not active in another live family |
| `GET /v1/links/{token}` | anyone with the token; the family shape only (4.18) |

The owner is the person who made the group, until they leave or delete their account (ownership then passes, 4.5,
4.6). Decided 2026-11-17 (docs/product.md "Forwarded links"): the owner is shown on the group's Members list.
`Group.ownerMemberId` is the owner's member row (4.6). The phone must not offer owner-only actions to others (13.13).
"Known user" checks (4.6) and every membership check ignore deleted groups.

`direct` groups: no rename, no adding or removing members, no delete (they disappear for a user only by leaving at
net 0), no owner rules, except undo-join: a direct split with a friend without the app can be joined through the
friend's link, and the person who made it can undo that join.

### 4.3 Idempotency

For each mutating request: compute `request_hash`; `SELECT` from `idempotency_keys` by `(user_id, key)`.

- Found, same hash: **replay**. Answer with the stored `status` and the entity's current representation (re-read by
  `entity_id`), plus header `Idempotent-Replay: true`. Nothing is written. A replay that answers with content (group
  create, rename, member add, edit and undo-join, expense `PUT`, settlement `PUT` and confirm) first runs the 4.2 check against
  the path's group (for `POST /v1/groups`, the group the key created): a caller who left or was removed since gets
  `403 not_member`, a deleted group `410 group_deleted`, an entity not in that group `404 not_found`. Otherwise an old
  key and body would read the group as it is now (other members' phones and UPI IDs) for the 7 days the key is kept.
  Replays without content (the `204`s of leave, delete, remove and link revokes, `{ "version" }` of expense and
  settlement deletes, reminders, invite accept, `PATCH /v1/me`, the versions of an import, 4.17) answer as before, so a
  retried leave or delete still gets its answer.
- Found, different hash: `422 idempotency_key_reused`.
- Not found: run the mutation batch with the key insert as its last statement.

Independently of the key, every create is idempotent by its client-made id: creating an id that exists with the same
content answers `200` with the current row; with different content it answers `409 exists_different` (expenses
are the exception: `PUT` on an existing expense is an update). Deletes of something already deleted, a confirm of
a confirmed settlement and an accept of an invite already accepted by the caller answer `200`. Keys older than 7 days
are purged by the daily cron (4.13), so this second layer is what protects very old retries. The one write it cannot
protect is an expense update retried after its key was purged (a phone offline for over 7 days after a lost answer):
it is applied again by last-writer-wins, which can undo a newer edit by someone else. Accepted; the answer carries
`"overwrote": true` so the phone can say so.

### 4.4 Auth

Tokens:

- **Access token**: JWT HS256 signed with secret `SESSION_SECRET`; claims `iss: "xpense-api"`, `aud: "xpense-app"`,
  `sub: <userId>`, `sid: <sessionId>`, `iat`, `exp = iat + 86400`. On every authenticated request the Worker checks
  signature, `iss`, `aud`, `exp`, and reads the session row by `sid` (1 row): revoked or expired gives
  `401 unauthorized`. This makes sign-out and account deletion immediate whatever the token's lifetime, which is
  why it can live 24 hours: a 30-minute token meant about 4 refreshes per daily user, each writing the session row
  and its indexes (section 8).
- **Refresh token**: 32 random bytes, base64url. Only `SHA-256` hex is stored. Rotated on every refresh: the old
  hash moves to `prev_refresh_hash` and `rotated_at` is set. The phone refreshes one at a time (a `Mutex`), so the
  previous token comes back only when the answer to a refresh was lost on the way (common on mobile networks):
  - within 10 minutes of `rotated_at`: rotate again from it (a new token replaces `refresh_hash`;
    `prev_refresh_hash` and `rotated_at` stay as they are) and answer `200`;
  - later: treated as theft, the session is revoked, `401 refresh_invalid`.
  An unknown token answers `401 refresh_invalid` and revokes nothing.

Routes (no `Idempotency-Key`, no bearer unless said):

`POST /v1/auth/nonce` → `200 {"nonce": "<string>", "expiresAt": 0}`. Stateless: `base64url(issuedAtMillis ||
16 random bytes || HMAC-SHA256(SESSION_SECRET, "nonce" || issuedAt || random))`, valid 5 minutes.

`POST /v1/auth/google`

```json
{ "idToken": "<Google ID token>", "nonce": "<from /auth/nonce>", "installationId": "<uuid>" }
```

Verify with `jose`: `createRemoteJWKSet(new URL("https://www.googleapis.com/oauth2/v3/certs"))` (URL fixed in code,
never config), `jwtVerify(idToken, jwks, { issuer: ["accounts.google.com", "https://accounts.google.com"],
audience: env.GOOGLE_WEB_CLIENT_ID, algorithms: ["RS256"], clockTolerance: 60 })`. Then: the token's `nonce` claim
equals the body's `nonce`, and the nonce's HMAC and age are valid; if `env.GOOGLE_ANDROID_CLIENT_IDS` (comma list) is
non-empty, `azp` must be in it. Any failure: `401 google_token_invalid` (`nonce_invalid` for the nonce). Find the
user by `google_sub = sub`, or create one with `name` = the token's `name`, else `given_name`, else `"Friend"`
(trimmed to 60). Email and picture are not read or stored. Create a session with `nonce_hash` = SHA-256 of the
nonce: the nonce is stateless, so this unique column is what makes it single-use (a replayed ID token and nonce
fail the insert and get `401 nonce_invalid`).

Response (also for `/auth/dev`):

```json
{
  "user": { "id": "…", "name": "Arjun Mehta", "phone": null, "upiId": null },
  "isNew": true,
  "accessToken": "…", "accessExpiresAt": 0,
  "refreshToken": "…", "refreshExpiresAt": 0
}
```

`isNew` true shows "Your name (after sign-in)", prefilled with `user.name`.

`POST /v1/auth/dev` (development only)

```json
{ "devUser": "alice", "name": "Alice", "installationId": "<uuid>" }
```

`devUser` matches `^[a-z0-9]{1,20}$`; finds or creates the user with `dev_key = devUser`. The route exists only when
**both** hold, otherwise the router answers `404 not_found` as for any unknown path:

1. `env.ENVIRONMENT === "development"`, and
2. the request URL's hostname is `localhost`, `127.0.0.1`, `[::1]` or `10.0.2.2` (a deployed Worker is reached by
   its real host name, so this cannot be true in production even with a wrong var).

`wrangler.toml` sets `ENVIRONMENT = "production"` at the top level and has no `[env.*]` section that changes it.
Local runs set it on the command line (`npm run dev` = `wrangler dev --ip 127.0.0.1 --port 8787 --var
ENVIRONMENT:development`; `--ip 127.0.0.1` because the emulator's `10.0.2.2` reaches the Mac's IPv4 loopback only,
and never `0.0.0.0`, which would open the dev server to the local network). Local dev never uses `--remote` (that
would run against the real D1 database). `npm run deploy` first runs `scripts/check-prod-config.mjs`, which fails if
`wrangler.toml` contains `development` anywhere, has an `[env.*]` section, lacks `ENVIRONMENT = "production"` or
`invocation_logs = false` (10), or still holds a placeholder (`REPLACE_ME`, the all-zero `database_id`, `xpense.app`
in `LINK_HOST` / `WEB_ORIGIN`). The rules live in `scripts/prod-config-rules.mjs` (no Node APIs) so the Worker tests
can run them; a unit test asserts the same and that `/v1/auth/dev` answers 404 with `ENVIRONMENT = "production"`
(or any other value) and with a non-local host. The host check is the guard that holds even when someone runs
`wrangler deploy --var ENVIRONMENT:development` by hand.

`POST /v1/auth/refresh` `{ "refreshToken": "…" }` → `200 { "accessToken", "accessExpiresAt", "refreshToken",
"refreshExpiresAt" }`. The phone stores the new pair before it releases the refresh `Mutex`.

`POST /v1/auth/signout` (bearer) → `204`. Revokes the session; deletes the `devices` row of `X-Installation-Id`.

### 4.5 Me and devices

`GET /v1/me` → `200 User` where `User = { "id", "name", "phone": string|null, "upiId": string|null }`.

`PATCH /v1/me` `{ "name"?: string, "phone"?: string|null, "upiId"?: string|null }` → `200 User`. If name, phone or
UPI ID changes, every non-deleted group the user is an active member of gets a `member` / `update` change for the
user's member row (so other phones see it). This is **one batch of set-based statements**, never one batch per group
(a user may be in 300 groups and an invocation may run 50 queries): `UPDATE users`; `UPDATE groups SET version =
version + 1 WHERE deleted_at IS NULL AND id IN (SELECT group_id FROM members WHERE user_id = ?me AND removed_at IS
NULL)`; `UPDATE members SET version = (SELECT version FROM groups g WHERE g.id = members.group_id) WHERE user_id = ?me
AND removed_at IS NULL`; `INSERT INTO changes SELECT ... FROM members JOIN groups ...`. No room notify and no push for
profile changes (one per group would pass the 50-subrequest limit); other phones see it on their next sync.

**UPI ID rule.** The app copies it exactly, on "Your name (after sign-in)" and You > Account (docs/product.md "UPI ID"):

1. Drop spaces at both ends (Kotlin `trim()`; the server uses JavaScript `trim()`).
2. Empty after that: the person has no UPI ID. The app sends `"upiId": null`, never `""` (the server answers `""`
   with `400`).
3. Otherwise it must match `^[A-Za-z0-9._-]{2,64}@[A-Za-z][A-Za-z0-9]{1,63}$`: 2 to 64 letters, digits, dots,
   underscores or hyphens; one `@`; a letter, then 1 to 63 letters or digits. For example `name@okaxis`,
   `98xxxxxx10@ybl`. No spaces inside, no second `@`, no `+`.
4. Case is kept as typed. The server stores the trimmed value and returns it in `User.upiId`.

A value that breaks the rule gets `400 bad_request` with field `upiId`. The web page (7) checks the same pattern
before it shows a Pay button.

`DELETE /v1/me` → `204`. One batch of set-based statements, in this order:

1. Bump the version of every non-deleted group with a member row of the caller: active ones, and those the caller
   left or was removed from (their old row changes in step 5 too, and a phone already at the group's version would
   otherwise never pull it).
   1b. The caller's family expenses leave their families (4.18): every live expense of a live family the caller has a
   member row in, paid by one of the caller's member rows, is tombstoned at the version step 1 gave that family, and
   its shares are deleted. Set-based, by index (`members_user`, `expenses_group_version`):

   ```sql
   UPDATE expenses SET deleted_at = ?now, updated_at = ?now,
     version = (SELECT g.version FROM groups g WHERE g.id = expenses.group_id)
   WHERE deleted_at IS NULL
     AND group_id IN (SELECT m.group_id FROM members m JOIN groups g ON g.id = m.group_id
                      WHERE m.user_id = ?me AND g.kind = 'family' AND g.deleted_at IS NULL)
     AND paid_by_member_id IN (SELECT m.id FROM members m WHERE m.user_id = ?me);
   DELETE FROM expense_shares WHERE expense_id IN (
     SELECT e.id FROM expenses e WHERE e.deleted_at = ?now
       AND e.group_id IN (SELECT m.group_id FROM members m JOIN groups g ON g.id = m.group_id
                          WHERE m.user_id = ?me AND g.kind = 'family'));
   ```

   The rest is unchanged: a family with the partner on the app lives on with a "Former member" (a place not on the
   app, so it holds the second place: the partner can make a link for it, or remove it and add a new placeholder); a
   family with nobody else on the app is tombstoned by step 2.
2. Groups where the caller is the only member on the app: tombstoned (as `DELETE /groups/{gid}`, ignoring balances;
   rows purged by the cron).
3. Revoke every link of the caller's member rows and of those groups.
4. Groups the caller owns that live on: ownership passes to the active member on the app with the earliest
   `joined_at`.
5. A `member` / `update` change (or `group` / `delete`) per group (in a group the caller is no longer active in, for
   one of the caller's old rows), then every member row of the caller, removed ones included, becomes a friend without
   the app named `"Former member"` (`user_id` NULL, `phone` NULL, the `join_*` columns of 0002 NULL, so a join by link
   can no longer be undone) at the new version. Balances and history stay right for everyone else.
6. Delete the `users` row; sessions, devices and the user's `access_ended` rows go with it (`ON DELETE CASCADE`).
   `google_sub` is gone, so signing in again makes a new user.

No room notify or push (as for `PATCH /v1/me`).

`GET /v1/me/groups` →

```json
{ "groups": [ { "id", "kind": "group|direct|family", "name": "Flat 302", "version": 42, "myMemberId": "…",
                "ownerUserId": "…" } ],
  "lost": [ { "id": "<group id>", "reason": "join_undone|removed|left|deleted", "at": 0 } ],
  "serverTime": 0 }
```

`groups`: active memberships of non-deleted groups only, unpaginated (capped at 300 groups per user).

`lost`: the groups the user lost in the last 90 days and is not active in again (a person added back, a direct split
made again or a place taken again by a new link is in `groups` instead), with why and when (`access_ended`, 3).
Written in the batch that ends the access: `left` by leave (also when it tombstones the group), `removed` by a
removal of a member on the app, `join_undone` by undo-join, `deleted` by group delete (for every member on the app,
the owner included). The latest reason wins. A group the phone has that is in neither list (a loss over 90 days ago,
or before this was built) has no known reason. The phone uses it to tell an undone join from the rest (9.4); a group
lost to account deletion has no other person on the app, so nobody needs a reason for it.

`PUT /v1/devices/{installationId}` `{ "fcmToken": "…" }` → `204`. Upsert (the row moves to the caller: a phone
shared by two accounts in turn keeps one installation id); a token already stored under another installation moves
to this one. At most 3 devices per user: the least recently updated is dropped.

`DELETE /v1/devices/{installationId}` → `204`.

### 4.6 Groups and members

Shapes returned everywhere (pulls too):

```json
Group  = { "id", "kind": "group|direct|family", "name": "Flat 302"|null, "ownerUserId"|null, "ownerMemberId"|null,
           "version", "createdAt" }
Member = { "id", "groupId", "userId"|null, "name", "phone"|null, "upiId"|null, "onApp": true,
           "removed": false, "joinedAt": 0|null, "undoUntil": 0|null, "canUndo": false, "version" }
```

`Member.name` is the user's name when `userId` is set, else `members.name`. `phone` is the user's phone or the
friend's. `upiId` only for users. A user who left or was removed (`removed: true`) shows `phone` and `upiId` as null:
they are seen only by people in the user's groups. Never returned: emails, Google ids, sessions, devices, and the
friend's name and phone saved for an undo (the `join_*` columns, 3).

`Group.ownerMemberId` is the owner's active member row: the Members list labels it as the owner (docs/product.md
"Forwarded links"). It is null when nobody on the app owns the group. The group row is in every pull page (4.9), so the
label follows ownership when it passes (4.5, leave).

`joinedAt`, `undoUntil` and `canUndo` serve the owner's "{name} joined · Not {name}? Undo" (undo-join below). They are
set only for a person who took a friend's place through the friend's link (invite accept, 4.10), is still in it, and
joined less than 7 days before the answer: `joinedAt` is when (epoch ms), `undoUntil = joinedAt + 604,800,000` (7
days), and `canUndo` is true when the caller is the group's owner and not that person. For everyone else they are
`null`, `null`, `false`. Every member gets `joinedAt` and `undoUntil` (ownership can pass to them while the window is
open); only `canUndo` depends on the caller. A pulled row is not sent again when time passes or when the owner
changes, so `canUndo` is right only at the moment of the answer. The phone decides from its own data: show the banner
for a member who is on the app and not removed, whose `undoUntil` is set and later than now, in a group whose
`ownerUserId` is the phone's user.

Text validation everywhere (`400 bad_request` with `field`): trimmed; no control characters and no bidirectional
override characters (U+202A–U+202E, U+2066–U+2069, which can make a name read as something else on the public page);
group name 1–40 characters; member and user names 1–60; phone E.164 `^\+[1-9][0-9]{7,14}$`; UPI ID
`^[A-Za-z0-9._-]{2,64}@[A-Za-z][A-Za-z0-9]{1,63}$` after dropping spaces at both ends (the full rule is in 4.5).

`POST /v1/groups` → `201 { "group": Group, "members": [Member] }`

```json
{ "id": "<uuid>", "kind": "group", "name": "Goa trip", "myMemberId": "<uuid>",
  "members": [ { "id": "<uuid>", "userId": "<user id>" },
               { "id": "<uuid>", "name": "Priya Nair", "phone": "+919000000001" } ] }
```

Group and member ids made by the phone must be random UUIDs, version 4 (`xxxxxxxx-xxxx-4xxx-[89ab]xxx-…`), here and in
`POST .../members`, except the deterministic v5 ids of a direct split with an app user below, which the server
recomputes (`400 bad_request`, field `id`, `myMemberId` or `members[i].id`). Every co-member sees user ids, so without
this anyone could take a pair's direct-split id (or a member id in it) first, and the pair could never split directly.

The caller becomes owner and a member. A `{ userId }` entry is allowed only for a **known user**: someone who is an
active member of at least one group the caller is an active member of (else `403 not_allowed`). There is no lookup
of users by phone number or email (phones are not verified; a lookup would let anyone claim to be anyone and would
leak who uses the app). Added users get a `{"t":"groups"}` push. One mutation: change `group` / `create`.

**Direct splits** (Choose people > a single friend) use `kind: "direct"` with exactly one entry in `members` and no
`name`. With an app user the ids are deterministic so both phones make the same group offline:
`groupId = uuidv5(NS, "direct:" + min(uidA, uidB) + ":" + max(uidA, uidB))`, each member id
`uuidv5(NS, "member:" + groupId + ":" + userId)`, with `NS = 6f1c2a4e-3b8d-4c1e-9a57-2d0e8b6f4a13` (RFC 4122 v5,
SHA-1). The server checks these values (`400 bad_request` if they differ). Creating one that already exists with the
caller as one of its two users answers `200` with the current state, after making each removed member of the two
active again in the same batch (`removed_at` NULL, change `member` / `join`). A direct group is therefore never tombstoned by
leaving (4.6 leave), only by account deletion: otherwise a pair who both left their direct split could never split
again, because the deterministic id would stay taken by the tombstone. With a friend without the app, ids are random.
`GroupsRepository.directGroupWith(person)` first looks for an existing direct group with that user or phone (either
id style, for a friend who joined the app later), and only then makes the deterministic one.

Kind `family` (4.18) takes this route with exactly one member, the placeholder of the invite, and its own rules for
every route below; they are all in 4.18.

`PATCH /v1/groups/{gid}` `{ "name": "…" }` → `200 Group`. Kinds `group` and `family` (any active member); a `direct`
split answers `403 not_allowed`.

`DELETE /v1/groups/{gid}` → `204`. Owner only; `409 balances_not_settled` unless every net is 0 (checked against
the cached balances, guarded as in 3.1) **or nobody else in the group is on the app** (no active member with a
`userId` other than the caller; decided 2026-11-18 with Import from Splitwise, docs/splitwise-import.md 3.3). The
members are read inside the check; the guard stays the version rule, and an invite accept bumps the version, so a
friend who joins in between fails the batch and the second check answers `409`. Why: an import of the wrong file, or
with the wrong "which one is you", could otherwise never be removed (nobody else can settle it), and the phone's
clean-up of a refused import (4.17) needs it. Friends without the app see the group only through links, which the
delete revokes. This applies to every group with only friends without the app, imported or not. Tombstones the group, revokes its links, writes `access_ended` `deleted` for
every member on the app (4.5 `lost`), tells the room (`{"type":"revoked"}`) and pushes `{"t":"groups"}` to the
members on the app. Its rows are purged by the cron (4.13).

`POST /v1/groups/{gid}/leave` → `204`. `409 balances_not_settled` unless the caller's net is 0. Sets `removed_at`;
change `member` / `leave`; `access_ended` `left` for the caller. If the caller was owner, ownership passes as in
`DELETE /v1/me`. If no member on the app is left, a `group` is tombstoned in the same batch; a `direct` group stays
(both members removed) so its deterministic id can be used again.

Leave, remove member and delete group are online-only on the phone (not queued in the outbox): their rule depends on
balances at the moment the server applies them, so the person must see the answer. Offline, the phone says to check
the connection. One exception: the clean-up of a refused import (4.17, docs/splitwise-import.md 5.4) queues its
`DELETE /v1/groups/{gid}` in the outbox, because nobody is waiting on a screen for it. The server answers it like any
delete: `204` while nobody else is on the app, or `409 balances_not_settled` when a friend joined in between, after
which the phone drops the entry and the group comes back on the next pull.

`POST /v1/groups/{gid}/members` → `201 Member`. Body `{ "id", "name", "phone"? }` (friend without the app) or
`{ "id", "userId" }` (known user, as above). `409 already_member` if that user is already active. `409
limit_reached` at 50 active members. Change `member` / `create`.

`PATCH /v1/groups/{gid}/members/{mid}` `{ "name"?, "phone"?: string|null }` → `200 Member`. Friends without the app
only (`409 member_on_app`).

`DELETE /v1/groups/{gid}/members/{mid}` → `204`. Not yourself (use leave). The member's net must be 0
(`409 balances_not_settled`, guarded as in 3.1). Removing a member on the app needs the owner. Revokes the member's
links; change `member` / `remove`; `access_ended` `removed` for that user (a member on the app); the room closes that
user's sockets; the user gets `{"t":"groups"}`. Someone can take a friend's place through a link between the checks
and the batch, so the place is read again on each of the two attempts, the guard also names who held it when the
checks ran (that user, or nobody for a friend without the app), and `access_ended` is written inside the batch from
the member row as it is then. A place that changed fails the guard and every check runs again: a member who is not
the owner gets `403 not_owner` for a person who has just joined, and a removal by the owner tells that person why.

**Undo a join by link** (decided 2026-11-17, docs/product.md "Forwarded links"):
`POST /v1/groups/{gid}/members/{mid}/undo-join`, no body, `Idempotency-Key` as for every write → `200 Member` (the
place as restored: the friend without the app). Online-only on the phone, like remove (the person waits for the
answer). The checks, in the order their answers are given:

| Check | Answer |
|---|---|
| caller is an active member of a live group | `403 not_member`, `410 group_deleted` |
| caller is the group's owner (`ownerUserId`) | `403 not_owner` |
| `mid` is a member of `gid` | `404 not_found` |
| `mid` is not the caller's own place (use leave) | `403 not_allowed` |
| the place is held by someone who took it through a friend's link and is still in it | `409 not_joined_by_link` |
| that join is less than 7 days old (`now < undoUntil`) | `409 undo_expired` |

`not_joined_by_link` covers a member added directly, a friend without the app, a person who left or was removed, a
deleted account ("Former member"), a join already undone, and a join accepted before migration 0002 was applied. Both
kinds of group: in a direct split the owner is the person who made it.

One batch (3.1, its rule above):

- The member row becomes the friend without the app it was before the join: `user_id` NULL, `name` and `phone` as
  they were (saved at accept), `joined_at` and the `join_*` columns cleared, at the new version. It keeps its id, so
  its expenses, shares and settlements stay as they are, and so do balances. What the person added while in the group
  stays and now shows under the friend's name; members can delete it as usual.
- Every link for that place, and every link made from it (by the person, while they held it), is revoked. The link
  used was already revoked by the accept; the person must not keep any token that lets them back in.
- Change `member` / `update` (`changes.op` is fixed by 0001's CHECK, so there is no `undo` op). A phone that wants to
  tell an undo apart sees the same member id go from `onApp: true` to `onApp: false`.
- `access_ended` `join_undone` for the person: from outside the group they cannot see the change row, and their phone
  must not take the undo for a removal or a deletion (4.5 `lost`, the `not_member` `reason`; 9.4).
- After it, the room closes the person's sockets (`revoked`, code 4403) and their phones get `{"t":"groups"}`, so the
  group leaves their list (5.2). Everyone else gets the usual `changed` push, NORMAL priority: nobody is notified
  (copy not designed, 13.19).

A replay with the same `Idempotency-Key` answers `200 Member` again (after the 4.3 check). A repeat with a new key
answers `409 not_joined_by_link`. The phone then makes a new link for the place when the owner sends it to the right
person (4.10).

### 4.7 Expenses

In a family, an expense is the payer's own payment, not split, and only the payer writes it (4.18).

```json
ExpenseInput = {
  "description": "Swiggy", "category": "FOOD", "amountPaise": 45000, "occurredOn": "2026-11-16",
  "paidByMemberId": "…", "splitMethod": "EQUAL", "auto": false,
  "shares": [ { "memberId": "…", "sharePaise": 15000, "input": null } ],
  "baseVersion": null
}
Expense = ExpenseInput without baseVersion, plus { "id", "groupId", "currency": "INR", "createdByMemberId",
  "updatedByMemberId", "createdAt", "updatedAt", "deleted": false, "version" }
Deleted expense (in pulls) = { "id", "groupId", "deleted": true, "version" }
```

`PUT /v1/groups/{gid}/expenses/{eid}` body `ExpenseInput` → `201 Expense` (created) or `200 Expense` (updated).
Update replaces every field and all shares. **Last writer wins**: an update is applied even when `baseVersion` is
older than the stored version; the answer then has `"overwrote": true` (the phone may tell the user; nothing more).
An update to a deleted expense gives `410 expense_deleted`. Changes `expense` / `create` or `update`.

`DELETE /v1/groups/{gid}/expenses/{eid}` → `200 { "version" }`. Sets `deleted_at`, deletes its shares. Already
deleted: `200`. Change `expense` / `delete`.

Validation (`400 bad_request` with `field`, unless said):

- `description`: trimmed, 1–80 characters, no control or bidirectional override characters. The phone pre-fills it
  from the SMS counterparty, except when that counterparty is a UPI ID or holds 6 or more digits in a row (a phone or
  account number, common on person-to-person UPI SMS): then it pre-fills the category name. The person can edit it
  before Split.
- `category`: one of the seven names (the app's `Category` enum names).
- `amountPaise`: integer 1 to 1,000,000,000 (₹1 crore).
- `occurredOn`: a real date from 2000-01-01 to tomorrow in India time.
- `paidByMemberId`: an active member of the group, or, on update, the unchanged payer even if removed since.
- `shares`: 1–50 entries, unique `memberId`s, each an active member (or unchanged on update), `sharePaise` integer
  ≥ 0, sum equal to `amountPaise`. `input` by method: `EQUAL` null; `AMOUNTS` equal to `sharePaise`; `PERCENT`
  integer basis points 1–10,000 summing to 10,000; `SHARES` integer weight 1–1,000. The server recomputes the shares
  with 6.1 from the inputs and gives `400 shares_mismatch` if any differs.
- Members newly referenced by the payer or the shares must still be active when the batch runs (the 3.1 guard).
- In a `direct` group, payer and shares only use its two members.
- At most 20,000 non-deleted expenses per group (`409 limit_reached`).

Personal notes, account, card, "paid with", SMS fields and the exact time never go into an expense.

### 4.8 Settlements

Not in a family: every settlement route answers `403 not_allowed` there (4.18).

```json
SettlementInput = { "fromMemberId", "toMemberId", "amountPaise", "method": "UPI|CASH|OTHER",
                    "matchedFromSms": false, "occurredOn": "2026-11-16" }
Settlement = SettlementInput plus { "id", "groupId", "recordedByMemberId", "confirmedAt"|null, "createdAt",
                                     "deleted": false, "version" }
```

`PUT /v1/groups/{gid}/settlements/{sid}` → `201 Settlement`. Create only: same content again gives `200`, different
content `409 exists_different`. Caller must be `from` or `to`. `confirmedAt` is set at once when the other party is
not on the app, or when the receiver records it from an SMS match (`matchedFromSms`, 03/02 "Yes, clear it" and
03/03 "Nothing to tick"). Otherwise the other party can confirm, whoever recorded it: both 03/00 ("Paid another way:
Mark it as paid. Rahul can confirm.", payer side) and 03/01 ("Rahul can confirm. They get a note that you marked it
as paid.", receiver side) say so. Amount rules as expenses; it may be more or less than what is owed (partial
payments are fine). Change `settlement` / `create`. One exception to "same content gives `200`": when the existing
settlement is not confirmed and the same content comes from its receiver (`to`) with `matchedFromSms`, it is confirmed
(`200`, change `settlement` / `confirm`). Both phones record a payment matched from SMS under one id (9.5); when the
payer's record reached the server first, the receiver's own match confirms it, as it would had it come first.
"Same content" for two records matched from SMS (the stored one and the new one both `matchedFromSms`) ignores
`method` when the caller is `from` or `to`: the payer's debit after Pay by UPI says `UPI`, the receiver's credit SMS
often names no channel (`OTHER`), and under one SMS id with the same people, amount and day they are one payment. The
answer is the stored settlement, its method unchanged (confirmed first when it is the receiver's record, as above).
Any other difference (amount, day, people, a record typed by hand) is still `409 exists_different`.

`POST /v1/groups/{gid}/settlements/{sid}/confirm` → `200 Settlement`. The non-recording party only, on the app;
already confirmed: `200`. Change `settlement` / `confirm`.

`DELETE /v1/groups/{gid}/settlements/{sid}` → `200 { "version" }`. Its recorder, `from` or `to`; anyone else `403
not_allowed`. Through this route the recorder is always `from` or `to`; an import (4.17) records payments between
any two members with the importing owner as recorder, so the owner can remove a wrong one (nobody else may be on the
app to do it, and `PUT` cannot record a correcting payment between two other people). Change `settlement` /
`delete`. A settlement counts in balances from creation; confirming changes only its label.

### 4.9 Pulling changes

`GET /v1/groups/{gid}/changes?since=N` (N ≥ 0, default 0) → `200`:

```json
{
  "groupId": "…", "fromVersion": 10, "toVersion": 110, "latestVersion": 250, "hasMore": true,
  "group": Group,
  "members": [Member], "expenses": [Expense], "settlements": [Settlement],
  "changes": [ { "version": 11, "kind": "expense", "op": "create", "entityId": "…",
                 "actorMemberId": "…", "at": 0 } ]
}
```

- Window: `toVersion = min(latestVersion, since + 100)`. Each list holds the rows of that table with
  `since < version ≤ toVersion` (index range scans). Expenses include their shares. `group` is always included.
- The phone repeats with `since = toVersion` while `hasMore`. A row changed again after the window appears in a later
  page with its newer state; applying pages in order is always correct once the last page is applied. In between, a
  page can hold an expense whose payer's or sharer's member row comes only in a later page (a member row carries the
  version of its own last change), so the phone shows a group's balances only when its pull reached `latestVersion`
  (`observeLoad` stays `LOADING` until then) and never assumes a referenced member is present.
- `since > latestVersion` gives an empty page with `toVersion = latestVersion`. It can only happen after the server
  database was restored from a backup (D1 Time Travel): the phone then drops its copy of the group and pulls from 0.
- There is no snapshot route: entity rows are never trimmed, so `since=0` rebuilds a group (new phone, Join group).

`GET /v1/groups/{gid}/balances` → `200 { "version", "nets": [{ "memberId", "netPaise" }], "transfers":
[{ "fromMemberId", "toMemberId", "amountPaise" }] }`. Computed with section 6, served from `group_cache` (4.16). The
phone does not need it for screens (it computes the same locally); it exists for tests, debugging and the server's
own checks. A family answers `403 not_allowed` (every net is 0 by construction, 4.18). A family's pull pages carry
members and expenses, never settlements.

### 4.10 Share links, invites and the public link data

`PUT /v1/groups/{gid}/members/{mid}/links/{linkId}` `{ "token": "<24 characters>" }` → `201 { "linkId", "url",
"createdAt", "expiresAt" }`. Any active member, for any other active member. The phone makes `linkId` (UUID) and
`token` (18 bytes from `SecureRandom`, base64url, 24 characters) and stores both in `grp_links` before sending, so a
lost answer is simply sent again: the same `linkId` with the same token hash answers `200`, a different one `409
exists_different`. A link that was revoked or has expired meanwhile answers `404 link_not_found` (it is never given
out again; the phone forgets it and makes a new one, once). (A server-made token, returned once, could not be given back on a retry because only its hash is
stored.) The server checks the format (`^[A-Za-z0-9_-]{24}$`) and stores only SHA-256 of the token. Sent directly,
not through the outbox (the friend may open the link at once); not a group mutation (no version bump, no change
row). `url = "https://" + LINK_HOST + "/pay/" + token`. Links expire 90 days after creation; the phone reuses its
link for every reminder, and makes a new one (revoking the old) once its stored link is over 60 days old, so a
reminder never carries a link about to stop working.

`DELETE /v1/groups/{gid}/links/{linkId}` → `204` (revoke one). `DELETE /v1/groups/{gid}/members/{mid}/links` →
`204` (revoke all for that friend). Links are also revoked when the friend is removed, when the friend accepts the
invite, when the group is deleted, and when the owner undoes a join (4.6: the place's links and the links made from it).
When a member leaves or is removed, the links they made are revoked too, not only the links for their place: a
person who is out of the group could otherwise join again with a link they made for a friend without the app.

**The link is also an invite** (02/32, flow 04 "Invite to install", "If invited by link and has the app"): whoever
holds it and is signed in can take the friend's place in the group, and then sees the whole group, other members'
phone numbers and UPI IDs included. A link forwarded to the wrong chat can therefore be claimed by someone else.
Limits here: 144-bit tokens, expiry, the friend's place can be claimed once, and the join is announced to the group
under the joining account's own name (9.7 "Priya joined Flat 302"), so a stranger shows up as a stranger. For 7 days
the owner can undo the join, which puts the friend's place back and turns the link off (4.6 "Undo a join by link",
decided 2026-11-17).

`GET /v1/invites/{token}` (signed in) → the Join group screen:

```json
{ "groupId", "groupName": "Flat 302", "inviterName": "Rahul", "memberNames": ["Rahul", "Priya"],
  "memberCount": 3, "youOwePaise": 115000, "owedToYouPaise": 0, "expensesThisMonth": 5,
  "alreadyMember": false }
```

Every preview also carries `"kind": "group|direct|family"` and `"alreadyInFamily"` (both added at answer time, never
stored in the cache body; `alreadyInFamily` is always `false` for a `group` or `direct` link). A family link answers the
family shape of 4.18 instead (no cache, no money).

`memberNames` are the other active members' first words of their names. `youOwePaise` / `owedToYouPaise` are the
sums of the transfers (6.3) from / to the invited member. `expensesThisMonth` counts non-deleted expenses in the
current India month where the invited member paid or has a share. Everything except `alreadyMember` comes from
`group_cache` key `invite:<memberId>:<YYYY-MM>` (4.16; the month is in the key because the count changes when the
India month does, without any write).

`POST /v1/invites/{token}/accept` (signed in) → `200 { "groupId", "memberId", "version" }`. Online-only on the
phone. Sets the invited member's `user_id` to the caller and `joined_at`; keeps what an undo puts back (0002:
`join_link_id` = this link, `join_prev_name` and `join_prev_phone` = the friend's name and phone, never returned);
revokes the member's links; change `member` / `join`. If that member is already the caller: `200` (replay), also once the accept itself has revoked
the link (a repeat with a new `Idempotency-Key` from the person who joined, before expiry). `409 already_claimed` if
it belongs to someone else. `409 already_member` if the caller is already active in the group as another member
(merging two members is not supported). `409 limit_reached` at 300 groups. `404 link_not_found` if revoked, expired
or unknown. The friend's typed `phone` is cleared from `phone` on join (the user's own phone, if any, shows from then
on); only an undo brings it back.

A family link (4.18) lives 7 days instead of 90 and is made for the placeholder only; accepting it also needs the
caller not to be in another live family (`409 already_in_family`). The `PUT`'s `expiresAt` is always the stored one,
also on the `200` of a lost answer.

`GET /v1/links/{token}` (public, no sign-in) → the friend's web page data:

```json
{
  "groupName": "Flat 302",
  "senderName": "Arjun",
  "friendName": "Priya",
  "owes":   [ { "toName": "Arjun", "amountPaise": 15000, "upiId": "arjun@okhdfc" } ],
  "owed":   [],
  "expenses": [ { "description": "Swiggy", "category": "FOOD", "occurredOn": "2026-11-16",
                  "amountPaise": 45000, "yourSharePaise": 15000, "splitMethod": "EQUAL",
                  "peopleCount": 3, "paidByName": "Arjun" } ],
  "expensesTruncated": false
}
```

- `senderName` is the link creator's member name. `owes` / `owed` are the transfers (6.3) involving the friend
  (never with amount 0); `upiId` is the payee's, or null (then no Pay button for that row). An `owed` row is
  `{ "fromName": "Rahul", "amountPaise": 5000 }` (no UPI ID: the friend is the payee). `groupName` is null for a
  `direct` group. A UPI ID often holds a
  phone number (`98xxxxxx10@ybl`): the UPI ID field must say that friends with a link see it (13).
- `expenses`: non-deleted expenses where the friend paid or has a share above 0, newest first, at most 20.
- Never included: phone numbers, other members' shares, member or group ids, notes, user ids.
- Served from `group_cache` key `link:<linkId>`: a hit reads about 3 rows (link by token hash, group, cache row)
  whatever the group's size. Computing it reads the whole group (every expense, share and settlement), so without
  the cache one person reloading a link to a big group a few hundred times could use the day's 5 million read rows.
- Headers: `Access-Control-Allow-Origin: <WEB_ORIGIN>` (also `http://localhost:8788` when `ENVIRONMENT` is
  development), `Vary: Origin`, `X-Robots-Tag: noindex, nofollow`, `Referrer-Policy: no-referrer`,
  `Cache-Control: no-store`. `OPTIONS` preflight answered for GET only.
- Revoked, expired or unknown token, or a deleted group: `404 link_not_found` (same answer, so tokens cannot be
  probed).
- A family link answers `{ "family": true, "familyName", "senderName" }` and nothing else (4.18).

### 4.11 Reminders

Not in a family (`403 not_allowed`, 4.18).

`POST /v1/groups/{gid}/reminders` `{ "memberIds": ["…"] }` → `200 { "sent": ["…"], "skipped": [{ "memberId",
"reason": "not_on_app|too_soon|nothing_owed" }] }`. For members on the app who owe the caller (6.3) and were not
reminded in the last 20 hours: push `{"t":"remind","g":gid,"m":<caller's member id>}` and set `last_reminded_at`.
The member id lets the phone write "Arjun reminded you: ₹1,150 for Flat 302" (9.7) from its own data; without it the
phone could not tell who reminded when several people are owed. Friends without the app are reminded by the phone
through WhatsApp with their link (03/05); the server sends nothing to them. No change row, no version bump, so with
push off (no Firebase project yet) a reminder to an app user reaches nobody. A removed member is `nothing_owed`.
There is no entity to re-read on a replay, so the first answer is kept as JSON in `idempotency_keys.entity_id` and a
replay repeats it.

### 4.12 Live room

`GET /v1/groups/{gid}/live` with `Upgrade: websocket` and the usual `Authorization` header (OkHttp sets headers on
the upgrade request; no token in the URL). The Worker authenticates, checks membership, then forwards to
`env.GROUP_ROOM.get(env.GROUP_ROOM.idFromName(gid))` with headers `X-User-Id`, `X-Session-Id`, `X-Token-Expires-At`
(the access token's `exp`, epoch millis) and `X-Installation-Id`.

Durable Object `GroupRoom` (SQLite-backed class: the free plan supports only those, so the migration uses
`new_sqlite_classes`), using the hibernation API:

- `fetch` `/connect`: `new WebSocketPair()`, `this.ctx.acceptWebSocket(server, ["u:" + userId, "i:" +
  installationId, "s:" + sessionId, "e:" + expiresAt])`, return 101. The phone uses protocol-level pings (OkHttp `pingInterval`), which the runtime
  answers without waking the object and which are not billed; the constructor also sets
  `this.ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"))` for any app-level ping.
- `fetch` `/notify` (internal, POST `{ "groupId": "…", "version": n, "kick": ["userId"] }`; `groupId` because the
  object does not know the name it was made from): first reads which of the open sockets' sessions are still live
  (one D1 query, `SELECT id FROM sessions WHERE id IN (…) AND revoked_at IS NULL AND expires_at > now`, rows read =
  open sockets, only when a socket is open) and closes, with code 4401 and nothing sent, every socket whose session
  ended (sign-out, account deletion: the row is revoked or gone) or whose access token has expired; if that read fails,
  every socket is closed the same way. Then it sends `{"type":"changed","groupId":"…","version":n}` to every socket
  left, closes sockets tagged with a kicked user with code 4403 after sending `{"type":"revoked"}`, and answers
  `{ "connected": [{ "userId", "installationId" }] }` (from the tags of the sockets left open). Delete group kicks
  every member on the app, so each socket gets `changed`, then `revoked`, then the 4403 close. So a socket outlives
  sign-out, account deletion or its token only until the group's next change, and receives nothing after it.
- `webSocketMessage`: anything other than the auto-answered ping is ignored. `webSocketClose` / `webSocketError`:
  close. The room stores nothing.
- The Worker refuses `/connect` and `/notify` from outside: the DO is reachable only through its binding.

After every accepted group mutation the Worker, in `ctx.waitUntil`, calls `/notify` and then sends FCM (4.14) to the
devices of the group's members on the app, minus each device whose own user holds a socket in the room with that
installation id (another phone of the same person still gets its push; the installation id on a socket is whatever
the phone sent, so on its own it could name someone else's phone), minus the caller's installation when
`X-Installation-Id` is one of the caller's own devices (a header naming someone else's installation must not silence
their phone).

### 4.13 Other

- `GET /v1/health` → `200 {"ok": true}` (no database access).
- Cron trigger `30 0 * * *` (06:00 India). Free-plan limits apply to it too (10 ms CPU, 50 queries), so it only
  issues SQL, in this order:
  - delete `idempotency_keys` older than 7 days, sessions expired or revoked more than 30 days ago, links expired or
    revoked more than 30 days ago and their `group_cache` rows, `invite:` cache rows of past India months (their
    key holds the month, so nothing else would ever replace them), and `access_ended` rows over 90 days old;
  - purge tombstoned groups (`deleted_at` set, `purged_at` NULL), oldest first, children before parents (foreign keys
    are on in D1): `expense_shares`, `expenses`, `settlements`, `links`, `changes`, `group_cache`, `members`, each
    `DELETE ... WHERE <key> IN (SELECT ... WHERE group_id = ? LIMIT 500)`, stopping after about 10,000 rows a day
    (10% of the free written rows); set `purged_at` when a group has no child row left.
- Unknown paths and methods: `404 not_found` / `405 method_not_allowed` (with `Allow`). A path id that is not a
  lowercase UUID is an unknown path (404). A malformed `/v1/links/{token}` or `/v1/invites/{token}` gets
  `404 link_not_found` like any other bad token.

### 4.14 Push (FCM HTTP v1)

- Config: var `FCM_PROJECT_ID`, secret `FCM_SERVICE_ACCOUNT_JSON`. If either is empty, push is off (a no-op), which
  is the local default.
- OAuth: sign a JWT RS256 with the service account's `private_key` (`jose` `importPKCS8`) with `iss` = `client_email`,
  `scope: "https://www.googleapis.com/auth/firebase.messaging"`, `aud: "https://oauth2.googleapis.com/token"`,
  1 hour; exchange at `https://oauth2.googleapis.com/token` (`grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer`).
  Cache the access token in module memory until 5 minutes before expiry.
- Send `POST https://fcm.googleapis.com/v1/projects/{FCM_PROJECT_ID}/messages:send`, one per device token:

  ```json
  { "message": { "token": "…", "data": { "t": "changed", "g": "<groupId>", "v": "42" },
                 "android": { "priority": "HIGH", "ttl": "86400s", "collapse_key": "<groupId>" } } }
  ```

  Types: `changed` (`g`, `v`), `groups` (my group list changed), `remind` (`g`, `m`). Data only: ids and a version, no
  names, amounts or text. An answer of `UNREGISTERED` / 404 deletes that `devices` row.
- Priority: `HIGH` only when the change will make a notification on that phone (9.7): the recipient paid, has a
  share, is a settlement party, was reminded, added or removed. Otherwise `NORMAL`. Android lowers the priority of
  apps whose high-priority messages do not lead to a notification.
- `collapse_key` per group: Android keeps at most four collapse keys per device. A `changed` push makes the phone pull
  that group only (9.6), so a push dropped while a device in Doze has more than four groups waiting leaves that group
  for the next foreground sync or the 12-hour sync. Exceptions: `groups` uses the key `"groups"`, and `remind` has
  no `collapse_key`, because its notification text needs the push's own `m` and a later `changed` push for the same
  group would otherwise replace it.
- Fan-out cap: at most 40 sends per request (the free plan allows 50 outbound requests per invocation, and JWKS / OAuth
  / the room use some). Over the cap, the rest are skipped: those phones catch up on their next open or periodic
  sync.

### 4.15 Rate limits

Workers Rate Limiting bindings (`[[ratelimits]]` with `name`, `namespace_id` and `[ratelimits.simple]` `limit` and
`period`, which must be 10 or 60; needs wrangler 4.36 or later). Whether the free plan has it was not confirmed on
Cloudflare's page; if not, fall back to an in-memory per-isolate token bucket with the same numbers. Counts are
approximate and per Cloudflare location, which is enough to slow a looping client. Keys are never stored or logged.
Built: the binding is used when `env.RL_*` exists and its call succeeds, else a per-isolate fixed 60 s window
(`server/src/ratelimit.ts`); "mutations" are the signed-in routes other than GET and sign-out.

| Binding | Key | Limit |
|---|---|---|
| `RL_AUTH` | client IP (`CF-Connecting-IP`) | 20 / 60 s on `/v1/auth/nonce`, `/google`, `/dev` |
| `RL_REFRESH` | SHA-256 of the presented refresh token | 5 / 60 s on `/v1/auth/refresh` |
| `RL_PUBLIC` | client IP | 30 / 60 s on `/v1/links/*` |
| `RL_USER` | user id | 120 / 60 s on all signed-in routes |
| `RL_WRITE` | user id | 30 / 60 s on mutations; an import counts once per 10 items (below) |

Refresh is not limited by IP: many Indian mobile users share one public IPv4 address through carrier NAT, and every
signed-in phone refreshes, so a per-IP limit there would sign out unrelated people. Over the limit, on every limited
route, the import included:

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 60
Content-Type: application/json; charset=utf-8
Cache-Control: no-store
X-Content-Type-Options: nosniff

{ "error": { "code": "rate_limited", "message": "Too many requests." } }
```

Tests inject a fake limiter. Also capped: 50 members per group, 300 groups per user,
20,000 expenses per group, 3 devices per user, 32 KB bodies, 50 items per import request and version 6,000 for an
import (4.17); one live family per user, 2 active members per family (the invite's placeholder included) and 7 days
per family link (4.18). A family counts against the 300 groups.

**Imports** (4.17; decided 2026-11-19, open point 13.20 option (b)). An import request counts once against `RL_USER`
and ceil(items / 10) times against `RL_WRITE`: 1 to 10 items once, 50 items five times. The binding has no weight, so
the router calls it that many times (`enforce(..., cost)`, `writeCost` on the route, `importWriteCost` in
`routes/imports.ts`) and stops at the first refusal. The first hit is taken before the body is read, as for every
write; the others after the idempotency lookup (4.3) and before the handler's checks, so:

- a replay (same key and body) counts once, like any replayed write, and still gets its answer with one hit left;
  the same key with another body (`422`) also counts once;
- a request refused on a later hit writes nothing, its key included, so its retry after `Retry-After` is handled as
  new (not a replay);
- a body without an `items` array counts once and one of more than 50 counts 5 (both are `400`); a refused request
  (`400`, `403`, `409`) and an `applied: false` answer pay the same as an accepted one.

At 30 a minute that is 6 parts of 50 (300 items) a minute. A 1,000-row Splitwise group is 20 requests and 100 hits:
the phone sends 6 parts, gets a `429`, waits `Retry-After` and goes on, about 3 to 4 minutes in all, which the import
screen's progress covers (the outbox's 429 wait, docs/splitwise-import.md 5.4). The largest file the phone sends (5,000
items) takes about 17 minutes. Item by item through `PUT .../expenses` 1,000 rows would be 1,000 requests and over 30
minutes.

A client that imports in a loop is now bounded by the same limiter as `PUT`: about 6 requests of 50 a minute, about
2,100 written rows a minute (351 measured per request), so a day's 100,000 free writes take about 50 minutes instead
of about 10 (item-by-item `PUT`s: about 270 rows a minute, about 6 hours). Also bounding it: a group stops at version
6,000 (about 42,000 rows, the most one import should take, docs/splitwise-import.md decision 8); the phone's parts
are fixed outbox entries whose retries write nothing (replay or `applied: false`); and a new import needs the person
to pick a file again. As for every route, the limits are per user: many accounts at once are not bounded here.

### 4.16 Derived data cache (`group_cache`)

Balances (6.2, 6.3), the public link page (4.10) and the invite preview read the whole history of a group, so they
are cached by group version, never maintained by writes:

1. Read `groups.version` and the cache row by `(group_id, cache_key)` (2 rows).
2. Same version: answer from `body`.
3. Otherwise compute, answer, and store with `INSERT ... ON CONFLICT (group_id, cache_key) DO UPDATE SET version =
   excluded.version, body = excluded.body WHERE excluded.version > group_cache.version` (a slower reader never
   overwrites a newer row).

The balance rules (leave, remove, delete group) check `balances` this way, so a repeated attempt on a group that is
not settled costs a few rows, and the 3.1 guard makes sure nothing was written in between. Cost: at most one recompute
per group version per key; group writes are rate-limited, so a reader cannot make the server recompute more often
than the group changes.

### 4.17 Import from Splitwise

Built 2026-11-18 from docs/splitwise-import.md (the file is read on the phone; the server only stores what any group
holds). The phone makes the group with `POST /v1/groups` (friends without the app, names only), then sends its past
expenses and payments in parts of at most 50 items. Each item still gets its own version and change row, so pulls
(4.9), the version counter and the outbox (5.1) work as for any other write. `server/src/routes/imports.ts`.

`POST /v1/groups/{gid}/import` (bearer token, `X-Installation-Id`, `Idempotency-Key` required, `RL_USER` once and
`RL_WRITE` once per 10 items, 32 KB body cap: `413 too_large`) → `200`:

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

```json
{ "applied": true, "firstVersion": 2, "lastVersion": 51 }
```

- An expense item is an `ExpenseInput` (4.7) plus `kind` and `id`; `auto` may be absent or `false`, `baseVersion`
  absent or `null`. A settlement item has exactly the fields above; the server stores it with `method: "OTHER"`,
  `matchedFromSms: false`, `recordedByMemberId` = the caller's member and `confirmedAt` = now (a Splitwise payment is
  already settled). Other keys are ignored, as on every route.
- Item `i` (0-based, body order) is written at version `firstVersion + i`, with its change row: `expense` / `create`
  or `settlement` / `create`, actor = the caller's member. Expenses answer as created by and updated by the caller.
- When every item id already exists in this group, each in its own table (an old retry whose key was purged, 4.13):
  `200 { "applied": false, "firstVersion": null, "lastVersion": null }`, and nothing is written (no key either). Its
  content is not compared.
- Past dates are the point: any date from 2000-01-01 to tomorrow (India), as 4.7. Any member may pay, and a settlement
  may be between any two members (two friends without the app included), unlike `PUT .../settlements` (4.8). Its
  recorder (the importing owner), `from` and `to` may delete it (4.8); the phone must offer Delete on such a payment
  to the owner, though the owner is neither party.

Checks, in the order their answers are given (each item fully, in body order, for 5 to 8), except that check 12
reads right after 8 and gives its `applied: false` answer there, before 9 to 11: a part applied long ago and retried
after its key was purged gets `applied: false` even if a friend has joined, a named member was removed or the group
reached the cap since (a refusal would make the phone delete the group, docs/splitwise-import.md 5.4). Its `409`
still comes last. Changed 2026-11-18 after review; docs/splitwise-import.md 3.1 lists the old order.

| # | Check | Answer |
|---|---|---|
| 1 | caller is an active member of a live group | `403 not_member` (+`reason`), `410 group_deleted` |
| 2 | the group's `kind` is `group` | `403 not_allowed` |
| 3 | caller is the owner (`ownerUserId`) | `403 not_owner` |
| 4 | the body is an object (else field `body`); `items` is an array of 1 to 50 | `400 bad_request`, field `items` |
| 5 | each item is an object (field `items[i]`) whose `kind` is `expense` or `settlement` | `400`, `items[i].kind` |
| 6 | `id` is a lowercase UUID version 4, unique in the body | `400`, `items[i].id` |
| 7 | expense: every rule of 4.7 (`parseExpenseFields`, shared with `PUT .../expenses`), including the 6.1 recompute; `auto` absent or false; `baseVersion` absent or null | `400 bad_request` or `shares_mismatch`, field `items[i].…` (e.g. `items[0].shares`) |
| 8 | settlement: `fromMemberId`, `toMemberId` UUIDs that differ (else `items[i].toMemberId`); `amountPaise` 1 to 10^9; `occurredOn` as 4.7 | `400`, `items[i].…` |
| 9 | nobody else in the group is on the app (no active member with a `userId` other than the caller) | `403 not_allowed` ("Import only fills a group nobody else has joined.") |
| 10 | every member named (payers, sharers, `from`, `to`) is an active member of the group | `400 bad_request`, the first failing field in body order (`items[1].paidByMemberId`, `items[0].shares[1].memberId`, `items[2].toMemberId`) |
| 11 | group `version` + number of items ≤ 6,000 | `409 limit_reached` |
| 12 | item ids (expense ids in `expenses`, settlement ids in `settlements`): none exists → write; all exist in this group → `applied: false` (answered after 8, see above); anything else | `409 exists_different` |

Checks 9 and 10 read the group's members once; check 12 is one batch of two reads. The batch (3.1), one `ctx.db.batch`, in order and atomic: for each item
`bump`, the entity statements (`expenseStmts(..., { create: true })`: a plain insert, so an id that appeared meanwhile
fails on the primary key instead of being updated, and no `DELETE` of shares; or `insertSettlementStmt`), then
`guard(op: create)`, the first one carrying `importRule` (3.1); last, the key row, whose `entity_id` is the group's
version at that point as text (`IdemWriter.stmtGroupVersion`), which is `lastVersion`.

- **Guard failure** (a friend took a place through a link, ownership changed, a member was removed, the group was
  deleted, between the checks and the batch): every item is rolled back. Checks 1 to 3, 9 and 10 run again and the
  first that fails answers; if none does, `503 internal` and the phone retries.
- **Primary key of `expenses.id` or `settlements.id`** (two copies at once): if this request's `Idempotency-Key` is
  stored by then, it is the router's replay (4.3); otherwise check 12 runs again (`applied: false`, `409`, or `503`).
- **Replay** (same key and body): `200 { "applied": true, "firstVersion": last − items + 1, "lastVersion": last }` from
  the key row, `Idempotent-Replay: true`. No access check: the answer holds no group content. Same key, other body:
  `422 idempotency_key_reused`. Both count once against `RL_WRITE`.
- **Rate limit** (4.15, decided 2026-11-19): ceil(items / 10) hits on `RL_WRITE` (5 for 50 items); the first before
  the body is read, the rest after the replay lookup and before check 1. Over the limit: `429 rate_limited`,
  `Retry-After: 60`, the shape of 4.15, and nothing is written (no key either), so the phone waits and resends the same
  part with the same key. At 30 a minute a 1,000-row file takes about 3 to 4 minutes.
- **After the batch:** one room notify and one NORMAL `changed` push at `lastVersion` (only the caller's other phones
  get it: nobody else is on the app). No HIGH push, no notification.
- **Logs:** the route template `/v1/groups/:gid/import` only (`log.ts`); never a name, description, amount or id.
- **Why a new route** (docs/splitwise-import.md 2): a 1,000-row file item by item is 1,000 requests and 1,000 room
  notifies, over 30 minutes at `RL_WRITE`'s 30 a minute (against 3 to 4 for the import), about 9,000 written rows, and Splitwise payments between two
  other people could not be recorded at all (`PUT .../settlements` is for the payer or the receiver only).
- **Phone side:** parts are `IMPORT` outbox entries; a refused part makes the phone delete the group again, which the
  delete rule of 4.6 allows while nobody else is on the app (docs/splitwise-import.md 5.4).

### 4.18 Family

Decided 2026-11-18 (docs/family.md, its decisions 1 to 20; design `06-family`, flow lane "03B Family month"). A
**family** is a group of kind `family`: one owner who set it up, at most `MAX_FAMILY_MEMBERS = 2` active members (the
placeholder of an invite not yet accepted counts), one live family per user, free. A **family expense** is a group
expense in the family with exactly one share, the payer's, for the full amount, so every net is always 0: nobody owes
anybody, and balances, settle-up and reminders never apply. Only the payer shares, edits or deletes a family expense.
What a family expense carries: description (the payee, or the category's name; the 4.7 rule for UPI IDs and long
digit runs applies), category, amount, India date and the payer. Never the note, account, card, "paid with", SMS text
or sender, exact time, or any expense the person did not mark Family (10). The phone's side is 9.9.

New error `409 already_in_family` ("Already in a family.", 4.1). New constants `MAX_FAMILY_MEMBERS = 2`
(`db/groups.ts`) and `FAMILY_LINK_TTL_MS = 7 days` (`db/links.ts`). Helpers in `db/groups.ts`: `inAnotherFamily(db,
userId, gid)` (an active member row of the user in a live family other than `gid`; index `members_user`),
`NOT_IN_ANOTHER_FAMILY(userParam)` (the same as SQL for a guard, `?1` being the batch's group) and
`familyExpenseTombstoneStmts(db, gid, mid, actor, now)` (the member's live expenses in that family tombstoned at the
batch's version, `updated_by_member_id` = actor, and their shares deleted; index `expenses_group_version`).

**Create**: `POST /v1/groups` (`Idempotency-Key`; online only on the phone):

```json
{ "id": "<uuid v4>", "kind": "family", "name": "Home", "myMemberId": "<uuid v4>",
  "members": [ { "id": "<uuid v4>", "name": "Partner" } ] }
```

→ `201 { "group": Group (kind "family", version 1), "members": [me, the placeholder (userId null, onApp false)] }`.
`name` 1–40 (text rules of 4.6). `members` exactly one entry (`400`, field `members`): no `userId` (`400`, field
`members[0].userId`, "Family members join with a link."), no `phone` (field `members[0].phone`), `name` 1–60. The phone
always sends the fixed name `"Partner"`, never shown: once someone joins, their own name wins. Ids random v4 as for
groups. Before the batch: `409 already_in_family` when the caller is active in another live family, `409
limit_reached` at 300 groups. The batch is the usual create (3.1) whose change row is the family guard (3.1): two
creates racing from two phones, the second fails, is checked again and answers `409 already_in_family` (else `503
internal`). The same id again: the usual answers (`200` same content, `409 exists_different`, `410`). No push: nobody
else is in it.

**Rename**: `PATCH /v1/groups/{gid}`, any active member, as for a group. Queued on the phone, last writer wins.

**Delete**: `DELETE /v1/groups/{gid}` → `403 not_allowed` ("A family ends when its last member leaves."). Not offered
by the phone.

**Leave**: `POST /v1/groups/{gid}/leave` → `204` (online only). No balance check. One batch: bump; the member removed
and every link for that place and made by it revoked; `familyExpenseTombstoneStmts` for the leaver's expenses;
ownership passes when the caller owned it; guard `member` / `leave` with rule `removed_at IS NOT NULL`; the family
tombstoned (links revoked) when no member on the app is left (the placeholder does not keep it alive); `access_ended`
`left`; the key. A guard failure answers as `requireMember` does (`403 not_member`, `410`), else `503 internal`. After:
the leaver's sockets close and their phones get `{"t":"groups"}`; the partner's phones get a NORMAL `changed` and pull
the tombstones at the new version.

**Remove**: `DELETE /v1/groups/{gid}/members/{mid}` → `204` (online only). Not yourself (`403 not_allowed`); the
partner on the app: the owner only (`403 not_owner`); the placeholder: any member ("Cancel invite"). Already removed:
`204`. No balance check. One batch: bump; the member removed and its links revoked; `familyExpenseTombstoneStmts` for
that member (none for a placeholder); guard `member` / `remove` with the family rule of 3.1 (a place taken through
the link in between fails it, and every check runs once more; a second failure is `503 internal`); `access_ended`
`removed` for whoever holds the place in the batch; the key. After: the removed person's sockets close and their
phones get `{"t":"groups"}`, NORMAL: no HIGH push, no notification (none is designed, 13).

**Add the placeholder again**: `POST /v1/groups/{gid}/members` `{ "id": "<uuid v4>", "name": "Partner" }` → `201
Member` (online only; the phone does it when the partner left or the invite was cancelled). `userId` → `403
not_allowed` ("Family members join with a link."); `phone` → `400`, field `phone`; 2 active members already → `409
limit_reached`, guarded by `(SELECT COUNT(*) ...) <= 2`.

**Edit a member**: `PATCH /v1/groups/{gid}/members/{mid}` → `403 not_allowed` on a family (the placeholder keeps its
fixed name and never gets a phone).

**Not for families** (`403 not_allowed`, checked right after the membership check): `POST .../members/{mid}/undo-join`
("In a family the owner removes instead."); `GET .../balances`, `PUT .../settlements/{sid}`, `POST
.../settlements/{sid}/confirm`, `DELETE .../settlements/{sid}`, `POST .../reminders` ("Families do not settle up.");
`POST .../import` ("Only a group can be imported into.", 4.17).

**Family expenses**: `PUT /v1/groups/{gid}/expenses/{eid}` (queued) with the usual `ExpenseInput`:

```json
{ "description": "BigBasket", "category": "GROCERIES", "amountPaise": 234000, "occurredOn": "2026-11-15",
  "paidByMemberId": "<caller's member>", "splitMethod": "EQUAL", "auto": false,
  "shares": [ { "memberId": "<caller's member>", "sharePaise": 234000, "input": null } ], "baseVersion": null }
```

After the 4.7 checks of the body: `paidByMemberId` other than the caller's member → `403 not_allowed` ("In a family
you share your own payments only."); an update of an expense someone else paid → `403 not_allowed`; `splitMethod`
other than `EQUAL`, or `shares` other than exactly `[{ caller, amountPaise, null }]` → `400`, field `shares` ("Family
expenses are not split."); `auto: true` → `400`, field `auto`. Everything else as 4.7 (text 1–80, category, amount
1 to 1,000,000,000, date 2000-01-01 to tomorrow, 20,000 per group, last writer wins, `410 expense_deleted`,
`overwrote`). After: a NORMAL `changed` to the partner's phones (no HIGH push: no notification per family expense).
`DELETE .../expenses/{eid}`: an expense someone else paid → `403 not_allowed`; otherwise as 4.7.

**Links**: `PUT /v1/groups/{gid}/members/{mid}/links/{linkId}` on a family: the target must be the placeholder (a
member on the app → `409 member_on_app`). The link expires `FAMILY_LINK_TTL_MS` (7 days) after it is made: whoever
holds it can join and then sees every family entry, and the partner normally joins the same day. `expiresAt` in the
answer is always the stored one. Revoke routes unchanged.

**Invite preview**: `GET /v1/invites/{token}` of a family link (no cache, no whole-group read):

```json
{ "groupId": "…", "kind": "family", "groupName": "Home", "inviterName": "Arjun", "memberNames": ["Arjun"],
  "memberCount": 1, "youOwePaise": 0, "owedToYouPaise": 0, "expensesThisMonth": 0,
  "alreadyMember": false, "alreadyInFamily": false }
```

`memberNames` and `memberCount`: the active members on the app other than the placeholder (first words).
`alreadyInFamily`: the caller is active in another live family (the phone shows "You're already in a family"). Group
and direct links carry `kind` too, and `alreadyInFamily: false`.

**Accept**: `POST /v1/invites/{token}/accept` as 4.10, plus `409 already_in_family` when the caller is active in another
live family; the guard rule is `user_id = ?caller AND NOT_IN_ANOTHER_FAMILY(?caller)`. On a guard failure the handler
reads again and answers the replay (`200`), `already_claimed`, `already_in_family` or `link_not_found`. After, as for a
group: HIGH to everyone on the app, so the owner gets "Meera joined Home" (9.7), and `{"t":"groups"}` to the joiner's
other phones.

**Public page**: `GET /v1/links/{token}` of a family link → `200 { "family": true, "familyName": "Home", "senderName":
"Arjun" }` and nothing else: no expenses, amounts, other names or ids. `senderName` is the first word of the link
creator's member name, as the page says it ("Arjun invited you to Home", 06-family/28) and as Join Family shows it. Not
cached (one member read). Revoked, expired, unknown or deleted: the same `404
link_not_found`; headers as 4.10.

**Account deletion**: step 1b of `DELETE /v1/me` (4.5).

**Unchanged**: `GET .../changes` (a family's pages carry members and expenses, never settlements), `GET /v1/me/groups`
(kind `family`), `PATCH /v1/me`, the live room (allowed, not used by the phone), the cron (a tombstoned family is
purged like any group), rate limits, logging (route templates only).

## 5. Sync

### 5.1 Phone writes (outbox)

1. The user acts. In one Room transaction: write the entity to the `grp_*` tables with `syncState = PENDING`, update
   any linked personal expense (9.4), and append an outbox row `{ idempotencyKey: UUID, groupId, entityId, method,
   path, bodyJson, restoreJson }`. `restoreJson` is the entity as last synced (null for a create) together with the
   linked personal row's previous `status`, `myShareMinor` and `groupExpenseId`: what "roll back" (4.1) puts back.
   Without it a rejected update would stay on the phone for good, because the next pull starts at the local version
   and does not bring the entity again. Ids of new groups, members, expenses and settlements are made on the phone
   (`UUID.randomUUID()`, or v5 for direct groups).
2. `SyncScheduler.requestSync(expedited = true)`.
3. `SyncWorker` sends outbox rows in order, one at a time (a group create must reach the server before its
   expenses); it increments `attempts` before each send. Success: store the returned entity (`syncState = SYNCED`, its
   `version`), delete the row. Errors: the table in 4.1. Network errors and 5xx: stop, keep the row, retry with
   WorkManager back-off (exponential from 30 s, network required). 429 with our JSON: wait `Retry-After`, go on. An
   entry that failed with 5xx five times in a row is parked: it blocks only the later entries of its own group
   (entries without a group block nothing), the other groups go on, and it is tried again on the next sync, whatever
   starts it (a write, the foreground, a push, the 12-hour sync). A parked entry does not make WorkManager back off:
   one queued sync serves every group, so its back-off would hold all of them. A sync request that finds the queued sync
   waiting out a back-off replaces it, so a new change never waits for an old failure's back-off (up to 5 hours).
4. Coalescing before sending, only between entries never attempted (`attempts = 0`: an attempted one may already be
   applied even without an answer): a create followed by a delete of the same expense are both dropped (Undo on
   "Split. Sorted." offline sends nothing); a create followed by updates becomes one `PUT` with the last body; two
   updates keep only the last (give the merged row the last key, and keep the first entry's `restoreJson`). The
   merged entry is the last one, with its key and its place in the order, so it never goes before something it
   names (a member added in between). Only expense entries are coalesced.
   **Import parts** (`entityKind = IMPORT`, 4.17; docs/splitwise-import.md 5.4) go through the same loop: order,
   keys, 429 waits, parking after five 5xx (which blocks only that group), back-off; they are never coalesced. The
   entry's `entityId` is the part's own id and its body names the items. Accepted: each item without another waiting
   change becomes `SYNCED` at version `firstVersion + i` (an `applied: false` answer keeps its version until the next
   pull), `grp_imports.sentItems` grows, and the import is `DONE` once no other part of the group waits. Refused with
   `not_member` or `group_deleted`: as for any entry, and the import is `FAILED`. Refused with any other code (`400`,
   `403 not_allowed` / `not_owner`, `409`, `413`, `422`): the whole import is taken back in one transaction
   (`LocalGroupData.failImport`): `FAILED`, the group removed with its splits forgotten (mirrors go, linked payments
   go back to how they were), and `DELETE /v1/groups/{gid}` queued (4.6 allows it while nobody else is on the app).
   The same push run looks at the outbox once more, so that delete leaves at once. A refused group create of an
   import (its rollback removes the group) also makes the import `FAILED`; nothing is deleted on the server then.
5. Not queued (online-only, the person waits for the answer): leave group, remove member, undo a join, delete group
   (except the clean-up of a refused import, 4.6), accept invite, link create, sign-out and delete account. For a
   family (4.18) also: create the family, add the placeholder again, cancel the invite (remove the placeholder),
   remove the partner and leave. Queued like any write: rename the family, and family expenses (create, update,
   delete), which `FamilyLinker` writes (9.9). A refused family expense is not rolled back into a "Not saved" row: the
   linked payment simply becomes Just me again (9.9).

### 5.2 Phone reads (pull)

`SyncWorker`, after the outbox:

1. `GET /v1/me/groups`. Groups missing from `groups` are deleted locally (their rows, links and outbox rows),
   except groups whose own create is still in the outbox: a group made a second ago is not on the server yet. A
   listed group whose `DELETE` waits in the outbox (only the clean-up of a refused import queues one, 5.1) is skipped:
   neither added again nor pulled, or the partial copy would come back before the delete is sent. If that delete is
   refused (`409 balances_not_settled`: a friend joined meanwhile), the entry is dropped and the group comes back on
   the next pull. The
   local groups are read before the outbox, so a group made between the two reads is left alone rather than removed
   with its create. Personal mirror rows stay (9.4), unless `lost` says `join_undone` for that group (9.4: its splits
   are forgotten); personal rows linked to group expenses that never reached the server are rolled back (9.4). A
   `403 not_member` from any group route removes the group the same way, by its `reason`.
2. For each group whose server `version` > local `version`: pull pages (4.9) from the local version. Apply each page
   in one Room transaction: upsert members, expenses (with shares), settlements, tombstones; skip any entity that has
   a `PENDING` outbox row (the outbox wins, then the server's answer replaces it) and write the server's copy of it
   (tombstones included) into that entity's waiting entries' `restoreJson`: the local version moves past this page, so
   if the change is later refused, the rollback must put back the server's newest state, not the state the person
   started from (a settlement deleted meanwhile stays deleted, an expense edited meanwhile shows the edit); update
   personal mirrors (9.4); set the local version to `toVersion`.
3. Make notifications from the pages' `changes` whose `actorMemberId` is not mine, that touch me (9.7) and whose `at`
   is less than 24 hours old. A pull from version 0 (Join group, new phone, sign-in again) makes none: otherwise a
   restored group would post its whole history.

Family (9.9, docs/family.md 7.3): `SyncEngine.sync` runs `FamilyLinker.reconcile()` before the push and again after
the pull. It shares the Family-tagged payments that should be shared and are not yet (earlier ones after a set-up or a
join, or after a new install), deletes shared ones that no longer qualify, and never pushes field differences (two
phones of one person would overwrite each other). When it queued something after the pull it asks for one more,
expedited sync. A family pulled from version 0 after Join Family shows the loading state of 06/06 until its first page.

Sync runs: on app start and every return to foreground (if signed in), on every outbox write, on an FCM `groups` or
`remind` message, and every 12 hours (periodic, network required). A pull of one group only: on an FCM `changed`
message whose version is above the local one (a group the phone does not know gets a full sync), when a live socket
opens, and on a WebSocket `changed` with a version above the local one.

### 5.3 Live while the app is open

While a screen of one group is visible (Group page, Settle up, Split it with a group chosen), the phone keeps one
WebSocket to `/v1/groups/{gid}/live` (OkHttp, `pingInterval(30 s)` protocol-level pings; close when the screen goes).
A group made on this phone connects only once its create has left the outbox (sent and accepted; `LiveRoom` watches
the outbox): before that the server does not know the group and answers `403 not_member`, which would read as "no
longer in the group" and sync the list for nothing. A create the server refused removes the group, and nothing
connects.
Each time the socket opens (first connect and every reconnect) the phone pulls that group once: while a phone counts
as connected the server sends it no push, and a change made while an old socket was silently dead (a network change
noticed only after a missed ping) reached neither. A `changed` message with `version` above the local one triggers a
pull of that group only. The comparison is the sync engine's (`SyncEngine.pullChanged`, as for a `changed` push,
4.14), made once any sync in progress has ended: an import notifies the room once per part (4.17) while the phone's
own push holds the sync, and comparing on arrival queued one pull per message, up to 100 `GET /changes` in a burst.
`revoked` or close code 4403: sync `/me/groups`. Close code 4401 (session ended or token
expired) and any other close: reconnect with back-off (2 s, 4 s, ... 60 s) while the screen is visible. Other screens rely
on FCM and foreground syncs (fewer Durable Object requests).

### 5.4 Conflict rules

- Expenses: last writer wins by server order; every update carries the full expense, so there is never a merge of
  half-states. Shares always come together with the expense.
- Deletes are tombstones: a later update gets `410 expense_deleted`; the phone drops its edit and applies the
  tombstone. There is no undelete; Undo of a sent delete creates a new expense with a new id.
- Settlements are immutable except confirm and delete.
- Members: removal needs a zero balance at the moment the server applies it; if an expense arrived first, the removal
  gets `409 balances_not_settled`; if the removal arrived first, an expense that newly names that member gets
  `400 bad_request` and is rolled back on its phone (3.1 guard).
- Group name: last writer wins.

## 6. Split maths and balances

Implemented twice: TypeScript `server/src/domain/splits.ts` and Kotlin module `:splits` (`com.expenses.splits`, pure
Kotlin, no Android). Both test suites run the same vectors in `shared/vectors/splits.json` and
`shared/vectors/balances.json` (input and expected output). A change to either implementation must keep every vector
passing in both. All values are integers (Kotlin `Long`; TypeScript `number`, safe because amounts ≤ 10^9 and
weights are capped).

**Where the vectors are today:** work A was limited to `server/`, so the vector files were written to
`server/test/vectors/` (`splits.json` 38 cases, `balances.json` 18, `direct_ids.json` 6, made by an independent
Python implementation, not by the TypeScript code). Moving them to `shared/vectors/` is a `git mv` by the lead plus
the three import paths at the top of `server/test/vectors.test.ts`. Until then `:splits` reads them from
`server/test/vectors/`. Format: splits cases `{ name, method, amountPaise, inputs: [{ memberId, input }], expected:
[{ memberId, sharePaise }] }` (inputs in any order, expected sorted by member id); balance cases `{ name, expenses:
[{ paidBy, amountPaise, shares: [{ memberId, sharePaise }] }], settlements: [{ from, to, amountPaise }],
expectedNets: [{ memberId, netPaise }] (every id seen, sorted, zeros included), expectedTransfers: [{ fromMemberId,
toMemberId, amountPaise }] }`; direct ids `{ namespace, cases: [{ userA, userB, groupId, memberIdA, memberIdB }] }`.

A family (4.18) never needs any of this: each family expense has one share, the payer's, for the full amount, so every
net is 0 by construction. The server refuses `GET .../balances` on a family and the phone never computes family
balances (its family month is a sum of full amounts, 9.9).

### 6.1 Shares from a method

Participants are ordered by member id (plain string compare, ascending). `A` = amount in paise.

- `EQUAL` (n participants): `base = A div n`, `r = A mod n`; the first `r` participants get `base + 1`, the rest
  `base`.
- `AMOUNTS`: share = input. Must sum to `A`.
- `PERCENT` (inputs in basis points, sum 10,000) and `SHARES` (weights, total `W`; for percent `W = 10000`):
  `raw_i = A × input_i`; `share_i = raw_i div W`; `R = A − Σ share_i`; the `R` participants with the largest
  `raw_i mod W` (ties: lower member id first) get `+1`.

The phone uses the same functions to fill the screen (02/09–11: "₹50 is still left to assign" is `A − Σ` of typed
amounts) and sends both inputs and shares.

### 6.2 Net per member

`net(m) = Σ amount of expenses m paid − Σ m's shares + Σ settlements m paid (from) − Σ settlements m received (to)`,
over non-deleted expenses and settlements of the group. Positive means the group owes m. The nets sum to 0.

Server query (one statement, rows read ≈ expenses + shares + settlements of that group):

```sql
SELECT member_id, SUM(delta) AS net FROM (
  SELECT paid_by_member_id AS member_id, amount_paise AS delta FROM expenses
    WHERE group_id = ?1 AND deleted_at IS NULL
  UNION ALL SELECT s.member_id, -s.share_paise FROM expense_shares s JOIN expenses e ON e.id = s.expense_id
    WHERE e.group_id = ?1 AND e.deleted_at IS NULL
  UNION ALL SELECT from_member_id, amount_paise FROM settlements WHERE group_id = ?1 AND deleted_at IS NULL
  UNION ALL SELECT to_member_id, -amount_paise FROM settlements WHERE group_id = ?1 AND deleted_at IS NULL
) GROUP BY member_id;
```

### 6.3 Simplified debts ("fewest payments")

Within one group (never across groups): creditors = nets > 0, debtors = nets < 0. Repeat until none left: take the
creditor with the largest amount and the debtor with the largest owed amount (ties: lower member id), make a
transfer `debtor → creditor` of the smaller of the two, reduce both. The result has at most n − 1 transfers. (This
greedy is not always the true minimum; it is deterministic, which matters more here.)

### 6.4 What the screens show (phone)

- Group page "Balances" (02/24): for each other active member X, the transfer between me and X in that group: X → me
  shows "+₹… owes you", me → X shows "you owe" with the amount; none and X's net is 0 shows "all settled ₹0"; none
  and X's net is not 0 shows "₹0" with "settles with others" (copy not in the design; see 13). Header "FLAT 302 OWES
  YOU ₹2,350" = my net (negative: "YOU OWE FLAT 302", copy to confirm).
- Groups (02/20): each group row = my net; "OVERALL YOU ARE OWED" = Σ my nets over all groups (kind `group` and
  `direct`), "across N groups" = the number of group rows listed (the design counts Office lunch at "all settled ₹0":
  three rows, "across 3 groups", ₹2,350 − ₹800 + ₹0 = ₹1,550). The design has no direct splits on this screen, so
  with direct splits the overall is more than the sum of the rows; their amounts show under Friends. Negative overall:
  "OVERALL YOU OWE" (copy to confirm, 13). Friends = per person, Σ of my transfers with them over all groups; a person
  is a user id when on the app, else the friend's phone number (E.164), else "name:" and the friend's name in lower
  case (`personKeyOf`; a friend added by name only is the same person in every group, and two such friends with the
  same name are one).
- Home (02/00) "Owed to you" = Σ of my positive nets, "You owe" = Σ of my negative nets (as a positive amount).
- Settle up (03/00) "You owe Rahul ₹1,200 · 4 expenses · Flat 302": the transfer; the count is the non-deleted
  expenses in that group, dated after the last settlement between the two, where one paid and the other has a share.

## 7. Web link for friends without the app (Pages)

Matches `design/spec/screens/03-getting-paid-back/06-friend-web-page-no-app` in both themes (the browser bar in the
mock-up is the browser's, not ours). Address: `https://<LINK_HOST>/pay/<token>`; the token is in the path, never a
query string. `LINK_HOST` is the free pages.dev host that wrangler reports when the Pages project is created:
`<name>.pages.dev`, or `<name>-xxxx.pages.dev` when the name is taken (`docs/server-setup.md` 3). The placeholder
`xpense.app` stays until it exists; changing it later breaks shared links, so it is chosen once.

`server/web/` layout (static, no framework, no build tool beyond a small Node script; its own `package.json`, so the
Worker's dependencies and tests stay separate):

```
server/web/
  public/
    pay.html, pay.js, pay.css     # the page; colours, sizes and text styles only through tokens.css variables/classes
    index.html, 404.html          # one line: what this is / not found; noindex (without 404.html Pages would serve
                                  # index.html with 200 for every unknown path)
    fonts/                        # Bricolage Grotesque and DM Sans woff2 (latin, latin-ext), self-hosted, OFL texts
                                  # beside them; no Google Fonts call. `npm run fonts` copies them from Fontsource.
    _redirects                    # /pay/*  /pay  200   (not /pay.html: Pages answers /pay.html with a 308 to /pay,
                                  # which drops the token from the address; checked with wrangler pages dev)
    _headers                      # see below; %API_ORIGIN% is filled by build.mjs
    robots.txt                    # User-agent: *  Disallow: /
    .well-known/assetlinks.json   # Android App Links (package + SHA-256 cert placeholder)
  build.mjs                       # copies public/ to dist/; writes dist/tokens.css from design/spec/tokens.json and
                                  # typography.json (light on :root, dark under prefers-color-scheme), copies the 3D
                                  # icons it uses from design/icons/svg, writes dist/config.js from env API_BASE,
                                  # APP_DOWNLOAD_URL, PLAY_STORE_URL, APP_NAME (default: app_name in the app's
                                  # strings.xml) and fills
                                  # assetlinks.json from ANDROID_PACKAGE and ANDROID_CERT_SHA256. `--production`
                                  # refuses an http, local or placeholder API_BASE and needs ANDROID_PACKAGE; it
                                  # only warns when ANDROID_CERT_SHA256 is empty (placeholder fingerprint kept).
  wrangler.toml                   # the Pages project (name = the project name, not its host; output dist/). Also
                                  # stops `wrangler pages dev` from reading the Worker's ../wrangler.toml.
  scripts/                        # mock-api.mjs + preview-mock.mjs (every state without the Worker), seed-local.mjs
                                  # (seeds the local Worker and checks each link answer), copy-fonts.mjs, tokens.mjs
  test/                           # node --test + happy-dom; names avoid *.test.* so the Worker's vitest skips them
```

- `_headers` for `/*`: `X-Robots-Tag: noindex, nofollow`, `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY`,
  `X-Content-Type-Options: nosniff`, `Content-Security-Policy: default-src 'self'; connect-src <API origin>; img-src
  'self' data:; style-src 'self'; font-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`; for
  `/pay/*` also `Cache-Control: no-store`. `pay.html` also has `<meta name="robots" content="noindex, nofollow">` and
  `<meta name="referrer" content="no-referrer">`. The page has no inline script or style (the CSP forbids both).
- `pay.js`: reads the token from `location.pathname` (`^/pay/([A-Za-z0-9_-]{24})$`, else the not-found state),
  fetches `GET {API_BASE}/v1/links/{token}` without credentials, renders. No analytics, no cookies, no storage.
  Every name, description and group name is typed by app users and shown to whoever opens the link: render with
  `textContent` only (never `innerHTML` or template strings into HTML); the CSP, which allows no inline script, is
  the second line of defence.
- What the friend sees (design text): avatar initial and "{sender} sent you this", "{sender} split a bill with you",
  the hero card ("YOUR SHARE" ₹150 with "Swiggy · 16 Nov · Flat 302" when one expense is listed; with several, the
  total owed and the list: see 13), the expense row ("{description}, ₹450", e.g. "Swiggy, ₹450"; "Split equally between 3 people."), "No app
  or sign-up needed", the primary button "Pay ₹150 by UPI" and "Get {app name} (optional)" (the download link from config,
  `APP_DOWNLOAD_URL`, else the Play Store link, `PLAY_STORE_URL`; hidden with neither; the name
  is `app_name` from the app's `strings.xml` unless the build's `APP_NAME` says otherwise).
- Pay by UPI: `upi://pay?pa={upiId}&pn={toName}&am={rupees with 2 decimals}&cu=INR&tn={groupName}` (values
  URL-encoded, except that `@` in `pa` stays as is: it is legal in a query and some UPI apps do not decode `%40`;
  `tn` is left out for a direct split, whose `groupName` is null), one button per `owes` row with a UPI ID that
  matches the 4.6 pattern. Without one the button is not shown and the row reads "Ask {name} how to pay" (copy not
  in the design).
- Copy for what the design does not show (13.4), built from the app's own components: several expenses or a
  different total give the card "YOU OWE" with "{n} expenses · {group}" and an "Expenses" list of Expense Rows
  ("16 Nov · Arjun paid ₹3,600", your share, "your share"); a payee who is not the sender, or several payees, add a
  "Who to pay" list of Balance Rows ("you owe", "-₹1,150") and buttons "Pay {name} ₹X by UPI"; owed only shows a green
  "YOU ARE OWED" card, a "Who owes you" list ("owes you", "+₹200") and "Nothing to pay"; all square shows a neutral
  "YOU OWE ₹0 / All settled · {group}" card; link not working: "This link no longer works" / "Ask the person who sent
  it for a new link."; could not load: "Could not load this page" / "Check your connection and try again. Nothing is
  lost." with Try again. The footer with the Pay button stays on screen (sticky) when it holds one action.
- The friend can only look and pay. There is no "I paid" on the web (not in the design): the money-received SMS on
  the payee's phone clears it (flow lane 04/05; with simplified debts the payee may not be the sender), or the payee
  marks it paid.
- States: loading; ready (`owes` not empty); owed only (`owes` empty, `owed` not empty: the friend is owed money and
  has nothing to pay; copy not in the design, 13); all square (both empty); link not working (404, including
  expired); could not load (other errors, with Try again, wording from 02/23).
- Money format matches the app's `formatRupees` (Indian grouping, paise only when not zero).
- Local, in `server/web` after `npm install`: `npm run preview` (builds with `API_BASE=http://localhost:8787` and runs
  `wrangler pages dev dist --ip 127.0.0.1 --port 8788`; open `http://localhost:8788/pay/<token>`, not 127.0.0.1, since
  the Worker's development CORS allows `http://localhost:8788`). `npm run seed:local` makes links for every state on
  the local Worker; `npm run preview:mock` shows every state from fixtures without the Worker; `npm test` runs the
  tests. Deploy (owner, after the Pages project exists and the Worker is deployed; `docs/server-setup.md` 3 and 4):
  `PAGES_PROJECT=<name> API_BASE=https://<worker host> ANDROID_PACKAGE=<app.applicationId> ANDROID_CERT_SHA256=...
  [APP_DOWNLOAD_URL=https://...apk] [PLAY_STORE_URL=...] npm run deploy`, to the production branch `main`. `wrangler` is a devDependency of
  `server/web` too: run from `server/node_modules`, `wrangler pages dev` reads the Worker's `wrangler.toml` and fails
  on its Durable Object.
- When the app is installed, the App Link opens the app instead (9.8), so the same link is the invite.
- **Family invite** (design `06-family/28`, 4.18): a family link's data is `{ "family": true, "familyName",
  "senderName" }`. `parseLinkData` requires both names as text (else "Could not load this page"), `stateOf` gives
  `familyInvite`, and `renderFamilyInvite` draws, with `textContent` only: the sticker tile (brand soft, `heart_3d`),
  Display/L "{sender} invited you to {family}", Body/M "{family} is a family month on {app name}: one monthly total for
  the two of you.", a list card with the Info Row (`user_plus_3d`) "Get the app" / "Install {app name} on your phone,
  then open this link again to join.", and the footer button Primary Large "Get {app name}" only when the build has a
  download or Play Store link, the download link first (not the "(optional)" ghost button of the other states). Document title "Family invite". No amounts,
  no Pay button. `heart_3d` is in the build's icon list (`build.mjs` `ICONS`, copied from `design/icons/svg`).

## 8. Free-tier budget

Free limits per day: Workers 100,000 requests; D1 5,000,000 rows read and 100,000 rows written (index updates count
as writes); Durable Objects 100,000 requests and 13,000 GB-s of duration; Pages unlimited static requests. Limits that
are not per day: **D1 500 MB per database** (5 GB per account), **10 ms CPU per Worker request and per cron run**,
50 D1 queries and 50 subrequests per invocation. Estimate per daily active signed-in user (my own assumptions: 6 app
opens, 2 group writes, 1 group page visit with a socket, groups of ~4 people, half a link or invite view):

| Item | Worker req | DO req | D1 rows read | D1 rows written |
|---|---|---|---|---|
| Token refresh (1, 24 h access token, kept on the phone across processes, 9.2) | 1 | | 2 | 4 (session row + refresh index) |
| `/me/groups` (6) | 6 | | 6 × 10 | |
| Pulls (8 pages) | 8 | | 8 × 30 | |
| Writes (2: expense or settlement) | 2 | 2 (notify) | 2 × 12 | 2 × 9 (group, expense + version index, 3 shares, change, key, key purge) |
| Room session check on each notify (open sockets) | | | 2 × ~2 | |
| Pushes received (6, with Firebase set up: groups of ~4, 2 writes each a day) | 6 (one-group pull) | | 6 × 30 | |
| Link / invite views (0.5) | 0.5 | | ~100 (one recompute per group version) | 0.5 (cache row) |
| Socket open (1), and its pull of the group | 2 | 1 | 3 + 30 | |
| Device / profile (rare) | 0.2 | | 1 | 1 |
| Auth check on each request | | | ~26 | |
| **Total** | **~26** | **~3** | **~670** | **~24** |

So the first daily limits are Worker requests and D1 writes at about 4,000 daily users each (requests: about 5,000
without Firebase, whose pushes add the pulls); D1 reads allow about 7,500 and Durable Objects about 33,000. A refresh on
every cold start (a push, the periodic sync, the app reopened), as the first build did by keeping the access token in
memory only, would add about 20 written rows per user a day and bring the write limit down to about 2,200 users. The
daily cron adds a few full scans by time (idempotency keys, sessions, links, invite cache rows; tens of thousands of
rows a day at these numbers); purging a deleted group reads only that group's rows. When a daily limit is reached, requests fail until 05:30 India time;
nothing is billed. The phone treats that like being offline (outbox keeps everything).

**Storage is the wall that does not reset.** A group expense keeps about 1 KB (row, version index, ~4 shares, change
row, B-tree overhead; ids are 36-character strings). At 2 writes per daily user that is about 2 KB a day, so 500 MB
lasts roughly 8 months at 1,000 daily users and 3 months at 3,000. When the database is full every write fails,
sign-in included. Before release: watch the size (`wrangler d1 info xpense`, in `docs/server-setup.md`) and decide at
400 MB between Workers Paid (10 GB per database) and a second free database split by group id. Keep all group-owned
SQL in one module (`server/src/db/`) so that split touches one place. Trimming `changes` older than 60 days would save
about 13% but needs an index on `created_at` (one more written row per mutation); not done now.

**An import from Splitwise (4.17)** is the largest single action. 1,000 rupee rows (900 expenses of 3 shares and 100
payments) into a 3-person group, measured locally (`test/import.test.ts`) where it says so:

| Item | Worker req | DO req | D1 rows read | D1 rows written |
|---|---|---|---|---|
| Group create (`POST /v1/groups`) | 1 | 1 | ~10 | ~10 |
| Import, 20 requests of 50 items | 20 | 20 (one notify each) | **~11,300 measured** (about 11 per item: bump 1, insert 3, guard 6, id check 1; plus about 11 per request for the session, access and members) | **6,720 measured**: 900 × 7 (bump, row, version index, 3 shares, change) + 100 × 4 + 20 keys |
| Pull back from 0 (10 pages of 100 versions) and `/me/groups` | 11 | | ~5,000 | |
| Links on Group imported (2 friends) | 2 | | ~10 | 2 |
| **Total** | **~34** | **~21** | **~16,500 (0.3% of 5 M)** | **~6,730 (6.7% of 100,000)** |

The 20 requests take about 3 to 4 minutes: they count 100 against `RL_WRITE`'s 30 a minute (once per 10 items, 4.15),
and the phone waits each `429`'s `Retry-After`. The largest file the phone sends (5,000 items of 3 people, about 17
minutes) writes about 33,000 rows, a third of a day's writes; that is why the phone
caps a file at 5,000 items and the server a group at version 6,000. Storage is about 1 KB per expense, so about 1 MB
per 1,000 rows. CPU per import request: parse at most 32 KB, validate at most 50 items (with `computeShares`), build
about 200 statements; about 10 ms of wall time locally, and expected at 1 to 3 ms of CPU. Check it against the 10 ms
cap in the Workers dashboard after deploy. Since 2026-11-19 (open point 13.20) an import request counts once per 10
items against `RL_WRITE`, so a client that imports into new groups again and again writes at most about 2,100 rows a
minute and needs about 50 minutes for a day's writes, not 10 (4.15).

**CPU (10 ms).** Paths to watch: Google ID token check on a cold isolate (JWKS fetch and RS256), the FCM OAuth token
(RS256 signing, once an hour per isolate), a cache recompute for a big group, a 100-version pull page with many
shares, and an import request of 50 items (4.17). `wrangler dev` and the tests do not enforce the limit; after deploy, check CPU time in the dashboard's
Workers metrics. Durable Object duration stays far under 13,000 GB-s because hibernated sockets and protocol pings
cost nothing and `/notify` returns at once.

Choices that keep it there:

- No balance columns or counters: a write touches only its own rows; balances are computed on the phone, and on the
  server only for links, invites, removes and deletes, cached by group version (4.16) so readers cannot multiply the
  cost.
- Access tokens live 24 hours (every request checks the session row anyway); refresh only when one is needed.
- A deleted group's rows are purged by the cron at about 10,000 rows a day, never in one go.
- Pull only groups whose version moved (`/me/groups` first); pages of 100 versions; no polling (periodic sync every
  12 h only).
- WebSocket only on an open group screen; protocol pings; hibernation; one notify per write.
- Push data-only with `collapse_key` per group; no push to phones connected to the room or to the writer's phone; a
  `changed` push pulls only its group, and only when the phone is behind.
- The access token is kept (encrypted) on the phone, so a new process reuses it instead of refreshing (9.2).
- Few indexes; `WITHOUT ROWID` tables for expenses, settlements, shares, changes, cache and idempotency keys (no
  separate primary-key index to write).
- Profile changes and account deletion are set-based statements in one batch, with no per-group notify or push.
- Rate limits (4.15) slow a looping client so it eats the day's budget in hours, not minutes. The import route counts
  once per 10 items, so a loop of imports needs about 50 minutes (open point 13.20); one group also stops at version
  6,000.
- Watch in the Cloudflare dashboard; the paid plan (US$5/month) is the step if the app outgrows this.

## 9. Android side

### 9.1 Modules and packages

| Module / package | What lives there |
|---|---|
| `:splits` (`com.expenses.splits`) | Pure Kotlin: `SplitMath.shares(method, amountMinor, inputs: Map<String, Long?>)`, `Balances.nets(...)`, `Balances.simplify(nets)`, `DirectIds.groupId(uidA, uidB)`, `DirectIds.memberId(groupId, uid)` (UUID v5 written out with SHA-1: `java.util.UUID.nameUUIDFromBytes` is v3, MD5, and would give ids the server rejects). Tests read `shared/vectors/*.json`. |
| `com.expenses.app.groups.api` | `GroupsApi` (OkHttp + kotlinx.serialization, one function per route in section 4, plus `send` for stored outbox entries), DTOs (`@Serializable`, the JSON above), `GroupBodies` (bodies whose fields depend on the member kind), `ApiError(status, code, field, fromServer, retryAfterSeconds)`, `ApiResult<T>` (`Ok` / `Error`). |
| `com.expenses.app.groups.auth` | `AuthRepository` (also the `SessionProvider` of the API client), `TokenStore`, `KeystoreCipher` (a `ValueCipher`), `GoogleSignIn` (a `GoogleIdTokens`), `DevSignIn` (interface; `devSignIn(api)` is real in `src/debug`, a no-op in `src/release`), `AuthHooks`, `AuthInterceptor`, `TokenAuthenticator`. |
| `com.expenses.app.groups.data` | Room entities and DAOs for the `grp_*` tables, `GroupsRepository`, `MirrorWriter` (9.4), `GroupBalance` (6.2-6.4 on the phone), `GroupText` (4.6/4.7 text rules, phone numbers), mappers to and from the DTOs, `RestoreSnapshot`, read models. |
| `com.expenses.app.groups.sync` | `Outbox`, `SyncEngine` (5.1, 5.2; the worker only calls it), `SyncWorker`, `SyncScheduler` / `WorkSyncScheduler`, `LocalGroupData` (removing a group, sign-out, a different account), `LiveRoom` (WebSocket) and the `LiveGroup` composable, `GroupEvent`, `GroupNotifier` / `GroupNotifications`. |
| `com.expenses.app.groups.push` | `PushSetup` (manual Firebase init, 9.6), `GroupsMessagingService`. |
| `com.expenses.app.groups.match` | Phone-only SMS matching for settlements (9.5). |
| `:splitwise` (`com.expenses.splitwise`) | Pure Kotlin, no Android: reads a Splitwise export (`SplitwiseCsv.parse`, `fileHash`), the group name from the file name (`GroupNameFromFile`), "which one is you" (`SplitwisePeople`), the category table (`SplitwiseCategories`), and the item order and parts of an import (`ImportParts`). docs/splitwise-import.md 4. |
| `com.expenses.app.groups.splitwise` | `ImportPlanner` (a read file to the group's rows, members, items and parts, 4.17), `ImportBodies` (part bodies written out field by field; reading the item ids back), `ImportCategories` (the table, else `CategoryGuesser`). |
| `com.expenses.app.ui.screens.groups`, `.split`, `.settle`, `.signin`, `.join`, `.splitwise` | Screens (work D; `.splitwise` is Import from Splitwise and Group imported). |

`AppContainer` gains `auth: AuthRepository`, `groups: GroupsRepository`, `api: GroupsApi`, `sync: SyncScheduler`.
New dependencies (versions in `gradle/libs.versions.toml`, latest stable when added): `com.squareup.okhttp3:okhttp`,
`androidx.credentials:credentials`, `androidx.credentials:credentials-play-services-auth`,
`com.google.android.libraries.identity.googleid:googleid`, `com.google.firebase:firebase-messaging` (no
`google-services` plugin), `okhttp3:mockwebserver` for tests.

### 9.2 Auth on the phone

- **Token storage**: DataStore Preferences file `auth`, every value encrypted with AES-256-GCM using a key in the
  Android Keystore (alias `groups_auth_v1`, `KeystoreCipher`). Not EncryptedSharedPreferences (deprecated). Stored:
  `userId`, profile, `refreshToken`, `refreshExpiresAt`, `accessToken`, `accessExpiresAt`, `installationId` (made
  once, not encrypted is fine). The access token is stored so a new process (the app reopened after Android killed
  it, a push, the periodic sync) uses it until a minute before it expires instead of refreshing first: each refresh
  writes the session row (section 8). Storing it adds nothing an attacker with the store would not already have (the
  refresh token is there too), and every request still checks the session row, so sign-out ends it at once. Backups
  are already off for every domain.
- `AuthRepository`:

  ```kotlin
  val state: StateFlow<AuthState>                         // Loading (until the store is read) | SignedOut | SignedIn(userId, name)
  suspend fun isSignedIn(): Boolean                       // reads the store; for workers before state has loaded
  suspend fun profile(): UserDto?                         // name, phone, UPI ID as last stored
  suspend fun signInWithGoogle(activity: Activity, auto: Boolean): SignInResult
      // Success(isNew, name, phone) | Cancelled | Failed | NotConfigured (blank web client id; auto: Cancelled)
  suspend fun signInDev(devUser: String, name: String): SignInResult               // debug builds only
  suspend fun saveProfile(name: String, phone: String?, upiId: Patch<String?> = Unchanged): ApiError?
      // one PATCH /me, and SettingsRepository.setUserName(name); Account and Your name run it so it finishes even
      // when the screen goes
  suspend fun setUpiId(upiId: String?): ApiError?
  suspend fun signOut(force: Boolean = false): SignOutResult    // UNSENT_CHANGES (nothing done) unless force | DONE
  suspend fun deleteAccount(): ApiError?
  suspend fun token(): TokenResult                        // refreshes when under 60 s left; one refresh at a time (Mutex)
      // Valid(token) | SignedOut (no refresh token, or the server answered 401) | Unavailable(error) (offline, 5xx,
      // not our JSON, 429: the session stays; never a 401)
  ```

- `AuthInterceptor` adds `Authorization` and `X-Installation-Id`. `TokenAuthenticator` refreshes once on
  `401 token_expired|unauthorized`. A refresh that fails for a passing reason is **not** a sign-out either: the API
  client answers the call with that error (offline or `internal`; through `TokenAuthenticator`, the call fails as
  offline), so the sync engine retries and screens say to check the connection. Only no stored refresh token, or a
  `401` to the refresh, gives `ApiError.signedOut`. `refresh_invalid` is **not** a sign-out: the phone forgets the tokens and shows
  Sign in where sign-in is needed, but keeps the `grp_*` tables and the outbox ("Nothing is lost", 02/23). Signing in
  again as the same user resumes the outbox; as a different user, the 9.2 "New account on this phone" rule runs and
  the old outbox is dropped.
- **Credential Manager**: when the Sign in screen opens, the account sheet may appear by itself (`auto = true`,
  errors there are silent). It asks twice, as Android's guide describes: first
  `GetGoogleIdOption.Builder().setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID).setFilterByAuthorizedAccounts(true)
  .setAutoSelectEnabled(true).setNonce(nonce)`, with auto-select off after the person signed out on this phone (a
  stored flag, cleared by the next sign-in); on `NoCredentialException`, again with `setFilterByAuthorizedAccounts
  (false)`, no auto-select and the same nonce (the nonce is good for 5 minutes and used up only by a sign-in). The
  "Continue with Google" button uses `GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID)
  .setNonce(nonce)`. The nonce comes from `POST /v1/auth/nonce`. Take
  `GoogleIdTokenCredential.createFrom(credential.data).idToken` and send it to `/v1/auth/google`. A blank
  `GOOGLE_WEB_CLIENT_ID` (no Google project yet) gives `Failed` at once.
- **Forgetting the Google session**: on Sign out and Delete account the phone calls
  `CredentialManager.clearCredentialState(ClearCredentialStateRequest())`, so every credential provider drops this
  app's session. A failure there is dropped, not logged, and never stops the sign-out. A refused refresh token
  (`refresh_invalid`) is not a sign-out the person chose and does not call it.
- **Dev sign-in**: only when `BuildConfig.DEV_SIGN_IN` (true in debug, false in release) and only from
  `src/debug`; the debug Sign in screen shows an extra text button "Dev sign-in" under Not now that asks for a name
  (debug only, not in the design). Release builds contain no code that calls `/v1/auth/dev`.
- **Sign out** (Account): flush the outbox (up to 10 s); call `/v1/auth/signout`; clear tokens, `grp_*` tables,
  outbox and links; remove every "Groups" notification (9.7); cancel group work; then `clearCredentialState`; keep
  personal expenses, including split mirrors. If the outbox could not be flushed, warn first (copy not designed; see
  13). Account deletion and another account signing in remove the notifications the same way.
- **New account on this phone**: when a sign-in returns a `userId` other than the last one stored, delete mirror rows
  with `source = GROUP` and unlink `groupExpenseId` on the others before syncing.
- **Delete account**: `DELETE /v1/me`, then `clearCredentialState`, then `ActivityManager.clearApplicationUserData()`
  (flow 09: "The app is as new", back to Welcome). `clearCredentialState` comes first because clearing the app data
  ends the process, and nothing after it runs.

### 9.3 Room tables (same `AppDatabase`, separate tables)

Version 2 with an `AutoMigration(1 → 2)`. Group ids are `String`. Money `Long` paise. Dates `LocalDate`.

| Table | Columns |
|---|---|
| `grp_groups` | `id` PK, `kind` (`GROUP`, `DIRECT`), `name?`, `ownerUserId?`, `myMemberId`, `version: Long` (last applied), `latestVersion: Long` (the server's version as last reported, written before each pull: `observeLoad` is LOADING only while `version` is 0 and `latestVersion` above 0, so a later pull, or one cut off offline, keeps the stored data on show), `loadFailed` (last pull failed: FAILED), `syncState`, `createdAt` |
| `grp_members` | `id` PK, `groupId` (FK cascade), `userId?`, `name`, `phone?`, `upiId?`, `onApp`, `removed`, `version` |
| `grp_expenses` | `id` PK, `groupId` (FK cascade), `description`, `category: Category`, `amountMinor`, `occurredOn`, `paidByMemberId`, `splitMethod`, `auto`, `createdByMemberId`, `updatedByMemberId`, `updatedAt`, `deleted`, `version`, `syncState` (`SYNCED`, `PENDING`, `REJECTED`). Index `(groupId, occurredOn)`. |
| `grp_expense_shares` | PK (`expenseId`, `memberId`), `shareMinor`, `input: Long?`; FK cascade on expense |
| `grp_settlements` | `id` PK, `groupId` (FK cascade), `fromMemberId`, `toMemberId`, `amountMinor`, `method`, `matchedFromSms`, `occurredOn`, `recordedByMemberId`, `confirmedAt?`, `deleted`, `version`, `syncState` |
| `grp_outbox` | `seq` PK auto, `idempotencyKey`, `groupId?`, `entityKind`, `entityId`, `op` (`CREATE`, `UPDATE`, `DELETE`, `CONFIRM`: an expense create and update are both `PUT`), `method`, `path`, `bodyJson`, `restoreJson?` (5.1), `createdAt`, `attempts`, `failures` (5xx in a row; 5 makes the entry stuck, 5.1 step 3), `lastErrorCode?` |
| `grp_links` | `memberId` PK, `groupId`, `linkId`, `token`, `createdAt`, `expiresAt` (links this phone made; id and token made here, 4.10) |
| `grp_split_rules` | `merchantKey` PK (`CategoryGuesser.merchantKey`), `groupId`, `splitMethod`, `inputsJson`, `auto` (the "Do this every time" switch and the "Last time you split Swiggy with Flat 302" hint) |
| `grp_pending_upi` | `id` PK, `groupId`, `toMemberId`, `amountMinor`, `startedAt` (Pay by UPI started, 9.5) |

Personal `expenses` gains `groupExpenseId: String?` (unique index) and `ExpenseSource.GROUP` (appended; never rename
constants).

Version 3 (automatic migration, for server migration 0002): `grp_groups.ownerMemberId` (`Group.ownerMemberId`, the
"Owner" label); `grp_members.joinedAt` and `undoUntil` (from `Member`; `canUndo` is never stored, the banner rule is
`GroupDetail.joinsToUndo(now)`: the person owns the group, the member is on the app, not removed, not the person, and
`undoUntil` is after now) and `placeName` (phone only: the friend's name the place had before someone joined it by
link, so the banner can read "Vikram joined · Not Priya?"); and `grp_rejected` (`groupId` FK cascade, `entityKind`,
`entityId`, `op`, `method`, `path`, `bodyJson`, `restoreJson`, `errorCode`, `rejectedAt`): a change the server refused,
kept after its rollback so the Group page can show "Not saved" with Try again and Dismiss (02-everyday/43).

Version 4 (automatic migration): `grp_link_priors` (`groupExpenseId` PK, `groupId` FK cascade, `expenseId`, `status`):
how a personal payment was just before a split linked it (Split on a payment: its status then; Add expense paid by
me: ADDED; a restore link, 9.4: DETECTED or ADDED). The first link of a payment is kept. Only an undone join reads it
(9.4). Rows go with their group.

Version 5 (automatic migration, 2026-11-19, Import from Splitwise): `grp_imports` (`groupId` PK, `fileHash` (hex
SHA-256 of the file, indexed; on this phone only, for "This file already came across"), `groupName` (as imported, so
"did not come across" can name a group that is gone), `expenseCount`, `itemCount`, `sentItems`, `state` (`SENDING`,
`DONE`, `FAILED`, by name), `createdAt`). No foreign key: a `FAILED` row outlives the group it removed. Sign-out and
another account clear it, and Delete group or Leave group on this phone forgets that group's row, whatever its state
(parts that still waited go with the group, so the row could never leave `SENDING`). The outbox gains `entityKind = IMPORT` (appended; stored by name, so no column changes). A
group with a non-failed import row is never shown as LOADING: all its data was written here, and its first pull may
wait minutes for the parts (429 waits).

Version 6 (automatic migration, Family, docs/family.md 6.1; 5 is Splitwise import's): personal `expenses` gains
`familyExpenseId: String?` (unique index; the family expense this payment is shared as, on the payer's own row only),
`category_rules` gains `scope: ExpenseScope?` (the "Do this every time" choice of Just me or Family, null when the rule
says nothing), and `grp_groups.kind` may be `FAMILY` (stored by name; no column change). Unknown kinds from the server
still read as `GROUP`.

### 9.4 Personal payments and group expenses

The month rule in `docs/architecture.md` stays as written (sum of `myShareMinor` of ADDED debits); group data reaches
it through personal rows:

- **Split on my payment** (Home card, notification, Review payments card, Add to expenses "Split it instead",
  Expense detail "Split this expense"): only from Split, never on Add. One Room transaction: create the group expense
  (payer = my member) with shares and an outbox row; set the personal row `status = ADDED`, `myShareMinor` = my
  share, `groupExpenseId` = the new id. Only description (the counterparty), category, amount, date and the shares
  go to the server. Signed out: Sign in opens first; Not now leaves the payment as it was (DETECTED stays waiting).
- **Undo** on "Split. Sorted.": delete the group expense (5.1 coalescing) and put the personal row back as it was
  (status, `myShareMinor = amountMinor`, `groupExpenseId = null`).
- **Someone else paid and I have a share** (synced, or Add expense with "Paid by Rahul"): upsert a mirror personal row
  `source = GROUP`, `status = ADDED`, `direction = DEBIT`, `amountMinor` = total, `myShareMinor` = my share,
  `counterparty` = description, `category`, `occurredAt` = `occurredOn` 00:00 India time, `paidWith = OTHER`,
  `accountId = null`, `groupExpenseId`. Activity shows it as "Food · your share of ₹450".
- **Add expense paid by me with no SMS** (02/15 with Split): a personal row `source = MANUAL` linked the same way as an
  SMS payment.
- **Sync changes** to a linked expense update the personal row (amount, my share, category, description, date).
  An SMS row keeps what the bank said (amount, payee, time, original message): only its share and category follow
  the group expense. Typed (MANUAL) rows and GROUP mirrors follow every field.
  - A `GROUP` mirror (someone else paid) is deleted when the group expense is deleted or my share becomes 0.
  - An SMS or MANUAL row (I paid) keeps `myShareMinor` = my share while I am the payer, **even when my share is 0**
    (I paid for the others; they owe me all of it, and counting the full amount would count it twice). It is
    unlinked with `myShareMinor = amountMinor` (my payment again, unsplit) only when the group expense is deleted or
    its payer changes away from me; in the second case a `GROUP` mirror is made if I have a share.
- **Rolled back or dropped** (4.1 roll back, a group that disappeared before its expense was sent, sign-out or
  another account signing in with a split still unsent): the personal row goes back to the `status`, `myShareMinor`
  and `groupExpenseId` saved in the outbox entry's `restoreJson` (a row the change linked meanwhile, such as a mirror
  made when the payer changed away from me, gives the link back), then follows the group expense that stays. Otherwise
  the month would count only my share of a split nobody else sees.
- **Restored from the server** (new phone, reinstall, signing in again): a pulled expense paid by my member that no
  local row links is linked to the one local SMS or MANUAL debit with the same amount and an `occurredAt` within a
  day of `occurredOn`, DETECTED or ADDED and not linked (it becomes ADDED with my share). With none, or more than one,
  a `GROUP` mirror is made. Without this, the payment found again by "Scan SMS on this phone" (flow 01) would wait as
  a new payment and count in full when added, on top of the split.
- **Edits on Expense detail** of a linked row (category, what it was for) edit the group expense (outbox), which then
  updates the row. A personal note stays personal.
- **Delete expense** on Expense detail of a linked row deletes the group expense too (outbox), after a confirm that
  names the group (copy not designed, 13). Deleting only the personal row would leave friends owing for it, and a
  `GROUP` mirror deleted alone would come back with the next sync.
- **Leaving, removal or group deletion** keeps the mirrors: the spending happened. A split is never counted twice: a
  group expense is linked to at most one personal row (unique `groupExpenseId`).
- **An undone join** (4.6) forgets them: the person who joined by a friend's link was never that friend, so the
  spending of the place was not theirs (joining pulled the group from 0, which made mirrors for every share the place
  had and could link their own payment of the same amount to one the place paid). The server says why the person
  lost the group: `lost` in `/me/groups` (4.5) and the `reason` of `403 not_member` (4.1). Only `join_undone` forgets
  the splits: before removing the group the phone deletes the `GROUP` mirrors of its expenses and unlinks the
  person's payments from them (`groupExpenseId = null`, `myShareMinor = amountMinor`), each put back to the status it
  had just before the split linked it (`grp_link_priors`, 9.3): a payment that was waiting (DETECTED) waits again
  for Add, Split or Ignore, so it neither counts in full without the person deciding nor counts twice; one already
  added stays added, in full; a payment typed in with Add expense and Split stays the person's own expense, in full
  (as a refused one does). With no remembered status (a link made before `grp_link_priors` existed, or one a
  rollback put back), a payment read from an SMS waits, and one the person typed in stays added, in full: it was
  never detected, and a waiting typed row would show as "Payment detected". `removed`, `left`, `deleted`, no reason
  (a loss over 90 days ago, an older server) and `410 group_deleted` keep the mirrors and links, also inside the 7 days
  of a join: the spending happened. Before 2026-11-18 the phone guessed from the join window and took a removal or a
  deletion inside those 7 days for an undo.
- **Imported from Splitwise** (4.17; docs/splitwise-import.md 5.5, decision 11): every imported expense follows the
  rules above as it is written, in the import's own transaction: someone else paid and I have a share, a `GROUP`
  mirror counts my share in its month; I paid, the one SMS or typed debit of the same amount within a day is linked
  (as a restore), else a mirror counts my share. Rows dated before tracking began still get personal rows, as a join
  from version 0 does. A refused import, or a sign-out (or another account) with parts unsent, forgets them
  (`MirrorWriter.forget`: mirrors go, linked payments go back to their remembered status), since nobody will ever see
  that group. Risk: a Splitwise expense I paid with no matching SMS within a day becomes a mirror while the SMS
  payment may already count in full.
- **Recurring rule** (`grp_split_rules.auto`): a new DETECTED payment whose merchant key has an auto rule is split by
  `IngestPipeline`'s caller right after detection, with a notification ("Split with Flat 302") as flow lane 03 says;
  the expense has `auto = true`.

### 9.5 Settling up on the phone

- **Pay by UPI** (03/00): open `upi://pay?...` with the friend's `upiId`; insert `grp_pending_upi`. When a DEBIT SMS of
  that exact amount arrives within 30 minutes (and, when the SMS shows the payee's UPI ID, it is the friend's), record
  the settlement (`method = UPI`, `matchedFromSms = true`) and
  set that payment to IGNORED (repaying a friend is not spending; see 13), then delete the pending row. Only a debit
  paid by UPI, or whose SMS names no channel (some banks' UPI SMS name neither UPI nor a UPI ID), counts: a card, ATM,
  wallet, cash or bank transfer debit of the same amount is spending. Without the
  friend's UPI ID (friends without the app, users who have not set one), Pay by UPI is disabled and "Paid another way"
  remains.
- **Money received** (03/02, 03/03): a new CREDIT expense, at most a day old (a credit read late, when the app catches
  up, may predate the debt), is compared, on the phone, with transfers where someone owes
  me exactly that amount. One candidate whose name matches the SMS counterparty (case-insensitive word match): record
  the settlement(s) at once and post "All square with Rahul". Same amount, unclear name: show Money received ("Was this
  Rahul paying you back?"); "Yes, clear it" records it. Several groups: one settlement per group for each transfer.
  Money into an account the person switched off is left alone (no settlement, no notification, Money received shows
  nothing), as everywhere else in the app. Only the settlement goes to the server; the SMS stays on the phone.
- **One payment, one settlement.** When both people have the app, both phones see a UPI repayment: the payer's debit
  and the receiver's credit (or "Yes, clear it"). Every settlement matched from SMS takes an id both phones work out
  the same way, `UUID.nameUUIDFromBytes("sms-settlement/{groupId}/{fromMemberId}/{toMemberId}/{amountPaise}/{SMS
  date}")` (version 3, lower case; the SMS date is the day the SMS arrived, India time), with `/{n}` added for the
  n-th equal repayment of that pair and day (n = 1, 2, …): the phone takes the first n whose id no settlement on it
  has, a deleted one included. Both phones hold the same earlier settlements (their own record or the one they pulled),
  so they pick the same n, and `occurredOn` is the SMS date on both. So both records are one settlement: the second
  `PUT` gets `200` with the stored settlement (and confirms it when it is the receiver's, 4.8), also when the two
  name the method differently: the payer's debit says `UPI`, the receiver's credit often names no channel and says
  `OTHER`. The stored method stays and both phones end with the server's copy; nothing is rolled back, and the
  receiver gets no Confirm prompt for a payment their own phone matched. (Before 2026-11-18 that case got `409
  exists_different`: the second record was rolled back, the payer saw "Not saved", and the receiver was asked to
  confirm.) A settlement already on the phone is never replaced by a new record (`recordSettlement` refuses a stored
  id), so a rollback never removes a settlement the server has.
- **The receiver's record first.** When the receiver's record reaches the payer's phone before the payer's debit SMS
  is read, and it is the receiver's own SMS match (`matchedFromSms`, recorded by the friend, confirmed at or after the
  Pay by UPI tap, less 1 minute for clocks), the debit is that payment: it is set to IGNORED, the pending row goes,
  and nothing new is recorded. When the receiver's phone already has the payer's record when its credit SMS is read,
  the friend no longer owes that amount, so nothing is matched and nothing new is recorded (the receiver can confirm
  the payer's record).
- **Known limits** (accepted, not fixed):
  - The receiver's record is recognised on the payer's phone only when it was matched from SMS. If the friend instead
    marked it as paid by hand (Mark as paid or Money received by hand: a new id, `matchedFromSms = false`) after the
    tap and before the payer's debit SMS is read, the payer's phone finds the balance already clear: it records
    nothing (right, one settlement) but leaves the debit waiting as a payment (DETECTED), and the person must Ignore
    it (Add would count a repayment as spending). A record typed by hand carries no time of its own on the phone
    (`grp_settlements` has no `createdAt`), so it cannot be told apart from an earlier repayment of the same amount.
  - A payment made from a UPI app without the app's Pay by UPI has no pending row, so the payer's debit is not matched
    (it waits as a payment); the receiver's credit match still records the settlement.
  - The payer's debit and the receiver's credit read on either side of midnight (India time) give two ids, so two
    settlements, and the repayment counts twice in the balance, but only when neither phone had the other's record
    when it read its own SMS (each case above stops it otherwise). Either party can delete one (4.8). Merging them on
    the server would answer a phone with a settlement of another id than the one it stored.
  - Two equal repayments between the same two people in one group on one day, the first matched only on the payer's
    phone and the second only on the receiver's (each phone without the other's record when it matched), take the
    same n and count once.
- **Mark as paid** (03/00 "Paid another way", 03/01): settlement with `method = CASH` or `OTHER`, recorded by the
  receiver or by the payer; the other party, if on the app, gets a notification with a Confirm action ("Rahul can
  confirm" on both screens, 4.8).

### 9.6 FCM client without `google-services.json`

- No `com.google.gms.google-services` plugin. Manifest: remove the auto-init provider
  (`<provider android:name="com.google.firebase.provider.FirebaseInitProvider" android:authorities="${applicationId}.firebaseinitprovider" tools:node="remove" />`)
  and set meta-data `firebase_messaging_auto_init_enabled` to `false`.
- `PushSetup.init(context)` in `ExpensesApplication`: if `FCM_PROJECT_ID`, `FCM_APPLICATION_ID`, `FCM_API_KEY` and
  `FCM_SENDER_ID` are all non-blank, `FirebaseApp.initializeApp(context, FirebaseOptions.Builder()...build())`.
  Otherwise do nothing: the app runs on foreground syncs, the socket and the 12 h periodic sync. Every
  `FirebaseMessaging` call is behind `PushSetup.enabled`.
- **Token registration** (`PushSetup.register`): enable auto-init, get the token and `PUT /v1/devices/{installationId}`.
  It runs on sign-in and **on each app start while signed in** (in `ExpensesApplication`, next to
  `schedulePeriodic`), as Firebase advises when auto-init starts off. Without the start-up call, a phone that signed
  in before this build had push, or whose `PUT` failed, would never send a token. The service also sends a new token
  from `onNewToken` and `onRegistered`.
  - The token the server last accepted is stored with its user id (`TokenStore`). The same token for the same user is
    not sent again: a push, a worker or the SMS receiver can each start the process, and each `PUT` is a D1 write.
  - The token is stored only when the `PUT` answers `Ok`. A failed `PUT` stores nothing, so the next start sends it
    again.
  - The stored token is forgotten on sign-out, on account deletion and when another account signs in, so the next
    token is sent.
  - A failed `getToken` needs no extra code: auto-init is saved as on before the call, and Firebase's own sync then
    delivers the token through `onNewToken` or `onRegistered`.
- `GroupsMessagingService.onMessageReceived`: for `changed`, `SyncScheduler.requestGroupPull(g, v)`, which pulls group
  `g` only and only when `v` is above the local version (one request, not `/me/groups` and every group; a group the
  phone does not know gets a full sync, and a failed pull hands over to the one main sync); for `groups`,
  `requestSync(expedited = true)`; for `remind`, `requestReminder(g, m)` (a sync, then the reminder notification from
  the phone's own data). It never shows text from the message (there is none).
- firebase-messaging 26 deprecates `getToken` / `deleteToken` / `onNewToken` in favour of `register()` and
  `onRegistered`. `PushSetup` still uses `getToken` (it gives the token at once) and the service sends the token from
  both `onNewToken` and `onRegistered` (the `PUT` is idempotent).

### 9.7 Group notifications

Channel "Groups". After a pull, for changes by others that involve me: expense created ("Rahul added Swiggy · you owe
₹150"), settlement created to me ("Priya marked ₹1,200 as paid", with Confirm when I am the other party), settlement
confirmed, joined ("Priya joined Flat 302", with the joining account's own name, shown to every member: it is how a
wrong join through a forwarded link gets noticed, 4.10), reminder ("Arjun reminded you: ₹1,150 for Flat 302", the
name from the push's `m`). Strings in `strings_notify.xml`. Not shown for the group whose page is open, for changes
over 24 hours old, or on a pull from version 0 (5.2). Amount and names only, as with payments.

- **Names** follow the screens' rule (`shortName`, the design's first names in sentences): the first name, or the
  whole name when another member of the group has the same first name. "{name} joined" follows the owner's banner
  (02-everyday/40): whole when the account that took a friend's place shares the friend's first name but not the
  whole name ("Rahul Kumar" in "Rahul Verma"'s place), so a wrong join still shows; "Priya Nair" in the place of
  "Priya" is "Priya".
- **One bundle.** Every "Groups" notification (these, and the ones the settle-up matcher posts, 9.5) carries one
  group key, under one summary. The summary is posted together with every notification, the first one too: Android
  shows one alone without its summary. Its tap opens the app as it was left and does not clear what is under it (not
  auto-cancelled; a summary tap that cancels takes every notification under it along). Without the app's own summary,
  Android 16 bundles two or more by itself under a summary of its own, and a tap on that bundle clears them all: an
  unanswered "Was this Priya paying you back?" was lost that way. Each notification still goes on its own tap, its
  action or a swipe.
- **The summary goes with the last notification**, and an empty one never stays. Android leaves the summary of an
  empty bundle on show when the app removes the last notification, and its shade is not relied on to take the summary
  along when the person does (not checked on every Android version from 8 up). So the app does it and is told of every
  removal: its own (Confirm, an answered question, sign-out); a swipe or "Clear all" (each notification's delete
  intent, to `GroupActionReceiver`); and a tap (the intent that opens the app, which `MainActivity` hands to
  `GroupNotifications.removedByPerson`). Each notification's tap and swipe intents carry its own id (the id is also
  their request code, so two notifications that open the same group keep an intent each), and that notification counts
  as gone at once. A tap therefore does not wait for a sync: none follows offline, within 30 seconds of the last one,
  or while the app is on screen already. The tidy after each sync stays as a net. A tidy only ever removes the
  summary: it is posted with a notification and never by itself, so nothing can bring an empty one back. The summary
  makes no sound of its own.
- **The system's list is behind.** `notify()` and `cancel()` return before Android has applied them, so
  `activeNotifications`, read right after one of the app's own calls, misses what was just posted and still shows
  what was just removed. `GroupNotifications` never decides from that list alone: for five seconds it believes its own
  calls over it (`RecentCalls`: a notification posted counts as there until the list shows it, one removed, by the
  app or by the person, counts as gone), and one change of the bundle runs at a time. Erring towards "still there"
  is the safe side: Android cancels every notification under a summary that is cancelled. Before, the summary came
  only from the second notification on and was counted from that list, so a notification posted a while after the
  first one never got it; and the tidy that follows a sync could run before the list showed what that sync had
  posted.
- **Removed on sign-out**, account deletion and another account signing in (`LocalGroupData.clearAll`): they name
  other people, and their links and Confirm would point at data that is gone.
- **Families** (4.18): only "{name} joined {family}" (the joined notification, HIGH, with the family's own name); it
  opens Home on the Family side. A family expense, a leave or a removal makes no notification: their pushes are
  NORMAL and only start a background sync (about 60 family expenses a month would be noise, and Android lowers apps
  whose HIGH pushes show nothing).

### 9.8 BuildConfig, manifest and links

| Field | Debug | Release |
|---|---|---|
| application id | `app.applicationId` + `.debug` (version name + `-debug`), so it never replaces the released app | `app.applicationId` |
| `API_BASE_URL` | `"http://10.0.2.2:8787"` | gradle property `app.apiBaseUrl` (committed: the public server) |
| `LINK_HOST` | `app.linkHost` (unchanged) | same |
| `GOOGLE_WEB_CLIENT_ID` | `app.googleWebClientId` (committed: the public server's web client) | same |
| `FCM_PROJECT_ID`, `FCM_APPLICATION_ID`, `FCM_API_KEY`, `FCM_SENDER_ID` | `app.fcm.projectId`, `app.fcm.applicationId`, `app.fcm.apiKey`, `app.fcm.senderId` (placeholders empty) | same |
| `DEV_SIGN_IN` | `true` | `false` |

Committed `gradle.properties` holds the public server's `app.apiBaseUrl`, `app.googleWebClientId` and
`app.linkHost` (none is secret), so a release build works from a clean clone; the four `app.fcm.*` values stay empty
there, so a self-built app has no push. `~/.gradle/gradle.properties` or `ORG_GRADLE_PROJECT_...` environment
variables override them (docs/server-setup.md). A release build fails while any of `app.apiBaseUrl`,
`app.googleWebClientId` or `app.linkHost` holds a placeholder. `xpense.app` (the old placeholder link host) is a
domain nobody on this project owns: a link built with it would send a friend's token to a stranger's server. Links
from a debug build must not be sent to real people. Debug builds sign in with Dev sign-in against the local server;
Google sign-in and push in a debug build would need a Google Android client and a Firebase app for the `.debug` id.

- `INTERNET` and `ACCESS_NETWORK_STATE` permissions. Main `network_security_config` forbids cleartext;
  `src/debug/res/xml/network_security_config.xml` allows cleartext for `10.0.2.2` only (the debug `API_BASE_URL`;
  `localhost` was dropped on the lead's instruction, 2026-11-17).
- The App Link intent filter (manifest placeholder `linkHost` from `app.linkHost`) and the `JoinGroup(token)` route
  are in place (2026-11-17 scaffold); Join group's screen is built by stage 2 (docs/groups-build-plan.md).
- App Link: intent filter `autoVerify="true"`, `https`, host `${linkHost}` (manifest placeholder from
  `app.linkHost`), path prefix `/pay/`. `MainActivity` accepts only `/pay/[A-Za-z0-9_-]{24}` and opens route
  `JoinGroup(token)` (02/32). Signed out: Sign in first, then Join group.
- The token is never logged, and the URL is built only from `LINK_HOST`.

### 9.9 Family on the phone

The full rules are docs/family.md 6 to 8; the server's side is 4.18. In short:

- `ExpenseScope.FAMILY` stays the person's own tag on a payment. A row is shared exactly while it is `scope =
  FAMILY`, `ADDED`, a rupee `DEBIT`, not split (`groupExpenseId` null), within the server's limits (amount and date
  of 4.7), and a family is on the phone and loaded. `FamilyLinker` (`groups/data/FamilyLinker.kt`) keeps the link
  `expenses.familyExpenseId` and writes the outbox entries (`PUT` and `DELETE` of 4.18, no personal snapshot): it is
  told by `ExpenseRepository` after every write that can change whether a row qualifies or what it sends, in the same
  transaction, and by `GroupsRepository.splitPayment` (splitting a shared Family payment takes it out of the family
  month; undoing the split shares it again).
- What it sends is 4.18's list only: `description = GroupText.descriptionFor(payee, category name)`, category,
  `amountPaise`, the India date of the payment, the person's member as payer and only share, `EQUAL`, `auto: false`.
  Never the note, account, card, "paid with", SMS text or sender, or the time.
- `reconcile()` (5.2) shares the earlier Family rows on set-up and join; Set up Family and Join Family say how many
  first ("Your 12 Family expenses on this phone are shared too."). A row is shared only once the family's data is
  complete (pulled up to the newest version, last pull not failed): before that, the person's own family expense for
  the payment (shared by their old phone) may not be pulled yet, and sharing it again would count it twice. Taking a
  shared row out and its edits still go while a pull runs.
- Pulled family expenses never become personal rows on the partner's phone. On the payer's own phone a pulled own
  expense links the matching payment (or a `GROUP`-source mirror on a new install); a tombstone unlinks it with scope
  Just me (taken out on another phone of the same person).
- A refused family expense (`onRejected`) leaves the payment Just me and unlinked; nothing goes to `grp_rejected`.
- Leaving, being removed, the family deleted or `lost`: the links go (`unlinkFamily`) and the rows stay tagged Family
  locally. Another account signing in: every link goes (`unlinkAll`). Sign-out keeps the links, so the same account
  finds them again.
- Families never show in Groups, Friends, totals, split labels, known people, settle-up or reminders;
  `CreditMatching` and `SettlementMatcher` skip them. Home's Family side shows the family month (total, by
  person, top categories, latest) from the pulled family expenses at full amounts, India month bounds.
- Invite: `FamilyRepository.inviteLink` re-adds the placeholder when there is none (4.18), makes the placeholder's
  link (7 days, made again after 3) and opens the system share sheet with the invite message (docs/family.md 1.3).
  The invite's place is the member not on the app named `"Partner"`. A partner who deleted their account leaves a
  "Former member" place with no link: the phone shows "Invite your partner" (just you), and Invite partner removes
  that place before it adds a new placeholder (it counts toward the 2 members).
- `409 already_member` on Join Family (or a preview that says so) asks for a sync, so a family joined on another
  phone comes to this one, and Home opens on the Family side.

## 10. Security and privacy checklist

- [ ] No SMS text, SMS sender, account, card, bank, bill, note, "paid with", exact time or personal (unsplit) expense
      in any request. A unit test on the DTOs and a review of every `GroupsApi` call.
- [ ] Phone number and UPI ID only if the user typed them; shown only to members of their groups; the phone number
      never in the link page. The UPI ID is on the link page for whoever owes that person (it may contain a phone
      number; the field must say so, 13). The SMS-detected UPI ID is never uploaded without the user choosing it.
- [ ] Expense descriptions pre-filled from SMS never carry a UPI ID or a long digit run (4.7).
- [ ] Friends without the app: their name and phone are stored on the server and shown to the group, typed by
      someone else. The privacy policy must say so (13).
- [ ] No lookup of users by phone or email.
- [ ] Google ID token verified (signature by Google's JWKS, issuer, audience, expiry, nonce); nonce single-use
      (`sessions.nonce_hash`); JWKS URL fixed in code.
- [ ] Session revocation checked on every request, and by the live room before each message (4.12); refresh tokens
      hashed, rotated, reuse after the 10-minute lost-answer window detected.
- [ ] Dev sign-in impossible in production: var + host check + config check script + tests; absent from release APK.
- [ ] Every group route checks active membership; role rules of 4.2; tests for each route as a non-member, and for
      idempotent replays with content after the caller left (4.3).
- [ ] Group and member ids are random v4, except the checked deterministic direct-split ids (4.6).
- [ ] Every mutation batch carries the 3.1 guard: no write lands in a deleted group, in another group's entity, or
      on a removed member.
- [ ] Share tokens: 144 bits of randomness, stored hashed, revocable, expire after 90 days, path not query,
      `noindex`, `no-referrer`, no third-party requests on the page (fonts self-hosted), CSP, `textContent`-only
      rendering, same 404 for revoked, expired and unknown. The invite risk of a forwarded link is written down (4.10).
- [ ] Inputs validated with limits (4.7, 4.15); body size limit; parameterised SQL only.
- [ ] Rate limits on auth, public link and per user.
- [ ] Secrets only as Wrangler secrets / `.dev.vars` (git-ignored): `SESSION_SECRET`, `FCM_SERVICE_ACCOUNT_JSON`.
      Add to `.gitignore`: `server/.dev.vars`, `server/node_modules/`, `server/.wrangler/`, `server/web/dist/`,
      `server/web/node_modules/`, `server/web/.wrangler/` (`server/web/.gitignore` already lists the last three).
      The service account key is never kept in the repository: it goes from `~/Downloads` straight into `wrangler
      secret put` and is deleted (`docs/server-setup.md` 6.6). Google does not document the download's file name, so
      `.gitignore`'s `*service-account*.json` and `xpense-515014-*.json` are a best-effort guard only.
- [ ] Push payloads carry ids and a version only.
- [ ] Phone: tokens encrypted with a Keystore key; backups off (already); cleartext only in debug to the emulator
      host; tokens and API bodies never logged (no OkHttp logging interceptor in release; in debug, level `BASIC`,
      which logs no bodies or headers).
- [ ] Account deletion removes the user row, sessions, devices, phone, UPI ID, and anonymises their member rows.
- [ ] Family (4.18, 9.9): a family expense carries only description, category, amount, India date and payer, and only
      for a payment the person marked Family (and, on set-up or join, the earlier Family rows the screen counted
      first). Never the note, account, card, "paid with", SMS text or sender, exact time, or a Just me payment. The
      family's public page shows the family's name and the sender's name only. A family link lives 7 days; whoever
      joins through it sees every family entry until the owner removes them (docs/family.md Risks).
- [ ] Import from Splitwise (4.17): the file is picked through the system's document picker (no storage permission,
      no persistable grant), read once into memory and never written to disk, logged or sent. Only what any group
      holds reaches the server (the group and friends' names without phones, descriptions, categories, amounts,
      dates, shares). The file's SHA-256 stays in `grp_imports` on the phone, for "already here". Checked by
      `ImportPrivacyTest` (no logging in the code that reads the file) and `SplitwiseImportTest` (nothing logged
      during an import).

**What the server logs.** One line per request: method, route template (`/v1/groups/:gid/expenses/:eid`, never the
real path), status, error `code`, duration ms. Uncaught exceptions: the error class name only. Never: ids, tokens,
names, phones, UPI IDs, amounts, descriptions, request or response bodies, headers, IP addresses. `console.log` is
called only from `src/log.ts`. A test runs a full flow with a console spy and fails if any user value appears.
`[observability] enabled = true` (Workers Logs, free tier) with these lines only, and **`[observability.logs]
invocation_logs = false`**: Cloudflare's automatic invocation log records each request's method and URL, and our URLs
hold group and member ids and, on `/v1/links/{token}` and `/v1/invites/{token}`, the share token itself. No Logpush.

## 11. Configuration

`server/wrangler.toml` is committed with the real production values (the Worker's name, the Google client ids, the
Firebase project id, the link host and web origin, the D1 database id): none of them is a secret, and
`scripts/check-prod-config.mjs` refuses a deploy that still holds a placeholder. The block below shows the file's
shape with placeholders in place of those values, as written before the accounts existed; read the values from the
file itself, and do not copy them or any secret into this document. The file also sets `preview_urls = false` (only
the current version has a public address) and one `[[ratelimits]]` block per limit of 4.15.

```toml
name = "xpense-api"
main = "src/index.ts"
compatibility_date = "2026-11-01"   # the date the build starts, not later than the installed wrangler supports
workers_dev = true

[vars]
ENVIRONMENT = "production"
GOOGLE_WEB_CLIENT_ID = "REPLACE_ME.apps.googleusercontent.com"
GOOGLE_ANDROID_CLIENT_IDS = ""
FCM_PROJECT_ID = ""
LINK_HOST = "xpense.app"
WEB_ORIGIN = "https://xpense.app"

[[d1_databases]]
binding = "DB"
database_name = "xpense"
database_id = "00000000-0000-0000-0000-000000000000"
migrations_dir = "migrations"

[[durable_objects.bindings]]
name = "GROUP_ROOM"
class_name = "GroupRoom"

[[migrations]]
tag = "v1"
new_sqlite_classes = ["GroupRoom"]

[triggers]
crons = ["30 0 * * *"]

[observability]
enabled = true

[observability.logs]
invocation_logs = false              # request URLs hold ids and share tokens (10)

[[ratelimits]]                       # one block each for RL_AUTH, RL_REFRESH, RL_PUBLIC, RL_USER, RL_WRITE (4.15)
name = "RL_AUTH"
namespace_id = "1001"
  [ratelimits.simple]
  limit = 20
  period = 60
```

Secrets: `SESSION_SECRET` (32+ random bytes, base64; required: the Worker answers `500` to everything without it),
`FCM_SERVICE_ACCOUNT_JSON` (optional). Both are set on the live Worker with `wrangler secret put` and exist nowhere
in the repository (docs/server-setup.md 1.6 and 6.6). Local: `server/.dev.vars`, never committed, copied from the
committed `server/.dev.vars.example` (`SESSION_SECRET=local-dev-only-change-me-0123456789abcdef`, a value for local
runs only).

`server/package.json` scripts: `dev` (`wrangler dev --ip 127.0.0.1 --port 8787 --var ENVIRONMENT:development`),
`db:migrate:local`
(`wrangler d1 migrations apply xpense --local`), `test` (`vitest run`), `typecheck` (`tsc --noEmit`), `deploy`
(`node scripts/check-prod-config.mjs && wrangler deploy`). Dependencies: `jose` (runtime); dev: `wrangler`,
`typescript`, `vitest`, `@cloudflare/vitest-plugin`, `@cloudflare/workers-types`. No web framework: a small
hand-written router; hand-written validators. (`@cloudflare/vitest-pool-workers` was renamed
`@cloudflare/vitest-plugin` by Cloudflare; the old name is deprecated. Versions are pinned exactly in
`server/package.json`, each at least two weeks old on 2026-11-17: wrangler 4.156.2, @cloudflare/vitest-plugin 1.5.0
(built for that wrangler), vitest 4.1.12, typescript 5.9.3, jose 6.2.15, @cloudflare/workers-types 5.20261102.1.)

## 12. Out of scope here

- **Family** (06) is no longer out of scope: built as kind `family` (4.18, 9.9; docs/family.md). Import from Splitwise
  makes kind `group` only, and a family can never be imported into (4.17 check 2).
- **Splitwise import**: the server route is built (4.17, 2026-11-18); the file is read on the phone only
  (docs/splitwise-import.md).
- **Plus**: never.
- Foreign currencies (INR only), receipt scan, exports, automatic WhatsApp sending (Android cannot send for the user;
  automatic card bill reminders remain local notifications to the user).

## 13. Open points for the lead and product

1. **UPI ID has no screen.** Decided 2026-11-17 (docs/product.md "UPI ID"): an optional "UPI ID" field on Your name
   (after sign-in) and You > Account, with a note that friends in your groups see it on pay links. The field follows
   the 4.5 UPI ID rule.
2. **Account deletion vs shared groups.** Decided 2026-11-17 (docs/product.md "Delete account") as this contract
   already does: groups where the user is the only person on the app are deleted; in every other group, owned or not,
   the user becomes "Former member" and ownership passes on, so other people's balances and history stay right. The
   Delete account screen says this in place of "your groups".
3. **Group page copy** for a member with no transfer to me but a non-zero net ("settles with others"), the header
   when I owe the group, and "OVERALL YOU OWE" on Groups.
4. **Web page with several expenses.** The design shows one expense with "YOUR SHARE". With several, show the total as
   "YOU OWE" and list them. No design for: several payees, no UPI ID, link no longer works (revoked or expired), all
   square, and the friend being owed money with nothing to pay.
5. **Choose people with several single friends** (not a group): this contract supports one group or one friend per
   expense. Several friends: offer New group prefilled. Needs a decision.
6. **Paying a friend by UPI**: the matched debit SMS is set to IGNORED as not spending. Confirm.
7. **Sign out with unsynced changes**: warning copy not designed.
8. **Link revoking** has no screen (Group settings > person). API exists.
9. **Short link look**: the design shows `xpense.app/pay/k3x9`; real tokens are 24 characters.
10. **API host**: the API runs on `*.workers.dev` (Cloudflare calls it meant for hobby use); the link page is on
    `*.pages.dev`. A custom domain later needs no app change beyond `app.apiBaseUrl`.
11. `docs/architecture.md` must gain the Sign in, Your name (after sign-in), Groups (not signed in), You (not signed
    in) and Join group routes and the 9.3 schema change; `strings_onboarding.xml`'s comment about Sign up and Enter
    code is stale (from the design report).
12. **A forwarded link can be claimed by a stranger** (4.10). The design makes the friend's pay link the invite, so
    whoever opens it in the app and taps Join group takes the friend's place and sees the group, including members'
    phone numbers and UPI IDs. Built here: expiry, one claim, the join announced under the joiner's own name.
    Decided 2026-11-17 (docs/product.md "Forwarded links"): option (b), for the owner only. Built: undo-join (4.6).
13. **The owner is invisible in the design.** Decided 2026-11-17: the owner is shown on the group's Members list
    (`Group.ownerMemberId`, 4.6). Still open: Group settings (02/25) shows Delete group to everyone, but only the owner
    may delete the group or remove someone on the app. Decide whether others see it disabled with a reason, or not at
    all (copy not designed).
14. **Rejected changes**: copy for "A change in Flat 302 could not be saved" (4.1 roll back), and the confirm for
    deleting an expense that is in a group (9.4).
15. **Automatic reminders** (flow 06, You "Automatic reminders"): decided 2026-11-21 (lead): automatic reminders are
    local notifications to the person only (docs/card-bills.md); the phone never calls `/reminders` by itself.
16. **Friends without the app**: their name and phone are typed by someone else and kept on the server for the
    group. The privacy policy must say so, and say how such a friend can ask for removal.
17. **Pay by UPI with an amount** (`upi://pay?...&am=`), from the app and from a web page: some UPI apps limit
    person-to-person payment links that carry an amount. Not checked; try the main UPI apps on real phones before
    relying on it.
18. **Link host**: decided 2026-11-17, `xpense-pay.pages.dev` (a temporary name). It can change until a real link
    reaches a friend; after that a change breaks every link already sent.
19. **Undo a join: copy not designed** (4.6). The banner text is decided ("{name} joined · Not {name}? Undo"). Not
    designed: a confirm before Undo, if any; the answers `undo_expired` (the 7 days ran out while the screen was open)
    and `not_joined_by_link` (the person left meanwhile); what the person who was undone sees (today the group just
    leaves their list, with no message); and whether others are told. Expenses that person added stay in the group
    under the friend's name; members can delete them as usual.
20. **A per-user budget for imports** (4.15, 8; raised in review 2026-11-18). An import request counted once against
    `RL_WRITE`, so a client that imports into new groups in a loop could write about 10,500 rows a minute and use a
    day's 100,000 free writes in about 10 minutes. The options were (a) a per-user daily cap on imported items, (b)
    counting an import against `RL_WRITE` once per 10 items, (c) accepting the risk. **Decided 2026-11-19 by the lead:
    option (b), built.** An import counts ceil(items / 10) against `RL_WRITE` (5 for 50 items) and answers `429
    rate_limited` with `Retry-After: 60` like every limited route; a replay counts once. A loop now needs about 50
    minutes for a day's writes; a 1,000-row file takes about 3 to 4 minutes and 5,000 rows about 17, which the import
    screen's progress covers. No migration, no new copy (the phone already waits a `429` on an IMPORT entry).
21. **"Join as {name}" on Join group** (docs/splitwise-import.md Risks). Group imported sends every friend's link in
    one message to the group's chat, so anyone in the chat can take someone else's place; Join group (32) does not
    say whose place it is, and the owner's 7-day undo is the only guard. Showing "Join as Priya Nair" would make a
    wrong tap visible. Copy and design not decided.
22. **A history view for old group expenses** (docs/splitwise-import.md Risks). The Group page lists this month only,
    so "Balances and 46 past expenses came across" is true but most of those expenses show nowhere except the
    person's own Activity mirrors. Not designed.
23. **Deleting a settlement on the phone** (4.8, 4.17). The server lets the recorder, `from` or `to` delete a
    settlement, and the phone can queue it (`GroupsRepository.deleteSettlement`, with `canBeDeletedBy`, so the owner
    may delete an imported payment between two friends). No screen lists settlements or offers Delete on one yet:
    the design draws none (a payment row on the Group page and its detail with Delete). Until then a wrong imported
    payment can only be undone by deleting the group while nobody else has joined (143).
24. **Joint bank accounts in a family** (docs/family.md Risks). Both partners can get the same debit SMS; if both mark
    it Family, the family month counts it twice. Not handled in version 1; a later fix could flag likely duplicates
    (same amount, date and payee from both people).
25. **A person removed from a family is not told** (4.18). The family just leaves their Home; no copy is designed, and
    the removal push is NORMAL. The same open point as 19 for an undone join.

## 14. Work split

Build order: A and the `:splits` part of C start together (both depend only on `shared/vectors`, which A writes
first, in its first commit, with at least 30 split and 15 balance cases including rounding ties, plus
`shared/vectors/direct_ids.json` with at least 5 user-id pairs and their v5 group and member ids); B needs A's
`/v1/links`; D needs C's repository API. Nobody commits; the lead commits.

**(A) Server: `server/`, `shared/vectors/`, `docs/server-setup.md`**
- Everything in sections 3, 4, 6.1–6.3, 8, 10, 11: migration `0001_init.sql`, router, auth (Google, dev, nonce,
  refresh, sign-out), me, devices, groups, members, expenses, settlements, changes, balances, links, invites,
  reminders, the `GroupRoom` Durable Object, FCM sender, rate limits, cron, logging, `check-prod-config.mjs`.
- Tests with `@cloudflare/vitest-pool-workers` against local D1 (migrations applied in the test setup): every route's
  happy path and its 4.2 rule as a non-member; idempotent replays and `422`; LWW and `410`; version pages; shares
  vectors; balance vectors; link page contents (no phone, no ids); Google token verify with a locally made RSA key
  and an intercepted JWKS fetch (the URL in code stays Google's); dev sign-in 404 in production and from a non-local
  host; room `changed` broadcast and kick; FCM request shape with an intercepted fetch; the logging privacy test.
  Also: the 3.1 guard (an expense written to a group tombstoned after the handler's checks, an expense id that lives
  in another group, a share for a member removed after the checks, a leave racing an expense: each rolls back the
  whole batch and leaves no row); a nonce used twice; refresh with the previous token inside and outside the
  10-minute window; link `PUT` replayed and expired; the link page reading at most 5 rows on a cache hit (D1's
  `meta.rows_read`); `PATCH /v1/me` and `DELETE /v1/me` for a user in 60 groups as one batch; a direct group both
  people left, created again; the cron purge of a large deleted group stopping at its daily row budget;
  `check-prod-config.mjs` failing without `invocation_logs = false`.
- `docs/server-setup.md`: the owner's later steps: Cloudflare account; `wrangler login`; `wrangler d1 create xpense`
  and filling `database_id`; `wrangler d1 migrations apply xpense --remote`; `wrangler secret put SESSION_SECRET`;
  Google Cloud project, OAuth consent screen, Web client id (for `GOOGLE_WEB_CLIENT_ID` and `app.googleWebClientId`)
  and Android client ids (package + SHA-1 of debug and release keys, for `GOOGLE_ANDROID_CLIENT_IDS`); Firebase
  project (messaging only), Android app registration, the four `app.fcm.*` values, a service account key with only
  the "Firebase Cloud Messaging API Admin" role put in `FCM_SERVICE_ACCOUNT_JSON` and then deleted from disk; Pages
  project, and the host wrangler reports for it in `LINK_HOST`, `WEB_ORIGIN`, `app.linkHost`; `assetlinks.json`
  fingerprints (through the web build's `ANDROID_CERT_SHA256`); Play Store URL; checking
  the free limits again (including whether the free plan has Rate Limiting bindings); watching database size and
  Worker CPU time (section 8). Each step says which file or secret it fills.
- Done when `npm test` and `npm run typecheck` pass and `npm run dev` answers the app on the emulator.

**(B) Web link page: `server/web/`**
- Section 7. Matches 06-friend-web-page-no-app in dark and light (`prefers-color-scheme`), 360 px wide first, text at
  least 14 px, touch targets at least 48 px, state not by colour alone.
- Tested against A's local Worker with a seeded group (a small Node script calling the dev sign-in and API), all
  six states (7); check headers with `wrangler pages dev`; a name containing `<script>` renders as text.

**(C) Android auth, sync and data layer**
- `:splits` module (with the shared vectors) and section 9.1–9.4, 9.6, 9.8: API client, auth with Credential Manager
  and dev sign-in, token storage, Room v2 tables and migration, `GroupsRepository`, outbox, `SyncWorker`, `LiveRoom`,
  FCM guard, personal mirrors, BuildConfig fields, manifest, network security config.
- `GroupsRepository` (main-safe, `Flow` reads from Room only):

  ```kotlin
  fun observeGroups(): Flow<List<GroupSummary>>          // kind GROUP: name, memberCount, myNetMinor
  fun observeFriends(): Flow<List<FriendBalance>>        // 6.4 people rollup
  fun observeTotals(): Flow<OwedTotals>                  // owedToMeMinor, iOweMinor, overallMinor, groupCount (6.4)
  fun observeGroup(id: String): Flow<GroupDetail?>       // members with pair balance, expenses, my net
  fun observeLoad(id: String): Flow<LoadState>           // LOADING, READY, FAILED (02/22, 02/23)
  fun observeKnownPeople(): Flow<List<Person>>           // users and friends from my groups (New group, Choose people)
  suspend fun createGroup(name: String, people: List<NewPerson>): String
  suspend fun renameGroup(id: String, name: String)
  suspend fun addMember(groupId: String, person: NewPerson): String
  suspend fun editFriend(groupId: String, memberId: String, name: String, phone: String?)
  suspend fun removeMember(groupId: String, memberId: String): ActionResult
  suspend fun leaveGroup(id: String): ActionResult
  suspend fun deleteGroup(id: String): ActionResult
  suspend fun directGroupWith(person: Person): String    // finds or makes the direct group
  suspend fun splitPayment(expenseId: Long, groupId: String, method: SplitMethod, inputs: Map<String, Long?>,
                           paidByMemberId: String? = null, rememberRule: Boolean, auto: Boolean): String
  suspend fun addGroupExpense(groupId: String, draft: GroupExpenseDraft): String   // 02/15
  suspend fun updateGroupExpense(id: String, draft: GroupExpenseDraft)
  suspend fun deleteGroupExpense(id: String)
  suspend fun undoSplit(groupExpenseId: String)
  suspend fun recordSettlement(groupId: String, fromMemberId: String, toMemberId: String, amountMinor: Long,
                               method: SettleMethod, matchedFromSms: Boolean): String
  suspend fun confirmSettlement(id: String)
  suspend fun shareLink(groupId: String, memberId: String): ApiResult<String>      // URL; reuses the link, or makes one online (4.10)
  suspend fun previewInvite(token: String): ApiResult<JoinPreview>
  suspend fun acceptInvite(token: String): ApiResult<String>                       // group id
  suspend fun remind(groupId: String, memberIds: List<String>): ApiResult<RemindResult>
  fun splitRuleFor(counterparty: String?): Flow<SplitRule?>
  ```

- Tests: JVM tests with MockWebServer for the API and auth refresh (including a lost refresh answer, and
  `refresh_invalid` keeping the outbox); Robolectric Room tests for outbox order, coalescing (never of an attempted
  entry), roll back from `restoreJson`, a stuck entry blocking only its group, pull apply, skip-pending, a pending
  group surviving `/me/groups`, no notifications on a pull from 0, mirrors (every case in 9.4, including a payer
  with a 0 share and a restored expense linking to its SMS row); `:splits` vectors including `direct_ids.json`. An emulator run against A's
  `npm run dev` with dev sign-in: two debug users on one emulator in turn (sign out, sign in as the other) share a
  group and see each other's expense after sync.
- Updates `docs/architecture.md` for everything it adds.

**(D) Android UI wiring**
- Sign in, Sign in (could not sign in), Your name (after sign-in) and the debug dev sign-in button; Groups (not
  signed in), You (not signed in), the Sign in row and Account (name, optional phone, Sign out, Delete account with
  confirm); Welcome "I already have an account".
- Groups, Groups empty/loading/could not load, Group page, Group settings, New group, Add friend (contacts picker:
  `ActivityResultContracts.PickContact`, read only the picked contact's name and number; never upload the address
  book), Join group (App Link).
- Split flow: Split it (Equally, Amounts, %, Shares, "left to assign", Do this every time), Choose people, Split.
  Sorted. with Undo, Recurring split offer, Add expense with Paid by and Split, Expense detail "Split this expense";
  Home "Owed to you / You owe", the "Last time you split …" hint, offline banner (outbox pending + no network);
  Activity "From splits" and Splits filter from mirrors.
- Settle up, Mark as paid, Money received, All square, Remind on WhatsApp (send with `ACTION_SEND` to WhatsApp,
  text from 03/05 and the link; Copy link), Card bill view's "Friends owe you" from group data.
- Signed-out Split: Sign in first, then back to the split; Not now leaves the payment waiting.
- Every screen matches `design/spec` in both themes, with theme tokens, 14 sp text, 48 dp targets, state not by
  colour alone, 3D icons in rows and line icons in bars. Strings in `strings_<feature>.xml`.
- Compose UI tests or screenshot checks per screen with sample states, and an emulator walk-through against the local
  server.

## Review notes (2026-11-17)

An adversarial review changed the contract as follows. Cloudflare facts were rechecked on Cloudflare's pages the same
day: D1 has 500 MB per database on Free, a batch counts as one query, Workers Free has 10 ms CPU per request and per
cron run, and invocation logs record the request URL.

**Security**

1. Nonces are now single-use (`sessions.nonce_hash` UNIQUE). Before, a stolen ID token and nonce could sign in again
   within the 5 minutes (4.4).
2. Refresh after a lost answer: the old token now gets a new pair within 10 minutes instead of `refresh_superseded`.
   Before, the phone had no "newest token" to fall back to and was signed out after 60 s. `refresh_superseded`
   removed (4.1, 4.4).
3. `[observability.logs] invocation_logs = false`: Cloudflare's invocation logs would have stored every URL, share
   tokens included, against the "never log ids or tokens" rule. `check-prod-config.mjs` checks it (4.4, 10, 11).
4. Forwarded links can be claimed by a stranger. Written down, with links expiring after 90 days, the join announced
   under the joiner's own name, and an undo option in 13.12 (4.10, 9.7).
5. Link tokens are now made on the phone (`PUT .../links/{linkId}`). Before, a server-made token "returned once"
   could not be returned on a retry or a replay, since only its hash is stored (3, 4.10, 9.3).
6. Push suppression: only the caller's own `X-Installation-Id` is skipped, and the skip is per connected
   installation, not per user. Before, a header could silence someone else's phone, and a person's second phone lost
   its push (4.12).
7. Input rules: lengths, E.164, UPI ID format, and no bidirectional override characters on any text shown on the
   public page. The web page renders with `textContent` only (4.6, 4.7, 7).
8. Dev server binds `127.0.0.1` (the emulator reaches IPv4 loopback; `0.0.0.0` would expose dev sign-in to the
   local network). Never `wrangler dev --remote`. Release builds fail on placeholders, including
   `app.linkHost=xpense.app`, a domain nobody here owns (4.4, 9.8, 11, 13.18).
9. Refresh rate limit is keyed by token, not IP (carrier NAT in India). Rate-limit syntax filled in (4.15, 11).

**Sync and data integrity**

10. Batch guard (3.1). The old "PK collision on `changes`" argument failed once a deleted group's change rows were
    removed, which let writes land in a deleted group. Nothing stopped a path id from another group, a share for a
    member removed between check and batch, or a leave racing an expense. The change insert now fails (NOT NULL) and
    rolls the batch back in all four cases.
11. `PATCH /v1/me` and `DELETE /v1/me` are now set-based statements in one batch. "One batch per group" breaks the
    50-query limit for users in more than about 45 groups, and their per-group notify and push broke the
    50-subrequest limit (4.5).
12. Direct groups are never tombstoned by leaving, and creating them again reactivates removed members. Before, a
    deterministic id could be left permanently taken (4.6).
13. Outbox (5.1):
    - Coalescing only between never-attempted entries. An attempted create may already be applied, and dropping it
      on Undo would have left the expense on the server.
    - `restoreJson` for rolling back rejected changes. A rejected update otherwise stayed on the phone, because the
      next pull starts above it.
    - A stuck entry blocks only its own group.
    - 429 waits `Retry-After`.
    - Online-only actions listed.
14. Pull (5.2, 4.9):
    - Pending-create groups are not deleted by `/me/groups`.
    - `since > latestVersion` means re-pull from 0.
    - Screens wait for a full pull, since member rows can arrive in later pages.
    - No notifications on a pull from 0 or for changes over 24 hours old.
15. A forced sign-out (`refresh_invalid`) keeps the outbox and the group tables (9.2).
16. `restoreJson` also undoes a personal row's split when its group expense is rejected or never sent (9.4).

**Money**

17. A payer whose share is 0 keeps the link with `myShareMinor = 0`. Before, the payment was unlinked and counted in
    full while friends owed all of it (9.4).
18. A restored expense paid by me links to the rescanned SMS payment instead of leaving it to be added in full (9.4).
19. Deleting a linked expense on Expense detail deletes the group expense. Before, a mirror came back with the next
    sync, or friends kept owing for a deleted payment (9.4).
20. Kotlin v5 ids: `UUID.nameUUIDFromBytes` is v3. Added `shared/vectors/direct_ids.json` (9.1, 14).
21. Pay by UPI matching also compares the payee's UPI ID when the SMS shows one (9.5).

**Free tier**

22. `group_cache` keyed by group version. The public link page, invite preview, balances and every leave or delete
    attempt read a group's whole history; one person reloading a link to a big group could use the day's 5 M read
    rows (4.10, 4.16).
23. A group deletion tombstones the group and the cron purges about 10,000 rows a day. A 20,000-expense group
    deleted at once is about 140,000 written rows (expenses and their index, shares, changes), more than a day's
    100,000 (3, 4.13).
24. Access token 24 h, since every request already checks the session. This removes about 8 written rows and 3
    Worker requests per daily user (4.4).
25. `WITHOUT ROWID` for expenses and settlements, which saves one written row per create (3).
26. Section 8 redone. Adds the 500 MB database cap (about 8 months at 1,000 daily users, the first wall that does not
    reset), 10 ms CPU and Durable Object duration. New daily caps are about 4,000 users (writes) and 5,000 (requests).
27. FCM uses HIGH priority only when the phone will notify, since Android lowers apps that send HIGH without one. A
    reminder push carries the reminder's member id so the phone can name them (4.11, 4.14).

**Privacy**

28. Descriptions pre-filled from person-to-person UPI SMS (UPI IDs, phone numbers) are replaced by the category name
    (4.7).
29. UPI IDs show on the public page and often contain phone numbers, so the field must say so. Friends' phone numbers
    typed by others must be in the privacy policy (10, 13.1, 13.16).

**Design mismatches**

30. "across N groups" counts the listed groups: 02/20 shows "across 3 groups" with one group at ₹0 (6.4).
31. Settlement confirm follows 03/00 and 03/01 ("Rahul can confirm" on both payer and receiver screens). It is
    automatic only for SMS matches or a friend without the app. Before, receiver-recorded settlements were always
    confirmed at once (4.8, 9.5).
32. The web page gets an "owed only" state. "All square" was wrong when the friend is owed money (7).
33. Flow 09 says "owned groups" are deleted with the account. The 13.2 trade-off is restated with that wording. The
    owner is invisible in the design (13.13).
34. New open points 13.12–13.18: link takeover, owner visibility, rejected-change copy, automatic reminders,
    third-party phones, UPI links with an amount, and the link host.

## Build notes, server (work A, 2026-11-17)

Choices made while building `server/` that the sections above did not fix. The contract text was edited where a
shape or rule changed (4.4 deploy check, 4.6 removed members, 4.10 accept, 4.11 replay, 4.12 notify body, 4.13 cron
and unknown paths, 4.14 collapse keys, 4.15 fallback, 6 vector location, 11 dependencies).

1. **Answers the contract left open.** Remove yourself: `403 not_allowed` (use leave). A non-member asking about a
   deleted group: `403 not_member` (only members learn it was deleted, `410`). An expense or settlement id that lives
   in another group, on `PUT`, `DELETE` or confirm: `404 not_found`. A `POST .../members` id already used anywhere
   with other content: `409 exists_different`. Confirm of a deleted settlement: `404 not_found`. `/live` without
   `Upgrade: websocket`: `400 bad_request` (field `Upgrade`). A guard failure the re-read cannot explain:
   `503 internal` (the phone keeps the entry and retries).
2. **Leave** also revokes the leaver's own links (as remove does). **Delete expense** also sets
   `updated_by_member_id`.
3. **`PUT` expense answers** carry `overwrote` on updates only (true when `baseVersion` is null or older than the
   stored version). Creates have no `overwrote` field.
4. **Settlement tombstones in pulls** keep every field with `"deleted": true` (expenses use the short tombstone of
   4.7), so a phone pulling from 0 can still show a deleted settlement if it wants to.
5. **Expense cap.** The 20,000 count runs only once the group version reaches 20,000 (the version counts every
   mutation, so below that the cap cannot be reached): creates stay at about 12 rows read.
6. **Profile change with nothing changed** writes nothing (no version bump).
7. **Test hooks.** `server/src/testing.ts` holds three hooks (`beforeBatch`, `onDb`, `limiter`) that tests set; they
   are undefined in production and cost one property check.
8. **Logs in `wrangler dev`.** Wrangler's own local console prints `POST /v1/auth/dev 200` lines with real paths.
   That is the local dev console only; deployed, `invocation_logs = false` and the Worker's own lines hold route
   templates only.

## Review notes 2 (2026-11-17, second review)

A second review of the built server and Android layer found the problems below; each was confirmed and fixed, and
the sections above now describe the result. No finding was rejected.

**Server**

1. Idempotent replays that answer with content now run the membership check first (4.3). Before, someone who had
   left could resend an old request with its key and read the group as it is now, other members' phones and UPI IDs
   included, for the 7 days a key is kept. Test: `authz.test.ts`.
2. Group and member ids must be UUID v4 except the checked direct-split ids (4.6). Before, anyone in a group with two
   people could take their deterministic direct-split id first. Test: `authz.test.ts`.
3. The live room checks the socket's session and token expiry before each message and closes ended ones with 4401
   (4.12). Before, a socket stayed connected and kept getting group changes after sign-out or account deletion.
   Test: `room.test.ts`.
4. Push is skipped for a device only when the socket holding its installation id belongs to the device's own user
   (4.12). Before, a socket opened with someone else's installation id silenced that person's pushes. Test:
   `push.test.ts`.
5. `DELETE /v1/me` also bumps the groups the user had left or been removed from (4.5), so every phone pulls the
   "Former member" row. Test: `me.test.ts`.
6. `0001_init.sql` (3): no foreign keys from the member columns of expenses, shares and settlements (each member row
   the cron purged read every expense, share and settlement in the database); new indexes `groups_owner`,
   `groups_to_purge`, `links_group` for rare writes; the 3.1 member rule checks existence as well. Tests:
   `cron.test.ts` (a purge next to a 2,000-expense group reads under 500 rows), `me.test.ts` (account deletion next to
   500 other groups reads under 200 rows).
7. Web page: the app's name defaults to `app_name` from the app's `strings.xml` (7). The invite-preview test no
   longer depends on the order of random member ids.

**Android**

8. A refresh that fails for a passing reason (offline, 5xx, Cloudflare's own page, 429) is no longer reported as
   signed out (9.2, `TokenResult`). Before, the sync stopped as "signed out" with no retry, and online actions said
   Sign in while the person was signed in.
9. The access token is stored encrypted and reused by a new process (9.2), so a refresh happens about once a day as
   section 8 assumes, not on every cold start.
10. A rollback puts back the server's newest copy when a pull skipped the row while a change waited (5.2, 4.1).
    Before, a settlement deleted by the other person came back after a refused confirm, for good. Restoring a personal
    row also takes the link back from a mirror the change made (9.4).
11. A parked (stuck) outbox entry no longer makes WorkManager back off, and a sync request replaces a sync waiting out
    a back-off (5.1). Before, one stuck entry held every group's changes for up to 5 hours.
12. Sign-out and a different account signing in put back payments whose split was never sent (9.4).
13. The live socket pulls its group whenever it opens (5.3), so changes made while an old socket was silently dead are
    not missed.
14. A `changed` push pulls only its group, and only when behind (9.6, 4.14); section 8 now counts pushes.
15. `/me/groups` handling reads the local groups before the outbox, so a group made during a sync is not removed with
    its create (5.2).
16. Your name (after sign-in): the system Back does what the top bar's Back does (continue).
17. "Continue with Google" loads Roboto Medium through the Google Fonts provider instead of trusting the system font.
18. The working name left the code: `GroupsApi`, `GroupsMessagingService`, Keystore alias `groups_auth_v1`.

## Build notes, undo-join (2026-11-17)

Built from the "Splitting decisions (2026-11-17)" in docs/product.md. The sections above now describe the result: 3
(migration 0002, notes), 3.1 (rule), 4.1, 4.2, 4.3, 4.5 (UPI ID rule, account deletion), 4.6 (shapes, undo-join),
4.10, 5.1, 13 (1, 2, 12, 13 decided; 19 new).

1. **Deploy order.** The owner applies `0002_undo_join.sql` to the live database first
   (`npx wrangler d1 migrations apply xpense --remote`, in `server/`), then deploys. The new Worker reads
   `members.join_link_id` in every member read, so it fails every group route without 0002. 0002 alone is safe for the
   Worker that runs now. Nobody else runs remote commands. Checked locally: 0001, then rows, then 0002, on a scratch
   `--persist-to` folder; existing rows keep their values and the new columns start empty.
2. **Route:** `POST /v1/groups/{gid}/members/{mid}/undo-join`, beside the other member routes. It answers `200 Member`
   (not `204` like remove), so the owner's phone can show the restored place at once; its replay runs the 4.3 check
   like member add and edit.
3. **What accept saves** goes in three columns on `members`, not a table of its own: no extra written row per join,
   and the cron purge needs no new step.
4. **Links made by the person** are revoked too, not only the place's links. While in the group the person could make
   links for other friends without the app, and with one of those tokens could join again in another place.
5. **The change row is `member` / `update`.** A new op would need the `changes` table rebuilt (its CHECK is in the
   frozen 0001), which reads and writes every change row on the live database.
6. **Joins accepted before 0002** have no saved link, so they answer `409 not_joined_by_link`. No real join exists yet.
7. **Owner label.** `Group.ownerMemberId` is a subquery of the group read (one index read, `members_one_per_user`), in
   every answer with a Group. A member-level flag would go stale: when ownership passes, only the group row is sent
   again.
8. **`canUndo` is computed per caller and per answer.** Rows the phone keeps are not sent again when time passes or the
   owner changes, so the phone decides with `undoUntil`, the clock and `Group.ownerUserId` (4.6).
9. **UPI ID.** The server now drops spaces at both ends before the pattern check (it refused `" name@okaxis"` before)
   and stores the trimmed value. `""` is still `400`: the app sends `null` for an empty field. Same pattern as before.
10. **Delete account** was already right for the product decision (only groups with no other app user are deleted;
    shared ones keep "Former member"). It now also clears the `join_*` columns. New test: a user who does not own a
    shared group, a group whose other app user left, and a direct split with a friend.
11. **Cost.** Undo is one batch, about 20 rows read (measured locally) whatever else is in the database; the links
    statement reads the group's links only (`links_group`). The owner label adds one index read to each group route.
12. **Tests:** `test/undo.test.ts` (11), plus additions in `me.test.ts` (UPI ID rule, account deletion),
    `authz.test.ts` (undo-join as a non-member, in a deleted group, signed out), `groups.test.ts` (the owner label
    follows a leave) and `sync.test.ts` (Member keys, no `join_` data in pulls).
13. **Leave and remove** now also revoke the links the member *made* (the same statement as undo-join, on the
    `links_group` index). Before, a person who left or was removed could keep a link they made for a friend without
    the app and join as that friend. Test: `links.test.ts`.
14. **A revoked or expired link is not given out again** by `PUT .../links/{linkId}` (`404 link_not_found`). Before,
    another phone that still had the link stored (another member, the owner's second phone) sent a dead link after
    an undo-join or a Turn off link. The phone now forgets it and makes a new one. Test: `links.test.ts`.
15. **The deploy checks the live database first.** `npm run deploy` runs `scripts/check-remote-migrations.mjs`
    (`wrangler d1 migrations list xpense --remote`, a read) and stops while a migration is not applied there, or when
    it cannot tell. The routine is in docs/server-setup.md 4.2. Test: `config.test.ts` (the rule only; no test reaches
    Cloudflare).

## Build notes, stage 2 merge (2026-11-18)

The six stage 2 areas are merged (docs/groups-build-plan.md section 7). The sections above describe the result: 4.8,
6.4, 9.3 and 9.5. Nothing is deployed; the live Worker still needs the steps of the plan's 7.3.

1. **A settlement matched from SMS on both phones is one settlement.** Before, the payer's debit match and the
   receiver's credit match each made a random id, the server stored two settlements, and the pair balance flipped.
   The phone now uses a fixed id for every SMS match (9.5), and `GroupsRepository.recordSettlement` takes it (`id`,
   a new one by default). Server: a same-content `PUT` of an unconfirmed settlement by its receiver with
   `matchedFromSms` confirms it (4.8; `confirmExisting` in `routes/ledger.ts`, shared with the confirm route). Tests:
   `settlements.test.ts` ("one payment matched from SMS on both phones ..."), `SettlementMatcherTest`
   (`thePayersDebitAndTheReceiversCreditAreOneSettlement`).
2. **Matcher rules** (9.5): Pay by UPI settles only from a UPI debit or one whose SMS names no channel; a credit more
   than a day old settles and asks nothing.
3. **Load state** (9.3): LOADING now means "nothing pulled yet". It used to cover every pull (the phone writes
   `latestVersion` before pulling), so the Group page flashed its placeholders on each live change, and a pull cut off
   offline hid the stored balances until the next sync.
4. **Friends known only by name** (6.4): one person across groups (`personKeyOf`), and a second split with such a
   friend reuses the direct split instead of making a new one each time.
5. **No server change for the other areas.** Their copy, screens and phone rules are in docs/architecture.md
   ("Groups and splitting screens").

## Build notes, sync and money polish (2026-11-18)

The sections above describe the result: 3 (0002, notes), 4.1, 4.2, 4.5, 4.6, 4.8, 4.13, 5.2, 5.3, 9.2, 9.3, 9.4,
9.5 and 9.7. Nothing is deployed; 0002 is still not applied to the live database.

1. **Why a person lost a group.** 0002 gains the table `access_ended` (amended before any `--remote` apply; additive).
   Leave, remove, undo-join and group delete write it in their batch; `GET /v1/me/groups` returns `lost` and
   `403 not_member` carries `reason`. Before, the phone guessed from its join window, so a removal or a group
   deletion within 7 days of a join by link also forgot the person's splits. Tests: `test/lost.test.ts`. Later the
   same day (4.6, remove a member): the reason is written from the member row inside the batch and the guard names
   who held the place, so a person who takes a friend's place while it is being removed is told why, and only the
   owner can remove them. No schema change. Tests: `test/lost.test.ts` ("a place taken through a link…", "a member who
   is not the owner…", "the remove guard fails…").
2. **An undone join puts payments back as they were.** The phone remembers how a payment was before a split linked it
   (`grp_link_priors`, Room version 4), and an undone join puts it back: a payment that was waiting waits again instead
   of counting in full. Tests: `SyncEngineTest` (`anUndoneJoin…`, `aRemovalOrADeletionInsideTheJoinWindowKeepsTheMirrors`,
   `notAMemberWithoutAReasonKeepsTheMirrors`), `DatabaseMigrationTest` (3 to 4). Later the same day (9.4): with
   nothing remembered, a payment the person typed in stays added instead of waiting as "Payment detected". Test:
   `MirrorWriterTest.forgettingASplitWithNothingRememberedKeepsATypedExpenseAddedAndLetsAnSmsPaymentWait`.
3. **Live room of a new group.** `LiveRoom` waits until the group's create has left the outbox. Before, the Group page
   of a group made a second ago connected first and got `403 not_member`. Test: `LiveRoomTest`.
4. **Notifications.** One bundle with a summary that opens the app and keeps what is under it; all removed on sign out
   and account deletion; first names as the screens write them. Tests: `GroupNotificationsLinksTest`,
   `SyncEngineTest.notificationsNamePeopleAsTheScreensDo`. Later the same day (9.7 "One bundle", "The system's list
   is behind"): the summary comes with every notification and no longer depends on what Android lists right after the
   app's own call, and a tap or swipe tells the app which notification went, so the summary goes with the last one
   without a sync. Tests: `GroupNotificationsBundleTest` (a shade that applies calls late, as a phone does),
   `RecentCallsTest`, `GroupNotificationsLinksTest`, `GroupNotificationTapTest` (the tap through `MainActivity`).
5. **One SMS repayment named UPI by one phone and OTHER by the other** is one settlement (4.8): `200` with the stored
   row instead of `409 exists_different` and a rollback. Test: `settlements.test.ts`. The other settle points stay as
   accepted limits, written in 9.5.

## Build notes, Splitwise import (2026-11-18)

The server part of docs/splitwise-import.md (section 3). The sections above describe the result: 3 (no migration;
0002 marked applied, as docs/server-setup.md 4.2 already said), 3.1 (`importRule`, the import batch), 4.2, 4.3, 4.6
(delete), 4.15, 4.17 (new), 8 and 12. Nothing is deployed. The phone-side sections (5.1, 5.2, 9.1, 9.3, 9.4, 10, 13)
are left to the app work of the same spec.

1. **Code.** New `routes/imports.ts` (`importItems`, `replayImport`). `routes/ledger.ts`: `parseExpense` became
   `parseExpenseFields(raw, prefix, now)`, shared with the import (fields get the prefix, `items[3].shares`);
   `PUT .../expenses` answers exactly as before. `db/ledger.ts`: `expenseStmts(..., { create: true })`. `db/mutation.ts`:
   `importRule` and `IdemWriter.stmtGroupVersion`. `routes/groups.ts`: the delete rule and `othersOnApp`. One router
   line.
2. **`create: true` is a plain insert**, not only "no `DELETE` of shares" as the spec put it. With the update clause
   kept, a second copy of the same items sent with another key at the same moment would have overwritten the first
   copy's rows (same group, not deleted) instead of failing on the primary key, so check 12 could never answer it.
3. **Check 12 looks each id up in its own kind's table** (one primary-key read per item). D1 counts a read for every
   id probed, found or not, so reading all ids from both tables cost 100 rows per request for nothing; an expense id
   that is also a settlement's id is no conflict in the schema.
4. **Two copies at once.** The second copy fails on the primary key before its key insert, so the router's
   `IdempotencyRace` never fires for it. The handler looks for its own key first: same key, it answers as the replay;
   another key, check 12 (`applied: false`).
5. **Checks 9 and 10 share one read** of the group's members (`membersOf`) instead of a `membersByIds` of the named
   ids: one query fewer, and the named ids cannot exceed the group's own members. The 6,000 cap (check 11) is checked
   before the batch only, like the 20,000-expense cap: only the owner's own parallel requests could pass it, by at most
   one part.
6. **Field of a body that is not an object** is `body`, as on every route; `items` is the field of a missing or
   wrong-sized array. Items are checked one at a time in body order (checks 5 to 8 for item 0, then item 1), so the
   first bad field in the body answers.
7. **Measured** (test/import.test.ts): one request of 50 expenses with 3 shares writes 351 rows and reads about 570
   (check 12 and the write: 2 batches); 1,000 rows in 20 requests write 6,720 rows, as estimated, and read about
   11,300, about twice the spec's 6,000 (each 3.1 guard reads about 6 rows, not 5, and the insert 3), still 0.2% of a
   day. Wall time about 10 ms per request locally; CPU to be checked after deploy.
8. **Delete group** (4.6) reads the members only when a net is not 0, inside the check that the version guard repeats.
9. **Tests.** `test/import.test.ts` (14): the spec's flow with a settlement between two friends and the balances;
   every case of `balances.json` imported into a group of its own, nets and transfers equal; replay and `422`;
   `applied: false` after the key is purged and `409` for ids present or elsewhere; two copies at once with the same
   key and with another key; validation and `413` with nothing written; authorization (not a member, not the owner, a
   friend joined, a direct split, deleted, signed out); a join and a removal between the checks and the batch (all
   rolled back); the 6,000 cap; 150 items paged back in 100-version pages; the cost of 50 items; 1,000 rows in 20
   requests (20 `RL_WRITE` hits, 6,720 written rows, balances equal to the file's); one room message per request.
   `test/groups.test.ts` (2): delete while nobody else is on the app (`204`) and `409` once a friend joined; a join
   during the delete's checks gives `409`. `test/logging.test.ts`: an import and a refused import in the privacy flow.

## Review fixes, Splitwise import (2026-11-18)

A review of the import found one defect, one budget risk and two smaller points. `npm test`: 213 passing in 18 files
(210 before, plus the review's file `test/splitwise-import-review.test.ts` with 3 tests).

1. **Settlement delete by its recorder** (4.2, 4.8). `deleteSettlement` allowed only `from` and `to`, which matched
   "the recorder or the other party" while the recorder was always one of them. An import records payments between
   two friends with the owner as recorder, and the owner got `403 not_allowed`: nobody could ever remove a wrong
   imported payment short of deleting the group. Now the recorder, `from` or `to` may delete; anyone else still gets
   `403`. The phone must offer Delete on such a payment to the owner (for the app work).
2. **Check 12's `applied: false` comes before checks 9 to 11** (4.17). A part applied long ago and retried after its
   key was purged got `403 not_allowed` (a friend joined), `400` (a named member removed) or `409 limit_reached`
   instead of `applied: false`, and the phone treats those as a refused import and deletes the group. The id lookup
   now runs right after validation; its `409 exists_different` still comes last. Same queries on the success path;
   a refusal at 9 to 11 now also pays the lookup (at most 50 rows read).
3. **Imports and the write budget** (4.15, 8, open point 13.20). The docs said rate limits stop a looping client;
   for the import route they do not (about 10,500 written rows a minute). The docs now say so and the options are an
   open point. No cap was built: its size and what the phone shows are a product decision.
4. **Delete group from the outbox** (4.6). 4.6 said delete group is never queued; the import clean-up queues it. 4.6
   now names that exception.

## Build notes, import rate limit (2026-11-19)

Open point 13.20, decided by the lead as option (b). Sections 4.15, 4.17 and 8 describe the result. Nothing is
deployed; no migration, no `wrangler.toml` change (the binding keeps 30 / 60 s). `npm test`: 218 passing in 18 files
(213 before, plus 5).

1. **Code.** `ratelimit.ts`: `enforce(env, name, key, cost = 1)` takes `cost` hits one at a time and stops at the
   first refusal (the Workers binding has no weight); `rateLimited()` is the one `429` answer. `routes/imports.ts`:
   `importWriteCost(body)` = ceil(items / 10), items clamped to 1..50, 1 without an `items` array. `router.ts`: a route
   field `writeCost`, set on the import only; the extra hits are taken after the replay lookup and before the handler.
2. **Tests** (`test/import.test.ts`): the cost table; 50 items 5 hits, 11 items 2, 10 items 1, other writes still 1
   and `RL_USER` always 1; a burst through the real in-memory window (no fake limiter, no bindings): six parts of 50
   pass, the seventh and a 1-item part get `429 rate_limited` with `Retry-After: 60`; a `429` on a later hit writes
   nothing, the key included, and the same part and key is then applied as new; a replay counts 1 (and answers with
   one hit left), a `422` reuse counts 1. The 1,000-row test now expects 100 `RL_WRITE` hits.
3. **Time.** docs/splitwise-import.md 3.5 now gives the time with this limit: about 3 to 4 minutes for 1,000 rows
   (20 parts) and about 17 minutes for 5,000 (it said "well under a minute" before). The phone already waits a `429`
   on an IMPORT entry (docs/splitwise-import.md 5.4), so only its progress takes longer.

## Build notes, Splitwise import on the phone (2026-11-19)

The app part of docs/splitwise-import.md (section 5). The sections above describe the result: 5.1 (import parts,
a refused import), 5.2 (groups whose delete waits are skipped), 9.1 (`:splitwise`, `groups.splitwise`), 9.3 (Room
version 5), 9.4 (imported expenses), 10 (the file) and 13 (points 21 to 23). Nothing is committed or deployed.

1. **Room 5.** `grp_imports` as the spec draws it plus `groupName` (the name the group was imported with): Import
   failed (140) names a group that is already gone, also after the process was stopped. `GroupExtrasDao` gains the
   import queries, `observeImportedGroupIds` and `latestImportByHash` (a join with `grp_groups`, so a group no longer
   here does not count); `OutboxDao` gains `observeStatusForGroup` (no bodies), `allSeqs`, `groupIdsWith(op)` and
   `countImportParts`, so the push loop, the pull and each accepted part no longer load every body.
2. **Push runs twice over the outbox at most.** A refused part queues the group's delete while the run goes on; the
   second look sends it at once instead of on the next sync. Entries tried in the first look are not tried again.
3. **An imported group is never LOADING.** Its data is written here, and its first pull follows the last part, which
   can be minutes away with 429 waits; the Group page would otherwise show placeholders all that time.
4. **`importedFile(hash)`** gives the group's current name and the India date of the import (`PastImport`).
5. **Group imported.** The friends come in the order they were stored (the file's columns); the card names them in
   first names where those stay clear, the message in full names. Offline shows the banner and Share does nothing;
   `import_pending` and other errors show the Problem banner. One 429 wait per link, capped at 60 s.
6. **Delete a settlement.** `GroupsRepository.deleteSettlement` (queued, refused for anyone but the recorder, `from`
   or `to`: `canBeDeletedBy`) is built and tested, but no screen lists settlements to offer it: open point 13.23.
7. **New group** offers the import only when opened from the Groups tab with nobody filled in (`offersImport`
   checks both, so Choose people's "Make a group" with nobody picked does not offer it either).
8. **Tests** (JVM / Robolectric): `ImportPlannerTest` (8), `ImportCategoriesTest` (2), `ImportPrivacyTest` (1),
   `SplitwiseImportTest` (20: repository, mirrors, links held back, already here, settlement delete, sync with
   versions, parts and progress, offline then online, replay, `applied: false`, 429, a stuck part, a refused part
   with the payment put back and the pull skipping the group, a refused delete, a refused create, `not_member`,
   sign-out, loading, nothing logged), `DatabaseMigrationTest` 4 to 5, `ImportSplitwiseViewModelTest` (11),
   `GroupImportedViewModelTest` (7), `ImportSplitwiseScreenTest` (16), `SplitwiseNavigationTest` (5), and the changed
   `YouScreenTest`, `GroupSettingsViewModelTest`, `GroupSettingsScreenTest`. `:app:testDebugUnitTest`: 811 tests,
   all of these passing; the one failure (`GroupPageScreenTest.theFooterButtonsAreLaidOutAsTheirFacesAsDrawn`) comes
   from an uncommitted `AppButton.kt` change of other work (the Secondary outline no longer adds 3dp), not from import.

## Review fixes, Splitwise import on the phone (2026-11-20)

The code review and the design check of the app part (2026-11-19) and what was done about each. Sections 5.3 and 9.3
above, docs/splitwise-import.md (status, 5.1 to 5.3) and docs/architecture.md describe the result. Nothing is
deployed.

1. **A text of only U+FEFF** (review 1) was refused by the server after Import, which took the whole group back:
   Kotlin's `trim()` keeps a byte order mark and JavaScript's removes it. `GroupText.clean` and `SplitwiseText.clean`
   now treat U+FEFF as a space, so such a description takes the category's name and such a person's name refuses the
   file on screen 30. The mark is written as an escape in the sources (lint's `ByteOrderMark`).
2. **"Reading the file" (135) shows while the file is read** (review 2), not only while it is parsed: the state is
   set the moment the picker returns, so Choose file is off for the whole read (a file kept in Drive is fetched
   first). The name's line is empty until the provider gives the name (`pickedName`, asked before the read) and keeps
   its place meanwhile. A pick that arrives during a read replaces it; a read that ends after it was replaced shows
   nothing.
3. **Live room** (review 3, section 5.3): a `changed` message goes to `SyncEngine.pullChanged(groupId, version)`,
   which compares under the sync lock; `LiveRoom` no longer compares on arrival. The pull on every socket open is
   unchanged.
4. **Outbox.** `coalesce()` reads the expense entries only (`OutboxDao.ofKind`), so a push no longer loads the bodies
   of waiting import parts (about 3 MB with 100 parts); sign-out's flush asks for the seqs only, and Undo on "Split.
   Sorted." reads its own expense's entries.
5. **`importedFile`** gives the India date through `AppContainer.ZONE`, whatever zone the clock passed in has.
6. **Delete group and Leave group** forget the group's `grp_imports` row (`LocalGroupData.removeOwnGroup`, 9.3).
7. **Drop area** (design check 1): Figma counts the 1.5 inside outline in the frame's layout, so the texts sit 25.5
   from the top and bottom and 17.5 from the sides. The app adds the outline's width to the padding: 28 and 30 are 113
   high, 135 is 95 and 136 is 131 at 360dp, as drawn (they were 3 short).
8. **Previews** (design check 5): 26 and 142 show the Group name field focused as drawn, with no keyboard (the
   preview swallows the input session). Design check 2 to 4 (142's disabled Create group, 140's title wrap, the
   blank-name state of 29) were settled in the design; the app did not change for them.
9. **Tests.** `ImportSplitwiseReadingReviewTest` (1) and `LiveRoomPullsReviewTest` (1) pass as the review wrote
   them, apart from the room's constructor call; `ImportServerRulesReviewTest` (3). New: `ImportSplitwiseViewModelTest`
   (14, +3: 135 before the read with Choose file off, the empty name line, a second pick), `LiveRoomTest` (4, +1),
   `OutboxCoalesceTest` (2), `SplitwiseImportTest` (23, +3: the India date, delete while parts wait, a refused
   delete), `ImportSplitwiseDropAreaTest` (5; run at xhdpi, where 1.5dp is whole pixels; 28's caption fits on one
   line in Robolectric's fallback font, so its two-line height is pinned through 30). `:splitwise:test` 89,
   `:app:testDebugUnitTest` 828 (1 skipped: `LiveServerTest`, which needs a running local server), all passing;
   `:app:lintDebug` 0 errors.

## Build notes, family (2026-11-20)

The server part of docs/family.md (sections 2 to 5 and the server half of 10; build order step 1). The sections above
describe the result: 1, 2 (decision 9), 3 (0003, its notes and order), 3.1 (the family guards), 4.1
(`already_in_family`), 4.2 (the family table), 4.5 (`kind`, step 1b), 4.6 to 4.11 (pointers), 4.15 (caps), 4.18
(new; docs/family.md calls it 4.17, which Import from Splitwise had taken), 5.1, 5.2, 6, 7, 9.3, 9.7, 9.9 (new), 10,
12 and 13 (24, 25). The phone-side sections follow docs/family.md 6 to 8 as written; the app work of the same spec
builds them. **Nothing is deployed and 0003 is not applied to the live database** (steps in 6).

1. **Code.** `migrations/0003_family.sql` exactly as docs/family.md 2 gives it, after a header comment.
   `db/shapes.ts`: `GroupKind`. `http.ts`: `alreadyInFamily()`. `db/groups.ts`: `MAX_FAMILY_MEMBERS`,
   `inAnotherFamily`, `NOT_IN_ANOTHER_FAMILY`, `familyExpenseTombstoneStmts`, `createGroupStmts(..., onlyFamilyOf)`,
   rename for `kind <> 'direct'`, `tombstoneIfEmptyStmts` for `group` and `family`. `db/links.ts`:
   `FAMILY_LINK_TTL_MS`, `insertLink(..., ttlMs)`. `db/accounts.ts`: step 1b. `routes/groups.ts`: create, rename,
   delete, leave (`leaveFamily`), add, edit, remove (`familyPlaceRule`), undo-join and reminders.
   `routes/ledger.ts`: `checkFamilyExpense`, the expense delete rule, settlements and balances refused.
   `routes/links.ts`: the link's lifetime and target, the family preview (`computeFamilyInvite`), accept with the
   family guard, the family public page. `scripts/check-remote-migrations.mjs`: comment only (the rule already
   blocks a deploy while any file in `migrations/` is not applied, 0003 included; a test now says so).
   `server/web`: `pay.js` (`parseLinkData`, `stateOf`, `renderFamilyInvite`), `pay.css` (sticker, list card),
   `build.mjs` (`heart_3d` in `ICONS`).
2. **Choices where the spec was silent or the code differed.**
   - `PATCH .../members/{mid}` on a family answers `403 not_allowed` ("Family members join with a link."): the spec
     does not list it, and without it a member could give the placeholder a phone, which create and add refuse.
   - `POST .../import` into a family was already refused by the import's kind check (4.17 check 2); a test pins it.
   - A family remove that fails its guard twice answers `503 internal` (a group's answers `409
     balances_not_settled`, which means nothing in a family).
   - `senderName` on a family's public page is the first word of the link creator's member name (the spec's example
     and 06-family/28; the review fix of 2026-11-20). A group's page keeps the whole member name.
   - The heart icon: `server/web/public` has no `icons/` folder; the build copies every icon it lists from
     `design/icons/svg` (`ICONS`), so `heart_3d` was added to that list only (a copy in `public/` would be a second
     source).
   - Leave reads the membership once before `settled` and hands it in, so a group's leave makes the same queries as
     before and a family's leave goes its own way.
   - Making a family pushes nothing (nobody else is in it). A family expense's create is NORMAL, like every update.
3. **Migration, checked on a populated database three ways.**
   - `test/migration.test.ts` (second D1 binding `MIGRATION_DB`, `vitest.config.ts` and `test/env.d.ts`): 0001 and
     0002 applied, rows of every kind written (users, sessions, devices, a group, a direct split, a deleted and
     purged group, a group with no owner, members on and off the app, joined by link and removed, live and deleted
     expenses with shares, settlements confirmed, open and deleted, changes, links live, revoked and expired,
     `group_cache`, `idempotency_keys`, `access_ended`); 0001 refuses `family`; 0003 applied; every table equal row
     for row, `PRAGMA table_info` and `foreign_key_list` of `groups` and of its four children equal,
     `groups_owner` and `groups_to_purge` as before (and used by the planner), no `groups_new`, `PRAGMA
     foreign_key_check` empty; a family inserts; a family without a name, a named direct split, a nameless group and
     kind `pool` fail the CHECK; a member of an unknown group, an unknown owner and deleting a group with members
     fail the foreign key; deleting the owner's user still sets `owner_user_id` NULL.
   - Locally with wrangler, never `--remote`: a throwaway folder under `/private/tmp/claude-501/` with its own
     `wrangler.toml` (binding `DB`, `database_name = "xpense"`, a made-up local id, `migrations_dir` holding 0001 and
     0002 only), `wrangler d1 migrations apply xpense --local --persist-to <folder>/state -c <folder>/wrangler.toml`,
     `wrangler d1 execute xpense --local ... --file scripts/rehearsal/populate-0001-0002.sql`, every table dumped
     with `--json`, 0003 copied in, `wrangler d1 migrations list` showing it pending, `migrations apply` (✅), `list`
     saying nothing is left, every table dumped again. 34 checks, all passing: 13 tables equal row for row (users 3,
     sessions 2, devices 1, groups 4, members 8, expenses 4, expense_shares 6, settlements 3, changes 11, links 3,
     group_cache 2, idempotency_keys 1, access_ended 2), columns, foreign keys and both indexes equal, children
     still reference `groups`, `foreign_key_check` empty, both indexes in query plans, the CHECKs and foreign keys as
     in the test above.
   - The same rehearsal with the last `PRAGMA defer_foreign_keys = off` left out: the apply fails ("internal
     error") and rolls back (`groups` keeps 0001's CHECK). So the PRAGMA is required, as docs/family.md says, and
     wrangler's local apply runs the file as one transaction.
4. **Tests.** `npm test`: 244 passing in 20 files (218 in 18 before). New `test/family.test.ts` (18): create (201,
   placeholder, change row, `/me/groups`, pull, replay); validation (`members` empty or two, `userId`, phone, names,
   v4 ids); one family (409, the guard on a racing create, a normal group still allowed, a new family after leaving);
   preview (kind, zeros, names without the placeholder, `alreadyInFamily`, no cache) and `kind` on group and direct
   links; accept (`already_in_family` checked and guarded); expenses (payer, stored payer on update and delete,
   shares, `SHARES` and `AMOUNTS`, `auto`, 4.7 rules, create, update, delete, `410`); everything refused (balances,
   settlements, reminders, undo-join, delete, edit member, import); leave (tombstones at the new version, shares gone,
   the partner's untouched and pulled, `left`, ownership, the last one out ends it, a lone placeholder does not keep
   it, replay); remove (owner only for the partner, tombstones, `removed`, socket kicked, cancel invite by a member who
   is not the owner, a join between checks and batch); add (placeholder only, `userId` 403, phone 400, 2 at most, the
   guard on a racing add); links (7 days, the stored `expiresAt`, an 8-day-old link nowhere, `member_on_app`); the
   public page (exactly three keys, nothing else); rename by the partner. New `test/migration.test.ts` (2). Changed:
   `groups.test.ts` (`kind: "pool"`, family without a member), `links.test.ts` (`kind`, `alreadyInFamily`, stored
   `expiresAt`), `authz.test.ts` (family routes as a non-member, signed out, deleted), `me.test.ts` (step 1b where
   the partner remains and where nobody does, one batch), `sync.test.ts` (a family's page), `push.test.ts` (join HIGH
   to the owner and `groups` to the joiner's other phone, family expense NORMAL, removal NORMAL `groups`, create
   pushes nothing), `logging.test.ts` (a family made, invited, previewed, joined, an expense shared, balances refused,
   left: no value logged), `config.test.ts` (the deploy refuses while 0003 is pending). `npm run typecheck` clean.
   `server/web` `npm test`: 73 passing (69 before): the family state, no Play Store link with another app name, three
   malformed answers, names that look like HTML.
5. **Not done here.** `docs/server-setup.md` (which migrations the live database has) is the owner's checklist and is
   left to the owner's apply (step 6); the app, design and other docs of docs/family.md 10 are other work.
6. **Owner steps for the live database** (in `server/`, signed in to Cloudflare; each one is the owner's call):
   1. Time Travel bookmark: `npx wrangler d1 time-travel info xpense`. Keep the bookmark it prints; a restore is
      `npx wrangler d1 time-travel restore xpense --bookmark=<bookmark>`.
   2. Rehearse on a throwaway remote database, the same way the live apply runs:
      `npx wrangler d1 create xpense-scratch`; a config outside the repository (for example
      `/tmp/xpense-scratch/wrangler.toml` with `name = "xpense-scratch"`, `main` any file, and one `[[d1_databases]]`
      with `binding = "DB"`, `database_name = "xpense-scratch"`, the printed `database_id` and `migrations_dir` a
      folder holding copies of 0001 and 0002 only); `npx wrangler d1 migrations apply xpense-scratch --remote -c
      <config>`; `npx wrangler d1 execute xpense-scratch --remote -c <config> --file
      scripts/rehearsal/populate-0001-0002.sql`; copy 0003 into that folder; `npx wrangler d1 migrations apply
      xpense-scratch --remote -c <config>`; then check with `npx wrangler d1 execute xpense-scratch --remote -c
      <config> --command "..."`: the row counts of the file's header, `PRAGMA foreign_key_check` (empty),
      `SELECT name FROM sqlite_master WHERE tbl_name = 'groups'` (`groups_owner`, `groups_to_purge`), and an insert of
      a `family` row. Then `npx wrangler d1 delete xpense-scratch`. If 0003 fails there, stop: the fallback is the
      `groups.family` flag column (docs/family.md decision 1).
   3. `npx wrangler d1 migrations list xpense --remote` (shows `0003_family.sql`), then
      `npx wrangler d1 migrations apply xpense --remote`.
   4. `npm run deploy` (check-prod-config, then check-remote-migrations, which passes only now, then `wrangler
      deploy`), and deploy the friends' page (`server/web`, `npm run deploy`, docs/server-setup.md 4.3) for the family
      invite state. Ship the app build with Family at the same time: an older build reads `family` as a named group.
   5. Update docs/server-setup.md 4.2: 0003 applied on the date.

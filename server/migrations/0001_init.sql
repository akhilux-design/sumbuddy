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

-- Family sharing (docs/family.md 2; docs/server.md 3, 4.18): groups.kind gains 'family', and a family, like a group,
-- has a name. 0001_init.sql and 0002_undo_join.sql are frozen: they are applied to the live database.
--
-- Apply it --remote BEFORE deploying the Worker that writes 'family' (scripts/check-remote-migrations.mjs refuses the
-- deploy until it is applied). On its own it changes nothing for the Worker that runs now: same columns, same rows.
--
-- Why a rebuild: 0001's CHECK on groups.kind allows only 'group' and 'direct', and SQLite cannot alter a CHECK in
-- place. So groups is rebuilt in the order Cloudflare documents for D1 (create the new table, copy the rows, drop the
-- old one, rename the new one) inside PRAGMA defer_foreign_keys. D1 runs the whole file as one transaction.
--
-- Why it is safe: members, expenses, settlements and links reference groups(id) with a plain REFERENCES (no ON DELETE
-- CASCADE or SET NULL; D1 runs cascades even with deferred foreign keys), so the implicit DELETE of DROP TABLE groups
-- removes no child row. It only counts foreign-key violations, and only while groups is gone: the children name the
-- table "groups", so once groups_new is renamed to it every child row has its parent again. The copy happens before
-- the DROP, so that count is still above 0 at the end; PRAGMA defer_foreign_keys = off discards it before the commit.
-- That last PRAGMA is required: without it the commit fails on the stale count and the migration rolls back.
-- Columns (names, types, order, defaults, the owner foreign key), every row and both partial indexes are kept exactly;
-- only the two CHECKs change. access_ended and group_cache name a group without a foreign key and are not touched.
--
-- No other schema change: a family uses groups, members, expenses, expense_shares, changes, links and access_ended as
-- they are. "One live family per user" and "at most 2 active members" are enforced by the handlers and the 3.1 guard
-- (members has no kind column an index could use).

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

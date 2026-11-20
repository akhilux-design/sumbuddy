-- Undo a join by link (docs/server.md 4.6 "Undo a join by link"; docs/product.md "Forwarded links"), and why a person
-- lost a group (4.5 "GET /v1/me/groups", 4.1 `not_member`).
-- 0001_init.sql is frozen: it is applied to the live database. This file only adds nullable columns and a new table, so
-- SQLite rewrites no row and the code that ran before it keeps working. Apply it --remote BEFORE deploying the Worker
-- that reads them (every member read names join_link_id; every leave, removal, undo and group delete writes
-- access_ended).
--
-- members.join_*: written by POST /v1/invites/{token}/accept, read and cleared by POST .../members/{mid}/undo-join,
-- cleared by DELETE /v1/me. Never returned by any route. No index: they are read with the member row (by its primary
-- key or its group's version range). No REFERENCES links(id): the cron deletes old links, and a foreign key would make
-- each of those deletes look for members by this unindexed column (the reason for members_no_fk in 0001).

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

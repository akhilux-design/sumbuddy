// Users, sessions and devices.
import type { Db } from "./db";
import type { UserRow } from "./shapes";

export interface SessionRow {
  id: string;
  user_id: string;
  refresh_hash: string;
  prev_refresh_hash: string | null;
  rotated_at: number | null;
  expires_at: number;
  revoked_at: number | null;
}

export function getUser(db: Db, id: string): Promise<UserRow | null> {
  return db.first<UserRow>("SELECT id, name, phone, upi_id FROM users WHERE id = ?1", id);
}

export function getSession(db: Db, sid: string): Promise<Pick<SessionRow, "user_id" | "expires_at" | "revoked_at"> | null> {
  return db.first("SELECT user_id, expires_at, revoked_at FROM sessions WHERE id = ?1", sid);
}

/**
 * Finds or creates the user by `column` (google_sub or dev_key) and creates a session, in one batch. A replayed
 * nonce fails the batch on sessions.nonce_hash. Returns the user and whether this call created it.
 */
export async function signInUser(
  db: Db,
  p: {
    column: "google_sub" | "dev_key";
    key: string;
    newUserId: string;
    name: string;
    sessionId: string;
    refreshHash: string;
    nonceHash: string | null;
    now: number;
    expiresAt: number;
  },
): Promise<{ user: UserRow; isNew: boolean }> {
  const res = await db.batch([
    db.stmt(
      `INSERT INTO users (id, ${p.column}, name, created_at, updated_at) VALUES (?1, ?2, ?3, ?4, ?4)
       ON CONFLICT(${p.column}) DO NOTHING`,
      p.newUserId,
      p.key,
      p.name,
      p.now,
    ),
    db.stmt(
      `INSERT INTO sessions (id, user_id, refresh_hash, nonce_hash, created_at, expires_at)
       VALUES (?1, (SELECT id FROM users WHERE ${p.column} = ?2), ?3, ?4, ?5, ?6)`,
      p.sessionId,
      p.key,
      p.refreshHash,
      p.nonceHash,
      p.now,
      p.expiresAt,
    ),
    db.stmt(`SELECT id, name, phone, upi_id FROM users WHERE ${p.column} = ?1`, p.key),
  ]);
  const user = (res[2].results as UserRow[])[0];
  return { user, isNew: user.id === p.newUserId };
}

export function findSessionByRefresh(db: Db, hash: string): Promise<SessionRow | null> {
  return db.first<SessionRow>(
    "SELECT id, user_id, refresh_hash, prev_refresh_hash, rotated_at, expires_at, revoked_at FROM sessions WHERE refresh_hash = ?1",
    hash,
  );
}

export function findSessionByPrevRefresh(db: Db, hash: string): Promise<SessionRow | null> {
  return db.first<SessionRow>(
    `SELECT id, user_id, refresh_hash, prev_refresh_hash, rotated_at, expires_at, revoked_at FROM sessions
     WHERE prev_refresh_hash = ?1`,
    hash,
  );
}

/** Normal rotation: the presented token becomes `prev`. Returns false when another refresh won the race. */
export async function rotateSession(db: Db, s: SessionRow, newHash: string, now: number, expiresAt: number) {
  const changed = await db.run(
    `UPDATE sessions SET prev_refresh_hash = refresh_hash, refresh_hash = ?3, rotated_at = ?4, expires_at = ?5
     WHERE id = ?1 AND refresh_hash = ?2 AND revoked_at IS NULL`,
    s.id,
    s.refresh_hash,
    newHash,
    now,
    expiresAt,
  );
  return changed === 1;
}

/** Lost-answer rotation from `prev`: a new token replaces `refresh_hash`; `prev` and `rotated_at` stay. */
export async function rerotateSession(db: Db, s: SessionRow, newHash: string, expiresAt: number) {
  const changed = await db.run(
    `UPDATE sessions SET refresh_hash = ?3, expires_at = ?4
     WHERE id = ?1 AND prev_refresh_hash = ?2 AND revoked_at IS NULL`,
    s.id,
    s.prev_refresh_hash,
    newHash,
    expiresAt,
  );
  return changed === 1;
}

export function revokeSession(db: Db, sid: string, now: number) {
  return db.run("UPDATE sessions SET revoked_at = ?2 WHERE id = ?1 AND revoked_at IS NULL", sid, now);
}

/** Sign-out: revoke the session and drop this installation's device row, in one batch. */
export function signOut(db: Db, sid: string, userId: string, installationId: string | null, now: number) {
  return db.batch([
    db.stmt("UPDATE sessions SET revoked_at = ?2 WHERE id = ?1 AND revoked_at IS NULL", sid, now),
    db.stmt("DELETE FROM devices WHERE installation_id = ?1 AND user_id = ?2", installationId, userId),
  ]);
}

/**
 * Upsert the device: the row moves to the caller, a token stored under another installation moves to this one,
 * and only the 3 most recently updated devices of the user are kept.
 */
export function putDevice(db: Db, userId: string, installationId: string, fcmToken: string, now: number) {
  return db.batch([
    db.stmt("DELETE FROM devices WHERE fcm_token = ?1 AND installation_id <> ?2", fcmToken, installationId),
    db.stmt(
      `INSERT INTO devices (installation_id, user_id, fcm_token, updated_at) VALUES (?1, ?2, ?3, ?4)
       ON CONFLICT(installation_id) DO UPDATE SET user_id = excluded.user_id, fcm_token = excluded.fcm_token,
         updated_at = excluded.updated_at`,
      installationId,
      userId,
      fcmToken,
      now,
    ),
    db.stmt(
      `DELETE FROM devices WHERE user_id = ?1 AND installation_id NOT IN (
         SELECT installation_id FROM devices WHERE user_id = ?1 ORDER BY updated_at DESC, installation_id LIMIT 3)`,
      userId,
    ),
  ]);
}

export function deleteDevice(db: Db, userId: string, installationId: string) {
  return db.run("DELETE FROM devices WHERE installation_id = ?1 AND user_id = ?2", installationId, userId);
}

export interface DeviceRow {
  installation_id: string;
  user_id: string;
  fcm_token: string;
}

/** Devices of the given users (at most 50 ids). */
export function devicesOf(db: Db, userIds: string[]): Promise<DeviceRow[]> {
  if (userIds.length === 0) return Promise.resolve([]);
  const ph = userIds.map((_, i) => `?${i + 1}`).join(", ");
  return db.all<DeviceRow>(`SELECT installation_id, user_id, fcm_token FROM devices WHERE user_id IN (${ph})`, ...userIds);
}

export function deleteDeviceByToken(db: Db, fcmToken: string) {
  return db.run("DELETE FROM devices WHERE fcm_token = ?1", fcmToken);
}

/**
 * PATCH /v1/me: one batch of set-based statements (docs/server.md 4.5): the user row, then a version bump, the
 * member row's version and a member/update change in every non-deleted group the user is active in.
 */
export function updateProfile(
  db: Db,
  userId: string,
  p: { name: string; phone: string | null; upiId: string | null },
  now: number,
  tail: D1PreparedStatement[],
) {
  const active = "SELECT group_id FROM members WHERE user_id = ?1 AND removed_at IS NULL";
  return db.batch([
    db.stmt("UPDATE users SET name = ?2, phone = ?3, upi_id = ?4, updated_at = ?5 WHERE id = ?1", userId, p.name, p.phone, p.upiId, now),
    db.stmt(`UPDATE groups SET version = version + 1, updated_at = ?2 WHERE deleted_at IS NULL AND id IN (${active})`, userId, now),
    db.stmt(
      `UPDATE members SET version = (SELECT g.version FROM groups g WHERE g.id = members.group_id)
       WHERE user_id = ?1 AND removed_at IS NULL
         AND group_id IN (SELECT id FROM groups WHERE deleted_at IS NULL)`,
      userId,
    ),
    db.stmt(
      `INSERT INTO changes (group_id, version, kind, op, entity_id, actor_member_id, created_at)
       SELECT m.group_id, g.version, 'member', 'update', m.id, m.id, ?2
       FROM members m JOIN groups g ON g.id = m.group_id
       WHERE m.user_id = ?1 AND m.removed_at IS NULL AND g.deleted_at IS NULL`,
      userId,
      now,
    ),
    ...tail,
  ]);
}

/**
 * DELETE /v1/me: one batch of set-based statements (docs/server.md 4.5). The change rows are written before the
 * tombstones so the guard-free set insert sees the bumped, live groups; the outcome is the order the contract lists.
 */
export function deleteAccount(db: Db, userId: string, now: number) {
  const active = "SELECT group_id FROM members WHERE user_id = ?1 AND removed_at IS NULL";
  const any = "SELECT group_id FROM members WHERE user_id = ?1";
  const othersOnApp = (g: string) =>
    `EXISTS (SELECT 1 FROM members o WHERE o.group_id = ${g} AND o.user_id IS NOT NULL AND o.user_id <> ?1 AND o.removed_at IS NULL)`;
  return db.batch([
    // 1. Bump every non-deleted group with a member row of the caller: active ones, and those the caller left or was
    //    removed from, whose old member row also becomes "Former member" (5b) and must reach every phone.
    db.stmt(`UPDATE groups SET version = version + 1, updated_at = ?2 WHERE deleted_at IS NULL AND id IN (${any})`, userId, now),
    // 1b. The caller's family expenses leave their families (docs/server.md 4.18), at the version step 1 gave each
    //     family, before step 2 tombstones a family with nobody else on the app. Their shares go as on a delete.
    db.stmt(
      `UPDATE expenses SET deleted_at = ?2, updated_at = ?2,
         version = (SELECT g.version FROM groups g WHERE g.id = expenses.group_id)
       WHERE deleted_at IS NULL
         AND group_id IN (SELECT m.group_id FROM members m JOIN groups g ON g.id = m.group_id
                          WHERE m.user_id = ?1 AND g.kind = 'family' AND g.deleted_at IS NULL)
         AND paid_by_member_id IN (SELECT m.id FROM members m WHERE m.user_id = ?1)`,
      userId,
      now,
    ),
    db.stmt(
      `DELETE FROM expense_shares WHERE expense_id IN (
         SELECT e.id FROM expenses e WHERE e.deleted_at = ?2
           AND e.group_id IN (SELECT m.group_id FROM members m JOIN groups g ON g.id = m.group_id
                              WHERE m.user_id = ?1 AND g.kind = 'family'))`,
      userId,
      now,
    ),
    // 5a'. Groups the caller is no longer active in: one member/update change for (one of) the caller's old rows.
    db.stmt(
      `INSERT INTO changes (group_id, version, kind, op, entity_id, actor_member_id, created_at)
       SELECT g.id, g.version, 'member', 'update', MIN(m.id), MIN(m.id), ?2
       FROM members m JOIN groups g ON g.id = m.group_id
       WHERE m.user_id = ?1 AND g.deleted_at IS NULL
         AND NOT EXISTS (SELECT 1 FROM members a WHERE a.group_id = g.id AND a.user_id = ?1 AND a.removed_at IS NULL)
       GROUP BY g.id`,
      userId,
      now,
    ),
    // 5a. One change per group: member/update where others on the app remain, else group/delete.
    db.stmt(
      `INSERT INTO changes (group_id, version, kind, op, entity_id, actor_member_id, created_at)
       SELECT g.id, g.version,
              CASE WHEN ${othersOnApp("g.id")} THEN 'member' ELSE 'group' END,
              CASE WHEN ${othersOnApp("g.id")} THEN 'update' ELSE 'delete' END,
              CASE WHEN ${othersOnApp("g.id")} THEN m.id ELSE g.id END,
              m.id, ?2
       FROM members m JOIN groups g ON g.id = m.group_id
       WHERE m.user_id = ?1 AND m.removed_at IS NULL AND g.deleted_at IS NULL`,
      userId,
      now,
    ),
    // 2. Groups where the caller is the only member on the app are tombstoned (rows purged by the cron).
    db.stmt(
      `UPDATE groups SET deleted_at = ?2 WHERE deleted_at IS NULL AND id IN (${active}) AND NOT ${othersOnApp("groups.id")}`,
      userId,
      now,
    ),
    // 3. Revoke the links of every member row of the caller and of the groups just tombstoned.
    db.stmt(
      `UPDATE links SET revoked_at = ?2 WHERE revoked_at IS NULL AND (
         member_id IN (SELECT id FROM members WHERE user_id = ?1)
         OR group_id IN (SELECT id FROM groups WHERE deleted_at = ?2 AND id IN (${active})))`,
      userId,
      now,
    ),
    // 4. Owned groups that live on pass to the active member on the app with the earliest joined_at (groups_owner
    //    index; deleting the user row below then finds no group to set NULL on, except tombstoned ones).
    db.stmt(
      `UPDATE groups SET owner_user_id = (
         SELECT o.user_id FROM members o WHERE o.group_id = groups.id AND o.user_id IS NOT NULL AND o.user_id <> ?1
           AND o.removed_at IS NULL ORDER BY o.joined_at, o.id LIMIT 1)
       WHERE owner_user_id = ?1 AND deleted_at IS NULL`,
      userId,
    ),
    // 5b. Every member row of the caller becomes a friend without the app named "Former member", at the version
    //     step 1 gave its group. A join by link can no longer be undone (its saved name and phone go too).
    db.stmt(
      `UPDATE members SET user_id = NULL, name = 'Former member', phone = NULL,
         join_link_id = NULL, join_prev_name = NULL, join_prev_phone = NULL,
         version = (SELECT g.version FROM groups g WHERE g.id = members.group_id)
       WHERE user_id = ?1`,
      userId,
    ),
    db.stmt("DELETE FROM idempotency_keys WHERE user_id = ?1", userId),
    // 6. The user row; sessions and devices go with it (ON DELETE CASCADE).
    db.stmt("DELETE FROM users WHERE id = ?1", userId),
  ]);
}

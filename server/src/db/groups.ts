// Groups and members: reads and statement builders.
import type { LostReason } from "../http";
import { DAY } from "../time";
import { chunk, type Db } from "./db";
import { V1 } from "./mutation";
import { MEMBER_COLUMNS, MEMBER_FROM, OWNER_MEMBER_ID, type GroupKind, type GroupRow, type MemberRow } from "./shapes";

export const MAX_MEMBERS = 50;
export const MAX_GROUPS_PER_USER = 300;
/** Active members of a family, the placeholder of an invite not yet accepted included (docs/server.md 4.18). */
export const MAX_FAMILY_MEMBERS = 2;

export interface Access {
  group: GroupRow;
  myMemberId: string | null;
}

/** One query: the group and the caller's active member id in it (docs/server.md 4.2). */
export async function groupAccess(db: Db, gid: string, userId: string): Promise<Access | null> {
  const row = await db.first<GroupRow & { my_member_id: string | null }>(
    `SELECT g.id, g.kind, g.name, g.owner_user_id, g.version, g.created_at, g.deleted_at, ${OWNER_MEMBER_ID},
       (SELECT m.id FROM members m WHERE m.group_id = g.id AND m.user_id = ?2 AND m.removed_at IS NULL) AS my_member_id
     FROM groups g WHERE g.id = ?1`,
    gid,
    userId,
  );
  if (!row) return null;
  const { my_member_id, ...group } = row;
  return { group, myMemberId: my_member_id };
}

export function getGroup(db: Db, gid: string): Promise<GroupRow | null> {
  return db.first<GroupRow>(
    `SELECT g.id, g.kind, g.name, g.owner_user_id, g.version, g.created_at, g.deleted_at, ${OWNER_MEMBER_ID}
     FROM groups g WHERE g.id = ?1`,
    gid,
  );
}

export function membersOf(db: Db, gid: string): Promise<MemberRow[]> {
  return db.all<MemberRow>(`SELECT ${MEMBER_COLUMNS} FROM ${MEMBER_FROM} WHERE m.group_id = ?1 ORDER BY m.id`, gid);
}

export function getMember(db: Db, mid: string): Promise<MemberRow | null> {
  return db.first<MemberRow>(`SELECT ${MEMBER_COLUMNS} FROM ${MEMBER_FROM} WHERE m.id = ?1`, mid);
}

/** Member rows by id (any group), at most 99 ids. */
export function membersByIds(db: Db, ids: string[]): Promise<MemberRow[]> {
  if (ids.length === 0) return Promise.resolve([]);
  const ph = ids.map((_, i) => `?${i + 1}`).join(", ");
  return db.all<MemberRow>(`SELECT ${MEMBER_COLUMNS} FROM ${MEMBER_FROM} WHERE m.id IN (${ph})`, ...ids);
}

/** How long the reason a person lost a group is kept (0002 `access_ended`; the cron deletes older rows). */
export const ACCESS_ENDED_KEEP_MS = 90 * DAY;

/** Why a person lost a group, the latest reason winning (one row per user and group). */
export function accessEndedStmt(db: Db, userId: string, gid: string, reason: LostReason, now: number) {
  return db.stmt(
    `INSERT INTO access_ended (user_id, group_id, reason, at) VALUES (?1, ?2, ?3, ?4)
     ON CONFLICT (user_id, group_id) DO UPDATE SET reason = excluded.reason, at = excluded.at`,
    userId,
    gid,
    reason,
    now,
  );
}

/**
 * Remove member: the person who holds the place when the batch runs loses the group. Read from the member row inside
 * the batch, not from an earlier read: someone can take a friend's place through a link in between. A friend without
 * the app gets no row.
 */
export function accessEndedForMemberStmt(db: Db, gid: string, mid: string, reason: LostReason, now: number) {
  // The WHERE clause is required: without it SQLite would read ON CONFLICT as a join constraint of the SELECT.
  return db.stmt(
    `INSERT INTO access_ended (user_id, group_id, reason, at)
     SELECT m.user_id, ?1, ?3, ?4 FROM members m WHERE m.id = ?2 AND m.group_id = ?1 AND m.user_id IS NOT NULL
     ON CONFLICT (user_id, group_id) DO UPDATE SET reason = excluded.reason, at = excluded.at`,
    gid,
    mid,
    reason,
    now,
  );
}

/** Group delete: every member on the app who is still active loses the group (the caller included). */
export function accessEndedForMembersStmt(db: Db, gid: string, reason: LostReason, now: number) {
  // The WHERE clause is required: without it SQLite would read ON CONFLICT as a join constraint of the SELECT.
  return db.stmt(
    `INSERT INTO access_ended (user_id, group_id, reason, at)
     SELECT m.user_id, ?1, ?2, ?3 FROM members m WHERE m.group_id = ?1 AND m.user_id IS NOT NULL AND m.removed_at IS NULL
     ON CONFLICT (user_id, group_id) DO UPDATE SET reason = excluded.reason, at = excluded.at`,
    gid,
    reason,
    now,
  );
}

/** Not active again: no active member row in that group, or the group was deleted. */
const NOT_BACK = `NOT EXISTS (SELECT 1 FROM members m JOIN groups g ON g.id = m.group_id
  WHERE m.group_id = a.group_id AND m.user_id = a.user_id AND m.removed_at IS NULL AND g.deleted_at IS NULL)`;

/**
 * The groups the user lost in the last 90 days and is not active in again, with why (4.5 `lost`). Reads the user's
 * rows only (the primary key starts with user_id); usually none.
 */
export function lostGroups(db: Db, userId: string, now: number) {
  return db.all<{ group_id: string; reason: LostReason; at: number }>(
    `SELECT a.group_id, a.reason, a.at FROM access_ended a
     WHERE a.user_id = ?1 AND a.at > ?2 AND ${NOT_BACK}
     ORDER BY a.group_id LIMIT ${MAX_GROUPS_PER_USER}`,
    userId,
    now - ACCESS_ENDED_KEEP_MS,
  );
}

/** Why the user lost group `gid`, or null when the server does not know (never in it, or over 90 days ago). */
export async function lostReason(db: Db, gid: string, userId: string, now: number): Promise<LostReason | null> {
  const row = await db.first<{ reason: LostReason }>(
    `SELECT a.reason FROM access_ended a WHERE a.user_id = ?1 AND a.group_id = ?2 AND a.at > ?3 AND ${NOT_BACK}`,
    userId,
    gid,
    now - ACCESS_ENDED_KEEP_MS,
  );
  return row?.reason ?? null;
}

export function myGroups(db: Db, userId: string) {
  return db.all<{ id: string; kind: string; name: string | null; version: number; owner_user_id: string | null; my_member_id: string }>(
    `SELECT g.id, g.kind, g.name, g.version, g.owner_user_id, m.id AS my_member_id
     FROM members m JOIN groups g ON g.id = m.group_id
     WHERE m.user_id = ?1 AND m.removed_at IS NULL AND g.deleted_at IS NULL
     ORDER BY g.id LIMIT ${MAX_GROUPS_PER_USER}`,
    userId,
  );
}

/**
 * "Known users" among `ids`: users active in at least one non-deleted group the caller is active in (4.6).
 * Returns their id and name. At most 98 ids.
 */
export function knownUsers(db: Db, me: string, ids: string[]): Promise<{ id: string; name: string }[]> {
  if (ids.length === 0) return Promise.resolve([]);
  const ph = ids.map((_, i) => `?${i + 2}`).join(", ");
  return db.all(
    `SELECT u.id, u.name FROM users u WHERE u.id IN (${ph}) AND EXISTS (
       SELECT 1 FROM members m1 JOIN groups g ON g.id = m1.group_id AND g.deleted_at IS NULL
       JOIN members m2 ON m2.group_id = m1.group_id AND m2.removed_at IS NULL AND m2.user_id = u.id
       WHERE m1.user_id = ?1 AND m1.removed_at IS NULL)`,
    me,
    ...ids,
  );
}

/** Users among `ids` already in 300 or more live groups. */
export async function usersAtGroupCap(db: Db, ids: string[]): Promise<string[]> {
  if (ids.length === 0) return [];
  const ph = ids.map((_, i) => `?${i + 1}`).join(", ");
  const rows = await db.all<{ user_id: string }>(
    `SELECT m.user_id FROM members m JOIN groups g ON g.id = m.group_id
     WHERE m.user_id IN (${ph}) AND m.removed_at IS NULL AND g.deleted_at IS NULL
     GROUP BY m.user_id HAVING COUNT(*) >= ${MAX_GROUPS_PER_USER}`,
    ...ids,
  );
  return rows.map((r) => r.user_id);
}

/**
 * The user is active in a live family other than `gid` (docs/server.md 4.18: one family per user). Reads the user's
 * member rows by index (members_user) and their groups by primary key.
 */
export async function inAnotherFamily(db: Db, userId: string, gid: string): Promise<boolean> {
  const row = await db.first<{ one: number }>(
    `SELECT 1 AS one FROM members m JOIN groups g ON g.id = m.group_id
     WHERE m.user_id = ?1 AND m.removed_at IS NULL AND g.kind = 'family' AND g.deleted_at IS NULL AND g.id <> ?2 LIMIT 1`,
    userId,
    gid,
  );
  return row !== null;
}

/**
 * `inAnotherFamily` as SQL for a guard rule or a guarded change insert: true while the user named by `userParam` (a
 * parameter like "?7") is not active in a live family other than the batch's group `?1`. Evaluated inside the batch,
 * so two phones racing past the handler's check cannot both win.
 */
export function NOT_IN_ANOTHER_FAMILY(userParam: string): string {
  return `NOT EXISTS (SELECT 1 FROM members m2 JOIN groups g2 ON g2.id = m2.group_id
    WHERE m2.user_id = ${userParam} AND m2.removed_at IS NULL AND g2.kind = 'family' AND g2.deleted_at IS NULL
      AND g2.id <> ?1)`;
}

/**
 * Leave, removal (docs/server.md 4.18): the family expenses member `mid` paid leave the family. Tombstoned at the
 * batch's version (so every phone's pull carries them), and their shares go as on a delete. Reads the family's own
 * expenses only (expenses_group_version).
 */
export function familyExpenseTombstoneStmts(db: Db, gid: string, mid: string, actor: string, now: number): D1PreparedStatement[] {
  return [
    db.stmt(
      `UPDATE expenses SET deleted_at = ?3, updated_at = ?3, updated_by_member_id = ?4,
         version = (SELECT version FROM groups WHERE id = ?1)
       WHERE group_id = ?1 AND paid_by_member_id = ?2 AND deleted_at IS NULL`,
      gid,
      mid,
      now,
      actor,
    ),
    db.stmt(
      `DELETE FROM expense_shares WHERE expense_id IN
         (SELECT id FROM expenses WHERE group_id = ?1 AND paid_by_member_id = ?2 AND deleted_at IS NOT NULL)`,
      gid,
      mid,
    ),
  ];
}

export interface NewMember {
  id: string;
  userId: string | null;
  name: string;
  phone: string | null;
}

/**
 * Group create: groups row, members and change at version 1 (docs/server.md 3.1, the one exception to the bump).
 * With `onlyFamilyOf` (a family, 4.18) the change row is the guard: its entity id is NULL, and the batch fails, when
 * that user is already active in another live family by the time the batch runs.
 */
export function createGroupStmts(
  db: Db,
  p: {
    gid: string;
    kind: GroupKind;
    name: string | null;
    ownerUserId: string;
    actor: string;
    members: NewMember[];
    now: number;
    onlyFamilyOf?: string;
  },
): D1PreparedStatement[] {
  const stmts = [
    db.stmt(
      `INSERT INTO groups (id, kind, name, owner_user_id, version, created_at, updated_at) VALUES (?1, ?2, ?3, ?4, 1, ?5, ?5)`,
      p.gid,
      p.kind,
      p.name,
      p.ownerUserId,
      p.now,
    ),
  ];
  for (const part of chunk(p.members, 10)) {
    const values: (string | number | null)[] = [];
    const rows = part.map((m, i) => {
      const b = i * 9;
      values.push(m.id, p.gid, m.userId, m.name, m.phone, p.actor, m.userId ? p.now : null, 1, p.now);
      return `(?${b + 1}, ?${b + 2}, ?${b + 3}, ?${b + 4}, ?${b + 5}, ?${b + 6}, ?${b + 7}, ?${b + 8}, ?${b + 9})`;
    });
    stmts.push(
      db.stmt(
        `INSERT INTO members (id, group_id, user_id, name, phone, added_by_member_id, joined_at, version, created_at)
         VALUES ${rows.join(", ")}`,
        ...values,
      ),
    );
  }
  if (p.onlyFamilyOf !== undefined) {
    stmts.push(
      db.stmt(
        `INSERT INTO changes (group_id, version, kind, op, entity_id, actor_member_id, created_at)
         VALUES (?1, 1, 'group', 'create', (SELECT ?1 WHERE ${NOT_IN_ANOTHER_FAMILY("?4")}), ?2, ?3)`,
        p.gid,
        p.actor,
        p.now,
        p.onlyFamilyOf,
      ),
    );
    return stmts;
  }
  stmts.push(
    db.stmt(
      `INSERT INTO changes (group_id, version, kind, op, entity_id, actor_member_id, created_at)
       VALUES (?1, 1, 'group', 'create', ?1, ?2, ?3)`,
      p.gid,
      p.actor,
      p.now,
    ),
  );
  return stmts;
}

export function renameGroupStmt(db: Db, gid: string, name: string) {
  return db.stmt("UPDATE groups SET name = ?2 WHERE id = ?1 AND kind <> 'direct'", gid, name);
}

export function tombstoneGroupStmts(db: Db, gid: string, now: number): D1PreparedStatement[] {
  return [
    db.stmt("UPDATE groups SET deleted_at = ?2 WHERE id = ?1 AND deleted_at IS NULL", gid, now),
    db.stmt("UPDATE links SET revoked_at = ?2 WHERE group_id = ?1 AND revoked_at IS NULL", gid, now),
  ];
}

export function insertMemberStmt(db: Db, gid: string, m: NewMember, actor: string, now: number) {
  return db.stmt(
    `INSERT INTO members (id, group_id, user_id, name, phone, added_by_member_id, joined_at, version, created_at)
     VALUES (?2, ?1, ?3, ?4, ?5, ?6, ?7, ${V1}, ?8)`,
    gid,
    m.id,
    m.userId,
    m.name,
    m.phone,
    actor,
    m.userId ? now : null,
    now,
  );
}

export function editFriendStmt(db: Db, gid: string, mid: string, name: string, phone: string | null) {
  return db.stmt(
    `UPDATE members SET name = ?3, phone = ?4, version = ${V1}
     WHERE id = ?2 AND group_id = ?1 AND user_id IS NULL AND removed_at IS NULL`,
    gid,
    mid,
    name,
    phone,
  );
}

/**
 * Remove and leave: the member is marked removed, and every link for that place or made by it stops working. A
 * person who left or was removed could otherwise keep a link they made for a friend without the app and join again
 * in that friend's place (the same rule as undo-join).
 */
export function removeMemberStmts(db: Db, gid: string, mid: string, now: number): D1PreparedStatement[] {
  return [
    db.stmt(
      `UPDATE members SET removed_at = ?3, version = ${V1} WHERE id = ?2 AND group_id = ?1 AND removed_at IS NULL`,
      gid,
      mid,
      now,
    ),
    // links_group index: reads the group's links only.
    db.stmt(
      `UPDATE links SET revoked_at = ?3
       WHERE group_id = ?1 AND (member_id = ?2 OR created_by_member_id = ?2) AND revoked_at IS NULL`,
      gid,
      mid,
      now,
    ),
  ];
}

export function reactivateMemberStmt(db: Db, gid: string, mid: string) {
  return db.stmt(
    `UPDATE members SET removed_at = NULL, version = ${V1} WHERE id = ?2 AND group_id = ?1 AND removed_at IS NOT NULL`,
    gid,
    mid,
  );
}

/**
 * Undo a join by link (4.6): the place becomes the friend without the app it was before the join (name and phone as
 * they were then), and every link for that place, or made by it while the person held it, stops working. The member
 * update matches only the join the handler checked (same user, same link, still active, joined after `since`); if it
 * matches nothing, the guard fails and the batch rolls back. The row keeps its id, so expenses, shares and
 * settlements, and so balances, stay as they are.
 */
export function undoJoinStmts(
  db: Db,
  p: { gid: string; mid: string; userId: string; linkId: string; since: number; now: number },
): D1PreparedStatement[] {
  return [
    db.stmt(
      `UPDATE members SET user_id = NULL, name = COALESCE(join_prev_name, name), phone = join_prev_phone,
         joined_at = NULL, join_link_id = NULL, join_prev_name = NULL, join_prev_phone = NULL, version = ${V1}
       WHERE id = ?2 AND group_id = ?1 AND user_id = ?3 AND join_link_id = ?4 AND removed_at IS NULL AND joined_at > ?5`,
      p.gid,
      p.mid,
      p.userId,
      p.linkId,
      p.since,
    ),
    // links_group index: reads the group's links only.
    db.stmt(
      `UPDATE links SET revoked_at = ?3
       WHERE group_id = ?1 AND (member_id = ?2 OR created_by_member_id = ?2) AND revoked_at IS NULL`,
      p.gid,
      p.mid,
      p.now,
    ),
  ];
}

/** Ownership passes to the active member on the app with the earliest joined_at (or NULL when none). */
export function passOwnershipStmt(db: Db, gid: string, fromUserId: string) {
  return db.stmt(
    `UPDATE groups SET owner_user_id = (
       SELECT o.user_id FROM members o WHERE o.group_id = ?1 AND o.user_id IS NOT NULL AND o.user_id <> ?2
         AND o.removed_at IS NULL ORDER BY o.joined_at, o.id LIMIT 1)
     WHERE id = ?1 AND owner_user_id = ?2`,
    gid,
    fromUserId,
  );
}

/**
 * Tombstones a 'group' or a 'family' (never a 'direct') once no member on the app is left; revokes its links too. A
 * family's placeholder (an invite not yet accepted) does not keep it alive.
 */
export function tombstoneIfEmptyStmts(db: Db, gid: string, now: number): D1PreparedStatement[] {
  const empty = `kind IN ('group', 'family') AND NOT EXISTS (SELECT 1 FROM members o WHERE o.group_id = ?1 AND o.user_id IS NOT NULL AND o.removed_at IS NULL)`;
  return [
    db.stmt(`UPDATE groups SET deleted_at = ?2 WHERE id = ?1 AND deleted_at IS NULL AND ${empty}`, gid, now),
    db.stmt(
      `UPDATE links SET revoked_at = ?2 WHERE group_id = ?1 AND revoked_at IS NULL
         AND EXISTS (SELECT 1 FROM groups WHERE id = ?1 AND deleted_at = ?2)`,
      gid,
      now,
    ),
  ];
}

export function activeMemberCount(db: Db, gid: string) {
  return db.first<{ n: number }>("SELECT COUNT(*) AS n FROM members WHERE group_id = ?1 AND removed_at IS NULL", gid);
}

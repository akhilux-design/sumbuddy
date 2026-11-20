// Statements every group mutation is built from (docs/server.md 3.1):
//   1. bump the group version, 2. entity statements at that version, 3. the guard (also the change row),
//   4. the idempotency key.
import type { Db } from "./db";

export type ChangeKind = "group" | "member" | "expense" | "settlement";
export type ChangeOp = "create" | "update" | "delete" | "join" | "leave" | "remove" | "confirm";

/** SQL for the group's current version; `?1` must be the group id. */
export const V1 = "(SELECT version FROM groups WHERE id = ?1)";

export function bump(db: Db, gid: string, now: number): D1PreparedStatement {
  return db.stmt("UPDATE groups SET version = version + 1, updated_at = ?2 WHERE id = ?1 AND deleted_at IS NULL", gid, now);
}

export interface GuardRule {
  /** SQL over the entity row (unaliased columns) or other tables; parameters numbered from ?7. */
  sql: string;
  params: (string | number)[];
}

const TABLES = { group: "groups", member: "members", expense: "expenses", settlement: "settlements" } as const;

/**
 * The guard insert. Fails (NOT NULL on changes.version or changes.entity_id) and so rolls the batch back when the
 * group is deleted, the entity was not written at this version in this group, or the rule no longer holds.
 * Returns the change's version.
 */
export function guard(
  db: Db,
  p: { gid: string; kind: ChangeKind; op: ChangeOp; entityId: string; actor: string | null; now: number; rule?: GuardRule },
): D1PreparedStatement {
  const rule = p.rule ? `(${p.rule.sql})` : "1";
  const table = TABLES[p.kind];
  const entity =
    table === "groups"
      ? `(SELECT id FROM groups WHERE id = ?1 AND id = ?4 AND ${rule})`
      : `(SELECT id FROM ${table} WHERE id = ?4 AND group_id = ?1 AND version = ${V1} AND ${rule})`;
  const sql = `INSERT INTO changes (group_id, version, kind, op, entity_id, actor_member_id, created_at)
    VALUES (?1, (SELECT version FROM groups WHERE id = ?1 AND deleted_at IS NULL), ?2, ?3, ${entity}, ?5, ?6)
    RETURNING version`;
  return db.stmt(sql, p.gid, p.kind, p.op, p.entityId, p.actor, p.now, ...(p.rule?.params ?? []));
}

/**
 * Rule: every one of `ids` (distinct) is an active member of the group, so a member removed meanwhile cannot gain a
 * balance. It also stands in for a foreign key: the member columns of expenses, shares and settlements have none
 * (0001_init.sql, members_no_fk).
 */
export function membersActiveRule(ids: string[]): GuardRule | undefined {
  const unique = [...new Set(ids)];
  if (unique.length === 0) return undefined;
  const ph = unique.map((_, i) => `?${7 + i}`).join(", ");
  return {
    sql: `(SELECT COUNT(*) FROM members mx WHERE mx.id IN (${ph}) AND mx.group_id = ?1 AND mx.removed_at IS NULL) = ${unique.length}`,
    params: unique,
  };
}

/** Rule for the balance checks: nothing was written since the version the check used. */
export function versionRule(checked: number): GuardRule {
  return { sql: `${V1} = ?7 + 1`, params: [checked] };
}

/**
 * Rule for removing a member: `versionRule`, and the place is still held by the person the checks saw (`userId`), or
 * by nobody when they saw a friend without the app. A place taken through a link between the read of the member and
 * the batch then fails the guard, whichever version the checks read.
 */
export function placeRule(checked: number, userId: string | null): GuardRule {
  const version = versionRule(checked);
  return userId === null
    ? { sql: `${version.sql} AND user_id IS NULL`, params: version.params }
    : { sql: `${version.sql} AND user_id = ?8`, params: [...version.params, userId] };
}

/**
 * Rule for an import (docs/server.md 4.17), on the first guard of its batch: every member the items name is an active
 * member of the group, the caller still owns it, and nobody else in it is on the app. D1 runs one batch at a time, so
 * checking it once covers the whole batch: a friend who takes a place through a link, or an ownership change, between
 * the handler's checks and the batch fails it and rolls every item back. At most 50 ids plus the caller: ?7 to ?57.
 */
export function importRule(ids: string[], callerUserId: string): GuardRule {
  const unique = [...new Set(ids)];
  const ph = unique.map((_, i) => `?${7 + i}`).join(", ");
  const caller = `?${7 + unique.length}`;
  return {
    sql: `(SELECT COUNT(*) FROM members mx WHERE mx.id IN (${ph}) AND mx.group_id = ?1 AND mx.removed_at IS NULL) = ${unique.length}
      AND (SELECT owner_user_id FROM groups WHERE id = ?1) = ${caller}
      AND NOT EXISTS (SELECT 1 FROM members my WHERE my.group_id = ?1 AND my.user_id IS NOT NULL
                      AND my.user_id <> ${caller} AND my.removed_at IS NULL)`,
    params: [...unique, callerUserId],
  };
}

/** Writes the Idempotency-Key row as the last statement of a batch. */
export interface IdemWriter {
  stmt(status: number, entityId: string | null): D1PreparedStatement;
  /**
   * The key row with the group's version as it is at that point of the batch (as text in `entity_id`): the last
   * version an import wrote, which its replay answers from (4.17).
   */
  stmtGroupVersion(status: number, gid: string): D1PreparedStatement;
}

export function idemWriter(db: Db, userId: string, key: string, hash: string, now: number): IdemWriter {
  return {
    stmt: (status, entityId) =>
      db.stmt(
        "INSERT INTO idempotency_keys (user_id, key, request_hash, status, entity_id, created_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
        userId,
        key,
        hash,
        status,
        entityId,
        now,
      ),
    stmtGroupVersion: (status, gid) =>
      db.stmt(
        `INSERT INTO idempotency_keys (user_id, key, request_hash, status, entity_id, created_at)
         VALUES (?1, ?2, ?3, ?4, (SELECT CAST(version AS TEXT) FROM groups WHERE id = ?5), ?6)`,
        userId,
        key,
        hash,
        status,
        gid,
        now,
      ),
  };
}

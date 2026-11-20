// Share links (docs/server.md 4.10). Only the SHA-256 of a token is stored.
import type { Db } from "./db";

export const LINK_TTL_MS = 90 * 24 * 60 * 60 * 1000;
/**
 * A family invite link works for 7 days (docs/server.md 4.18): whoever holds it can join and see every family
 * expense, and the partner normally joins the same day.
 */
export const FAMILY_LINK_TTL_MS = 7 * 24 * 60 * 60 * 1000;

export interface LinkRow {
  id: string;
  token_hash: string;
  group_id: string;
  member_id: string;
  created_by_member_id: string;
  created_at: number;
  expires_at: number;
  revoked_at: number | null;
}

export function getLinkById(db: Db, linkId: string): Promise<LinkRow | null> {
  return db.first<LinkRow>(
    "SELECT id, token_hash, group_id, member_id, created_by_member_id, created_at, expires_at, revoked_at FROM links WHERE id = ?1",
    linkId,
  );
}

export interface LiveLink {
  id: string;
  group_id: string;
  member_id: string;
  created_by_member_id: string;
  version: number;
  kind: string;
  name: string | null;
}

/**
 * A working link by token hash with its group (about 2 rows read): not revoked, not expired, group not deleted.
 * Null otherwise, whatever the reason (same answer for every case, 4.10).
 */
export function liveLinkByHash(db: Db, tokenHash: string, now: number): Promise<LiveLink | null> {
  return db.first<LiveLink>(
    `SELECT l.id, l.group_id, l.member_id, l.created_by_member_id, g.version, g.kind, g.name
     FROM links l JOIN groups g ON g.id = l.group_id
     WHERE l.token_hash = ?1 AND l.revoked_at IS NULL AND l.expires_at > ?2 AND g.deleted_at IS NULL`,
    tokenHash,
    now,
  );
}

/**
 * For a repeated accept: the link (revoked by the accept itself) whose friend slot now belongs to `userId`, in a
 * live group and before expiry. Null otherwise.
 */
export function acceptedLinkByHash(db: Db, tokenHash: string, userId: string, now: number) {
  return db.first<{ group_id: string; member_id: string; version: number }>(
    `SELECT l.group_id, l.member_id, m.version FROM links l
     JOIN members m ON m.id = l.member_id JOIN groups g ON g.id = l.group_id
     WHERE l.token_hash = ?1 AND l.expires_at > ?3 AND m.user_id = ?2 AND m.removed_at IS NULL AND g.deleted_at IS NULL`,
    tokenHash,
    userId,
    now,
  );
}

/**
 * Inserts the link, expiring `ttlMs` after `now`, only while the friend is an active member of a live group. Returns
 * rows inserted (0 or 1).
 */
export function insertLink(
  db: Db,
  p: { linkId: string; tokenHash: string; gid: string; mid: string; createdBy: string; now: number; ttlMs: number },
): Promise<number> {
  return db.run(
    `INSERT INTO links (id, token_hash, group_id, member_id, created_by_member_id, created_at, expires_at)
     SELECT ?1, ?2, ?3, ?4, ?5, ?6, ?7
     WHERE EXISTS (SELECT 1 FROM members m JOIN groups g ON g.id = m.group_id
                   WHERE m.id = ?4 AND m.group_id = ?3 AND m.removed_at IS NULL AND g.deleted_at IS NULL)`,
    p.linkId,
    p.tokenHash,
    p.gid,
    p.mid,
    p.createdBy,
    p.now,
    p.now + p.ttlMs,
  );
}

export function revokeLinkStmt(db: Db, gid: string, linkId: string, now: number) {
  return db.stmt("UPDATE links SET revoked_at = ?3 WHERE id = ?2 AND group_id = ?1 AND revoked_at IS NULL", gid, linkId, now);
}

export function revokeMemberLinksStmt(db: Db, gid: string, mid: string, now: number) {
  return db.stmt(
    "UPDATE links SET revoked_at = ?3 WHERE member_id = ?2 AND group_id = ?1 AND revoked_at IS NULL",
    gid,
    mid,
    now,
  );
}

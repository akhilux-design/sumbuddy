// Daily cron (docs/server.md 4.13). SQL only: free-plan limits (10 ms CPU, 50 queries) apply to it too.
import type { Db } from "./db";
import { ACCESS_ENDED_KEEP_MS } from "./groups";
import { DAY, indiaMonth } from "../time";

export const PURGE_ROW_BUDGET = 10_000;
export const PURGE_CHUNK = 500;
const MAX_QUERIES = 45;

/**
 * Expired idempotency keys, old sessions, old links with their cache rows, invite cache rows of past months, and the
 * reasons people lost groups (`access_ended`) once 90 days old.
 */
export async function purgeExpired(db: Db, now: number): Promise<void> {
  const linkGone = "(expires_at < ?1 OR (revoked_at IS NOT NULL AND revoked_at < ?1))";
  await db.batch([
    db.stmt("DELETE FROM idempotency_keys WHERE created_at < ?1", now - 7 * DAY),
    db.stmt(
      "DELETE FROM sessions WHERE expires_at < ?1 OR (revoked_at IS NOT NULL AND revoked_at < ?1)",
      now - 30 * DAY,
    ),
    db.stmt(
      `DELETE FROM group_cache WHERE (group_id, cache_key) IN (SELECT group_id, 'link:' || id FROM links WHERE ${linkGone})`,
      now - 30 * DAY,
    ),
    db.stmt(`DELETE FROM links WHERE ${linkGone}`, now - 30 * DAY),
    db.stmt(
      "DELETE FROM group_cache WHERE cache_key LIKE 'invite:%' AND substr(cache_key, -7) < ?1",
      indiaMonth(now),
    ),
    // Why people lost groups (0002): kept 90 days. A small table (recent leaves, removals, undos and deletes only).
    db.stmt("DELETE FROM access_ended WHERE at < ?1", now - ACCESS_ENDED_KEEP_MS),
  ]);
}

/**
 * Purges tombstoned groups, oldest first, children before parents, in chunks, stopping at about `budget` deleted
 * rows. A group whose children are all gone gets `purged_at`. Returns the number of rows deleted.
 */
export async function purgeDeletedGroups(
  db: Db,
  now: number,
  budget = PURGE_ROW_BUDGET,
  chunkSize = PURGE_CHUNK,
): Promise<number> {
  let deleted = 0;
  const groups = await db.all<{ id: string }>(
    "SELECT id FROM groups WHERE deleted_at IS NOT NULL AND purged_at IS NULL ORDER BY deleted_at, id LIMIT 10",
  );
  const steps: string[] = [
    `DELETE FROM expense_shares WHERE (expense_id, member_id) IN (
       SELECT s.expense_id, s.member_id FROM expense_shares s JOIN expenses e ON e.id = s.expense_id
       WHERE e.group_id = ?1 LIMIT ?2)`,
    "DELETE FROM expenses WHERE id IN (SELECT id FROM expenses WHERE group_id = ?1 LIMIT ?2)",
    "DELETE FROM settlements WHERE id IN (SELECT id FROM settlements WHERE group_id = ?1 LIMIT ?2)",
    "DELETE FROM links WHERE id IN (SELECT id FROM links WHERE group_id = ?1 LIMIT ?2)",
    "DELETE FROM changes WHERE (group_id, version) IN (SELECT group_id, version FROM changes WHERE group_id = ?1 LIMIT ?2)",
    "DELETE FROM group_cache WHERE (group_id, cache_key) IN (SELECT group_id, cache_key FROM group_cache WHERE group_id = ?1 LIMIT ?2)",
    "DELETE FROM members WHERE id IN (SELECT id FROM members WHERE group_id = ?1 LIMIT ?2)",
  ];
  for (const g of groups) {
    for (const sql of steps) {
      for (;;) {
        // Out of budget: stop; the group stays unpurged and the next run carries on where this one stopped.
        if (deleted >= budget || db.queries >= MAX_QUERIES) return deleted;
        const limit = Math.min(chunkSize, budget - deleted);
        const n = await db.run(sql, g.id, limit);
        deleted += n;
        if (n < limit) break; // this table has no row of the group left
      }
    }
    if (db.queries >= MAX_QUERIES) return deleted;
    await db.run("UPDATE groups SET purged_at = ?2 WHERE id = ?1", g.id, now);
  }
  return deleted;
}

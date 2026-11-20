// Expenses, shares, settlements, change pages and the per-group derived data (balances).
import { chunk, type Db } from "./db";
import { V1 } from "./mutation";
import {
  MEMBER_COLUMNS,
  MEMBER_FROM,
  type ExpenseRow,
  type MemberRow,
  type SettlementRow,
  type ShareRow,
} from "./shapes";
import { nets, simplify, type Net, type Transfer } from "../domain/splits";

export const MAX_EXPENSES_PER_GROUP = 20_000;
export const PAGE_VERSIONS = 100;

const EXPENSE_COLUMNS = `id, group_id, description, category, amount_paise, currency, occurred_on, paid_by_member_id,
  split_method, auto, created_by_member_id, updated_by_member_id, version, created_at, updated_at, deleted_at`;
const SETTLEMENT_COLUMNS = `id, group_id, from_member_id, to_member_id, amount_paise, method, matched_from_sms,
  occurred_on, recorded_by_member_id, confirmed_at, version, created_at, deleted_at`;

export async function getExpense(db: Db, eid: string): Promise<{ expense: ExpenseRow; shares: ShareRow[] } | null> {
  const [e, s] = await db.batch([
    db.stmt(`SELECT ${EXPENSE_COLUMNS} FROM expenses WHERE id = ?1`, eid),
    db.stmt("SELECT expense_id, member_id, share_paise, input FROM expense_shares WHERE expense_id = ?1", eid),
  ]);
  const expense = (e.results as ExpenseRow[])[0];
  return expense ? { expense, shares: s.results as ShareRow[] } : null;
}

export async function liveExpenseCount(db: Db, gid: string): Promise<number> {
  const r = await db.first<{ n: number }>("SELECT COUNT(*) AS n FROM expenses WHERE group_id = ?1 AND deleted_at IS NULL", gid);
  return r?.n ?? 0;
}

export interface ExpenseWrite {
  gid: string;
  eid: string;
  description: string;
  category: string;
  amountPaise: number;
  occurredOn: string;
  paidByMemberId: string;
  splitMethod: string;
  auto: boolean;
  actor: string;
  shares: { memberId: string; sharePaise: number; input: number | null }[];
  now: number;
}

/**
 * Upsert (never touching another group's row or a tombstone), then replace every share. With `create` (an import,
 * docs/server.md 4.17) it is a plain insert: an id that exists fails the batch on the primary key instead of updating
 * the row, and there are no old shares to delete.
 */
export function expenseStmts(db: Db, w: ExpenseWrite, opts: { create?: boolean } = {}): D1PreparedStatement[] {
  const upsert = opts.create
    ? ""
    : `ON CONFLICT(id) DO UPDATE SET description = excluded.description, category = excluded.category,
         amount_paise = excluded.amount_paise, occurred_on = excluded.occurred_on,
         paid_by_member_id = excluded.paid_by_member_id, split_method = excluded.split_method, auto = excluded.auto,
         updated_by_member_id = excluded.updated_by_member_id, version = excluded.version,
         updated_at = excluded.updated_at
       WHERE expenses.group_id = excluded.group_id AND expenses.deleted_at IS NULL`;
  const stmts = [
    db.stmt(
      `INSERT INTO expenses (id, group_id, description, category, amount_paise, currency, occurred_on, paid_by_member_id,
         split_method, auto, created_by_member_id, updated_by_member_id, version, created_at, updated_at)
       VALUES (?2, ?1, ?3, ?4, ?5, 'INR', ?6, ?7, ?8, ?9, ?10, ?10, ${V1}, ?11, ?11)
       ${upsert}`,
      w.gid,
      w.eid,
      w.description,
      w.category,
      w.amountPaise,
      w.occurredOn,
      w.paidByMemberId,
      w.splitMethod,
      w.auto ? 1 : 0,
      w.actor,
      w.now,
    ),
  ];
  if (!opts.create) stmts.push(db.stmt("DELETE FROM expense_shares WHERE expense_id = ?1", w.eid));
  for (const part of chunk(w.shares, 25)) {
    const values: (string | number | null)[] = [];
    const rows = part.map((s, i) => {
      values.push(w.eid, s.memberId, s.sharePaise, s.input);
      return `(?${i * 4 + 1}, ?${i * 4 + 2}, ?${i * 4 + 3}, ?${i * 4 + 4})`;
    });
    stmts.push(db.stmt(`INSERT INTO expense_shares (expense_id, member_id, share_paise, input) VALUES ${rows.join(", ")}`, ...values));
  }
  return stmts;
}

export function deleteExpenseStmts(db: Db, gid: string, eid: string, actor: string, now: number): D1PreparedStatement[] {
  return [
    db.stmt(
      `UPDATE expenses SET deleted_at = ?3, updated_at = ?3, updated_by_member_id = ?4, version = ${V1}
       WHERE id = ?2 AND group_id = ?1 AND deleted_at IS NULL`,
      gid,
      eid,
      now,
      actor,
    ),
    db.stmt(
      "DELETE FROM expense_shares WHERE expense_id = ?1 AND EXISTS (SELECT 1 FROM expenses WHERE id = ?1 AND group_id = ?2)",
      eid,
      gid,
    ),
  ];
}

export function getSettlement(db: Db, sid: string): Promise<SettlementRow | null> {
  return db.first<SettlementRow>(`SELECT ${SETTLEMENT_COLUMNS} FROM settlements WHERE id = ?1`, sid);
}

export function insertSettlementStmt(
  db: Db,
  s: {
    gid: string;
    sid: string;
    from: string;
    to: string;
    amountPaise: number;
    method: string;
    matchedFromSms: boolean;
    occurredOn: string;
    recordedBy: string;
    confirmedAt: number | null;
    now: number;
  },
) {
  return db.stmt(
    `INSERT INTO settlements (id, group_id, from_member_id, to_member_id, amount_paise, method, matched_from_sms,
       occurred_on, recorded_by_member_id, confirmed_at, version, created_at)
     VALUES (?2, ?1, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ${V1}, ?11)`,
    s.gid,
    s.sid,
    s.from,
    s.to,
    s.amountPaise,
    s.method,
    s.matchedFromSms ? 1 : 0,
    s.occurredOn,
    s.recordedBy,
    s.confirmedAt,
    s.now,
  );
}

export function confirmSettlementStmt(db: Db, gid: string, sid: string, now: number) {
  return db.stmt(
    `UPDATE settlements SET confirmed_at = ?3, version = ${V1}
     WHERE id = ?2 AND group_id = ?1 AND deleted_at IS NULL AND confirmed_at IS NULL`,
    gid,
    sid,
    now,
  );
}

export function deleteSettlementStmt(db: Db, gid: string, sid: string, now: number) {
  return db.stmt(
    `UPDATE settlements SET deleted_at = ?3, version = ${V1} WHERE id = ?2 AND group_id = ?1 AND deleted_at IS NULL`,
    gid,
    sid,
    now,
  );
}

export interface ChangeRow {
  version: number;
  kind: string;
  op: string;
  entity_id: string;
  actor_member_id: string | null;
  created_at: number;
}

/** Rows of one pull window `since < version <= to` (index range scans), one batch. */
export async function changePage(db: Db, gid: string, since: number, to: number) {
  const [m, e, s, st, c] = await db.batch([
    db.stmt(`SELECT ${MEMBER_COLUMNS} FROM ${MEMBER_FROM} WHERE m.group_id = ?1 AND m.version > ?2 AND m.version <= ?3 ORDER BY m.version, m.id`, gid, since, to),
    db.stmt(`SELECT ${EXPENSE_COLUMNS} FROM expenses WHERE group_id = ?1 AND version > ?2 AND version <= ?3 ORDER BY version, id`, gid, since, to),
    db.stmt(
      `SELECT s.expense_id, s.member_id, s.share_paise, s.input FROM expense_shares s JOIN expenses e ON e.id = s.expense_id
       WHERE e.group_id = ?1 AND e.version > ?2 AND e.version <= ?3 AND e.deleted_at IS NULL`,
      gid,
      since,
      to,
    ),
    db.stmt(`SELECT ${SETTLEMENT_COLUMNS} FROM settlements WHERE group_id = ?1 AND version > ?2 AND version <= ?3 ORDER BY version, id`, gid, since, to),
    db.stmt(
      `SELECT version, kind, op, entity_id, actor_member_id, created_at FROM changes
       WHERE group_id = ?1 AND version > ?2 AND version <= ?3 ORDER BY version`,
      gid,
      since,
      to,
    ),
  ]);
  return {
    members: m.results as MemberRow[],
    expenses: e.results as ExpenseRow[],
    shares: s.results as ShareRow[],
    settlements: st.results as SettlementRow[],
    changes: c.results as ChangeRow[],
  };
}

// ---------- derived data (docs/server.md 4.16) ----------

/** Reads the cache row; answers from it when it was computed at `version`, else computes and stores. */
export async function cached<T>(db: Db, gid: string, version: number, key: string, compute: () => Promise<T>): Promise<T> {
  const row = await db.first<{ version: number; body: string }>(
    "SELECT version, body FROM group_cache WHERE group_id = ?1 AND cache_key = ?2",
    gid,
    key,
  );
  if (row && row.version === version) return JSON.parse(row.body) as T;
  const body = await compute();
  await db.run(
    `INSERT INTO group_cache (group_id, cache_key, version, body) VALUES (?1, ?2, ?3, ?4)
     ON CONFLICT (group_id, cache_key) DO UPDATE SET version = excluded.version, body = excluded.body
     WHERE excluded.version > group_cache.version`,
    gid,
    key,
    version,
    JSON.stringify(body),
  );
  return body;
}

export interface Balances {
  version: number;
  nets: Net[];
  transfers: Transfer[];
}

/** 6.2 in one statement, then 6.3. Every member of the group is listed, with 0 when it has no rows. */
export async function computeBalances(db: Db, gid: string, version: number): Promise<Balances> {
  const [rows, members] = await db.batch([
    db.stmt(
      `SELECT member_id, SUM(delta) AS net FROM (
         SELECT paid_by_member_id AS member_id, amount_paise AS delta FROM expenses
           WHERE group_id = ?1 AND deleted_at IS NULL
         UNION ALL SELECT s.member_id, -s.share_paise FROM expense_shares s JOIN expenses e ON e.id = s.expense_id
           WHERE e.group_id = ?1 AND e.deleted_at IS NULL
         UNION ALL SELECT from_member_id, amount_paise FROM settlements WHERE group_id = ?1 AND deleted_at IS NULL
         UNION ALL SELECT to_member_id, -amount_paise FROM settlements WHERE group_id = ?1 AND deleted_at IS NULL
       ) GROUP BY member_id`,
      gid,
    ),
    db.stmt("SELECT id FROM members WHERE group_id = ?1", gid),
  ]);
  const byId = new Map<string, number>();
  for (const m of members.results as { id: string }[]) byId.set(m.id, 0);
  for (const r of rows.results as { member_id: string; net: number }[]) byId.set(r.member_id, r.net);
  const list = [...byId.entries()]
    .sort((a, b) => (a[0] < b[0] ? -1 : 1))
    .map(([memberId, netPaise]) => ({ memberId, netPaise }));
  return { version, nets: list, transfers: simplify(list) };
}

export function balances(db: Db, gid: string, version: number): Promise<Balances> {
  return cached(db, gid, version, "balances", () => computeBalances(db, gid, version));
}

/** All live expenses with shares and settlements of a group, for the link page and invite preview. */
export async function wholeGroup(db: Db, gid: string) {
  const [e, s, st] = await db.batch([
    db.stmt(`SELECT ${EXPENSE_COLUMNS} FROM expenses WHERE group_id = ?1 AND deleted_at IS NULL`, gid),
    db.stmt(
      `SELECT s.expense_id, s.member_id, s.share_paise, s.input FROM expense_shares s JOIN expenses e ON e.id = s.expense_id
       WHERE e.group_id = ?1 AND e.deleted_at IS NULL`,
      gid,
    ),
    db.stmt(`SELECT ${SETTLEMENT_COLUMNS} FROM settlements WHERE group_id = ?1 AND deleted_at IS NULL`, gid),
  ]);
  const expenses = e.results as ExpenseRow[];
  const shares = st.results as SettlementRow[];
  const shareRows = s.results as ShareRow[];
  const sharesByExpense = new Map<string, ShareRow[]>();
  for (const r of shareRows) {
    const list = sharesByExpense.get(r.expense_id) ?? [];
    list.push(r);
    sharesByExpense.set(r.expense_id, list);
  }
  const n = nets(
    expenses.map((x) => ({
      paidBy: x.paid_by_member_id,
      amountPaise: x.amount_paise,
      shares: (sharesByExpense.get(x.id) ?? []).map((r) => ({ memberId: r.member_id, sharePaise: r.share_paise })),
    })),
    shares.map((x) => ({ from: x.from_member_id, to: x.to_member_id, amountPaise: x.amount_paise })),
  );
  return { expenses, sharesByExpense, settlements: shares, transfers: simplify(n) };
}

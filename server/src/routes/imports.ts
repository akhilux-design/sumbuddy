// Import from Splitwise (docs/server.md 4.17, docs/splitwise-import.md 3): up to 50 past expenses or settlements in one
// request and one D1 batch, each with its own version and change row, into a group nobody else has joined yet.
// Nothing here is logged: the router writes the route template only (log.ts).
import { after, guardVersion, IdempotencyRace, me, requireMember, runBatch, type Ctx, type Member } from "../context";
import { constraintOn, guardFailed } from "../db/db";
import { membersOf } from "../db/groups";
import { expenseStmts, insertSettlementStmt } from "../db/ledger";
import { bump, guard, importRule } from "../db/mutation";
import type { MemberRow } from "../db/shapes";
import { ApiError, badRequest, conflict, json, notAllowed, notOwner } from "../http";
import { isUuidV4 } from "../ids";
import * as v from "../validate";
import { othersOnApp } from "./groups";
import { parseExpenseFields, type ExpenseInput } from "./ledger";

/** Items per request. The phone sends at most this many, in a body of at most 30,000 bytes (32 KB cap). */
export const MAX_IMPORT_ITEMS = 50;
/** An import may not take a group's version past this (room for the create and a few member changes). */
export const MAX_IMPORT_VERSION = 6000;
/** An import counts against `RL_WRITE` once per this many items, rounded up (docs/server.md 4.15, open point 13.20). */
export const IMPORT_ITEMS_PER_WRITE = 10;

/**
 * How many times an import request counts against `RL_WRITE`: ceil(items / 10), from 1 (1 to 10 items) to 5 (41 to
 * 50). A body without an `items` array counts once; a list over 50 counts as 50 (it is refused with `400` anyway).
 * Read from the raw body before any check, so a refused request pays the same as an accepted one.
 */
export function importWriteCost(body: unknown): number {
  const items = body !== null && typeof body === "object" && !Array.isArray(body) ? (body as { items?: unknown }).items : undefined;
  const n = Array.isArray(items) ? Math.min(Math.max(items.length, 1), MAX_IMPORT_ITEMS) : 1;
  return Math.ceil(n / IMPORT_ITEMS_PER_WRITE);
}

const retryLater = () => new ApiError(503, "internal", "The change could not be applied; retry.");
const notAlone = () => notAllowed("Import only fills a group nobody else has joined.");

type ImportItem =
  | { kind: "expense"; id: string; expense: ExpenseInput }
  | { kind: "settlement"; id: string; from: string; to: string; amountPaise: number; occurredOn: string };

interface Answer {
  applied: boolean;
  firstVersion: number | null;
  lastVersion: number | null;
}

// ---------- checks (4.17 table, in the order their answers are given) ----------

/** Checks 1 to 3: an active member of a live group (`kind` group) who owns it. */
async function requireOwner(ctx: Ctx, gid: string): Promise<Member> {
  const access = await requireMember(ctx, gid); // 1: 403 not_member, 410 group_deleted
  if (access.group.kind !== "group") throw notAllowed("Only a group can be imported into."); // 2
  if (access.group.owner_user_id !== me(ctx)) throw notOwner(); // 3
  return access;
}

/** Checks 4 to 8: the body and every item, in body order. */
function parseItems(ctx: Ctx): ImportItem[] {
  const body = v.object(ctx.body);
  const list = v.array(body.items, "items", 1, MAX_IMPORT_ITEMS);
  const ids = new Set<string>();
  return list.map((raw, i): ImportItem => {
    const p = `items[${i}]`;
    const o = v.object(raw, p);
    const kind = v.oneOf(o.kind, `${p}.kind`, ["expense", "settlement"] as const);
    const id = v.uuid(o.id, `${p}.id`);
    if (!isUuidV4(id)) throw badRequest(`${p}.id`, "Expected a random (version 4) UUID.");
    if (ids.has(id)) throw badRequest(`${p}.id`, "Item ids must be unique.");
    ids.add(id);
    if (kind === "expense") {
      const expense = parseExpenseFields(o, `${p}.`, ctx.now);
      if (expense.auto) throw badRequest(`${p}.auto`, "Imported expenses do not split themselves.");
      if (expense.baseVersion !== null) throw badRequest(`${p}.baseVersion`, "An import only creates.");
      return { kind, id, expense };
    }
    const from = v.uuid(o.fromMemberId, `${p}.fromMemberId`);
    const to = v.uuid(o.toMemberId, `${p}.toMemberId`);
    if (from === to) throw badRequest(`${p}.toMemberId`, "From and to must differ.");
    return {
      kind,
      id,
      from,
      to,
      amountPaise: v.amount(o.amountPaise, `${p}.amountPaise`),
      occurredOn: v.date(o.occurredOn, `${p}.occurredOn`, ctx.now),
    };
  });
}

/** Check 9: nobody else in the group is on the app. */
function requireAlone(ctx: Ctx, members: MemberRow[]): void {
  if (othersOnApp(members, me(ctx))) throw notAlone();
}

/** Every member an item names, with its field, in body order. */
function namedMembers(items: ImportItem[]): { id: string; field: string }[] {
  const out: { id: string; field: string }[] = [];
  items.forEach((it, i) => {
    const p = `items[${i}]`;
    if (it.kind === "expense") {
      out.push({ id: it.expense.paidByMemberId, field: `${p}.paidByMemberId` });
      it.expense.shares.forEach((s, j) => out.push({ id: s.memberId, field: `${p}.shares[${j}].memberId` }));
    } else {
      out.push({ id: it.from, field: `${p}.fromMemberId` }, { id: it.to, field: `${p}.toMemberId` });
    }
  });
  return out;
}

/** Check 10: every member named is an active member of the group; the first that is not gives its field. */
function requireActive(items: ImportItem[], members: MemberRow[]): void {
  const active = new Set(members.filter((m) => m.removed_at === null).map((m) => m.id));
  for (const n of namedMembers(items)) {
    if (!active.has(n.id)) throw badRequest(n.field, "Not an active member of the group.");
  }
}

/**
 * Check 12: which item ids exist already, each looked up in its own table (expense ids in `expenses`, settlement ids in
 * `settlements`: one primary-key read per item, one batch). `none`: write. `all` in this group: an old retry whose key
 * was purged (answer `applied: false`). `other` (some exist, or one exists in another group): `409 exists_different`.
 */
async function existing(ctx: Ctx, gid: string, items: ImportItem[]): Promise<"none" | "all" | "other"> {
  const lookup = (table: string, ids: string[]) =>
    ctx.db.stmt(`SELECT id, group_id FROM ${table} WHERE id IN (${ids.map((_, i) => `?${i + 1}`).join(", ") || "NULL"})`, ...ids);
  const [e, s] = await ctx.db.batch([
    lookup("expenses", items.filter((it) => it.kind === "expense").map((it) => it.id)),
    lookup("settlements", items.filter((it) => it.kind === "settlement").map((it) => it.id)),
  ]);
  const found = new Map(
    [...(e.results as { id: string; group_id: string }[]), ...(s.results as { id: string; group_id: string }[])].map((r) => [r.id, r.group_id]),
  );
  if (found.size === 0) return "none";
  return items.every((it) => found.get(it.id) === gid) ? "all" : "other";
}

const existsDifferent = () => conflict("exists_different", "Some of these items exist with other content.");

/** An Idempotency-Key row for this request exists: the other copy of it won (the router answers as a replay). */
async function keyTaken(ctx: Ctx): Promise<boolean> {
  const row = await ctx.db.first<{ n: number }>(
    "SELECT 1 AS n FROM idempotency_keys WHERE user_id = ?1 AND key = ?2",
    me(ctx),
    ctx.req.headers.get("Idempotency-Key"),
  );
  return row !== null;
}

const notApplied = (): Response => json({ applied: false, firstVersion: null, lastVersion: null } satisfies Answer);

// ---------- POST /v1/groups/{gid}/import ----------

export async function importItems(ctx: Ctx): Promise<Response> {
  const gid = ctx.params.gid;
  const access = await requireOwner(ctx, gid); // 1-3
  const items = parseItems(ctx); // 4-8
  // 12 reads first and answers `applied: false` before 9 to 11: a part applied long ago, retried after its key was
  // purged, must not be refused because a friend joined, a member was removed or the cap was reached since (the phone
  // would take a refusal for a failed import and delete the group). Its 409 still comes last.
  const found = await existing(ctx, gid, items);
  if (found === "all") return notApplied();
  const members = await membersOf(ctx.db, gid);
  requireAlone(ctx, members); // 9
  requireActive(items, members); // 10
  if (access.group.version + items.length > MAX_IMPORT_VERSION) throw conflict("limit_reached", "The group is too big to import into."); // 11
  if (found === "other") throw existsDifferent(); // 12

  const actor = access.myMemberId;
  const stmts: D1PreparedStatement[] = [];
  const guards: number[] = [];
  items.forEach((it, i) => {
    stmts.push(bump(ctx.db, gid, ctx.now));
    if (it.kind === "expense") {
      const x = it.expense;
      stmts.push(
        ...expenseStmts(
          ctx.db,
          {
            gid,
            eid: it.id,
            description: x.description,
            category: x.category,
            amountPaise: x.amountPaise,
            occurredOn: x.occurredOn,
            paidByMemberId: x.paidByMemberId,
            splitMethod: x.splitMethod,
            auto: false,
            actor,
            shares: x.shares,
            now: ctx.now,
          },
          { create: true },
        ),
      );
    } else {
      // A Splitwise payment: confirmed at once and recorded by the importer, between any two members (4.17).
      stmts.push(
        insertSettlementStmt(ctx.db, {
          gid,
          sid: it.id,
          from: it.from,
          to: it.to,
          amountPaise: it.amountPaise,
          method: "OTHER",
          matchedFromSms: false,
          occurredOn: it.occurredOn,
          recordedBy: actor,
          confirmedAt: ctx.now,
          now: ctx.now,
        }),
      );
    }
    guards.push(stmts.length);
    stmts.push(
      guard(ctx.db, {
        gid,
        kind: it.kind,
        op: "create",
        entityId: it.id,
        actor,
        now: ctx.now,
        // Once, on the first guard: D1 runs one batch at a time, so it holds for the whole batch.
        rule: i === 0 ? importRule(namedMembers(items).map((n) => n.id), me(ctx)) : undefined,
      }),
    );
  });
  // The key keeps the last version written, which a replay answers from.
  if (ctx.idem) stmts.push(ctx.idem.stmtGroupVersion(200, gid));

  let results: D1Result[];
  try {
    results = await runBatch(ctx, "import", stmts);
  } catch (err) {
    if (guardFailed(err)) {
      // Something changed after the checks: answer as checks 1, 3, 9 and 10 read now.
      await requireOwner(ctx, gid);
      const now = await membersOf(ctx.db, gid);
      requireAlone(ctx, now);
      requireActive(items, now);
      throw retryLater();
    }
    if (constraintOn(err, "expenses.id") || constraintOn(err, "settlements.id")) {
      // Another copy of these items landed first. With this request's key: a replay. Otherwise check 12 again.
      if (await keyTaken(ctx)) throw new IdempotencyRace();
      const again = await existing(ctx, gid, items);
      if (again === "all") return notApplied();
      if (again === "other") throw existsDifferent();
      throw retryLater();
    }
    throw err;
  }
  const firstVersion = guardVersion(results, guards[0]);
  const lastVersion = guardVersion(results, guards[guards.length - 1]);
  // One room notify and one NORMAL "changed" push: only the caller's other phones, since nobody else is on the app.
  after(ctx, { gid, version: lastVersion });
  return json({ applied: true, firstVersion, lastVersion } satisfies Answer);
}

/**
 * Same key and body: the versions from the key row (the last one written). No access check: the answer holds no
 * group content, like the replays of reminders and deletes (4.3).
 */
export async function replayImport(ctx: Ctx, entityId: string | null, status: number): Promise<Response> {
  const lastVersion = Number(entityId);
  const count = (ctx.body as { items: unknown[] }).items.length;
  return json({ applied: true, firstVersion: lastVersion - count + 1, lastVersion } satisfies Answer, status);
}

// Expenses (docs/server.md 4.7), settlements (4.8), pulls and balances (4.9), and their family rules (4.18).
import { after, guardVersion, keyStmt, memberView, requireMember, runBatch, type Ctx } from "../context";
import { guardFailed } from "../db/db";
import { getMember, membersByIds } from "../db/groups";
import {
  MAX_EXPENSES_PER_GROUP,
  PAGE_VERSIONS,
  balances,
  changePage,
  confirmSettlementStmt,
  deleteExpenseStmts,
  deleteSettlementStmt,
  expenseStmts,
  getExpense,
  getSettlement,
  insertSettlementStmt,
  liveExpenseCount,
} from "../db/ledger";
import { bump, guard, membersActiveRule } from "../db/mutation";
import { expenseJson, groupJson, memberJson, settlementJson, type ExpenseRow, type MemberRow, type SettlementRow } from "../db/shapes";
import { computeShares, type SplitMethod } from "../domain/splits";
import { ApiError, badRequest, conflict, expenseDeleted, json, notAllowed, notFound } from "../http";
import * as v from "../validate";
import { familiesDoNotSettle } from "./groups";

const retryLater = () => new ApiError(503, "internal", "The change could not be applied; retry.");
const ownPaymentsOnly = () => notAllowed("In a family you share your own payments only.");

/**
 * A family expense (4.18) is one person's own payment: paid by the caller, one share, the caller's, for the full
 * amount, EQUAL, not "splits itself". So every net in a family is always 0. An update or delete of an expense someone
 * else paid is refused, so each entry only ever changes from its payer's phone.
 */
function checkFamilyExpense(input: ExpenseInput, myMemberId: string, storedPayer: string | null): void {
  if (input.paidByMemberId !== myMemberId) throw ownPaymentsOnly();
  if (storedPayer !== null && storedPayer !== myMemberId) throw ownPaymentsOnly();
  const only = input.shares.length === 1 ? input.shares[0] : null;
  if (
    input.splitMethod !== "EQUAL" ||
    only === null ||
    only.memberId !== myMemberId ||
    only.sharePaise !== input.amountPaise ||
    only.input !== null
  ) {
    throw badRequest("shares", "Family expenses are not split.");
  }
  if (input.auto) throw badRequest("auto", "Family expenses are not split.");
}

// ---------- expenses ----------

export interface ExpenseInput {
  description: string;
  category: string;
  amountPaise: number;
  occurredOn: string;
  paidByMemberId: string;
  splitMethod: SplitMethod;
  auto: boolean;
  shares: { memberId: string; sharePaise: number; input: number | null }[];
  baseVersion: number | null;
}

/**
 * Every rule of docs/server.md 4.7 for one expense body, shared by `PUT .../expenses/{eid}` (prefix "") and the
 * import's items (prefix "items[3].", 4.17). Each `field` of an error starts with the prefix.
 */
export function parseExpenseFields(raw: unknown, prefix: string, now: number): ExpenseInput {
  const b = v.object(raw, prefix === "" ? "body" : prefix.replace(/\.$/, ""));
  const amountPaise = v.amount(b.amountPaise, `${prefix}amountPaise`);
  const splitMethod = v.oneOf(b.splitMethod, `${prefix}splitMethod`, v.SPLIT_METHODS);
  const list = v.array(b.shares, `${prefix}shares`, 1, 50);
  const seen = new Set<string>();
  const shares = list.map((raw, i) => {
    const f = `${prefix}shares[${i}]`;
    const s = v.object(raw, f);
    const memberId = v.uuid(s.memberId, `${f}.memberId`);
    if (seen.has(memberId)) throw badRequest(`${f}.memberId`, "Each member appears once.");
    seen.add(memberId);
    const sharePaise = v.int(s.sharePaise, `${f}.sharePaise`, 0, v.MAX_AMOUNT);
    let input: number | null;
    switch (splitMethod) {
      case "EQUAL":
        if (s.input !== null && s.input !== undefined) throw badRequest(`${f}.input`, "EQUAL takes no input.");
        input = null;
        break;
      case "AMOUNTS":
        input = v.int(s.input, `${f}.input`, 0, v.MAX_AMOUNT);
        if (input !== sharePaise) throw badRequest(`${f}.input`, "AMOUNTS input must equal the share.");
        break;
      case "PERCENT":
        input = v.int(s.input, `${f}.input`, 1, 10_000);
        break;
      case "SHARES":
        input = v.int(s.input, `${f}.input`, 1, 1_000);
        break;
    }
    return { memberId, sharePaise, input };
  });
  if (shares.reduce((n, s) => n + s.sharePaise, 0) !== amountPaise) {
    throw badRequest(`${prefix}shares`, "Shares must add up to the amount.");
  }
  if (splitMethod === "PERCENT" && shares.reduce((n, s) => n + (s.input ?? 0), 0) !== 10_000) {
    throw badRequest(`${prefix}shares`, "Percentages must add up to 100.");
  }
  // The server recomputes the shares from the inputs (6.1).
  const expected = computeShares(splitMethod, amountPaise, shares);
  for (const e of expected) {
    if (shares.find((s) => s.memberId === e.memberId)!.sharePaise !== e.sharePaise) {
      throw new ApiError(400, "shares_mismatch", "Shares do not match the split method.", `${prefix}shares`);
    }
  }
  const baseVersion =
    b.baseVersion === undefined || b.baseVersion === null
      ? null
      : v.int(b.baseVersion, `${prefix}baseVersion`, 0, Number.MAX_SAFE_INTEGER);
  return {
    description: v.text(b.description, `${prefix}description`, 1, 80),
    category: v.oneOf(b.category, `${prefix}category`, v.CATEGORIES),
    amountPaise,
    occurredOn: v.date(b.occurredOn, `${prefix}occurredOn`, now),
    paidByMemberId: v.uuid(b.paidByMemberId, `${prefix}paidByMemberId`),
    splitMethod,
    auto: b.auto === undefined ? false : v.bool(b.auto, `${prefix}auto`),
    shares,
    baseVersion,
  };
}

/** Payer and sharers must be active members of the group, or unchanged on an update (even if removed since). */
function checkMembers(gid: string, input: ExpenseInput, rows: MemberRow[], stored: Set<string>, storedPayer: string | null) {
  const ok = (id: string, unchanged: boolean) => {
    const r = rows.find((x) => x.id === id);
    return !!r && r.group_id === gid && (r.removed_at === null || unchanged);
  };
  if (!ok(input.paidByMemberId, input.paidByMemberId === storedPayer)) {
    throw badRequest("paidByMemberId", "The payer is not an active member of the group.");
  }
  input.shares.forEach((s, i) => {
    if (!ok(s.memberId, stored.has(s.memberId))) throw badRequest(`shares[${i}].memberId`, "Not an active member of the group.");
  });
}

export async function putExpense(ctx: Ctx): Promise<Response> {
  const { gid, eid } = ctx.params;
  const access = await requireMember(ctx, gid);
  const input = parseExpenseFields(ctx.body, "", ctx.now);
  const existing = await getExpense(ctx.db, eid);
  if (existing && existing.expense.group_id !== gid) throw notFound();
  if (existing && existing.expense.deleted_at !== null) throw expenseDeleted();
  const family = access.group.kind === "family";
  if (family) checkFamilyExpense(input, access.myMemberId, existing?.expense.paid_by_member_id ?? null);

  const refIds = [...new Set([input.paidByMemberId, ...input.shares.map((s) => s.memberId)])];
  const storedShares = new Set(existing?.shares.map((s) => s.member_id) ?? []);
  const storedAll = new Set([...storedShares, ...(existing ? [existing.expense.paid_by_member_id] : [])]);
  checkMembers(gid, input, await membersByIds(ctx.db, refIds), storedShares, existing?.expense.paid_by_member_id ?? null);
  const newIds = refIds.filter((id) => !storedAll.has(id));

  if (!existing && access.group.version >= MAX_EXPENSES_PER_GROUP) {
    // The version counts every mutation, so below 20,000 the cap cannot be reached: count only past it.
    if ((await liveExpenseCount(ctx.db, gid)) >= MAX_EXPENSES_PER_GROUP) throw conflict("limit_reached", "Too many expenses.");
  }
  const status = existing ? 200 : 201;
  const write = expenseStmts(ctx.db, {
    gid,
    eid,
    description: input.description,
    category: input.category,
    amountPaise: input.amountPaise,
    occurredOn: input.occurredOn,
    paidByMemberId: input.paidByMemberId,
    splitMethod: input.splitMethod,
    auto: input.auto,
    actor: access.myMemberId,
    shares: input.shares,
    now: ctx.now,
  });
  const guardIndex = 1 + write.length;
  let results: D1Result[];
  try {
    results = await runBatch(ctx, "expense.put", [
      bump(ctx.db, gid, ctx.now),
      ...write,
      guard(ctx.db, {
        gid,
        kind: "expense",
        op: existing ? "update" : "create",
        entityId: eid,
        actor: access.myMemberId,
        now: ctx.now,
        rule: membersActiveRule(newIds),
      }),
      ...keyStmt(ctx, status, eid),
    ]);
  } catch (err) {
    if (guardFailed(err)) await diagnoseExpense(ctx, gid, eid, newIds);
    throw err;
  }
  const version = guardVersion(results, guardIndex);
  const people = new Set([input.paidByMemberId, ...input.shares.filter((s) => s.sharePaise > 0).map((s) => s.memberId)]);
  // A family expense makes no notification (4.18): the partner's phones get a NORMAL "changed" and sync quietly.
  after(ctx, { gid, version, high: existing || family ? [] : await userIdsOf(ctx, [...people]) });
  const row: ExpenseRow = {
    id: eid,
    group_id: gid,
    description: input.description,
    category: input.category,
    amount_paise: input.amountPaise,
    currency: "INR",
    occurred_on: input.occurredOn,
    paid_by_member_id: input.paidByMemberId,
    split_method: input.splitMethod,
    auto: input.auto ? 1 : 0,
    created_by_member_id: existing?.expense.created_by_member_id ?? access.myMemberId,
    updated_by_member_id: access.myMemberId,
    version,
    created_at: existing?.expense.created_at ?? ctx.now,
    updated_at: ctx.now,
    deleted_at: null,
  };
  const shares = input.shares.map((s) => ({ expense_id: eid, member_id: s.memberId, share_paise: s.sharePaise, input: s.input }));
  const body: Record<string, unknown> = expenseJson(row, shares);
  if (existing) body.overwrote = input.baseVersion === null || input.baseVersion < existing.expense.version;
  return json(body, status);
}

/** Picks the answer after a failed guard (docs/server.md 3.1). */
async function diagnoseExpense(ctx: Ctx, gid: string, eid: string, newIds: string[]): Promise<never> {
  await requireMember(ctx, gid); // 410 group_deleted, 403 not_member
  const now = await getExpense(ctx.db, eid);
  if (now && now.expense.group_id !== gid) throw notFound();
  if (now && now.expense.deleted_at !== null) throw expenseDeleted();
  const rows = await membersByIds(ctx.db, newIds);
  if (newIds.some((id) => !rows.some((r) => r.id === id && r.group_id === gid && r.removed_at === null))) {
    throw badRequest("shares", "A member is no longer active in the group.");
  }
  throw retryLater();
}

async function userIdsOf(ctx: Ctx, memberIds: string[]): Promise<string[]> {
  const rows = await membersByIds(ctx.db, memberIds);
  return rows.filter((r) => r.user_id).map((r) => r.user_id!);
}

/** Replays that answer with content check access first (4.3), as a first request would. */
export async function replayExpense(ctx: Ctx, entityId: string | null, status: number): Promise<Response> {
  await requireMember(ctx, ctx.params.gid);
  const e = await getExpense(ctx.db, entityId!);
  if (!e || e.expense.group_id !== ctx.params.gid) throw notFound();
  return json(expenseJson(e.expense, e.shares), status);
}

export async function deleteExpense(ctx: Ctx): Promise<Response> {
  const { gid, eid } = ctx.params;
  const access = await requireMember(ctx, gid);
  const existing = await getExpense(ctx.db, eid);
  if (!existing || existing.expense.group_id !== gid) throw notFound();
  if (access.group.kind === "family" && existing.expense.paid_by_member_id !== access.myMemberId) throw ownPaymentsOnly();
  if (existing.expense.deleted_at !== null) return json({ version: existing.expense.version });
  let results: D1Result[];
  try {
    results = await runBatch(ctx, "expense.delete", [
      bump(ctx.db, gid, ctx.now),
      ...deleteExpenseStmts(ctx.db, gid, eid, access.myMemberId, ctx.now),
      guard(ctx.db, { gid, kind: "expense", op: "delete", entityId: eid, actor: access.myMemberId, now: ctx.now }),
      ...keyStmt(ctx, 200, eid),
    ]);
  } catch (err) {
    if (guardFailed(err)) {
      await requireMember(ctx, gid);
      const now = await getExpense(ctx.db, eid);
      if (now?.expense.deleted_at !== null && now) return json({ version: now.expense.version });
      throw retryLater();
    }
    throw err;
  }
  const version = guardVersion(results, 3);
  after(ctx, { gid, version });
  return json({ version });
}

export async function replayVersionOfExpense(ctx: Ctx, entityId: string | null, status: number): Promise<Response> {
  const e = await getExpense(ctx.db, entityId!);
  if (!e) throw notFound();
  return json({ version: e.expense.version }, status);
}

// ---------- settlements ----------

export async function putSettlement(ctx: Ctx): Promise<Response> {
  const { gid, sid } = ctx.params;
  const access = await requireMember(ctx, gid);
  if (access.group.kind === "family") throw familiesDoNotSettle();
  const b = v.object(ctx.body);
  const from = v.uuid(b.fromMemberId, "fromMemberId");
  const to = v.uuid(b.toMemberId, "toMemberId");
  if (from === to) throw badRequest("toMemberId", "From and to must differ.");
  const input = {
    from,
    to,
    amountPaise: v.amount(b.amountPaise, "amountPaise"),
    method: v.oneOf(b.method, "method", v.SETTLE_METHODS),
    matchedFromSms: b.matchedFromSms === undefined ? false : v.bool(b.matchedFromSms, "matchedFromSms"),
    occurredOn: v.date(b.occurredOn, "occurredOn", ctx.now),
  };
  const existing = await getSettlement(ctx.db, sid);
  if (existing) {
    if (existing.group_id !== gid) throw notFound();
    const samePayment =
      existing.from_member_id === input.from &&
      existing.to_member_id === input.to &&
      existing.amount_paise === input.amountPaise &&
      existing.occurred_on === input.occurredOn;
    const same = samePayment && existing.method === input.method && (existing.matched_from_sms === 1) === input.matchedFromSms;
    // Both phones matched one repayment from SMS under one id (docs/server.md 9.5), and each SMS names the channel its
    // own way: the payer's debit after Pay by UPI says UPI, the receiver's credit may name none (OTHER). Same people,
    // amount and day under that id: the same payment, whatever each record calls it. The stored method stays. Only a
    // party to it is answered this way, and it gains nothing a party could not do anyway (read it, confirm it).
    const sameSmsPayment =
      samePayment &&
      existing.matched_from_sms === 1 &&
      input.matchedFromSms &&
      (access.myMemberId === input.from || access.myMemberId === input.to);
    if (!same && !sameSmsPayment) throw conflict("exists_different", "The settlement exists with other content.");
    // A payment matched from SMS on both phones is recorded twice under one id (docs/server.md 9.5). When the payer's
    // record came first, the receiver's own match confirms it, as it would have had the receiver's come first.
    if (
      input.matchedFromSms &&
      access.myMemberId === input.to &&
      existing.recorded_by_member_id !== access.myMemberId &&
      existing.confirmed_at === null &&
      existing.deleted_at === null
    ) {
      return confirmExisting(ctx, existing, access.myMemberId);
    }
    return json(settlementJson(existing), 200);
  }
  if (access.myMemberId !== from && access.myMemberId !== to) throw notAllowed("Only the payer or the receiver records a settlement.");
  const rows = await membersByIds(ctx.db, [from, to]);
  for (const [id, field] of [
    [from, "fromMemberId"],
    [to, "toMemberId"],
  ] as const) {
    if (!rows.some((r) => r.id === id && r.group_id === gid && r.removed_at === null)) {
      throw badRequest(field, "Not an active member of the group.");
    }
  }
  const other = rows.find((r) => r.id === (access.myMemberId === from ? to : from))!;
  // Confirmed at once when the other party is not on the app, or when the receiver records an SMS match.
  const confirmedAt = other.user_id === null || (input.matchedFromSms && access.myMemberId === to) ? ctx.now : null;
  let results: D1Result[];
  try {
    results = await runBatch(ctx, "settlement.create", [
      bump(ctx.db, gid, ctx.now),
      insertSettlementStmt(ctx.db, { gid, sid, ...input, recordedBy: access.myMemberId, confirmedAt, now: ctx.now }),
      guard(ctx.db, {
        gid,
        kind: "settlement",
        op: "create",
        entityId: sid,
        actor: access.myMemberId,
        now: ctx.now,
        rule: membersActiveRule([from, to]),
      }),
      ...keyStmt(ctx, 201, sid),
    ]);
  } catch (err) {
    if (err instanceof Error && err.message.includes("settlements.id")) {
      const again = await getSettlement(ctx.db, sid);
      if (again && again.group_id === gid) return json(settlementJson(again), 200);
      throw conflict("exists_different", "The settlement exists with other content.");
    }
    if (guardFailed(err)) {
      await requireMember(ctx, gid);
      throw badRequest("fromMemberId", "A member is no longer active in the group.");
    }
    throw err;
  }
  const version = guardVersion(results, 2);
  after(ctx, { gid, version, high: other.user_id ? [other.user_id] : [] });
  const row: SettlementRow = {
    id: sid,
    group_id: gid,
    from_member_id: from,
    to_member_id: to,
    amount_paise: input.amountPaise,
    method: input.method,
    matched_from_sms: input.matchedFromSms ? 1 : 0,
    occurred_on: input.occurredOn,
    recorded_by_member_id: access.myMemberId,
    confirmed_at: confirmedAt,
    version,
    created_at: ctx.now,
    deleted_at: null,
  };
  return json(settlementJson(row), 201);
}

export async function replaySettlement(ctx: Ctx, entityId: string | null, status: number): Promise<Response> {
  await requireMember(ctx, ctx.params.gid);
  const s = await getSettlement(ctx.db, entityId!);
  if (!s || s.group_id !== ctx.params.gid) throw notFound();
  return json(settlementJson(s), status);
}

export async function confirmSettlement(ctx: Ctx): Promise<Response> {
  const { gid, sid } = ctx.params;
  const access = await requireMember(ctx, gid);
  if (access.group.kind === "family") throw familiesDoNotSettle();
  const s = await getSettlement(ctx.db, sid);
  if (!s || s.group_id !== gid || s.deleted_at !== null) throw notFound();
  const parties = [s.from_member_id, s.to_member_id];
  if (!parties.includes(access.myMemberId) || s.recorded_by_member_id === access.myMemberId) {
    throw notAllowed("Only the party that did not record it confirms.");
  }
  if (s.confirmed_at !== null) return json(settlementJson(s));
  return confirmExisting(ctx, s, access.myMemberId);
}

/** Confirms [s], unconfirmed and not deleted, for [actor], the party that did not record it (200 with the row). */
async function confirmExisting(ctx: Ctx, s: SettlementRow, actor: string): Promise<Response> {
  const gid = s.group_id;
  const sid = s.id;
  let results: D1Result[];
  try {
    results = await runBatch(ctx, "settlement.confirm", [
      bump(ctx.db, gid, ctx.now),
      confirmSettlementStmt(ctx.db, gid, sid, ctx.now),
      guard(ctx.db, { gid, kind: "settlement", op: "confirm", entityId: sid, actor, now: ctx.now }),
      ...keyStmt(ctx, 200, sid),
    ]);
  } catch (err) {
    if (guardFailed(err)) {
      await requireMember(ctx, gid);
      const now = await getSettlement(ctx.db, sid);
      if (now && now.deleted_at === null && now.confirmed_at !== null) return json(settlementJson(now));
      throw notFound();
    }
    throw err;
  }
  const version = guardVersion(results, 2);
  const recorder = await getMember(ctx.db, s.recorded_by_member_id);
  after(ctx, { gid, version, high: recorder?.user_id ? [recorder.user_id] : [] });
  return json(settlementJson({ ...s, confirmed_at: ctx.now, version }));
}

export async function deleteSettlement(ctx: Ctx): Promise<Response> {
  const { gid, sid } = ctx.params;
  const access = await requireMember(ctx, gid);
  if (access.group.kind === "family") throw familiesDoNotSettle();
  const s = await getSettlement(ctx.db, sid);
  if (!s || s.group_id !== gid) throw notFound();
  // The recorder, the payer or the receiver (4.2, 4.8). An import records payments between two other members, with
  // the importing owner as recorder (4.17); without the recorder here nobody could ever delete such a payment.
  if (![s.from_member_id, s.to_member_id, s.recorded_by_member_id].includes(access.myMemberId)) {
    throw notAllowed("Only the recorder or the other party deletes a settlement.");
  }
  if (s.deleted_at !== null) return json({ version: s.version });
  let results: D1Result[];
  try {
    results = await runBatch(ctx, "settlement.delete", [
      bump(ctx.db, gid, ctx.now),
      deleteSettlementStmt(ctx.db, gid, sid, ctx.now),
      guard(ctx.db, { gid, kind: "settlement", op: "delete", entityId: sid, actor: access.myMemberId, now: ctx.now }),
      ...keyStmt(ctx, 200, sid),
    ]);
  } catch (err) {
    if (guardFailed(err)) {
      await requireMember(ctx, gid);
      const now = await getSettlement(ctx.db, sid);
      if (now && now.deleted_at !== null) return json({ version: now.version });
      throw retryLater();
    }
    throw err;
  }
  const version = guardVersion(results, 2);
  after(ctx, { gid, version });
  return json({ version });
}

export async function replayVersionOfSettlement(ctx: Ctx, entityId: string | null, status: number): Promise<Response> {
  const s = await getSettlement(ctx.db, entityId!);
  if (!s) throw notFound();
  return json({ version: s.version }, status);
}

// ---------- pulls and balances ----------

export async function changes(ctx: Ctx): Promise<Response> {
  const gid = ctx.params.gid;
  const access = await requireMember(ctx, gid);
  const raw = ctx.url.searchParams.get("since");
  if (raw !== null && !/^(0|[1-9][0-9]{0,14})$/.test(raw)) throw badRequest("since", "Expected an integer from 0.");
  const since = raw === null ? 0 : Number(raw);
  const latest = access.group.version;
  const group = groupJson(access.group);
  if (since >= latest) {
    // since > latest only after a database restore: the phone drops its copy and pulls from 0.
    return json({
      groupId: gid,
      fromVersion: since,
      toVersion: latest,
      latestVersion: latest,
      hasMore: false,
      group,
      members: [],
      expenses: [],
      settlements: [],
      changes: [],
    });
  }
  const to = Math.min(latest, since + PAGE_VERSIONS);
  const page = await changePage(ctx.db, gid, since, to);
  const view = memberView(ctx, access.group);
  return json({
    groupId: gid,
    fromVersion: since,
    toVersion: to,
    latestVersion: latest,
    hasMore: to < latest,
    group,
    members: page.members.map((m) => memberJson(m, view)),
    expenses: page.expenses.map((e) => expenseJson(e, page.shares)),
    settlements: page.settlements.map(settlementJson),
    changes: page.changes.map((c) => ({
      version: c.version,
      kind: c.kind,
      op: c.op,
      entityId: c.entity_id,
      actorMemberId: c.actor_member_id,
      at: c.created_at,
    })),
  });
}

export async function getBalances(ctx: Ctx): Promise<Response> {
  const gid = ctx.params.gid;
  const access = await requireMember(ctx, gid);
  if (access.group.kind === "family") throw familiesDoNotSettle();
  return json(await balances(ctx.db, gid, access.group.version));
}


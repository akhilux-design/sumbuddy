// Import from Splitwise (docs/server.md 4.17, docs/splitwise-import.md 3.1 to 3.7).
import { describe, expect, it } from "vitest";
import type { Db } from "../src/db/db";
import { nets, simplify } from "../src/domain/splits";
import { importWriteCost } from "../src/routes/imports";
import { testHooks } from "../src/testing";
import { befriend, call, createGroup, expenseBody, rows, shareToken, signIn, uuid, type TestUser } from "./helpers";
import balanceVectors from "./vectors/balances.json";

const path = (gid: string) => `/v1/groups/${gid}/import`;
const PAST = "2025-04-01";

async function setup() {
  const a = await signIn("Arjun Mehta");
  const g = await createGroup(a, { friends: [{ name: "Priya" }, { name: "Kiran" }] });
  return { a, g, ma: g.members[a.id], mp: g.members.Priya, mk: g.members.Kiran };
}

function expenseItem(paidBy: string, amount: number, people: string[], extra: Record<string, unknown> = {}) {
  return { kind: "expense", id: uuid(), ...expenseBody(paidBy, amount, people), occurredOn: PAST, ...extra };
}

function settlementItem(from: string, to: string, amount: number, extra: Record<string, unknown> = {}) {
  return { kind: "settlement", id: uuid(), fromMemberId: from, toMemberId: to, amountPaise: amount, occurredOn: PAST, ...extra };
}

function importItems(user: TestUser, gid: string, items: unknown[], idem?: string) {
  return call("POST", path(gid), { token: user.token, body: { items }, idem });
}

const version = async (gid: string) => (await rows("SELECT version FROM groups WHERE id = ?", gid))[0].version as number;
const count = async (table: string, gid: string) => (await rows(`SELECT COUNT(*) AS n FROM ${table} WHERE group_id = ?`, gid))[0].n as number;

async function snapshot(gid: string) {
  return {
    version: await version(gid),
    changes: await count("changes", gid),
    expenses: await count("expenses", gid),
    settlements: await count("settlements", gid),
  };
}

/** A friend without the app takes their place through their link (4.10). */
async function join(owner: TestUser, gid: string, memberId: string, user: TestUser) {
  const token = shareToken();
  const put = await call("PUT", `/v1/groups/${gid}/members/${memberId}/links/${uuid()}`, { token: owner.token, body: { token } });
  expect(put.status).toBe(201);
  const acc = await call("POST", `/v1/invites/${token}/accept`, { token: user.token });
  expect(acc.status).toBe(200);
}

describe("POST /v1/groups/{gid}/import", () => {
  it("writes expenses and a settlement between two friends, each at its own version with its own change row", async () => {
    const { a, g, ma, mp, mk } = await setup();
    const equal = expenseItem(ma, 3000, [ma, mp, mk], { description: "Rent", category: "RENT_AND_BILLS" });
    const amounts = expenseItem(mp, 1000, [ma, mk], {
      splitMethod: "AMOUNTS",
      shares: [
        { memberId: ma, sharePaise: 700, input: 700 },
        { memberId: mk, sharePaise: 300, input: 300 },
      ],
    });
    const notInSplit = expenseItem(ma, 500, [mp, mk], { description: "Paid for others" });
    const payment = settlementItem(mk, mp, 200, { occurredOn: "2025-04-03" });
    const r = await importItems(a, g.id, [equal, amounts, notInSplit, payment]);
    expect(r.status).toBe(200);
    expect(r.body).toEqual({ applied: true, firstVersion: 2, lastVersion: 5 });
    expect(await version(g.id)).toBe(5);

    const pulled = await call("GET", `/v1/groups/${g.id}/changes?since=0`, { token: a.token });
    expect(pulled.status).toBe(200);
    expect(pulled.body.changes.slice(1)).toEqual([
      { version: 2, kind: "expense", op: "create", entityId: equal.id, actorMemberId: ma, at: expect.any(Number) },
      { version: 3, kind: "expense", op: "create", entityId: amounts.id, actorMemberId: ma, at: expect.any(Number) },
      { version: 4, kind: "expense", op: "create", entityId: notInSplit.id, actorMemberId: ma, at: expect.any(Number) },
      { version: 5, kind: "settlement", op: "create", entityId: payment.id, actorMemberId: ma, at: expect.any(Number) },
    ]);
    const byId = Object.fromEntries(pulled.body.expenses.map((e: any) => [e.id, e]));
    expect(byId[equal.id]).toMatchObject({ version: 2, description: "Rent", category: "RENT_AND_BILLS", occurredOn: PAST, auto: false, createdByMemberId: ma });
    expect(byId[amounts.id]).toMatchObject({ version: 3, paidByMemberId: mp, splitMethod: "AMOUNTS", createdByMemberId: ma, updatedByMemberId: ma });
    expect(byId[notInSplit.id].shares.map((s: any) => s.memberId).sort()).toEqual([mp, mk].sort());
    expect(pulled.body.settlements).toEqual([
      expect.objectContaining({
        id: payment.id,
        fromMemberId: mk,
        toMemberId: mp,
        amountPaise: 200,
        method: "OTHER",
        matchedFromSms: false,
        occurredOn: "2025-04-03",
        recordedByMemberId: ma,
        confirmedAt: expect.any(Number),
        deleted: false,
        version: 5,
      }),
    ]);

    // Arjun paid 3,500 and owes 1,700; Priya paid 1,000, owes 1,250 and received 200; Kiran owes 1,550 and paid 200.
    const b = await call("GET", `/v1/groups/${g.id}/balances`, { token: a.token });
    const expected = [
      { memberId: ma, netPaise: 1800 },
      { memberId: mp, netPaise: -450 },
      { memberId: mk, netPaise: -1350 },
    ].sort((x, y) => (x.memberId < y.memberId ? -1 : 1));
    expect(b.body).toEqual({
      version: 5,
      nets: expected,
      transfers: [
        { fromMemberId: mk, toMemberId: ma, amountPaise: 1350 },
        { fromMemberId: mp, toMemberId: ma, amountPaise: 450 },
      ],
    });
  });

  it("balances of every shared vector come out the same through an import", async () => {
    const a = await signIn("Vector Owner");
    let checked = 0;
    for (const c of balanceVectors.cases) {
      const seen = [...new Set([...c.expenses.flatMap((e) => [e.paidBy, ...e.shares.map((s) => s.memberId)]), ...c.settlements.flatMap((s) => [s.from, s.to])])].sort();
      if (seen.length === 0) continue; // "no activity": nothing to import (an import has 1 to 50 items)
      // Fresh random ids in the same order, so "lower id first" ties are kept.
      const fresh = seen.map(() => uuid()).sort();
      const id = new Map(seen.map((s, i) => [s, fresh[i]]));
      const gid = uuid();
      const created = await call("POST", "/v1/groups", {
        token: a.token,
        body: { id: gid, kind: "group", name: "Vectors", myMemberId: fresh[0], members: fresh.slice(1).map((m, i) => ({ id: m, name: `P${i}` })) },
      });
      expect(created.status, c.name).toBe(201);
      const items = [
        ...c.expenses.map((e) => ({
          kind: "expense",
          id: uuid(),
          description: "Vector",
          category: "OTHER",
          amountPaise: e.amountPaise,
          occurredOn: PAST,
          paidByMemberId: id.get(e.paidBy),
          splitMethod: "AMOUNTS",
          shares: e.shares.map((s) => ({ memberId: id.get(s.memberId), sharePaise: s.sharePaise, input: s.sharePaise })),
        })),
        ...c.settlements.map((s) => settlementItem(id.get(s.from)!, id.get(s.to)!, s.amountPaise)),
      ];
      const r = await importItems(a, gid, items);
      expect(r.status, c.name).toBe(200);
      const got = await call("GET", `/v1/groups/${gid}/balances`, { token: a.token });
      expect(got.body.nets, c.name).toEqual(c.expectedNets.map((n) => ({ memberId: id.get(n.memberId), netPaise: n.netPaise })));
      expect(got.body.transfers, c.name).toEqual(
        c.expectedTransfers.map((t) => ({ fromMemberId: id.get(t.fromMemberId), toMemberId: id.get(t.toMemberId), amountPaise: t.amountPaise })),
      );
      checked++;
    }
    expect(checked).toBe(balanceVectors.cases.length - 1);
  });

  it("replays with the same key and body; the same key with another body is 422", async () => {
    const { a, g, ma, mp } = await setup();
    const key = uuid();
    const items = [expenseItem(ma, 1000, [ma, mp]), settlementItem(mp, ma, 500)];
    const first = await importItems(a, g.id, items, key);
    expect(first.status).toBe(200);
    expect(first.body).toEqual({ applied: true, firstVersion: 2, lastVersion: 3 });
    const before = await snapshot(g.id);
    // Another write in between: the replay still answers the versions it wrote.
    await importItems(a, g.id, [expenseItem(ma, 100, [ma])]);
    const again = await importItems(a, g.id, items, key);
    expect(again.status).toBe(200);
    expect(again.headers.get("Idempotent-Replay")).toBe("true");
    expect(again.body).toEqual(first.body);
    expect((await snapshot(g.id)).changes).toBe(before.changes + 1);
    const reused = await importItems(a, g.id, [expenseItem(ma, 1000, [ma, mp])], key);
    expect(reused.status).toBe(422);
    expect(reused.body.error.code).toBe("idempotency_key_reused");
  });

  it("an old retry after its key was purged answers applied:false; ids that exist elsewhere are 409", async () => {
    const { a, g, ma, mp, mk } = await setup();
    const key = uuid();
    const items = [expenseItem(ma, 1000, [ma, mp]), settlementItem(mk, ma, 300)];
    expect((await importItems(a, g.id, items, key)).status).toBe(200);
    await rows("DELETE FROM idempotency_keys WHERE user_id = ? AND key = ?", a.id, key);
    const before = await snapshot(g.id);

    const retry = await importItems(a, g.id, items, key);
    expect(retry.status).toBe(200);
    expect(retry.body).toEqual({ applied: false, firstVersion: null, lastVersion: null });
    expect(await snapshot(g.id)).toEqual(before);
    // Nothing is written for it, the key included.
    expect(await rows("SELECT * FROM idempotency_keys WHERE user_id = ? AND key = ?", a.id, key)).toHaveLength(0);
    // Even after one of them was deleted.
    expect((await call("DELETE", `/v1/groups/${g.id}/expenses/${items[0].id}`, { token: a.token })).status).toBe(200);
    expect((await importItems(a, g.id, items)).body.applied).toBe(false);

    const mixed = await importItems(a, g.id, [items[0], expenseItem(ma, 500, [ma, mk])]);
    expect(mixed.status).toBe(409);
    expect(mixed.body.error.code).toBe("exists_different");
    // An id that lives in another group.
    const other = await setup();
    const elsewhere = await importItems(other.a, other.g.id, [{ ...expenseItem(other.ma, 100, [other.ma]), id: items[0].id }]);
    expect(elsewhere.status).toBe(409);
    expect(elsewhere.body.error.code).toBe("exists_different");
    expect(await count("expenses", other.g.id)).toBe(0);
  });

  it("two copies at once: with the same key the second is a replay, with another key it answers applied:false", async () => {
    const { a, g, ma, mp } = await setup();
    const items = [expenseItem(ma, 1000, [ma, mp]), settlementItem(mp, ma, 100)];
    const key = uuid();
    let fired = false;
    testHooks.beforeBatch = async (label) => {
      if (label !== "import" || fired) return;
      fired = true;
      expect((await importItems(a, g.id, items, key)).status).toBe(200);
    };
    const same = await importItems(a, g.id, items, key);
    expect(same.status).toBe(200);
    expect(same.headers.get("Idempotent-Replay")).toBe("true");
    expect(same.body).toEqual({ applied: true, firstVersion: 2, lastVersion: 3 });

    const more = [settlementItem(ma, mp, 50), expenseItem(mp, 400, [ma, mp])];
    fired = false;
    testHooks.beforeBatch = async (label) => {
      if (label !== "import" || fired) return;
      fired = true;
      expect((await importItems(a, g.id, more)).body).toEqual({ applied: true, firstVersion: 4, lastVersion: 5 });
    };
    const other = await importItems(a, g.id, more);
    expect(other.status).toBe(200);
    expect(other.body).toEqual({ applied: false, firstVersion: null, lastVersion: null });
    expect(await count("changes", g.id)).toBe(5);
  });

  it("validates the body and every item, and writes nothing on a refusal", async () => {
    const { a, g, ma, mp, mk } = await setup();
    const elsewhere = await setup();
    const removed = uuid();
    expect((await call("POST", `/v1/groups/${g.id}/members`, { token: a.token, body: { id: removed, name: "Gone" } })).status).toBe(201);
    expect((await call("DELETE", `/v1/groups/${g.id}/members/${removed}`, { token: a.token })).status).toBe(204);
    const before = await snapshot(g.id);
    const ok = () => expenseItem(ma, 1000, [ma, mp]);
    const dupe = ok();
    const tooFar = new Date(Date.now() + 5.5 * 3600_000 + 2 * 86_400_000).toISOString().slice(0, 10);
    const [lo, hi] = [ma, mp].sort();
    const cases: [unknown, string, string?][] = [
      [{ items: [] }, "items"],
      [{ items: Array.from({ length: 51 }, () => settlementItem(mp, ma, 1)) }, "items"],
      [{ items: "all" }, "items"],
      [[ok()], "body"],
      [{ items: [7] }, "items[0]"],
      [{ items: [{ ...ok(), kind: "payment" }] }, "items[0].kind"],
      [{ items: [{ ...ok(), id: "6fa459ea-ee8a-11e6-a3b5-0242ac130003" }] }, "items[0].id"],
      [{ items: [{ ...ok(), id: "NOT-A-UUID" }] }, "items[0].id"],
      [{ items: [dupe, { ...settlementItem(mp, ma, 5), id: dupe.id }] }, "items[1].id"],
      [{ items: [ok(), { ...ok(), shares: [{ memberId: ma, sharePaise: 999, input: null }] }] }, "items[1].shares"],
      [
        { items: [{ ...ok(), amountPaise: 101, shares: [{ memberId: lo, sharePaise: 50, input: null }, { memberId: hi, sharePaise: 51, input: null }] }] },
        "items[0].shares",
        "shares_mismatch",
      ],
      [{ items: [{ ...ok(), auto: true }] }, "items[0].auto"],
      [{ items: [{ ...ok(), baseVersion: 3 }] }, "items[0].baseVersion"],
      [{ items: [{ ...ok(), description: "" }] }, "items[0].description"],
      [{ items: [{ ...ok(), description: "Pay ⁦me⁩" }] }, "items[0].description"],
      [{ items: [{ ...ok(), category: "General" }] }, "items[0].category"],
      [{ items: [{ ...ok(), amountPaise: 1_000_000_001 }] }, "items[0].amountPaise"],
      [{ items: [{ ...ok(), occurredOn: tooFar }] }, "items[0].occurredOn"],
      [{ items: [{ ...ok(), occurredOn: "1999-12-31" }] }, "items[0].occurredOn"],
      [{ items: [settlementItem(mp, mp, 100)] }, "items[0].toMemberId"],
      [{ items: [settlementItem(mp, ma, 0)] }, "items[0].amountPaise"],
      [{ items: [settlementItem(mp, ma, 100, { occurredOn: tooFar })] }, "items[0].occurredOn"],
      [{ items: [settlementItem("x", ma, 100)] }, "items[0].fromMemberId"],
      // Members: a member of another group, a removed one, a made-up id (check 10, after every item is read).
      [{ items: [ok(), expenseItem(elsewhere.ma, 100, [ma])] }, "items[1].paidByMemberId"],
      [
        { items: [{ ...ok(), shares: [{ memberId: ma, sharePaise: 600, input: 600 }, { memberId: elsewhere.mp, sharePaise: 400, input: 400 }], splitMethod: "AMOUNTS" }] },
        "items[0].shares[1].memberId",
      ],
      [{ items: [ok(), settlementItem(mk, removed, 100)] }, "items[1].toMemberId"],
      [{ items: [settlementItem(uuid(), ma, 100)] }, "items[0].fromMemberId"],
    ];
    for (const [body, field, code] of cases) {
      const r = await call("POST", path(g.id), { token: a.token, body });
      expect(r.status, field).toBe(400);
      expect(r.body.error.code, field).toBe(code ?? "bad_request");
      expect(r.body.error.field, JSON.stringify(body).slice(0, 200)).toBe(field);
    }
    const big = { items: [ok()], pad: "x".repeat(33 * 1024) };
    expect((await call("POST", path(g.id), { token: a.token, body: big })).status).toBe(413);
    const noKey = await call("POST", path(g.id), { token: a.token, body: { items: [ok()] }, idem: false });
    expect(noKey.body.error.field).toBe("Idempotency-Key");
    expect(await snapshot(g.id)).toEqual(before);
    // Any payer and any two members are fine; 50 items is the most.
    const fifty = Array.from({ length: 50 }, (_, i) => (i % 2 ? settlementItem(mk, mp, 10 + i) : expenseItem(mk, 300 + i, [ma, mp])));
    expect((await importItems(a, g.id, fifty)).status).toBe(200);
  });

  it("is for the owner of a group nobody else has joined", async () => {
    const a = await signIn("Owner");
    const b = await signIn("Joined");
    const stranger = await signIn("Stranger");
    const intro = await befriend(a, b); // b took a friend's place through a link
    const ma = intro.members[a.id];
    const item = () => [expenseItem(ma, 100, [ma])];

    const notMember = await importItems(stranger, intro.id, item());
    expect(notMember.status).toBe(403);
    expect(notMember.body.error.code).toBe("not_member");
    const notOwner = await importItems(b, intro.id, [expenseItem(intro.members[b.id], 100, [intro.members[b.id]])]);
    expect(notOwner.status).toBe(403);
    expect(notOwner.body.error.code).toBe("not_owner");
    const joined = await importItems(a, intro.id, item());
    expect(joined.status).toBe(403);
    expect(joined.body.error.code).toBe("not_allowed");

    const did = uuid();
    const mine = uuid();
    const direct = await call("POST", "/v1/groups", {
      token: a.token,
      body: { id: did, kind: "direct", myMemberId: mine, members: [{ id: uuid(), name: "Priya" }] },
    });
    expect(direct.status).toBe(201);
    const d = await importItems(a, did, [expenseItem(mine, 100, [mine])]);
    expect(d.status).toBe(403);
    expect(d.body.error.code).toBe("not_allowed");

    const { a: owner, g, ma: mo, mp } = await setup();
    expect((await importItems(owner, g.id, [expenseItem(mo, 900, [mo, mp])])).status).toBe(200);
    expect((await call("DELETE", `/v1/groups/${g.id}`, { token: owner.token })).status).toBe(204);
    const gone = await importItems(owner, g.id, [expenseItem(mo, 100, [mo])]);
    expect(gone.status).toBe(410);
    expect(gone.body.error.code).toBe("group_deleted");

    const anon = await call("POST", path(intro.id), { body: { items: item() } });
    expect(anon.status).toBe(401);
  });

  it("a friend who joins between the checks and the batch rolls the whole batch back (403 not_allowed)", async () => {
    const { a, g, ma, mp, mk } = await setup();
    const priya = await signIn("Priya Nair");
    let afterJoin = 0;
    let fired = false;
    testHooks.beforeBatch = async (label) => {
      if (label !== "import" || fired) return;
      fired = true;
      await join(a, g.id, mp, priya);
      afterJoin = await version(g.id);
    };
    const items = [expenseItem(ma, 900, [ma, mp, mk]), settlementItem(mk, mp, 100)];
    const r = await importItems(a, g.id, items);
    expect(r.status).toBe(403);
    expect(r.body.error.code).toBe("not_allowed");
    expect(afterJoin).toBeGreaterThan(1);
    expect(await version(g.id)).toBe(afterJoin);
    expect(await count("expenses", g.id)).toBe(0);
    expect(await count("settlements", g.id)).toBe(0);
    expect(await rows("SELECT * FROM changes WHERE group_id = ? AND kind IN ('expense', 'settlement')", g.id)).toHaveLength(0);
  });

  it("a member removed between the checks and the batch rolls it back (400 with the field)", async () => {
    const { a, g, ma, mp, mk } = await setup();
    testHooks.beforeBatch = async (label) => {
      if (label === "import") await rows("UPDATE members SET removed_at = ? WHERE id = ?", Date.now(), mk);
    };
    const r = await importItems(a, g.id, [expenseItem(ma, 900, [ma, mp]), settlementItem(mk, mp, 100)]);
    expect(r.status).toBe(400);
    expect(r.body.error.field).toBe("items[1].fromMemberId");
    expect(await version(g.id)).toBe(1);
    expect(await count("expenses", g.id)).toBe(0);
  });

  it("stops at version 6000 (409 limit_reached)", async () => {
    const { a, g, ma, mp } = await setup();
    await rows("UPDATE groups SET version = 5990 WHERE id = ?", g.id);
    const eleven = Array.from({ length: 11 }, () => settlementItem(mp, ma, 100));
    const r = await importItems(a, g.id, eleven);
    expect(r.status).toBe(409);
    expect(r.body.error.code).toBe("limit_reached");
    expect(await version(g.id)).toBe(5990);
    const ten = await importItems(a, g.id, eleven.slice(1));
    expect(ten.body).toEqual({ applied: true, firstVersion: 5991, lastVersion: 6000 });
  });

  it("150 items in 3 requests pull back in pages of 100 versions", async () => {
    const { a, g, ma, mp, mk } = await setup();
    const all = Array.from({ length: 150 }, (_, i) => (i % 10 === 9 ? settlementItem(mk, ma, 100 + i) : expenseItem(i % 3 ? mp : ma, 900 + i, [ma, mp, mk])));
    for (let i = 0; i < 150; i += 50) {
      const r = await importItems(a, g.id, all.slice(i, i + 50));
      expect(r.body).toEqual({ applied: true, firstVersion: i + 2, lastVersion: i + 51 });
    }
    const first = await call("GET", `/v1/groups/${g.id}/changes?since=0`, { token: a.token });
    expect(first.body).toMatchObject({ fromVersion: 0, toVersion: 100, latestVersion: 151, hasMore: true });
    expect(first.body.changes).toHaveLength(100);
    expect(first.body.expenses.length + first.body.settlements.length).toBe(99);
    const second = await call("GET", `/v1/groups/${g.id}/changes?since=100`, { token: a.token });
    expect(second.body).toMatchObject({ fromVersion: 100, toVersion: 151, latestVersion: 151, hasMore: false });
    expect(second.body.expenses.length + second.body.settlements.length).toBe(51);
    const pulled = [...first.body.expenses, ...first.body.settlements, ...second.body.expenses, ...second.body.settlements];
    expect(new Set(pulled.map((x: any) => x.id))).toEqual(new Set(all.map((x) => x.id)));
    // Item i is at version 2 + i.
    const versions = new Map(pulled.map((x: any) => [x.id, x.version]));
    all.forEach((x, i) => expect(versions.get(x.id)).toBe(2 + i));
  });

  it("one request of 50 expenses with 3 shares writes at most 50 x 7 + 5 rows and reads fewer than 600", async () => {
    const { a, g, ma, mp, mk } = await setup();
    let db: Db | undefined;
    testHooks.onDb = (d) => (db = d);
    const items = Array.from({ length: 50 }, (_, i) => expenseItem([ma, mp, mk][i % 3], 3000 + i, [ma, mp, mk]));
    const r = await importItems(a, g.id, items);
    expect(r.status).toBe(200);
    expect(db!.batches).toBe(2); // check 12's read and the write
    expect(db!.rowsWritten).toBeGreaterThan(0);
    expect(db!.rowsWritten).toBeLessThanOrEqual(50 * 7 + 5);
    expect(db!.rowsRead).toBeLessThan(600);
  });

  it("a 1,000-row Splitwise group comes across in 20 requests within the free-tier budget", async () => {
    const { a, g, ma, mp, mk } = await setup();
    const people = [ma, mp, mk];
    let seed = 7;
    const rand = (n: number) => {
      seed = (seed * 1_103_515_245 + 12_345) % 2_147_483_648;
      return seed % n;
    };
    // 900 expenses of 3 people (equal splits) and 100 payments, in date order.
    const items = Array.from({ length: 1000 }, (_, i) => {
      const day = `2025-${String(1 + Math.floor(i / 100)).padStart(2, "0")}-${String(1 + (i % 28)).padStart(2, "0")}`;
      if (i % 10 === 9) {
        const from = people[rand(3)];
        const to = people.filter((p) => p !== from)[rand(2)];
        return settlementItem(from, to, 100 + rand(500_000), { occurredOn: day });
      }
      return expenseItem(people[rand(3)], 1 + rand(2_000_000), people, { occurredOn: day, description: `Row ${i}` });
    });
    const dbs: Db[] = [];
    let writes = 0;
    testHooks.onDb = (d) => dbs.push(d);
    testHooks.limiter = async (name) => {
      if (name === "RL_WRITE") writes++;
      return true;
    };
    for (let i = 0; i < 1000; i += 50) {
      const r = await importItems(a, g.id, items.slice(i, i + 50));
      expect(r.status).toBe(200);
    }
    // 20 requests counting 5 each against RL_WRITE (once per 10 items, 4.15): 100 hits, so at 30 a minute a phone
    // that waits each 429's Retry-After takes about 3 to 4 minutes.
    expect(dbs).toHaveLength(20);
    expect(writes).toBe(100);
    const written = dbs.reduce((n, d) => n + d.rowsWritten, 0);
    const read = dbs.reduce((n, d) => n + d.rowsRead, 0);
    // docs/server.md 8 and 4.17: 900 x 7 + 100 x 4 + 20 keys = 6,720 written rows (6.7% of a day's 100,000), and about
    // 11 rows read per item, mostly the 3.1 guards (about 11,300 here, 0.2% of a day's 5,000,000).
    expect(written).toBeLessThanOrEqual(900 * 7 + 100 * 4 + 20);
    expect(read).toBeLessThan(12_000);
    expect(await version(g.id)).toBe(1001);

    // The balances are what the file says.
    const expected = nets(
      items.filter((x) => x.kind === "expense").map((x: any) => ({ paidBy: x.paidByMemberId, amountPaise: x.amountPaise, shares: x.shares })),
      items.filter((x) => x.kind === "settlement").map((x: any) => ({ from: x.fromMemberId, to: x.toMemberId, amountPaise: x.amountPaise })),
    );
    testHooks.onDb = undefined;
    const b = await call("GET", `/v1/groups/${g.id}/balances`, { token: a.token });
    expect(b.body.nets).toEqual(expected);
    expect(b.body.transfers).toEqual(simplify(expected));
  });

  it("tells the room once per request", async () => {
    const { a, g, ma, mp } = await setup();
    const live = await call("GET", `/v1/groups/${g.id}/live`, { token: a.token, headers: { Upgrade: "websocket", "X-Installation-Id": uuid() } });
    expect(live.status).toBe(101);
    const ws = live.res.webSocket!;
    const messages: any[] = [];
    ws.addEventListener("message", (e) => messages.push(JSON.parse(e.data as string)));
    ws.accept();
    const r = await importItems(a, g.id, Array.from({ length: 50 }, () => expenseItem(ma, 1000, [ma, mp])));
    expect(r.status).toBe(200);
    const end = Date.now() + 2000;
    while (messages.length === 0 && Date.now() < end) await new Promise((res) => setTimeout(res, 10));
    await new Promise((res) => setTimeout(res, 100));
    expect(messages).toEqual([{ type: "changed", groupId: g.id, version: r.body.lastVersion }]);
    ws.close();
  });
});

describe("import and RL_WRITE (4.15, open point 13.20: once per 10 items)", () => {
  /** A fake limiter that counts hits per limiter name and allows `writes` RL_WRITE hits (all when undefined). */
  function countingLimiter(writes?: number) {
    const hits: Record<string, number> = {};
    testHooks.limiter = async (name) => {
      hits[name] = (hits[name] ?? 0) + 1;
      return name !== "RL_WRITE" || writes === undefined || hits[name] <= writes;
    };
    return hits;
  }
  const settlements = (n: number, from: string, to: string) => Array.from({ length: n }, (_, i) => settlementItem(from, to, 100 + i));

  it("counts ceil(items / 10): 1 to 10 items once, 11 twice, 50 five times; RL_USER once whatever the size", () => {
    expect([1, 9, 10, 11, 20, 21, 41, 50].map((n) => importWriteCost({ items: new Array(n).fill(0) }))).toEqual([1, 1, 1, 2, 2, 3, 5, 5]);
    // Bodies the handler refuses count once (no items) or as 50 (too many).
    expect(importWriteCost({ items: [] })).toBe(1);
    expect(importWriteCost({ items: new Array(400).fill(0) })).toBe(5);
    expect([undefined, null, "x", 7, [1, 2], {}, { items: "all" }].map(importWriteCost)).toEqual([1, 1, 1, 1, 1, 1, 1]);
  });

  it("50 items consume 5 write tokens; 11 items 2; a 10-item part 1", async () => {
    const { a, g, ma, mp } = await setup();
    for (const [n, cost] of [
      [50, 5],
      [11, 2],
      [10, 1],
    ]) {
      const hits = countingLimiter();
      const r = await importItems(a, g.id, settlements(n, mp, ma));
      expect(r.status).toBe(200);
      expect(hits).toEqual({ RL_USER: 1, RL_WRITE: cost });
    }
    // Other writes still count once.
    const hits = countingLimiter();
    expect((await call("PUT", `/v1/groups/${g.id}/expenses/${uuid()}`, { token: a.token, body: expenseBody(ma, 100, [ma]) })).status).toBe(201);
    expect(hits).toEqual({ RL_USER: 1, RL_WRITE: 1 });
  });

  it("a burst of 50-item parts gets 429 rate_limited with Retry-After after 300 items (the real window)", async () => {
    const { a, g, ma, mp } = await setup();
    // No fake limiter and no bindings: the in-memory 60 s window with the same numbers (30 RL_WRITE a minute).
    testHooks.limiter = undefined;
    const noBindings = { RL_USER: undefined, RL_WRITE: undefined };
    const statuses: number[] = [];
    let refused: Awaited<ReturnType<typeof call>> | undefined;
    for (let i = 0; i < 8 && !refused; i++) {
      const r = await call("POST", path(g.id), { token: a.token, body: { items: settlements(50, mp, ma) }, env: noBindings });
      statuses.push(r.status);
      if (r.status === 429) refused = r;
    }
    expect(statuses).toEqual([200, 200, 200, 200, 200, 200, 429]);
    expect(refused!.body).toEqual({ error: { code: "rate_limited", message: "Too many requests." } });
    expect(refused!.headers.get("Retry-After")).toBe("60");
    expect(await version(g.id)).toBe(1 + 300);
    // Within the same window even a 1-item part is refused.
    const one = await call("POST", path(g.id), { token: a.token, body: { items: settlements(1, mp, ma) }, env: noBindings });
    expect(one.status).toBe(429);
    expect(one.headers.get("Retry-After")).toBe("60");
  });

  it("a 429 on the items past the first 10 writes nothing, key included; its retry after the wait is applied", async () => {
    const { a, g, ma, mp } = await setup();
    const key = uuid();
    const items = settlements(50, mp, ma);
    const before = await snapshot(g.id);
    // Room for 3 more writes this minute: the first hit (before the body is read) and 2 of the 4 extra ones pass.
    const hits = countingLimiter(3);
    const r = await importItems(a, g.id, items, key);
    expect(r.status).toBe(429);
    expect(r.body).toEqual({ error: { code: "rate_limited", message: "Too many requests." } });
    expect(r.headers.get("Retry-After")).toBe("60");
    expect(hits.RL_WRITE).toBe(4); // stops at the first refusal
    expect(await snapshot(g.id)).toEqual(before);
    expect(await rows("SELECT * FROM idempotency_keys WHERE user_id = ? AND key = ?", a.id, key)).toHaveLength(0);

    // A minute later: the same part and key is a new request, not a replay.
    countingLimiter();
    const retry = await importItems(a, g.id, items, key);
    expect(retry.status).toBe(200);
    expect(retry.headers.get("Idempotent-Replay")).toBeNull();
    expect(retry.body).toEqual({ applied: true, firstVersion: 2, lastVersion: 51 });
  });

  it("a replay does not consume extra: it counts once, like any replayed write", async () => {
    const { a, g, ma, mp } = await setup();
    const key = uuid();
    const items = settlements(50, mp, ma);
    let hits = countingLimiter();
    const first = await importItems(a, g.id, items, key);
    expect(first.status).toBe(200);
    expect(hits.RL_WRITE).toBe(5);

    hits = countingLimiter();
    const again = await importItems(a, g.id, items, key);
    expect(again.status).toBe(200);
    expect(again.headers.get("Idempotent-Replay")).toBe("true");
    expect(again.body).toEqual(first.body);
    expect(hits).toEqual({ RL_USER: 1, RL_WRITE: 1 });

    // With one write left this minute the replay still gets its answer.
    hits = countingLimiter(1);
    const tight = await importItems(a, g.id, items, key);
    expect(tight.status).toBe(200);
    expect(tight.body).toEqual(first.body);
    expect(hits.RL_WRITE).toBe(1);

    // The same key with another body: 422 after one hit.
    hits = countingLimiter();
    const reused = await importItems(a, g.id, settlements(50, mp, ma), key);
    expect(reused.status).toBe(422);
    expect(hits.RL_WRITE).toBe(1);
  });
});

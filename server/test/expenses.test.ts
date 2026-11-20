// Expenses (docs/server.md 4.7), idempotency (4.3) and the 3.1 batch guard.
import { describe, expect, it } from "vitest";
import { testHooks } from "../src/testing";
import { befriend, call, createGroup, env, expenseBody, putExpense, rows, signIn, today, uuid } from "./helpers";

async function pair() {
  const a = await signIn("Arjun");
  const b = await signIn("Rahul");
  const g = await befriend(a, b);
  return { a, b, g, ma: g.members[a.id], mb: g.members[b.id] };
}

const version = async (gid: string) => (await rows("SELECT version FROM groups WHERE id = ?", gid))[0].version as number;

describe("PUT /v1/groups/{gid}/expenses/{eid}", () => {
  it("creates (201), updates (200) with last writer wins and overwrote, and records changes", async () => {
    const { a, b, g, ma, mb } = await pair();
    const { eid, r } = await putExpense(a, g.id, expenseBody(ma, 45000, [ma, mb]));
    expect(r.status).toBe(201);
    expect(r.body).toMatchObject({
      id: eid,
      groupId: g.id,
      description: "Swiggy",
      category: "FOOD",
      amountPaise: 45000,
      currency: "INR",
      occurredOn: today(),
      paidByMemberId: ma,
      splitMethod: "EQUAL",
      auto: false,
      createdByMemberId: ma,
      updatedByMemberId: ma,
      deleted: false,
      version: 3,
    });
    expect(r.body.shares).toHaveLength(2);
    const v1 = r.body.version;

    const upd = await call("PUT", `/v1/groups/${g.id}/expenses/${eid}`, {
      token: b.token,
      body: expenseBody(mb, 30000, [ma, mb], { description: "Dinner", baseVersion: v1 }),
    });
    expect(upd.status).toBe(200);
    expect(upd.body).toMatchObject({ description: "Dinner", amountPaise: 30000, overwrote: false, createdByMemberId: ma, updatedByMemberId: mb });

    // a edits from an older base: applied anyway, flagged.
    const stale = await call("PUT", `/v1/groups/${g.id}/expenses/${eid}`, {
      token: a.token,
      body: expenseBody(ma, 100, [ma], { baseVersion: v1 }),
    });
    expect(stale.status).toBe(200);
    expect(stale.body.overwrote).toBe(true);
    expect(stale.body.shares).toEqual([{ memberId: ma, sharePaise: 100, input: null }]);
    const ch = await rows("SELECT op FROM changes WHERE group_id = ? AND entity_id = ? ORDER BY version", g.id, eid);
    expect(ch.map((c) => c.op)).toEqual(["create", "update", "update"]);
  });

  it("410 after delete; delete twice answers 200; another group's id is 404", async () => {
    const { a, g, ma } = await pair();
    const { eid } = await putExpense(a, g.id, expenseBody(ma, 100, [ma]));
    const del = await call("DELETE", `/v1/groups/${g.id}/expenses/${eid}`, { token: a.token });
    expect(del.status).toBe(200);
    expect(del.body.version).toBe(await version(g.id));
    expect((await call("DELETE", `/v1/groups/${g.id}/expenses/${eid}`, { token: a.token })).body).toEqual(del.body);
    const upd = await call("PUT", `/v1/groups/${g.id}/expenses/${eid}`, { token: a.token, body: expenseBody(ma, 100, [ma]) });
    expect(upd.status).toBe(410);
    expect(upd.body.error.code).toBe("expense_deleted");
    expect(await rows("SELECT * FROM expense_shares WHERE expense_id = ?", eid)).toHaveLength(0);

    const other = await createGroup(a);
    const { eid: e2 } = await putExpense(a, other.id, expenseBody(other.members[a.id], 100, [other.members[a.id]]));
    const cross = await call("PUT", `/v1/groups/${g.id}/expenses/${e2}`, { token: a.token, body: expenseBody(ma, 100, [ma]) });
    expect(cross.status).toBe(404);
    expect((await call("DELETE", `/v1/groups/${g.id}/expenses/${e2}`, { token: a.token })).status).toBe(404);
  });

  it("validates every field", async () => {
    const { a, g, ma, mb } = await pair();
    const tooFar = new Date(Date.now() + 5.5 * 3600_000 + 2 * 86_400_000).toISOString().slice(0, 10);
    const cases: [Record<string, unknown>, string, string?][] = [
      [{ description: "" }, "description"],
      [{ description: "x".repeat(81) }, "description"],
      [{ description: "Swi\u0007ggy" }, "description"],
      [{ description: "Pay ⁦me⁩" }, "description"],
      [{ category: "BOOZE" }, "category"],
      [{ amountPaise: 0, shares: [] }, "amountPaise"],
      [{ amountPaise: 1_000_000_001 }, "amountPaise"],
      [{ amountPaise: 12.5 }, "amountPaise"],
      [{ occurredOn: "2026-02-30" }, "occurredOn"],
      [{ occurredOn: "1999-12-31" }, "occurredOn"],
      [{ occurredOn: tooFar }, "occurredOn"],
      [{ paidByMemberId: uuid() }, "paidByMemberId"],
      [{ splitMethod: "RANDOM" }, "splitMethod"],
      [{ shares: [] }, "shares"],
      [{ shares: [{ memberId: ma, sharePaise: 100, input: null }] }, "shares"],
      [{ shares: [{ memberId: ma, sharePaise: 1000, input: null }, { memberId: ma, sharePaise: 0, input: null }] }, "shares[1].memberId"],
      [{ shares: [{ memberId: ma, sharePaise: 1000, input: 5 }] }, "shares[0].input"],
      [{ splitMethod: "AMOUNTS", shares: [{ memberId: ma, sharePaise: 1000, input: 999 }] }, "shares[0].input"],
      [{ splitMethod: "PERCENT", shares: [{ memberId: ma, sharePaise: 500, input: 5000 }, { memberId: mb, sharePaise: 500, input: 4000 }] }, "shares"],
      [{ splitMethod: "SHARES", shares: [{ memberId: ma, sharePaise: 1000, input: 0 }] }, "shares[0].input"],
      [{ auto: "yes" }, "auto"],
    ];
    for (const [patch, field] of cases) {
      const body = { ...expenseBody(ma, 1000, [ma]), ...patch };
      const r = await call("PUT", `/v1/groups/${g.id}/expenses/${uuid()}`, { token: a.token, body });
      expect(r.status, field).toBe(400);
      expect(r.body.error.code, field).toBe("bad_request");
      expect(r.body.error.field, JSON.stringify(patch)).toBe(field);
    }
  });

  it("recomputes shares from the method: shares_mismatch", async () => {
    const { a, g, ma, mb } = await pair();
    const [lo, hi] = [ma, mb].sort();
    const wrongEqual = { shares: [{ memberId: lo, sharePaise: 50, input: null }, { memberId: hi, sharePaise: 51, input: null }] };
    const r = await call("PUT", `/v1/groups/${g.id}/expenses/${uuid()}`, { token: a.token, body: { ...expenseBody(ma, 101, [ma, mb]), ...wrongEqual } });
    expect(r.status).toBe(400);
    expect(r.body.error.code).toBe("shares_mismatch");
    const pct = {
      splitMethod: "PERCENT",
      shares: [{ memberId: lo, sharePaise: 33, input: 3333 }, { memberId: hi, sharePaise: 67, input: 6667 }],
    };
    expect((await call("PUT", `/v1/groups/${g.id}/expenses/${uuid()}`, { token: a.token, body: { ...expenseBody(ma, 100, [ma, mb]), ...pct } })).status).toBe(201);
    const shares = {
      splitMethod: "SHARES",
      shares: [{ memberId: lo, sharePaise: 34, input: 1 }, { memberId: hi, sharePaise: 66, input: 2 }],
    };
    const s = await call("PUT", `/v1/groups/${g.id}/expenses/${uuid()}`, { token: a.token, body: { ...expenseBody(ma, 100, [ma, mb]), ...shares } });
    expect(s.body.error.code).toBe("shares_mismatch");
  });

  it("body limits and headers", async () => {
    const { a, g, ma } = await pair();
    const big = { ...expenseBody(ma, 100, [ma]), pad: "x".repeat(33 * 1024) };
    expect((await call("PUT", `/v1/groups/${g.id}/expenses/${uuid()}`, { token: a.token, body: big })).status).toBe(413);
    const noKey = await call("PUT", `/v1/groups/${g.id}/expenses/${uuid()}`, { token: a.token, body: expenseBody(ma, 100, [ma]), idem: false });
    expect(noKey.status).toBe(400);
    expect(noKey.body.error.field).toBe("Idempotency-Key");
    const badJson = await call("PUT", `/v1/groups/${g.id}/expenses/${uuid()}`, { token: a.token, body: "{oops" });
    expect(badJson.body.error.field).toBe("body");
    expect(badJson.headers.get("Cache-Control")).toBe("no-store");
    expect(badJson.headers.get("X-Content-Type-Options")).toBe("nosniff");
    expect(badJson.headers.get("Access-Control-Allow-Origin")).toBeNull();
  });
});

describe("idempotency (4.3)", () => {
  it("replays with the stored status and the current entity; a reused key with another request is 422", async () => {
    const { a, g, ma } = await pair();
    const key = uuid();
    const eid = uuid();
    const body = expenseBody(ma, 100, [ma]);
    const first = await call("PUT", `/v1/groups/${g.id}/expenses/${eid}`, { token: a.token, body, idem: key });
    expect(first.status).toBe(201);
    const v = await version(g.id);
    const again = await call("PUT", `/v1/groups/${g.id}/expenses/${eid}`, { token: a.token, body, idem: key });
    expect(again.status).toBe(201);
    expect(again.headers.get("Idempotent-Replay")).toBe("true");
    expect(again.body.id).toBe(eid);
    expect(await version(g.id)).toBe(v); // nothing written
    const reused = await call("PUT", `/v1/groups/${g.id}/expenses/${eid}`, { token: a.token, body: { ...body, description: "Other" }, idem: key });
    expect(reused.status).toBe(422);
    expect(reused.body.error.code).toBe("idempotency_key_reused");
    // Keys are per user.
    const b = await signIn();
    const mine = await call("POST", "/v1/groups", {
      token: b.token,
      idem: key,
      body: { id: uuid(), kind: "group", name: "B", myMemberId: uuid(), members: [] },
    });
    expect(mine.status).toBe(201);
  });

  it("a retried leave or delete still gets its first answer", async () => {
    const { a, b, g } = await pair();
    const key = uuid();
    expect((await call("POST", `/v1/groups/${g.id}/leave`, { token: b.token, idem: key })).status).toBe(204);
    const retry = await call("POST", `/v1/groups/${g.id}/leave`, { token: b.token, idem: key });
    expect(retry.status).toBe(204);
    expect(retry.headers.get("Idempotent-Replay")).toBe("true");
    const k2 = uuid();
    expect((await call("DELETE", `/v1/groups/${g.id}`, { token: a.token, idem: k2 })).status).toBe(204);
    expect((await call("DELETE", `/v1/groups/${g.id}`, { token: a.token, idem: k2 })).status).toBe(204);
  });

  it("two copies at once: the second answers as a replay", async () => {
    const { a, g, ma } = await pair();
    const key = uuid();
    const eid = uuid();
    const body = expenseBody(ma, 100, [ma]);
    let fired = false;
    testHooks.beforeBatch = async (label) => {
      if (label !== "expense.put" || fired) return;
      fired = true;
      // The other copy lands first, with the same key.
      const other = await call("PUT", `/v1/groups/${g.id}/expenses/${eid}`, { token: a.token, body, idem: key });
      expect(other.status).toBe(201);
    };
    const r = await call("PUT", `/v1/groups/${g.id}/expenses/${eid}`, { token: a.token, body, idem: key });
    expect(r.status).toBe(201);
    expect(r.headers.get("Idempotent-Replay")).toBe("true");
    expect(await rows("SELECT * FROM changes WHERE group_id = ? AND entity_id = ?", g.id, eid)).toHaveLength(1);
  });
});

describe("3.1 guard: the whole batch rolls back", () => {
  async function snapshot(gid: string) {
    return {
      version: await version(gid),
      changes: (await rows("SELECT COUNT(*) AS n FROM changes WHERE group_id = ?", gid))[0].n,
      expenses: (await rows("SELECT COUNT(*) AS n FROM expenses WHERE group_id = ?", gid))[0].n,
    };
  }

  it("an expense written to a group tombstoned after the handler's checks", async () => {
    const { a, g, ma } = await pair();
    const before = await snapshot(g.id);
    testHooks.beforeBatch = async (label) => {
      if (label === "expense.put") await env.DB.prepare("UPDATE groups SET deleted_at = ? WHERE id = ?").bind(Date.now(), g.id).run();
    };
    const { eid, r } = await putExpense(a, g.id, expenseBody(ma, 100, [ma]));
    expect(r.status).toBe(410);
    expect(r.body.error.code).toBe("group_deleted");
    expect(await snapshot(g.id)).toEqual(before);
    expect(await rows("SELECT * FROM expense_shares WHERE expense_id = ?", eid)).toHaveLength(0);
  });

  it("an expense id that lives in another group", async () => {
    const { a, g, ma } = await pair();
    const other = await createGroup(a);
    const mo = other.members[a.id];
    const eid = uuid();
    const before = await snapshot(g.id);
    testHooks.beforeBatch = async (label) => {
      if (label !== "expense.put") return;
      testHooks.beforeBatch = undefined;
      const r = await call("PUT", `/v1/groups/${other.id}/expenses/${eid}`, { token: a.token, body: expenseBody(mo, 777, [mo]) });
      expect(r.status).toBe(201);
    };
    const r = await call("PUT", `/v1/groups/${g.id}/expenses/${eid}`, { token: a.token, body: expenseBody(ma, 100, [ma]) });
    expect(r.status).toBe(404);
    expect(await snapshot(g.id)).toEqual(before);
    const stored = await rows("SELECT group_id, amount_paise FROM expenses WHERE id = ?", eid);
    expect(stored).toEqual([{ group_id: other.id, amount_paise: 777 }]);
    expect(await rows("SELECT member_id FROM expense_shares WHERE expense_id = ?", eid)).toEqual([{ member_id: mo }]);
  });

  it("a share for a member removed after the checks", async () => {
    const { a, g, ma } = await pair();
    const fid = uuid();
    await call("POST", `/v1/groups/${g.id}/members`, { token: a.token, body: { id: fid, name: "Kiran" } });
    const before = await snapshot(g.id);
    testHooks.beforeBatch = async (label) => {
      if (label === "expense.put") await env.DB.prepare("UPDATE members SET removed_at = ? WHERE id = ?").bind(Date.now(), fid).run();
    };
    const { r } = await putExpense(a, g.id, expenseBody(ma, 100, [ma, fid]));
    expect(r.status).toBe(400);
    expect(r.body.error.code).toBe("bad_request");
    expect(await snapshot(g.id)).toEqual(before);
  });

  it("a leave racing an expense", async () => {
    const { a, b, g, ma, mb } = await pair();
    let fired = false;
    testHooks.beforeBatch = async (label) => {
      if (label !== "group.leave" || fired) return;
      fired = true;
      const { r } = await putExpense(a, g.id, expenseBody(ma, 1000, [ma, mb]));
      expect(r.status).toBe(201);
    };
    const leave = await call("POST", `/v1/groups/${g.id}/leave`, { token: b.token });
    expect(leave.status).toBe(409);
    expect(leave.body.error.code).toBe("balances_not_settled");
    const m = await rows("SELECT removed_at FROM members WHERE id = ?", mb);
    expect(m[0].removed_at).toBeNull();
    expect(await rows("SELECT * FROM changes WHERE group_id = ? AND op = 'leave'", g.id)).toHaveLength(0);
  });

  it("a leave whose race leaves the balance at 0 is applied on the second check", async () => {
    const { a, b, g, ma } = await pair();
    let fired = false;
    testHooks.beforeBatch = async (label) => {
      if (label !== "group.leave" || fired) return;
      fired = true;
      await putExpense(a, g.id, expenseBody(ma, 1000, [ma]));
    };
    expect((await call("POST", `/v1/groups/${g.id}/leave`, { token: b.token })).status).toBe(204);
  });
});

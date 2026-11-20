// Me and devices (docs/server.md 4.5).
import { describe, expect, it } from "vitest";
import type { Db } from "../src/db/db";
import { testHooks } from "../src/testing";
import { befriend, call, createGroup, env, expenseBody, putExpense, rows, shareToken, signIn, today, uuid, type TestUser } from "./helpers";

describe("GET and PATCH /v1/me", () => {
  it("validates and returns the user", async () => {
    const u = await signIn("Asha");
    expect((await call("GET", "/v1/me", { token: u.token })).body).toEqual({ id: u.id, name: "Asha", phone: null, upiId: null });
    const ok = await call("PATCH", "/v1/me", { token: u.token, body: { name: "  Asha Rao ", phone: "+919800000001", upiId: "asha.example@okicici" } });
    expect(ok.body).toEqual({ id: u.id, name: "Asha Rao", phone: "+919800000001", upiId: "asha.example@okicici" });
    const cleared = await call("PATCH", "/v1/me", { token: u.token, body: { phone: null, upiId: null } });
    expect(cleared.body).toEqual({ id: u.id, name: "Asha Rao", phone: null, upiId: null });
    for (const [body, field] of [
      [{ name: "" }, "name"],
      [{ name: "x".repeat(61) }, "name"],
      [{ phone: "9800000001" }, "phone"],
      [{ upiId: "not a upi" }, "upiId"],
      [{ upiId: "a@1bank" }, "upiId"],
      [{ upiId: "" }, "upiId"],
      [{ upiId: "   " }, "upiId"],
      [{ upiId: "asha example@okicici" }, "upiId"],
      [{ upiId: "a@okicici" }, "upiId"],
      [{ upiId: "asha@o" }, "upiId"],
      [{ upiId: "asha@@okicici" }, "upiId"],
      [{ upiId: "asha+1@okicici" }, "upiId"],
      [{ upiId: 42 }, "upiId"],
    ] as const) {
      const r = await call("PATCH", "/v1/me", { token: u.token, body });
      expect(r.status).toBe(400);
      expect(r.body.error.field).toBe(field);
    }
  });

  it("UPI ID: spaces at both ends are dropped, case is kept, null clears (the rule the app copies, 4.5)", async () => {
    const u = await signIn("Asha");
    const cases: [string, string][] = [
      ["  asha.example@okicici ", "asha.example@okicici"],
      ["Asha_Example-1@OKAxis", "Asha_Example-1@OKAxis"],
      ["9876543210@ybl", "9876543210@ybl"],
      ["ab@upi", "ab@upi"],
      [`${"a".repeat(64)}@${"b".repeat(64)}`, `${"a".repeat(64)}@${"b".repeat(64)}`],
    ];
    for (const [sent, stored] of cases) {
      const r = await call("PATCH", "/v1/me", { token: u.token, body: { upiId: sent } });
      expect(r.status, sent).toBe(200);
      expect(r.body.upiId).toBe(stored);
      expect((await rows("SELECT upi_id FROM users WHERE id = ?", u.id))[0].upi_id).toBe(stored);
    }
    for (const bad of [`${"a".repeat(65)}@okaxis`, `asha@${"b".repeat(65)}`]) {
      expect((await call("PATCH", "/v1/me", { token: u.token, body: { upiId: bad } })).body.error.field).toBe("upiId");
    }
    expect((await call("PATCH", "/v1/me", { token: u.token, body: { upiId: null } })).body.upiId).toBeNull();
  });

  it("a profile change for a user in 60 groups is one batch of set-based statements", async () => {
    const u = await signIn("Many");
    const groups: string[] = [];
    for (let i = 0; i < 60; i++) groups.push((await createGroup(u, {}, "G" + i)).id);
    let db: Db | null = null;
    testHooks.onDb = (d) => (db = d);
    const r = await call("PATCH", "/v1/me", { token: u.token, body: { name: "Many Groups" } });
    expect(r.status).toBe(200);
    expect(db!.batches).toBe(1);
    expect(db!.queries).toBeLessThanOrEqual(5);
    const versions = await rows("SELECT id, version FROM groups WHERE owner_user_id = ?", u.id);
    expect(versions).toHaveLength(60);
    expect(versions.every((g) => g.version === 2)).toBe(true);
    const changes = await rows("SELECT kind, op, version FROM changes WHERE version = 2 AND group_id IN (SELECT id FROM groups WHERE owner_user_id = ?)", u.id);
    expect(changes).toHaveLength(60);
    expect(changes.every((c) => c.kind === "member" && c.op === "update")).toBe(true);
    const members = await rows("SELECT version FROM members WHERE user_id = ?", u.id);
    expect(members.every((m) => m.version === 2)).toBe(true);
    // No change: no bump.
    expect((await call("PATCH", "/v1/me", { token: u.token, body: { name: "Many Groups" } })).status).toBe(200);
    expect((await rows("SELECT MAX(version) AS v FROM groups WHERE owner_user_id = ?", u.id))[0].v).toBe(2);
  });
});

describe("DELETE /v1/me", () => {
  it("one batch: lone groups tombstoned, shared ones keep history with a Former member, ownership passes", async () => {
    const a = await signIn("Arjun");
    const b = await signIn("Rahul");
    const solo: string[] = [];
    for (let i = 0; i < 60; i++) solo.push((await createGroup(a, { friends: [{ name: "F" }] }, "S" + i)).id);
    const shared = await befriend(a, b); // a owns, b joined
    const ma = shared.members[a.id];
    const mb = shared.members[b.id];
    await putExpense(a, shared.id, expenseBody(ma, 1000, [ma, mb]));
    const linkToken = "B".repeat(24);
    await call("PUT", `/v1/groups/${shared.id}/members/${ma}/links/${uuid()}`, { token: b.token, body: { token: linkToken } });

    let db: Db | null = null;
    testHooks.onDb = (d) => (db = d);
    const r = await call("DELETE", "/v1/me", { token: a.token });
    expect(r.status).toBe(204);
    expect(db!.batches).toBe(1);

    expect(await rows("SELECT * FROM users WHERE id = ?", a.id)).toHaveLength(0);
    expect(await rows("SELECT * FROM sessions WHERE user_id = ?", a.id)).toHaveLength(0);
    expect((await call("GET", "/v1/me", { token: a.token })).status).toBe(401);
    const lone = await rows(`SELECT deleted_at FROM groups WHERE id IN (${solo.map(() => "?").join(",")})`, ...solo);
    expect(lone.every((g) => g.deleted_at !== null)).toBe(true);
    const loneChange = await rows("SELECT kind, op FROM changes WHERE group_id = ? ORDER BY version DESC LIMIT 1", solo[0]);
    expect(loneChange[0]).toEqual({ kind: "group", op: "delete" });

    const g = (await rows("SELECT owner_user_id, deleted_at FROM groups WHERE id = ?", shared.id))[0];
    expect(g).toEqual({ owner_user_id: b.id, deleted_at: null });
    const pull = await call("GET", `/v1/groups/${shared.id}/changes`, { token: b.token });
    const former = pull.body.members.find((m: any) => m.id === ma);
    expect(former).toMatchObject({ userId: null, name: "Former member", phone: null, onApp: false, removed: false });
    // Balances and history stay right for everyone else.
    const bal = await call("GET", `/v1/groups/${shared.id}/balances`, { token: b.token });
    expect(bal.body.transfers).toEqual([{ fromMemberId: mb, toMemberId: ma, amountPaise: 500 }]);
    expect((await call("GET", `/v1/links/${linkToken}`)).body.error.code).toBe("link_not_found");
    const last = await rows("SELECT kind, op, entity_id FROM changes WHERE group_id = ? ORDER BY version DESC LIMIT 1", shared.id);
    expect(last[0]).toEqual({ kind: "member", op: "update", entity_id: ma });
  });
});

describe("DELETE /v1/me, groups the user does not own (docs/product.md \"Delete account\")", () => {
  it("shared groups keep a Former member and their owner; groups with no other app user left are deleted", async () => {
    const a = await signIn("Arjun");
    const b = await signIn("Rahul");
    const shared = await befriend(a, b); // a owns; b joined through a's link
    const mb = shared.members[b.id];
    await putExpense(a, shared.id, expenseBody(shared.members[a.id], 1000, [shared.members[a.id], mb]));
    // b owns this one; a was in it and left, so b is its only member on the app.
    const lone = await createGroup(b, { users: [a] }, "Lone");
    expect((await call("POST", `/v1/groups/${lone.id}/leave`, { token: a.token })).status).toBe(204);
    // A direct split with a friend without the app.
    const direct = uuid();
    await call("POST", "/v1/groups", {
      token: b.token,
      body: { id: direct, kind: "direct", myMemberId: uuid(), members: [{ id: uuid(), name: "Priya" }] },
    });

    expect((await call("DELETE", "/v1/me", { token: b.token })).status).toBe(204);
    expect((await rows("SELECT owner_user_id, deleted_at FROM groups WHERE id = ?", shared.id))[0]).toEqual({ owner_user_id: a.id, deleted_at: null });
    const row = (await rows("SELECT user_id, name, phone, join_link_id, join_prev_name, join_prev_phone FROM members WHERE id = ?", mb))[0];
    expect(row).toEqual({ user_id: null, name: "Former member", phone: null, join_link_id: null, join_prev_name: null, join_prev_phone: null });
    const pull = await call("GET", `/v1/groups/${shared.id}/changes`, { token: a.token });
    expect(pull.body.members.find((m: any) => m.id === mb)).toMatchObject({ name: "Former member", onApp: false, canUndo: false });
    expect((await call("GET", `/v1/groups/${shared.id}/balances`, { token: a.token })).body.transfers).toEqual([
      { fromMemberId: mb, toMemberId: shared.members[a.id], amountPaise: 500 },
    ]);
    for (const id of [lone.id, direct]) {
      expect((await rows("SELECT deleted_at FROM groups WHERE id = ?", id))[0].deleted_at).not.toBeNull();
    }
  });
});

describe("DELETE /v1/me, rows of groups the user left", () => {
  it("bumps those groups too, so every phone pulls the Former member row", async () => {
    const a = await signIn("Arjun");
    const b = await signIn("Rahul");
    const g = await befriend(a, b);
    const mb = g.members[b.id];
    expect((await call("POST", `/v1/groups/${g.id}/leave`, { token: b.token })).status).toBe(204);
    const before = (await rows("SELECT version FROM groups WHERE id = ?", g.id))[0].version;

    expect((await call("DELETE", "/v1/me", { token: b.token })).status).toBe(204);
    const after = (await rows("SELECT version FROM groups WHERE id = ?", g.id))[0].version;
    expect(after).toBe(before + 1);
    expect((await rows("SELECT name, user_id, version FROM members WHERE id = ?", mb))[0]).toEqual({
      name: "Former member",
      user_id: null,
      version: after,
    });
    expect((await rows("SELECT kind, op, entity_id FROM changes WHERE group_id = ? AND version = ?", g.id, after))[0]).toEqual({
      kind: "member",
      op: "update",
      entity_id: mb,
    });
    // A phone that was up to date before still gets the row.
    const pull = await call("GET", `/v1/groups/${g.id}/changes?since=${before}`, { token: a.token });
    expect(pull.body.members.find((m: any) => m.id === mb)).toMatchObject({ name: "Former member", userId: null, removed: true });
  });

  it("reads rows by index only: other people's groups do not count", async () => {
    // 500 groups of someone else, written directly.
    const other = await signIn("Other");
    const now = Date.now();
    const stmts: D1PreparedStatement[] = [];
    for (let i = 0; i < 500; i++) {
      stmts.push(
        env.DB.prepare("INSERT INTO groups (id, kind, name, owner_user_id, version, created_at, updated_at) VALUES (?, 'group', 'G', ?, 1, ?, ?)").bind(
          uuid(),
          other.id,
          now,
          now,
        ),
      );
    }
    await env.DB.batch(stmts);
    const u = await signIn("Leaving");
    await createGroup(u, { friends: [{ name: "F" }] });
    let db: Db | null = null;
    testHooks.onDb = (d) => (db = d);
    expect((await call("DELETE", "/v1/me", { token: u.token })).status).toBe(204);
    expect(db!.rowsRead).toBeLessThan(200);
  });
});

describe("DELETE /v1/me, families (docs/server.md 4.18)", () => {
  const own = (mid: string, amount: number) => ({
    description: "BigBasket", category: "GROCERIES", amountPaise: amount, occurredOn: today(), paidByMemberId: mid,
    splitMethod: "EQUAL", auto: false, shares: [{ memberId: mid, sharePaise: amount, input: null }], baseVersion: null,
  });
  async function family(owner: TestUser, partner?: TestUser) {
    const [id, mine, place] = [uuid(), uuid(), uuid()];
    const r = await call("POST", "/v1/groups", { token: owner.token, body: { id, kind: "family", name: "Home", myMemberId: mine, members: [{ id: place, name: "Partner" }] } });
    expect(r.status).toBe(201);
    if (partner) {
      const token = shareToken();
      await call("PUT", `/v1/groups/${id}/members/${place}/links/${uuid()}`, { token: owner.token, body: { token } });
      expect((await call("POST", `/v1/invites/${token}/accept`, { token: partner.token })).status).toBe(200);
    }
    return { id, mine, place };
  }

  it("the caller's family expenses leave the family; the partner's stay; the family lives on with a Former member", async () => {
    const a = await signIn("Arjun");
    const b = await signIn("Meera");
    const fam = await family(a, b);
    const { eid: a1 } = await putExpense(a, fam.id, own(fam.mine, 234000));
    const { eid: a2 } = await putExpense(a, fam.id, own(fam.mine, 45000));
    const { eid: b1 } = await putExpense(b, fam.id, own(fam.place, 120000));
    // An expense of a in a normal group is not touched.
    const g = await befriend(a, b);
    const { eid: g1 } = await putExpense(a, g.id, expenseBody(g.members[a.id], 1000, [g.members[a.id], g.members[b.id]]));
    const before = (await rows("SELECT version FROM groups WHERE id = ?", fam.id))[0].version;

    let db: Db | null = null;
    testHooks.onDb = (d) => (db = d);
    expect((await call("DELETE", "/v1/me", { token: a.token })).status).toBe(204);
    expect(db!.batches).toBe(1);
    const v = (await rows("SELECT version FROM groups WHERE id = ?", fam.id))[0].version;
    expect(v).toBe(before + 1);
    expect(await rows("SELECT id, deleted_at IS NOT NULL AS gone, version FROM expenses WHERE id IN (?, ?) ORDER BY id", a1, a2)).toEqual(
      [a1, a2].sort().map((id) => ({ id, gone: 1, version: v })),
    );
    expect(await rows("SELECT * FROM expense_shares WHERE expense_id IN (?, ?)", a1, a2)).toHaveLength(0);
    expect((await rows("SELECT deleted_at FROM expenses WHERE id = ?", b1))[0].deleted_at).toBeNull();
    expect(await rows("SELECT * FROM expense_shares WHERE expense_id = ?", b1)).toHaveLength(1);
    expect((await rows("SELECT deleted_at FROM expenses WHERE id = ?", g1))[0].deleted_at).toBeNull();
    expect((await rows("SELECT owner_user_id, deleted_at FROM groups WHERE id = ?", fam.id))[0]).toEqual({ owner_user_id: b.id, deleted_at: null });

    const pull = await call("GET", `/v1/groups/${fam.id}/changes?since=${before}`, { token: b.token });
    expect(pull.body.expenses).toEqual(
      expect.arrayContaining([
        { id: a1, groupId: fam.id, deleted: true, version: v },
        { id: a2, groupId: fam.id, deleted: true, version: v },
      ]),
    );
    expect(pull.body.expenses).toHaveLength(2);
    expect(pull.body.members.find((m: any) => m.id === fam.mine)).toMatchObject({ userId: null, name: "Former member", onApp: false });
  });

  it("a family with nobody else on the app is tombstoned with the caller's expenses", async () => {
    const c = await signIn();
    const fam = await family(c);
    const { eid } = await putExpense(c, fam.id, own(fam.mine, 1000));
    expect((await call("DELETE", "/v1/me", { token: c.token })).status).toBe(204);
    expect((await rows("SELECT deleted_at FROM groups WHERE id = ?", fam.id))[0].deleted_at).not.toBeNull();
    expect((await rows("SELECT deleted_at FROM expenses WHERE id = ?", eid))[0].deleted_at).not.toBeNull();
    expect(await rows("SELECT * FROM expense_shares WHERE expense_id = ?", eid)).toHaveLength(0);
  });
});

describe("devices", () => {
  it("upserts, moves a token to the new installation and keeps 3 per user", async () => {
    const u = await signIn();
    const inst = [uuid(), uuid(), uuid(), uuid()];
    for (let i = 0; i < 4; i++) {
      expect((await call("PUT", `/v1/devices/${inst[i]}`, { token: u.token, body: { fcmToken: "tok-" + i + "-" + u.id } })).status).toBe(204);
      await new Promise((r) => setTimeout(r, 2));
    }
    const left = await rows("SELECT installation_id FROM devices WHERE user_id = ? ORDER BY updated_at", u.id);
    expect(left.map((d) => d.installation_id)).toEqual(inst.slice(1));
    // The token of inst[3] moves to a new installation.
    const moved = uuid();
    await call("PUT", `/v1/devices/${moved}`, { token: u.token, body: { fcmToken: "tok-3-" + u.id } });
    expect(await rows("SELECT * FROM devices WHERE installation_id = ?", inst[3])).toHaveLength(0);
    // A phone shared by two accounts in turn keeps one installation id: the row moves to the caller.
    const v = await signIn();
    await call("PUT", `/v1/devices/${moved}`, { token: v.token, body: { fcmToken: "tok-v-" + v.id } });
    expect((await rows("SELECT user_id FROM devices WHERE installation_id = ?", moved))[0].user_id).toBe(v.id);
    expect((await call("DELETE", `/v1/devices/${moved}`, { token: v.token })).status).toBe(204);
    expect(await rows("SELECT * FROM devices WHERE installation_id = ?", moved)).toHaveLength(0);
    expect((await call("PUT", `/v1/devices/${uuid()}`, { token: v.token, body: { fcmToken: "" } })).status).toBe(400);
  });
});

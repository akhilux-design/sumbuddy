// Groups and members (docs/server.md 4.6), reminders (4.11), /me/groups (4.5).
import { describe, expect, it } from "vitest";
import { testHooks } from "../src/testing";
import {
  befriend,
  call,
  createGroup,
  directGroupId,
  directMemberId,
  expenseBody,
  putExpense,
  rows,
  shareToken,
  signIn,
  uuid,
} from "./helpers";

describe("POST /v1/groups", () => {
  it("creates a group with the caller as owner, a known user and a friend without the app", async () => {
    const a = await signIn("Arjun Mehta");
    const b = await signIn("Rahul Shah");
    await befriend(a, b);
    const id = uuid();
    const mine = uuid();
    const rahul = uuid();
    const priya = uuid();
    const r = await call("POST", "/v1/groups", {
      token: a.token,
      body: {
        id,
        kind: "group",
        name: "Goa trip",
        myMemberId: mine,
        members: [{ id: rahul, userId: b.id }, { id: priya, name: "Priya Nair", phone: "+919000000001" }],
      },
    });
    expect(r.status).toBe(201);
    expect(r.body.group).toEqual({
      id,
      kind: "group",
      name: "Goa trip",
      ownerUserId: a.id,
      ownerMemberId: mine,
      version: 1,
      createdAt: r.body.group.createdAt,
    });
    const byId = Object.fromEntries(r.body.members.map((m: any) => [m.id, m]));
    expect(byId[mine]).toMatchObject({ userId: a.id, name: "Arjun Mehta", onApp: true, removed: false, version: 1 });
    expect(byId[rahul]).toMatchObject({ userId: b.id, name: "Rahul Shah", onApp: true });
    expect(byId[priya]).toMatchObject({ userId: null, name: "Priya Nair", phone: "+919000000001", upiId: null, onApp: false });
    const changes = await rows("SELECT * FROM changes WHERE group_id = ?", id);
    expect(changes).toEqual([expect.objectContaining({ version: 1, kind: "group", op: "create", entity_id: id, actor_member_id: mine })]);
    // b sees it in /me/groups.
    const list = await call("GET", "/v1/me/groups", { token: b.token });
    expect(list.body.groups).toContainEqual({ id, kind: "group", name: "Goa trip", version: 1, myMemberId: rahul, ownerUserId: a.id });
    expect(typeof list.body.serverTime).toBe("number");
  });

  it("refuses unknown users (no lookup by phone or email)", async () => {
    const a = await signIn();
    const stranger = await signIn();
    const r = await call("POST", "/v1/groups", {
      token: a.token,
      body: { id: uuid(), kind: "group", name: "X", myMemberId: uuid(), members: [{ id: uuid(), userId: stranger.id }] },
    });
    expect(r.status).toBe(403);
    expect(r.body.error.code).toBe("not_allowed");
  });

  it("validates names, phones and ids", async () => {
    const a = await signIn();
    const base = { id: uuid(), kind: "group", name: "Flat", myMemberId: uuid(), members: [] as any[] };
    const cases: [any, string][] = [
      [{ ...base, name: "" }, "name"],
      [{ ...base, name: "x".repeat(41) }, "name"],
      [{ ...base, name: "Flat‮evil" }, "name"],
      [{ ...base, id: "NOT-A-UUID" }, "id"],
      [{ ...base, members: [{ id: uuid(), name: "Priya", phone: "98450" }] }, "members[0].phone"],
      [{ ...base, members: [{ id: base.myMemberId, name: "Priya" }] }, "members[0].id"],
      [{ ...base, kind: "pool" }, "kind"],
      // A family is made with exactly its placeholder (docs/server.md 4.18): none is a missing member.
      [{ ...base, kind: "family" }, "members"],
    ];
    for (const [body, field] of cases) {
      const r = await call("POST", "/v1/groups", { token: a.token, body });
      expect(r.status, field).toBe(400);
      expect(r.body.error.field).toBe(field);
    }
  });

  it("same id and content answers 200; other content 409", async () => {
    const a = await signIn();
    const g = await createGroup(a, { friends: [{ name: "Priya" }] });
    const again = await call("POST", "/v1/groups", {
      token: a.token,
      body: { id: g.id, kind: "group", name: "Flat 302", myMemberId: g.members[a.id], members: [{ id: g.members.Priya, name: "Priya" }] },
    });
    expect(again.status).toBe(200);
    const other = await call("POST", "/v1/groups", {
      token: a.token,
      body: { id: g.id, kind: "group", name: "Flat 302", myMemberId: uuid(), members: [] },
    });
    expect(other.status).toBe(409);
    expect(other.body.error.code).toBe("exists_different");
  });
});

describe("direct splits", () => {
  it("checks the deterministic ids and is made again after both people left", async () => {
    const a = await signIn("Arjun");
    const b = await signIn("Rahul");
    await befriend(a, b);
    const gid = await directGroupId(a.id, b.id);
    const ma = await directMemberId(gid, a.id);
    const mb = await directMemberId(gid, b.id);
    const bad = await call("POST", "/v1/groups", {
      token: a.token,
      body: { id: uuid(), kind: "direct", myMemberId: ma, members: [{ id: mb, userId: b.id }] },
    });
    expect(bad.status).toBe(400);
    expect(bad.body.error.field).toBe("id");
    const badMember = await call("POST", "/v1/groups", {
      token: a.token,
      body: { id: gid, kind: "direct", myMemberId: uuid(), members: [{ id: mb, userId: b.id }] },
    });
    expect(badMember.body.error.field).toBe("myMemberId");

    const ok = await call("POST", "/v1/groups", {
      token: a.token,
      body: { id: gid, kind: "direct", myMemberId: ma, members: [{ id: mb, userId: b.id }] },
    });
    expect(ok.status).toBe(201);
    expect(ok.body.group).toMatchObject({ kind: "direct", name: null });
    // The other phone makes the same group offline: answered 200 with the current state.
    const fromB = await call("POST", "/v1/groups", {
      token: b.token,
      body: { id: gid, kind: "direct", myMemberId: mb, members: [{ id: ma, userId: a.id }] },
    });
    expect(fromB.status).toBe(200);

    // No rename, no adding, no delete.
    expect((await call("PATCH", `/v1/groups/${gid}`, { token: a.token, body: { name: "x" } })).body.error.code).toBe("not_allowed");
    expect((await call("POST", `/v1/groups/${gid}/members`, { token: a.token, body: { id: uuid(), name: "C" } })).body.error.code).toBe("not_allowed");
    expect((await call("DELETE", `/v1/groups/${gid}`, { token: a.token })).body.error.code).toBe("not_allowed");

    expect((await call("POST", `/v1/groups/${gid}/leave`, { token: a.token })).status).toBe(204);
    expect((await call("POST", `/v1/groups/${gid}/leave`, { token: b.token })).status).toBe(204);
    const g = (await rows("SELECT deleted_at FROM groups WHERE id = ?", gid))[0];
    expect(g.deleted_at).toBeNull(); // never tombstoned by leaving

    const again = await call("POST", "/v1/groups", {
      token: b.token,
      body: { id: gid, kind: "direct", myMemberId: mb, members: [{ id: ma, userId: a.id }] },
    });
    expect(again.status).toBe(200);
    expect(again.body.members.every((m: any) => !m.removed)).toBe(true);
    const joins = await rows("SELECT op FROM changes WHERE group_id = ? AND op = 'join'", gid);
    expect(joins).toHaveLength(2);
    expect((await call("GET", "/v1/me/groups", { token: a.token })).body.groups.map((x: any) => x.id)).toContain(gid);
  });

  it("with a friend without the app uses random ids", async () => {
    const a = await signIn();
    const r = await call("POST", "/v1/groups", {
      token: a.token,
      body: { id: uuid(), kind: "direct", myMemberId: uuid(), members: [{ id: uuid(), name: "Priya", phone: "+919000000001" }] },
    });
    expect(r.status).toBe(201);
    const two = await call("POST", "/v1/groups", {
      token: a.token,
      body: { id: uuid(), kind: "direct", myMemberId: uuid(), members: [{ id: uuid(), name: "A" }, { id: uuid(), name: "B" }] },
    });
    expect(two.status).toBe(400);
  });
});

describe("rename, delete, leave", () => {
  it("renames (any member) and bumps the version", async () => {
    const a = await signIn();
    const b = await signIn();
    const intro = await befriend(a, b);
    const r = await call("PATCH", `/v1/groups/${intro.id}`, { token: b.token, body: { name: "Renamed" } });
    expect(r.status).toBe(200);
    expect(r.body).toMatchObject({ id: intro.id, name: "Renamed", version: 3 });
  });

  it("delete: owner only, every net 0; then 410 and links revoked", async () => {
    const a = await signIn();
    const b = await signIn();
    const g = await befriend(a, b);
    const { r } = await putExpense(a, g.id, expenseBody(g.members[a.id], 1000, [g.members[a.id], g.members[b.id]]));
    expect(r.status).toBe(201);
    expect((await call("DELETE", `/v1/groups/${g.id}`, { token: b.token })).body.error.code).toBe("not_owner");
    expect((await call("DELETE", `/v1/groups/${g.id}`, { token: a.token })).body.error.code).toBe("balances_not_settled");
    await call("PUT", `/v1/groups/${g.id}/settlements/${uuid()}`, {
      token: b.token,
      body: { fromMemberId: g.members[b.id], toMemberId: g.members[a.id], amountPaise: 500, method: "CASH", occurredOn: "2026-11-16" },
    });
    expect((await call("DELETE", `/v1/groups/${g.id}`, { token: a.token })).status).toBe(204);
    expect((await call("GET", `/v1/groups/${g.id}/changes`, { token: b.token })).body.error.code).toBe("group_deleted");
    expect((await call("GET", "/v1/me/groups", { token: b.token })).body.groups.map((x: any) => x.id)).not.toContain(g.id);
    const live = await rows("SELECT * FROM links WHERE group_id = ? AND revoked_at IS NULL", g.id);
    expect(live).toHaveLength(0);
    const last = await rows("SELECT kind, op FROM changes WHERE group_id = ? ORDER BY version DESC LIMIT 1", g.id);
    expect(last[0]).toEqual({ kind: "group", op: "delete" });
  });

  it("delete: the owner deletes with balances not settled while nobody else is on the app (204), and gets 409 once a friend joins", async () => {
    const a = await signIn("Arjun");
    const priya = await signIn("Priya");
    const alone = await createGroup(a, { friends: [{ name: "Priya" }, { name: "Kiran" }] });
    const joined = await createGroup(a, { friends: [{ name: "Priya" }, { name: "Kiran" }] });
    for (const g of [alone, joined]) {
      const { r } = await putExpense(a, g.id, expenseBody(g.members[a.id], 900, [g.members[a.id], g.members.Priya, g.members.Kiran]));
      expect(r.status).toBe(201);
    }
    const token = shareToken();
    await call("PUT", `/v1/groups/${alone.id}/members/${alone.members.Kiran}/links/${uuid()}`, { token: a.token, body: { token } });

    expect((await call("DELETE", `/v1/groups/${alone.id}`, { token: a.token })).status).toBe(204);
    expect((await call("GET", `/v1/groups/${alone.id}/changes`, { token: a.token })).body.error.code).toBe("group_deleted");
    expect(await rows("SELECT * FROM links WHERE group_id = ? AND revoked_at IS NULL", alone.id)).toHaveLength(0);
    expect(await rows("SELECT reason FROM access_ended WHERE user_id = ? AND group_id = ?", a.id, alone.id)).toEqual([{ reason: "deleted" }]);

    // Priya takes her place through her link: the balances rule is back.
    const link = shareToken();
    await call("PUT", `/v1/groups/${joined.id}/members/${joined.members.Priya}/links/${uuid()}`, { token: a.token, body: { token: link } });
    expect((await call("POST", `/v1/invites/${link}/accept`, { token: priya.token })).status).toBe(200);
    const refused = await call("DELETE", `/v1/groups/${joined.id}`, { token: a.token });
    expect(refused.status).toBe(409);
    expect(refused.body.error.code).toBe("balances_not_settled");
    expect((await rows("SELECT deleted_at FROM groups WHERE id = ?", joined.id))[0].deleted_at).toBeNull();
  });

  it("delete: a friend who joins between the checks and the batch makes it 409 (the version guard, checked again)", async () => {
    const a = await signIn("Arjun");
    const priya = await signIn("Priya");
    const g = await createGroup(a, { friends: [{ name: "Priya" }] });
    await putExpense(a, g.id, expenseBody(g.members[a.id], 900, [g.members[a.id], g.members.Priya]));
    let fired = false;
    testHooks.beforeBatch = async (label) => {
      if (label !== "group.delete" || fired) return;
      fired = true;
      const link = shareToken();
      await call("PUT", `/v1/groups/${g.id}/members/${g.members.Priya}/links/${uuid()}`, { token: a.token, body: { token: link } });
      expect((await call("POST", `/v1/invites/${link}/accept`, { token: priya.token })).status).toBe(200);
    };
    const r = await call("DELETE", `/v1/groups/${g.id}`, { token: a.token });
    expect(r.status).toBe(409);
    expect(r.body.error.code).toBe("balances_not_settled");
    expect((await rows("SELECT deleted_at FROM groups WHERE id = ?", g.id))[0].deleted_at).toBeNull();
    expect((await call("GET", "/v1/me/groups", { token: priya.token })).body.groups.map((x: any) => x.id)).toContain(g.id);
  });

  it("leave: only at net 0; ownership passes; the last person on the app tombstones a group", async () => {
    const a = await signIn();
    const b = await signIn();
    const g = await befriend(a, b); // a owner, b joined, no friends without the app
    const { r } = await putExpense(b, g.id, expenseBody(g.members[b.id], 1000, [g.members[a.id], g.members[b.id]]));
    expect(r.status).toBe(201);
    expect((await call("POST", `/v1/groups/${g.id}/leave`, { token: a.token })).body.error.code).toBe("balances_not_settled");
    await call("PUT", `/v1/groups/${g.id}/settlements/${uuid()}`, {
      token: a.token,
      body: { fromMemberId: g.members[a.id], toMemberId: g.members[b.id], amountPaise: 500, method: "UPI", occurredOn: "2026-11-16" },
    });
    expect((await call("POST", `/v1/groups/${g.id}/leave`, { token: a.token })).status).toBe(204);
    const owner = (await rows("SELECT owner_user_id, deleted_at FROM groups WHERE id = ?", g.id))[0];
    expect(owner).toEqual({ owner_user_id: b.id, deleted_at: null });
    // The owner label moves with it: the group row is in every pull page.
    const pulled = await call("GET", `/v1/groups/${g.id}/changes?since=${g.version}`, { token: b.token });
    expect(pulled.body.group).toMatchObject({ ownerUserId: b.id, ownerMemberId: g.members[b.id] });
    expect((await call("GET", `/v1/groups/${g.id}/changes`, { token: a.token })).body.error.code).toBe("not_member");
    expect((await call("POST", `/v1/groups/${g.id}/leave`, { token: b.token })).status).toBe(204);
    expect((await rows("SELECT deleted_at FROM groups WHERE id = ?", g.id))[0].deleted_at).not.toBeNull();
  });
});

describe("members", () => {
  it("adds a friend, edits them, and refuses edits of app users", async () => {
    const a = await signIn();
    const b = await signIn();
    const g = await befriend(a, b);
    const fid = uuid();
    const add = await call("POST", `/v1/groups/${g.id}/members`, { token: b.token, body: { id: fid, name: "Kiran", phone: "+919800000001" } });
    expect(add.status).toBe(201);
    expect(add.body).toMatchObject({ id: fid, groupId: g.id, name: "Kiran", phone: "+919800000001", onApp: false });
    expect((await call("POST", `/v1/groups/${g.id}/members`, { token: b.token, body: { id: fid, name: "Kiran", phone: "+919800000001" } })).status).toBe(200);
    expect((await call("POST", `/v1/groups/${g.id}/members`, { token: b.token, body: { id: fid, name: "Other" } })).body.error.code).toBe("exists_different");

    const edit = await call("PATCH", `/v1/groups/${g.id}/members/${fid}`, { token: a.token, body: { name: "Kiran K", phone: null } });
    expect(edit.status).toBe(200);
    expect(edit.body).toMatchObject({ name: "Kiran K", phone: null });
    const onApp = await call("PATCH", `/v1/groups/${g.id}/members/${g.members[b.id]}`, { token: a.token, body: { name: "x" } });
    expect(onApp.body.error.code).toBe("member_on_app");
  });

  it("adds a known user once; already_member; limit of 50", async () => {
    const a = await signIn();
    const b = await signIn();
    await befriend(a, b);
    const g = await createGroup(a);
    const add = await call("POST", `/v1/groups/${g.id}/members`, { token: a.token, body: { id: uuid(), userId: b.id } });
    expect(add.status).toBe(201);
    expect(add.body.onApp).toBe(true);
    const dup = await call("POST", `/v1/groups/${g.id}/members`, { token: a.token, body: { id: uuid(), userId: b.id } });
    expect(dup.body.error.code).toBe("already_member");
    const big = await createGroup(a, { friends: Array.from({ length: 49 }, (_, i) => ({ name: "F" + i })) }, "Big");
    const over = await call("POST", `/v1/groups/${big.id}/members`, { token: a.token, body: { id: uuid(), name: "One more" } });
    expect(over.status).toBe(409);
    expect(over.body.error.code).toBe("limit_reached");
  });

  it("removes: not yourself, app users by the owner only, at net 0; removed members cannot gain a balance", async () => {
    const a = await signIn();
    const b = await signIn();
    const g = await befriend(a, b);
    const fid = uuid();
    await call("POST", `/v1/groups/${g.id}/members`, { token: a.token, body: { id: fid, name: "Kiran" } });
    expect((await call("DELETE", `/v1/groups/${g.id}/members/${g.members[b.id]}`, { token: b.token })).body.error.code).toBe("not_allowed");
    const c = await signIn();
    await befriend(b, c);
    await call("POST", `/v1/groups/${g.id}/members`, { token: b.token, body: { id: uuid(), userId: c.id } });
    const mc = (await rows("SELECT id FROM members WHERE group_id = ? AND user_id = ?", g.id, c.id))[0].id;
    expect((await call("DELETE", `/v1/groups/${g.id}/members/${mc}`, { token: b.token })).body.error.code).toBe("not_owner");

    await putExpense(a, g.id, expenseBody(g.members[a.id], 900, [g.members[a.id], fid]));
    expect((await call("DELETE", `/v1/groups/${g.id}/members/${fid}`, { token: b.token })).body.error.code).toBe("balances_not_settled");
    await call("PUT", `/v1/groups/${g.id}/settlements/${uuid()}`, {
      token: a.token,
      body: { fromMemberId: fid, toMemberId: g.members[a.id], amountPaise: 450, method: "CASH", occurredOn: "2026-11-16" },
    });
    expect((await call("DELETE", `/v1/groups/${g.id}/members/${fid}`, { token: b.token })).status).toBe(204);
    expect((await call("DELETE", `/v1/groups/${g.id}/members/${fid}`, { token: b.token })).status).toBe(204);
    const { r } = await putExpense(a, g.id, expenseBody(g.members[a.id], 900, [g.members[a.id], fid]));
    expect(r.status).toBe(400);
    expect(r.body.error.field).toBe("shares[1].memberId".replace("1", String([g.members[a.id], fid].sort().indexOf(fid))));
  });
});

describe("POST /v1/groups/{gid}/reminders", () => {
  it("sends to members on the app who owe the caller, not too soon, never to friends", async () => {
    const a = await signIn();
    const b = await signIn();
    const g = await befriend(a, b);
    const fid = uuid();
    await call("POST", `/v1/groups/${g.id}/members`, { token: a.token, body: { id: fid, name: "Kiran" } });
    await putExpense(a, g.id, expenseBody(g.members[a.id], 900, [g.members[a.id], g.members[b.id], fid]));
    const ids = [g.members[b.id], fid, g.members[a.id]];
    const r = await call("POST", `/v1/groups/${g.id}/reminders`, { token: a.token, body: { memberIds: ids } });
    expect(r.status).toBe(200);
    expect(r.body).toEqual({
      sent: [g.members[b.id]],
      skipped: [
        { memberId: fid, reason: "not_on_app" },
        { memberId: g.members[a.id], reason: "nothing_owed" },
      ],
    });
    const again = await call("POST", `/v1/groups/${g.id}/reminders`, { token: a.token, body: { memberIds: [g.members[b.id]] } });
    expect(again.body.skipped).toEqual([{ memberId: g.members[b.id], reason: "too_soon" }]);
    // No version bump, no change row.
    const ver = (await rows("SELECT version FROM groups WHERE id = ?", g.id))[0].version;
    expect((await rows("SELECT MAX(version) AS v FROM changes WHERE group_id = ?", g.id))[0].v).toBe(ver);
  });
});

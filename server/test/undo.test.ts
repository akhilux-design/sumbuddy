// Undo a join by link (docs/server.md 4.6 "Undo a join by link"; docs/product.md "Forwarded links").
import { describe, expect, it } from "vitest";
import type { Db } from "../src/db/db";
import { testHooks } from "../src/testing";
import { befriend, call, createGroup, env, expenseBody, putExpense, rows, shareToken, signIn, uuid, type TestUser } from "./helpers";

const WEEK = 7 * 86_400_000;

/** a owns the group; b was added directly; Priya and Kiran are friends without the app; s took Priya's place by link. */
async function joined() {
  const a = await signIn("Arjun Mehta");
  const b = await signIn("Rahul Shah");
  await befriend(a, b);
  const g = await createGroup(a, { users: [b], friends: [{ name: "Priya Nair", phone: "+919000000001" }, { name: "Kiran" }] });
  const [ma, mb, mp, mk] = [g.members[a.id], g.members[b.id], g.members["Priya Nair"], g.members.Kiran];
  const token = shareToken();
  const linkId = uuid();
  expect((await call("PUT", `/v1/groups/${g.id}/members/${mp}/links/${linkId}`, { token: a.token, body: { token } })).status).toBe(201);
  const s = await signIn("Sam Stranger");
  const acc = await call("POST", `/v1/invites/${token}/accept`, { token: s.token });
  expect(acc.status).toBe(200);
  return { a, b, s, g, ma, mb, mp, mk, token, linkId };
}

const undo = (u: TestUser, gid: string, mid: string, idem?: string) =>
  call("POST", `/v1/groups/${gid}/members/${mid}/undo-join`, { token: u.token, idem });

const version = async (gid: string) => (await rows("SELECT version FROM groups WHERE id = ?", gid))[0].version as number;

async function pullMember(u: TestUser, gid: string, mid: string) {
  const r = await call("GET", `/v1/groups/${gid}/changes?since=0`, { token: u.token });
  expect(r.status).toBe(200);
  return { group: r.body.group, member: r.body.members.find((m: any) => m.id === mid), body: r.body };
}

describe("accept stores what undo needs", () => {
  it("keeps the link and the friend's name and phone hidden; the owner gets canUndo for 7 days", async () => {
    const { a, b, s, g, ma, mb, mp, linkId } = await joined();
    const row = (await rows("SELECT user_id, phone, joined_at, join_link_id, join_prev_name, join_prev_phone FROM members WHERE id = ?", mp))[0];
    expect(row).toMatchObject({ user_id: s.id, phone: null, join_link_id: linkId, join_prev_name: "Priya Nair", join_prev_phone: "+919000000001" });

    const owner = await pullMember(a, g.id, mp);
    expect(owner.group.ownerMemberId).toBe(ma);
    expect(owner.member).toMatchObject({
      userId: s.id,
      name: "Sam Stranger",
      onApp: true,
      joinedAt: row.joined_at,
      undoUntil: row.joined_at + WEEK,
      canUndo: true,
    });
    // Members not joined by link carry no window.
    for (const id of [ma, mb]) {
      expect(owner.body.members.find((m: any) => m.id === id)).toMatchObject({ joinedAt: null, undoUntil: null, canUndo: false });
    }
    // Everyone sees the window (ownership can pass to them); only the owner may undo.
    for (const u of [b, s]) {
      const other = await pullMember(u, g.id, mp);
      expect(other.group.ownerMemberId).toBe(ma);
      expect(other.member).toMatchObject({ joinedAt: row.joined_at, undoUntil: row.joined_at + WEEK, canUndo: false });
      // The friend's phone saved for an undo never reaches anyone, the person who joined included.
      expect(JSON.stringify(other.body)).not.toContain("+919000000001");
    }
  });

  it("the public link page keeps its shape: no ids, no phones", async () => {
    const { a, g, mk } = await joined();
    const tk = shareToken();
    await call("PUT", `/v1/groups/${g.id}/members/${mk}/links/${uuid()}`, { token: a.token, body: { token: tk } });
    const page = await call("GET", `/v1/links/${tk}`);
    expect(page.status).toBe(200);
    expect(Object.keys(page.body).sort()).toEqual(["expenses", "expensesTruncated", "friendName", "groupName", "owed", "owes", "senderName"]);
    expect(JSON.stringify(page.body)).not.toMatch(/\+91|[0-9a-f]{8}-[0-9a-f]{4}-/);
  });
});

describe("POST /v1/groups/{gid}/members/{mid}/undo-join", () => {
  it("puts the friend's place back, stops its links, takes the person out, and keeps balances", async () => {
    const { a, s, g, ma, mp, mk, token } = await joined();
    // While in the group, s adds an expense as Priya and makes a link for Kiran.
    const { r: put } = await putExpense(s, g.id, expenseBody(mp, 1000, [ma, mp]));
    expect(put.status).toBe(201);
    const tk = shareToken();
    expect((await call("PUT", `/v1/groups/${g.id}/members/${mk}/links/${uuid()}`, { token: s.token, body: { token: tk } })).status).toBe(201);
    const balancesBefore = (await call("GET", `/v1/groups/${g.id}/balances`, { token: a.token })).body;
    const before = await version(g.id);

    const key = uuid();
    const r = await undo(a, g.id, mp, key);
    expect(r.status).toBe(200);
    expect(r.body).toEqual({
      id: mp,
      groupId: g.id,
      userId: null,
      name: "Priya Nair",
      phone: "+919000000001",
      upiId: null,
      onApp: false,
      removed: false,
      joinedAt: null,
      undoUntil: null,
      canUndo: false,
      version: before + 1,
    });
    const row = (await rows("SELECT user_id, joined_at, join_link_id, join_prev_name, join_prev_phone FROM members WHERE id = ?", mp))[0];
    expect(row).toEqual({ user_id: null, joined_at: null, join_link_id: null, join_prev_name: null, join_prev_phone: null });
    // One mutation: version bump and one change row, so every phone pulls it.
    expect(await version(g.id)).toBe(before + 1);
    expect(await rows("SELECT kind, op, entity_id, actor_member_id FROM changes WHERE group_id = ? AND version > ?", g.id, before)).toEqual([
      { kind: "member", op: "update", entity_id: mp, actor_member_id: ma },
    ]);
    const pull = await call("GET", `/v1/groups/${g.id}/changes?since=${before}`, { token: a.token });
    expect(pull.body.members).toEqual([r.body]);

    // Balances stay right: the place keeps its id, so its expenses and shares are unchanged.
    const balancesAfter = (await call("GET", `/v1/groups/${g.id}/balances`, { token: a.token })).body;
    expect(balancesAfter.nets).toEqual(balancesBefore.nets);
    expect(balancesAfter.transfers).toEqual(balancesBefore.transfers);

    // The person is out, and no link they used or made works.
    expect((await call("GET", `/v1/groups/${g.id}/changes`, { token: s.token })).body.error.code).toBe("not_member");
    expect((await call("GET", "/v1/me/groups", { token: s.token })).body.groups.map((x: any) => x.id)).not.toContain(g.id);
    expect((await call("POST", `/v1/invites/${token}/accept`, { token: s.token })).body.error.code).toBe("link_not_found");
    expect((await call("GET", `/v1/invites/${token}`, { token: s.token })).body.error.code).toBe("link_not_found");
    expect((await call("GET", `/v1/links/${tk}`)).body.error.code).toBe("link_not_found");
    expect((await call("POST", `/v1/invites/${tk}/accept`, { token: s.token })).body.error.code).toBe("link_not_found");
    expect(await rows("SELECT id FROM links WHERE group_id = ? AND (member_id = ? OR created_by_member_id = ?) AND revoked_at IS NULL", g.id, mp, mp)).toEqual([]);

    // Same key: a replay with the same answer. A new key: the join is no longer there.
    const replay = await undo(a, g.id, mp, key);
    expect(replay.status).toBe(200);
    expect(replay.headers.get("Idempotent-Replay")).toBe("true");
    expect(replay.body).toEqual(r.body);
    const again = await undo(a, g.id, mp);
    expect(again.status).toBe(409);
    expect(again.body.error.code).toBe("not_joined_by_link");

    // The right person joins with a new link; that join can be undone too.
    const t2 = shareToken();
    expect((await call("PUT", `/v1/groups/${g.id}/members/${mp}/links/${uuid()}`, { token: a.token, body: { token: t2 } })).status).toBe(201);
    const priya = await signIn("Priya Nair");
    expect((await call("POST", `/v1/invites/${t2}/accept`, { token: priya.token })).status).toBe(200);
    expect((await pullMember(a, g.id, mp)).member).toMatchObject({ userId: priya.id, canUndo: true });
    expect((await rows("SELECT join_prev_name, join_prev_phone FROM members WHERE id = ?", mp))[0]).toEqual({
      join_prev_name: "Priya Nair",
      join_prev_phone: "+919000000001",
    });
  });

  it("answers not_owner, not_found, not_allowed, not_joined_by_link and undo_expired", async () => {
    const { a, b, g, ma, mb, mp, mk } = await joined();
    const r1 = await undo(b, g.id, mp);
    expect(r1.status).toBe(403);
    expect(r1.body.error.code).toBe("not_owner");
    const elsewhere = await createGroup(a, { friends: [{ name: "X" }] }, "Other");
    for (const mid of [uuid(), elsewhere.members.X]) {
      const r = await undo(a, g.id, mid);
      expect(r.status).toBe(404);
      expect(r.body.error.code).toBe("not_found");
    }
    const self = await undo(a, g.id, ma);
    expect(self.status).toBe(403);
    expect(self.body.error.code).toBe("not_allowed");
    // Added directly, or a friend without the app: not a join by link.
    for (const mid of [mb, mk]) {
      const r = await undo(a, g.id, mid);
      expect(r.status).toBe(409);
      expect(r.body.error.code).toBe("not_joined_by_link");
    }

    // 7 days after the join: too late, and the rows say so.
    await env.DB.prepare("UPDATE members SET joined_at = ? WHERE id = ?").bind(Date.now() - WEEK - 1000, mp).run();
    const late = await undo(a, g.id, mp);
    expect(late.status).toBe(409);
    expect(late.body.error.code).toBe("undo_expired");
    expect((await pullMember(a, g.id, mp)).member).toMatchObject({ onApp: true, joinedAt: null, undoUntil: null, canUndo: false });
    // Just inside the window it still works.
    await env.DB.prepare("UPDATE members SET joined_at = ? WHERE id = ?").bind(Date.now() - WEEK + 60_000, mp).run();
    expect((await undo(a, g.id, mp)).status).toBe(200);
  });

  it("a person who left, or deleted their account, is no longer a join to undo", async () => {
    const { a, s, g, mp } = await joined();
    expect((await call("POST", `/v1/groups/${g.id}/leave`, { token: s.token })).status).toBe(204);
    expect((await undo(a, g.id, mp)).body.error.code).toBe("not_joined_by_link");

    const two = await joined();
    expect((await call("DELETE", "/v1/me", { token: two.s.token })).status).toBe(204);
    const r = await undo(two.a, two.g.id, two.mp);
    expect(r.status).toBe(409);
    expect(r.body.error.code).toBe("not_joined_by_link");
    expect((await rows("SELECT name, join_link_id, join_prev_name, join_prev_phone FROM members WHERE id = ?", two.mp))[0]).toEqual({
      name: "Former member",
      join_link_id: null,
      join_prev_name: null,
      join_prev_phone: null,
    });
  });

  it("works in a direct split with a friend who joined by link", async () => {
    const a = await signIn("Arjun");
    const gid = uuid();
    const [ma, mp] = [uuid(), uuid()];
    const made = await call("POST", "/v1/groups", {
      token: a.token,
      body: { id: gid, kind: "direct", myMemberId: ma, members: [{ id: mp, name: "Priya", phone: "+919000000001" }] },
    });
    expect(made.status).toBe(201);
    const token = shareToken();
    await call("PUT", `/v1/groups/${gid}/members/${mp}/links/${uuid()}`, { token: a.token, body: { token } });
    const s = await signIn("Sam");
    expect((await call("POST", `/v1/invites/${token}/accept`, { token: s.token })).status).toBe(200);
    const r = await undo(a, gid, mp);
    expect(r.status).toBe(200);
    expect(r.body).toMatchObject({ name: "Priya", phone: "+919000000001", onApp: false });
    expect((await rows("SELECT deleted_at FROM groups WHERE id = ?", gid))[0].deleted_at).toBeNull();
  });
});

describe("undo-join and the 3.1 guard: the whole batch rolls back", () => {
  async function snapshot(gid: string, mid: string) {
    return {
      version: await version(gid),
      changes: (await rows("SELECT COUNT(*) AS n FROM changes WHERE group_id = ?", gid))[0].n,
      member: (await rows("SELECT user_id, name, phone, join_link_id FROM members WHERE id = ?", mid))[0],
      liveLinks: (await rows("SELECT COUNT(*) AS n FROM links WHERE group_id = ? AND revoked_at IS NULL", gid))[0].n,
    };
  }

  /** s holds a link it made for Kiran, so a rolled-back link revoke shows. */
  async function setup() {
    const j = await joined();
    await call("PUT", `/v1/groups/${j.g.id}/members/${j.mk}/links/${uuid()}`, { token: j.s.token, body: { token: shareToken() } });
    return j;
  }

  it("the person leaves after the checks", async () => {
    const { a, s, g, mp } = await setup();
    testHooks.beforeBatch = async (label) => {
      if (label !== "member.undoJoin") return;
      testHooks.beforeBatch = undefined;
      expect((await call("POST", `/v1/groups/${g.id}/leave`, { token: s.token })).status).toBe(204);
    };
    const r = await undo(a, g.id, mp);
    expect(r.status).toBe(409);
    expect(r.body.error.code).toBe("not_joined_by_link");
    const m = (await rows("SELECT user_id, removed_at FROM members WHERE id = ?", mp))[0];
    expect(m.user_id).toBe(s.id); // left, not undone
    expect(m.removed_at).not.toBeNull();
  });

  it("ownership passes after the checks", async () => {
    const { a, b, g, mp } = await setup();
    const before = await snapshot(g.id, mp);
    testHooks.beforeBatch = async (label) => {
      if (label === "member.undoJoin") await env.DB.prepare("UPDATE groups SET owner_user_id = ? WHERE id = ?").bind(b.id, g.id).run();
    };
    const r = await undo(a, g.id, mp);
    expect(r.status).toBe(403);
    expect(r.body.error.code).toBe("not_owner");
    expect(await snapshot(g.id, mp)).toEqual(before);
  });

  it("the group is deleted after the checks", async () => {
    const { a, g, mp } = await setup();
    const before = await snapshot(g.id, mp);
    testHooks.beforeBatch = async (label) => {
      if (label === "member.undoJoin") await env.DB.prepare("UPDATE groups SET deleted_at = ? WHERE id = ?").bind(Date.now(), g.id).run();
    };
    const r = await undo(a, g.id, mp);
    expect(r.status).toBe(410);
    expect(r.body.error.code).toBe("group_deleted");
    expect(await snapshot(g.id, mp)).toEqual(before);
  });
});

describe("undo-join cost", () => {
  it("is one batch and reads rows by index only: other groups' links do not count", async () => {
    // 300 links in someone else's group, written directly.
    const other = await signIn("Other");
    const og = await createGroup(other, { friends: [{ name: "F" }] }, "Busy");
    const now = Date.now();
    const stmts: D1PreparedStatement[] = [];
    for (let i = 0; i < 300; i++) {
      stmts.push(
        env.DB.prepare(
          "INSERT INTO links (id, token_hash, group_id, member_id, created_by_member_id, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        ).bind(uuid(), uuid(), og.id, og.members.F, og.members[other.id], now, now + WEEK),
      );
    }
    await env.DB.batch(stmts);
    const { a, g, mp } = await joined();
    let db: Db | null = null;
    testHooks.onDb = (d) => (db = d);
    expect((await undo(a, g.id, mp)).status).toBe(200);
    expect(db!.batches).toBe(1);
    expect(db!.queries).toBeLessThanOrEqual(7);
    expect(db!.rowsRead).toBeLessThan(40); // about 20 measured
  });

  it("the owner label costs a pull no more than an index read", async () => {
    const a = await signIn();
    const b = await signIn();
    const g = await befriend(a, b);
    let db: Db | null = null;
    testHooks.onDb = (d) => (db = d);
    const r = await call("GET", `/v1/groups/${g.id}/changes?since=0`, { token: b.token });
    expect(r.body.group.ownerMemberId).toBe(g.members[a.id]);
    expect(db!.rowsRead).toBeLessThan(30);
  });
});

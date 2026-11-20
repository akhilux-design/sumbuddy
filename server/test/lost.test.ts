// Why a person lost a group (docs/server.md 4.5 "GET /v1/me/groups" `lost`, 4.1 `not_member` reason; 0002
// access_ended): the phone forgets a group's splits only when the person's join was undone (9.4).
import { describe, expect, it } from "vitest";
import { Db, guardFailed } from "../src/db/db";
import { purgeExpired } from "../src/db/cron";
import { accessEndedForMemberStmt, removeMemberStmts } from "../src/db/groups";
import { bump, guard, placeRule } from "../src/db/mutation";
import { testHooks } from "../src/testing";
import { befriend, call, createGroup, directGroupId, directMemberId, env, rows, shareToken, signIn, uuid, type TestUser } from "./helpers";

const DAY = 86_400_000;

/** a owns a group with b (added directly), k (added directly) and s, who took Priya's place through her link. */
async function group() {
  const a = await signIn("Arjun Mehta");
  const b = await signIn("Rahul Shah");
  const k = await signIn("Kiran Rao");
  await befriend(a, b);
  await befriend(a, k);
  const g = await createGroup(a, { users: [b, k], friends: [{ name: "Priya Nair" }] });
  const token = shareToken();
  const mp = g.members["Priya Nair"];
  expect((await call("PUT", `/v1/groups/${g.id}/members/${mp}/links/${uuid()}`, { token: a.token, body: { token } })).status).toBe(201);
  const s = await signIn("Sam Stranger");
  expect((await call("POST", `/v1/invites/${token}/accept`, { token: s.token })).status).toBe(200);
  return { a, b, k, s, g, mp };
}

/** a owns a group with b (added directly) and Meera, a friend without the app whose link a made. s is a stranger. */
async function friendWithLink() {
  const a = await signIn("Arjun Mehta");
  const b = await signIn("Rahul Shah");
  await befriend(a, b);
  const g = await createGroup(a, { users: [b], friends: [{ name: "Meera Iyer" }] });
  const mm = g.members["Meera Iyer"];
  const token = shareToken();
  expect((await call("PUT", `/v1/groups/${g.id}/members/${mm}/links/${uuid()}`, { token: a.token, body: { token } })).status).toBe(201);
  const s = await signIn("Sam Stranger");
  return { a, b, s, g, mm, token };
}

/** `run` happens once, between the checks of the next remove-member request and its batch (3.1 guard tests). */
function justBeforeTheRemoveBatch(run: () => Promise<void>) {
  let fired = false;
  testHooks.beforeBatch = async (label) => {
    if (label !== "member.remove" || fired) return;
    fired = true;
    await run();
  };
}

async function lostOf(u: TestUser) {
  const r = await call("GET", "/v1/me/groups", { token: u.token });
  expect(r.status).toBe(200);
  return r.body.lost as { id: string; reason: string; at: number }[];
}

async function notMemberReason(u: TestUser, gid: string) {
  const r = await call("GET", `/v1/groups/${gid}/changes?since=0`, { token: u.token });
  expect(r.status).toBe(403);
  expect(r.body.error.code).toBe("not_member");
  return r.body.error.reason as string | undefined;
}

describe("why a person lost a group", () => {
  it("an undone join, a removal and a leave each say so, in /me/groups and in not_member", async () => {
    const { a, b, k, s, g, mp } = await group();
    for (const u of [a, b, k, s]) expect(await lostOf(u)).toEqual([]);

    const before = Date.now();
    expect((await call("POST", `/v1/groups/${g.id}/members/${mp}/undo-join`, { token: a.token })).status).toBe(200);
    expect((await call("DELETE", `/v1/groups/${g.id}/members/${g.members[b.id]}`, { token: a.token })).status).toBe(204);
    expect((await call("POST", `/v1/groups/${g.id}/leave`, { token: k.token })).status).toBe(204);

    const undone = await lostOf(s);
    expect(undone).toEqual([{ id: g.id, reason: "join_undone", at: expect.any(Number) }]);
    expect(undone[0].at).toBeGreaterThanOrEqual(before);
    expect(await lostOf(b)).toEqual([{ id: g.id, reason: "removed", at: expect.any(Number) }]);
    expect(await lostOf(k)).toEqual([{ id: g.id, reason: "left", at: expect.any(Number) }]);
    expect(await lostOf(a)).toEqual([]);

    expect(await notMemberReason(s, g.id)).toBe("join_undone");
    expect(await notMemberReason(b, g.id)).toBe("removed");
    expect(await notMemberReason(k, g.id)).toBe("left");
    // Every route that checks membership answers the same, the live room's upgrade and replays included.
    const live = await call("GET", `/v1/groups/${g.id}/live`, { token: s.token, headers: { Upgrade: "websocket" } });
    expect(live.status).toBe(403);
    expect(live.body.error).toMatchObject({ code: "not_member", reason: "join_undone" });
    // Someone never in the group gets no reason.
    const stranger = await signIn("Nobody");
    expect(await notMemberReason(stranger, g.id)).toBeUndefined();
  });

  it("a deleted group says deleted to every member on the app, and 410 stays the answer on its routes", async () => {
    const { a, b, s, g } = await group();
    expect((await call("DELETE", `/v1/groups/${g.id}`, { token: a.token })).status).toBe(204);
    for (const u of [a, b, s]) expect(await lostOf(u)).toEqual([{ id: g.id, reason: "deleted", at: expect.any(Number) }]);
    expect((await call("GET", `/v1/groups/${g.id}/changes`, { token: b.token })).body.error.code).toBe("group_deleted");
  });

  it("the latest reason wins, and a group the person is back in is not listed", async () => {
    const { a, s, g, mp } = await group();
    expect((await call("POST", `/v1/groups/${g.id}/members/${mp}/undo-join`, { token: a.token })).status).toBe(200);
    expect((await lostOf(s))[0].reason).toBe("join_undone");
    // The owner sends the place's link again and the same person takes it: in the group again, nothing lost.
    const token = shareToken();
    expect((await call("PUT", `/v1/groups/${g.id}/members/${mp}/links/${uuid()}`, { token: a.token, body: { token } })).status).toBe(201);
    expect((await call("POST", `/v1/invites/${token}/accept`, { token: s.token })).status).toBe(200);
    expect(await lostOf(s)).toEqual([]);
    expect((await call("GET", `/v1/groups/${g.id}/changes`, { token: s.token })).status).toBe(200);
    // Removed this time: the reason is now that one.
    expect((await call("DELETE", `/v1/groups/${g.id}/members/${mp}`, { token: a.token })).status).toBe(204);
    expect(await lostOf(s)).toEqual([{ id: g.id, reason: "removed", at: expect.any(Number) }]);
    expect(await rows("SELECT reason FROM access_ended WHERE user_id = ? AND group_id = ?", s.id, g.id)).toEqual([{ reason: "removed" }]);
  });

  it("a place taken through a link while it is being removed says removed to the person who took it", async () => {
    const { a, s, g, mm, token } = await friendWithLink();
    // The owner removes Meera, a friend without the app when the checks ran. Sam holds the place when the batch runs.
    const socket: { code: number | null } = { code: null };
    justBeforeTheRemoveBatch(async () => {
      expect((await call("POST", `/v1/invites/${token}/accept`, { token: s.token })).status).toBe(200);
      // Sam's phone opens the group's live room right after joining.
      const live = await call("GET", `/v1/groups/${g.id}/live`, { token: s.token, headers: { Upgrade: "websocket", "X-Installation-Id": s.installationId } });
      expect(live.status).toBe(101);
      live.res.webSocket!.addEventListener("close", (e) => {
        socket.code = e.code;
      });
      live.res.webSocket!.accept();
    });

    expect((await call("DELETE", `/v1/groups/${g.id}/members/${mm}`, { token: a.token })).status).toBe(204);

    expect(await rows("SELECT user_id, removed_at IS NOT NULL AS removed FROM members WHERE id = ?", mm)).toEqual([{ user_id: s.id, removed: 1 }]);
    // Sam's phone learns why the group is gone, and its socket is closed.
    expect(await lostOf(s)).toEqual([{ id: g.id, reason: "removed", at: expect.any(Number) }]);
    expect(await notMemberReason(s, g.id)).toBe("removed");
    for (const end = Date.now() + 2000; socket.code === null && Date.now() < end; ) await new Promise((r) => setTimeout(r, 10));
    expect(socket.code).toBe(4403);
    expect(await rows("SELECT op FROM changes WHERE group_id = ? AND op IN ('join', 'remove') ORDER BY version", g.id)).toEqual([
      { op: "join" },
      { op: "remove" },
    ]);
  });

  it("a member who is not the owner cannot remove a person who took the place a moment before", async () => {
    const { b, s, g, mm, token } = await friendWithLink();
    // Any member may remove a friend without the app. Sam joins after those checks: only the owner may remove Sam.
    justBeforeTheRemoveBatch(async () => {
      expect((await call("POST", `/v1/invites/${token}/accept`, { token: s.token })).status).toBe(200);
    });
    const r = await call("DELETE", `/v1/groups/${g.id}/members/${mm}`, { token: b.token });

    expect(r.status).toBe(403);
    expect(r.body.error.code).toBe("not_owner");
    expect(await rows("SELECT user_id, removed_at FROM members WHERE id = ?", mm)).toEqual([{ user_id: s.id, removed_at: null }]);
    expect(await lostOf(s)).toEqual([]);
    expect((await call("GET", `/v1/groups/${g.id}/changes?since=0`, { token: s.token })).status).toBe(200);
    expect(await rows("SELECT * FROM changes WHERE group_id = ? AND op = 'remove'", g.id)).toHaveLength(0);
  });

  it("the remove guard fails when the place is not held by the person the checks saw", async () => {
    const { a, s, g, mm, token } = await friendWithLink();
    const version = async () => (await rows<{ version: number }>("SELECT version FROM groups WHERE id = ?", g.id))[0].version;
    const db = new Db(env.DB);
    const remove = async (sawUser: string | null) =>
      db.batch([
        bump(db, g.id, Date.now()),
        ...removeMemberStmts(db, g.id, mm, Date.now()),
        guard(db, { gid: g.id, kind: "member", op: "remove", entityId: mm, actor: g.members[a.id], now: Date.now(), rule: placeRule(await version(), sawUser) }),
        accessEndedForMemberStmt(db, g.id, mm, "removed", Date.now()),
      ]);
    expect((await call("POST", `/v1/invites/${token}/accept`, { token: s.token })).status).toBe(200);
    const before = await version();

    // The checks saw a friend without the app (or someone else), at the version the group has now: rolled back.
    for (const saw of [null, a.id]) {
      const err = await remove(saw).then(
        () => null,
        (e) => e,
      );
      expect(guardFailed(err)).toBe(true);
    }
    expect(await version()).toBe(before);
    expect(await rows("SELECT removed_at FROM members WHERE id = ?", mm)).toEqual([{ removed_at: null }]);
    expect(await rows("SELECT * FROM access_ended WHERE user_id = ?", s.id)).toEqual([]);

    // The checks saw Sam: applied, with Sam's reason written from the row.
    await remove(s.id);
    expect(await version()).toBe(before + 1);
    expect(await rows("SELECT reason FROM access_ended WHERE user_id = ? AND group_id = ?", s.id, g.id)).toEqual([{ reason: "removed" }]);
  });

  it("removing a friend without the app writes no reason", async () => {
    const { a, g, mm } = await friendWithLink();
    expect((await call("DELETE", `/v1/groups/${g.id}/members/${mm}`, { token: a.token })).status).toBe(204);
    expect(await rows("SELECT * FROM access_ended WHERE group_id = ?", g.id)).toEqual([]);
  });

  it("a direct split left and made again is not listed", async () => {
    const a = await signIn("Arjun");
    const b = await signIn("Rahul");
    await befriend(a, b);
    const gid = await directGroupId(a.id, b.id);
    const body = { id: gid, kind: "direct", myMemberId: await directMemberId(gid, a.id), members: [{ id: await directMemberId(gid, b.id), userId: b.id }] };
    expect((await call("POST", "/v1/groups", { token: a.token, body })).status).toBe(201);
    expect((await call("POST", `/v1/groups/${gid}/leave`, { token: b.token })).status).toBe(204);
    expect(await lostOf(b)).toEqual([{ id: gid, reason: "left", at: expect.any(Number) }]);
    expect((await call("POST", "/v1/groups", { token: a.token, body })).status).toBe(200);
    expect(await lostOf(b)).toEqual([]);
  });

  it("is kept 90 days, and goes with the account", async () => {
    const { a, b, s, g, mp } = await group();
    expect((await call("POST", `/v1/groups/${g.id}/members/${mp}/undo-join`, { token: a.token })).status).toBe(200);
    expect((await call("DELETE", `/v1/groups/${g.id}/members/${g.members[b.id]}`, { token: a.token })).status).toBe(204);
    const now = Date.now();
    await env.DB.prepare("UPDATE access_ended SET at = ? WHERE user_id = ?").bind(now - 91 * DAY, s.id).run();
    expect(await lostOf(s)).toEqual([]);
    expect(await notMemberReason(s, g.id)).toBeUndefined();

    await purgeExpired(new Db(env.DB), now);
    expect(await rows("SELECT * FROM access_ended WHERE user_id = ?", s.id)).toEqual([]);
    expect(await rows("SELECT reason FROM access_ended WHERE user_id = ?", b.id)).toEqual([{ reason: "removed" }]);

    expect((await call("DELETE", "/v1/me", { token: b.token })).status).toBe(204);
    expect(await rows("SELECT * FROM access_ended WHERE user_id = ?", b.id)).toEqual([]);
  });

  it("costs one read of the person's own rows on /me/groups", async () => {
    const { a, s, g, mp } = await group();
    expect((await call("POST", `/v1/groups/${g.id}/members/${mp}/undo-join`, { token: a.token })).status).toBe(200);
    // Other people's rows are never read: the primary key starts with the user id.
    for (let i = 0; i < 3; i++) await env.DB.prepare("INSERT INTO access_ended VALUES (?, ?, 'left', ?)").bind(a.id, uuid(), Date.now()).run();
    const plan = await rows<{ detail: string }>(
      "EXPLAIN QUERY PLAN SELECT a.group_id FROM access_ended a WHERE a.user_id = ? AND a.at > ?",
      s.id,
      0,
    );
    expect(plan.map((p) => p.detail).join(" ")).toMatch(/USING PRIMARY KEY \(user_id=\?\)/);
  });
});

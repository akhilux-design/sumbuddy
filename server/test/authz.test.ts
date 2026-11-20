// Every group route as a non-member (docs/server.md 4.2), as a former member of a deleted group, and signed out.
import { describe, expect, it } from "vitest";
import { befriend, call, createGroup, directGroupId, directMemberId, env, expenseBody, putExpense, signIn, today, uuid, type TestUser } from "./helpers";

function routes(gid: string, mid: string, eid: string, sid: string) {
  return [
    ["PATCH", `/v1/groups/${gid}`, { name: "x" }],
    ["DELETE", `/v1/groups/${gid}`, undefined],
    ["POST", `/v1/groups/${gid}/leave`, undefined],
    ["POST", `/v1/groups/${gid}/members`, { id: uuid(), name: "Z" }],
    ["PATCH", `/v1/groups/${gid}/members/${mid}`, { name: "Z" }],
    ["DELETE", `/v1/groups/${gid}/members/${mid}`, undefined],
    ["POST", `/v1/groups/${gid}/members/${mid}/undo-join`, undefined],
    ["PUT", `/v1/groups/${gid}/expenses/${eid}`, expenseBody(mid, 100, [mid])],
    ["DELETE", `/v1/groups/${gid}/expenses/${eid}`, undefined],
    ["PUT", `/v1/groups/${gid}/settlements/${sid}`, { fromMemberId: mid, toMemberId: uuid(), amountPaise: 1, method: "CASH", occurredOn: "2026-11-16" }],
    ["POST", `/v1/groups/${gid}/settlements/${sid}/confirm`, undefined],
    ["DELETE", `/v1/groups/${gid}/settlements/${sid}`, undefined],
    ["GET", `/v1/groups/${gid}/changes?since=0`, undefined],
    ["GET", `/v1/groups/${gid}/balances`, undefined],
    ["PUT", `/v1/groups/${gid}/members/${mid}/links/${uuid()}`, { token: "A".repeat(24) }],
    ["DELETE", `/v1/groups/${gid}/links/${uuid()}`, undefined],
    ["DELETE", `/v1/groups/${gid}/members/${mid}/links`, undefined],
    ["POST", `/v1/groups/${gid}/reminders`, { memberIds: [mid] }],
    ["GET", `/v1/groups/${gid}/live`, undefined],
  ] as const;
}

async function setup(): Promise<{ owner: TestUser; outsider: TestUser; gid: string; mid: string; eid: string }> {
  const owner = await signIn();
  const outsider = await signIn();
  const g = await createGroup(owner, { friends: [{ name: "Priya" }] });
  const { eid } = await putExpense(owner, g.id, expenseBody(g.members[owner.id], 100, [g.members[owner.id]]));
  return { owner, outsider, gid: g.id, mid: g.members.Priya, eid };
}

describe("authorization", () => {
  it("every group route answers 403 not_member to a non-member, and writes nothing", async () => {
    const { outsider, gid, mid, eid } = await setup();
    for (const [method, path, body] of routes(gid, mid, eid, uuid())) {
      const r = await call(method, path, { token: outsider.token, body, headers: { Upgrade: "websocket" } });
      expect(r.status, `${method} ${path}`).toBe(403);
      expect(r.body.error.code).toBe("not_member");
    }
  });

  it("a family: every route answers 403 not_member to a non-member and 401 signed out; a deleted family 410", async () => {
    const owner = await signIn();
    const outsider = await signIn();
    const [gid, mine, place] = [uuid(), uuid(), uuid()];
    const made = await call("POST", "/v1/groups", { token: owner.token, body: { id: gid, kind: "family", name: "Home", myMemberId: mine, members: [{ id: place, name: "Partner" }] } });
    expect(made.status).toBe(201);
    for (const [method, path, body] of routes(gid, place, uuid(), uuid())) {
      const r = await call(method, path, { token: outsider.token, body, headers: { Upgrade: "websocket" } });
      expect(r.status, `${method} ${path}`).toBe(403);
      expect(r.body.error.code).toBe("not_member");
      expect((await call(method, path, { body })).status, `${method} ${path}`).toBe(401);
    }
    // Leaving as the last member on the app tombstones it: the leaver is not a member any more.
    expect((await call("POST", `/v1/groups/${gid}/leave`, { token: owner.token })).status).toBe(204);
    const gone = await call("GET", `/v1/groups/${gid}/changes`, { token: owner.token });
    expect(gone.body.error).toMatchObject({ code: "not_member", reason: "left" });
    // A member still active in a deleted family (as after a restore) gets 410 everywhere.
    const other = await signIn();
    const [g2, m2, p2] = [uuid(), uuid(), uuid()];
    await call("POST", "/v1/groups", { token: other.token, body: { id: g2, kind: "family", name: "Home", myMemberId: m2, members: [{ id: p2, name: "Partner" }] } });
    await env.DB.prepare("UPDATE groups SET deleted_at = ? WHERE id = ?").bind(Date.now(), g2).run();
    for (const [method, path, body] of routes(g2, p2, uuid(), uuid())) {
      const r = await call(method, path, { token: other.token, body, headers: { Upgrade: "websocket" } });
      expect(r.status, `${method} ${path}`).toBe(410);
      expect(r.body.error.code).toBe("group_deleted");
    }
  });

  it("a group that does not exist is also not_member (nothing leaks)", async () => {
    const u = await signIn();
    const r = await call("GET", `/v1/groups/${uuid()}/changes`, { token: u.token });
    expect(r.body.error.code).toBe("not_member");
  });

  it("a deleted group answers 410 group_deleted to its members", async () => {
    const { owner, gid, mid, eid } = await setup();
    expect((await call("DELETE", `/v1/groups/${gid}/expenses/${eid}`, { token: owner.token })).status).toBe(200);
    expect((await call("DELETE", `/v1/groups/${gid}`, { token: owner.token })).status).toBe(204);
    for (const [method, path, body] of routes(gid, mid, uuid(), uuid())) {
      if (method === "DELETE" && path === `/v1/groups/${gid}`) continue; // that is the replay-free retry of delete
      const r = await call(method, path, { token: owner.token, body, headers: { Upgrade: "websocket" } });
      expect(r.status, `${method} ${path}`).toBe(410);
      expect(r.body.error.code).toBe("group_deleted");
    }
  });

  it("signed out: 401 everywhere", async () => {
    const { gid, mid, eid } = await setup();
    for (const [method, path, body] of routes(gid, mid, eid, uuid())) {
      const r = await call(method, path, { body });
      expect(r.status, `${method} ${path}`).toBe(401);
    }
    for (const path of ["/v1/me", "/v1/me/groups", `/v1/invites/${"A".repeat(24)}`]) {
      expect((await call("GET", path)).status).toBe(401);
    }
  });

  it("a replay that answers with content needs the caller to be an active member still", async () => {
    const a = await signIn("Alice");
    const b = await signIn("Bob");
    const g = await befriend(a, b); // a owns G, b joined it
    const [ma, mb] = [g.members[a.id], g.members[b.id]];

    // b's earlier requests, each with its own key, then b leaves.
    const hId = uuid();
    const createBody = { id: hId, kind: "group", name: "H", myMemberId: uuid(), members: [{ id: uuid(), userId: a.id }] };
    const keys = { create: uuid(), rename: uuid(), member: uuid(), expense: uuid(), settle: uuid(), leave: uuid() };
    const eid = uuid();
    const sid = uuid();
    const memberBody = { id: uuid(), name: "Friend", phone: "+919800000009" };
    const expense = expenseBody(mb, 300, [mb]);
    const settlement = { fromMemberId: mb, toMemberId: ma, amountPaise: 100, method: "CASH", matchedFromSms: false, occurredOn: today() };
    const sent = [
      ["POST", "/v1/groups", createBody, keys.create],
      ["PATCH", `/v1/groups/${g.id}`, { name: "Renamed" }, keys.rename],
      ["POST", `/v1/groups/${g.id}/members`, memberBody, keys.member],
      ["PUT", `/v1/groups/${g.id}/expenses/${eid}`, expense, keys.expense],
      ["PUT", `/v1/groups/${g.id}/settlements/${sid}`, settlement, keys.settle],
    ] as const;
    for (const [method, path, body, idem] of sent) {
      const r = await call(method, path, { token: b.token, body, idem });
      expect(r.status, `${method} ${path}`).toBeLessThan(300);
    }
    // Settle back so b can leave both groups.
    await call("PUT", `/v1/groups/${g.id}/settlements/${uuid()}`, {
      token: a.token,
      body: { ...settlement, fromMemberId: ma, toMemberId: mb },
    });
    expect((await call("POST", `/v1/groups/${hId}/leave`, { token: b.token })).status).toBe(204);
    expect((await call("POST", `/v1/groups/${g.id}/leave`, { token: b.token, idem: keys.leave })).status).toBe(204);
    // Meanwhile a sets a phone and UPI ID: none of it may reach b through an old key.
    await call("PATCH", "/v1/me", { token: a.token, body: { phone: "+919800000001", upiId: "alice.secret@okaxis" } });

    for (const [method, path, body, idem] of sent) {
      const r = await call(method, path, { token: b.token, body, idem });
      expect(r.status, `${method} ${path}`).toBe(403);
      expect(r.body.error.code).toBe("not_member");
      expect(JSON.stringify(r.body)).not.toContain("alice.secret");
    }
    // A content-free replay still answers: a retried leave gets its 204.
    const leave = await call("POST", `/v1/groups/${g.id}/leave`, { token: b.token, idem: keys.leave });
    expect(leave.status).toBe(204);
    expect(leave.headers.get("Idempotent-Replay")).toBe("true");

    // A deleted group: the replay of its create answers 410 to its former member.
    const d = uuid();
    const dBody = { id: d, kind: "group", name: "D", myMemberId: uuid(), members: [] };
    const dKey = uuid();
    expect((await call("POST", "/v1/groups", { token: a.token, body: dBody, idem: dKey })).status).toBe(201);
    expect((await call("DELETE", `/v1/groups/${d}`, { token: a.token })).status).toBe(204);
    const gone = await call("POST", "/v1/groups", { token: a.token, body: dBody, idem: dKey });
    expect(gone.status).toBe(410);
    expect(gone.body.error.code).toBe("group_deleted");
  });

  it("group and member ids must be random (v4) unless they are a checked direct split", async () => {
    const a = await signIn("Alice");
    const b = await signIn("Bob");
    const c = await signIn("Carol");
    const g = await befriend(a, b);
    await befriend(a, c);
    await call("POST", `/v1/groups/${g.id}/members`, { token: a.token, body: { id: uuid(), userId: c.id } });
    // Carol knows both user ids and tries to take Alice and Bob's direct split id first.
    const direct = await directGroupId(a.id, b.id);
    const squat = await call("POST", "/v1/groups", { token: c.token, body: { id: direct, kind: "group", name: "Mine", myMemberId: uuid(), members: [] } });
    expect(squat.status).toBe(400);
    expect(squat.body.error.field).toBe("id");
    const asFriend = await call("POST", "/v1/groups", {
      token: c.token,
      body: { id: direct, kind: "direct", myMemberId: uuid(), members: [{ id: uuid(), name: "Bob" }] },
    });
    expect(asFriend.status).toBe(400);
    const memberSquat = await call("POST", `/v1/groups/${g.id}/members`, {
      token: c.token,
      body: { id: await directMemberId(direct, a.id), name: "X" },
    });
    expect(memberSquat.status).toBe(400);
    expect(memberSquat.body.error.field).toBe("id");
    // So the real direct split still works.
    const ok = await call("POST", "/v1/groups", {
      token: a.token,
      body: { id: direct, kind: "direct", myMemberId: await directMemberId(direct, a.id), members: [{ id: await directMemberId(direct, b.id), userId: b.id }] },
    });
    expect(ok.status).toBe(201);
  });

  it("unknown paths 404, wrong methods 405", async () => {
    const u = await signIn();
    expect((await call("GET", "/v1/nope", { token: u.token })).status).toBe(404);
    expect((await call("GET", "/v1/groups/not-a-uuid/changes", { token: u.token })).status).toBe(404);
    const r = await call("POST", "/v1/me/groups", { token: u.token });
    expect(r.status).toBe(405);
    expect(r.headers.get("Allow")).toBe("GET");
  });
});

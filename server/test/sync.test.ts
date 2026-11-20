// Pulling changes (docs/server.md 4.9): version windows of 100, hasMore, tombstones, restore case.
import { describe, expect, it } from "vitest";
import { call, createGroup, expenseBody, putExpense, shareToken, signIn, today, uuid } from "./helpers";

describe("GET /v1/groups/{gid}/changes", () => {
  it("pages by 100 versions; a row changed later appears with its newer state; tombstones are small", async () => {
    const a = await signIn();
    const g = await createGroup(a, { friends: [{ name: "Priya", phone: "+919000000001" }] });
    const ma = g.members[a.id];
    const ids: string[] = [];
    for (let i = 0; i < 119; i++) {
      const { eid, r } = await putExpense(a, g.id, expenseBody(ma, 100 + i, [ma, g.members.Priya]));
      expect(r.status).toBe(201);
      ids.push(eid);
    }
    // v1 create, v2..v120 expenses. Now update the first one (v121) and delete the second (v122).
    await putExpense(a, g.id, expenseBody(ma, 5000, [ma]), ids[0]);
    await call("DELETE", `/v1/groups/${g.id}/expenses/${ids[1]}`, { token: a.token });

    const p1 = await call("GET", `/v1/groups/${g.id}/changes?since=0`, { token: a.token });
    expect(p1.status).toBe(200);
    expect(p1.body).toMatchObject({ groupId: g.id, fromVersion: 0, toVersion: 100, latestVersion: 122, hasMore: true });
    expect(p1.body.group).toMatchObject({ id: g.id, kind: "group", name: "Flat 302", ownerUserId: a.id, version: 122 });
    expect(p1.body.members).toHaveLength(2);
    expect(p1.body.changes).toHaveLength(100);
    expect(p1.body.changes[0]).toEqual({ version: 1, kind: "group", op: "create", entityId: g.id, actorMemberId: ma, at: expect.any(Number) });
    // ids[0] and ids[1] were touched later: they are not in this window.
    expect(p1.body.expenses.map((e: any) => e.id)).not.toContain(ids[0]);
    expect(p1.body.expenses).toHaveLength(97);
    expect(p1.body.expenses[0].shares).toHaveLength(2);

    const p2 = await call("GET", `/v1/groups/${g.id}/changes?since=100`, { token: a.token });
    expect(p2.body).toMatchObject({ fromVersion: 100, toVersion: 122, hasMore: false });
    const updated = p2.body.expenses.find((e: any) => e.id === ids[0]);
    expect(updated).toMatchObject({ amountPaise: 5000, version: 121, deleted: false });
    const tomb = p2.body.expenses.find((e: any) => e.id === ids[1]);
    expect(tomb).toEqual({ id: ids[1], groupId: g.id, deleted: true, version: 122 });
    expect(p2.body.changes.map((c: any) => c.op).slice(-2)).toEqual(["update", "delete"]);

    const empty = await call("GET", `/v1/groups/${g.id}/changes?since=122`, { token: a.token });
    expect(empty.body).toMatchObject({ toVersion: 122, hasMore: false, members: [], expenses: [], settlements: [], changes: [] });
    const restored = await call("GET", `/v1/groups/${g.id}/changes?since=500`, { token: a.token });
    expect(restored.body).toMatchObject({ fromVersion: 500, toVersion: 122, latestVersion: 122, hasMore: false, expenses: [] });
  });

  it("a family's page: kind family, both members, one-share expenses, tombstones, never a settlement", async () => {
    const a = await signIn("Arjun");
    const b = await signIn("Meera");
    const [fid, ma, mb] = [uuid(), uuid(), uuid()];
    await call("POST", "/v1/groups", { token: a.token, body: { id: fid, kind: "family", name: "Home", myMemberId: ma, members: [{ id: mb, name: "Partner" }] } });
    const token = shareToken();
    await call("PUT", `/v1/groups/${fid}/members/${mb}/links/${uuid()}`, { token: a.token, body: { token } });
    expect((await call("POST", `/v1/invites/${token}/accept`, { token: b.token })).status).toBe(200);
    const own = (mid: string, amount: number) => ({
      description: "BigBasket", category: "GROCERIES", amountPaise: amount, occurredOn: today(), paidByMemberId: mid,
      splitMethod: "EQUAL", auto: false, shares: [{ memberId: mid, sharePaise: amount, input: null }], baseVersion: null,
    });
    const { eid: e1 } = await putExpense(a, fid, own(ma, 234000));
    const { eid: e2 } = await putExpense(b, fid, own(mb, 45000));
    expect((await call("DELETE", `/v1/groups/${fid}/expenses/${e1}`, { token: a.token })).status).toBe(200);

    const pull = await call("GET", `/v1/groups/${fid}/changes?since=0`, { token: b.token });
    expect(pull.status).toBe(200);
    expect(pull.body).toMatchObject({ groupId: fid, fromVersion: 0, toVersion: 5, latestVersion: 5, hasMore: false, settlements: [] });
    expect(pull.body.group).toMatchObject({ id: fid, kind: "family", name: "Home", ownerUserId: a.id, ownerMemberId: ma, version: 5 });
    expect(pull.body.members.map((m: any) => [m.id, m.userId, m.name, m.onApp]).sort()).toEqual(
      [[ma, a.id, "Arjun", true], [mb, b.id, "Meera", true]].sort(),
    );
    expect(pull.body.expenses).toEqual([
      expect.objectContaining({ id: e2, paidByMemberId: mb, amountPaise: 45000, shares: [{ memberId: mb, sharePaise: 45000, input: null }], deleted: false, version: 4 }),
      { id: e1, groupId: fid, deleted: true, version: 5 },
    ]);
    expect(pull.body.changes.map((c: any) => [c.version, c.kind, c.op])).toEqual([
      [1, "group", "create"],
      [2, "member", "join"],
      [3, "expense", "create"],
      [4, "expense", "create"],
      [5, "expense", "delete"],
    ]);
  });

  it("validates since", async () => {
    const a = await signIn();
    const g = await createGroup(a);
    for (const s of ["-1", "abc", "1.5", "01"]) {
      const r = await call("GET", `/v1/groups/${g.id}/changes?since=${s}`, { token: a.token });
      expect(r.status, s).toBe(400);
      expect(r.body.error.field).toBe("since");
    }
  });

  it("member rows carry user data only while active; never emails, sessions or devices", async () => {
    const a = await signIn("Arjun");
    await call("PATCH", "/v1/me", { token: a.token, body: { phone: "+919800000009", upiId: "arjun@okhdfc" } });
    const g = await createGroup(a, { friends: [{ name: "Priya", phone: "+919000000001" }] });
    const pull = await call("GET", `/v1/groups/${g.id}/changes`, { token: a.token });
    const me = pull.body.members.find((m: any) => m.userId === a.id);
    expect(me).toEqual({
      id: g.members[a.id],
      groupId: g.id,
      userId: a.id,
      name: "Arjun",
      phone: "+919800000009",
      upiId: "arjun@okhdfc",
      onApp: true,
      removed: false,
      joinedAt: null,
      undoUntil: null,
      canUndo: false,
      version: 1,
    });
    const keys = new Set(pull.body.members.flatMap((m: any) => Object.keys(m)));
    expect([...keys].sort()).toEqual(
      ["canUndo", "groupId", "id", "joinedAt", "name", "onApp", "phone", "removed", "undoUntil", "upiId", "userId", "version"],
    );
    // Never the join columns of 0002 (the friend's saved name and phone).
    expect(JSON.stringify(pull.body)).not.toContain("join_");
    void uuid;
  });
});

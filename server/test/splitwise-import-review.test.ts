// Review of the Splitwise import (docs/server.md 4.17): problems found, each as a failing test.
import { describe, expect, it } from "vitest";
import { call, createGroup, expenseBody, rows, shareToken, signIn, uuid, type TestUser } from "./helpers";

/** A friend without the app takes their place through their link (4.10). */
async function join(owner: TestUser, gid: string, memberId: string, user: TestUser) {
  const token = shareToken();
  const put = await call("PUT", `/v1/groups/${gid}/members/${memberId}/links/${uuid()}`, { token: owner.token, body: { token } });
  expect(put.status).toBe(201);
  const acc = await call("POST", `/v1/invites/${token}/accept`, { token: user.token });
  expect(acc.status).toBe(200);
}

describe("Splitwise import review", () => {
  it("the importing owner, as recorder, can delete an imported settlement between two friends (4.2, 4.8: recorder or the other party)", async () => {
    const a = await signIn("Arjun Mehta");
    const g = await createGroup(a, { friends: [{ name: "Priya" }, { name: "Kiran" }] });
    const ma = g.members[a.id];
    const [mp, mk] = [g.members.Priya, g.members.Kiran];
    const sid = uuid();
    const imported = await call("POST", `/v1/groups/${g.id}/import`, {
      token: a.token,
      body: { items: [{ kind: "settlement", id: sid, fromMemberId: mk, toMemberId: mp, amountPaise: 5000, occurredOn: "2025-04-03" }] },
    });
    expect(imported.status).toBe(200);
    const pulled = await call("GET", `/v1/groups/${g.id}/changes?since=0`, { token: a.token });
    expect(pulled.body.settlements[0]).toMatchObject({ id: sid, recordedByMemberId: ma });

    // A wrong Splitwise payment between two friends without the app: the owner recorded it and is the only one on the
    // app, so nobody else can ever delete it, and PUT .../settlements cannot record a correcting one either (4.8).
    const del = await call("DELETE", `/v1/groups/${g.id}/settlements/${sid}`, { token: a.token });
    expect(del.status, JSON.stringify(del.body)).toBe(200);
    const b = await call("GET", `/v1/groups/${g.id}/balances`, { token: a.token });
    expect(b.body.nets.every((n: { netPaise: number }) => n.netPaise === 0)).toBe(true);
  });

  it("an imported settlement between two friends is deleted by its recorder, payer or receiver only", async () => {
    const a = await signIn("Arjun Mehta");
    const g = await createGroup(a, { friends: [{ name: "Priya" }, { name: "Kiran" }, { name: "Ravi" }] });
    const [mp, mk, mr] = [g.members.Priya, g.members.Kiran, g.members.Ravi];
    const [first, second] = [uuid(), uuid()];
    const settlement = (id: string) => ({ kind: "settlement", id, fromMemberId: mk, toMemberId: mp, amountPaise: 5000, occurredOn: "2025-04-03" });
    const imported = await call("POST", `/v1/groups/${g.id}/import`, { token: a.token, body: { items: [settlement(first), settlement(second)] } });
    expect(imported.status).toBe(200);
    // Later Ravi and Kiran take their places. Ravi is neither recorder, payer nor receiver.
    const ravi = await signIn("Ravi Kumar");
    const kiran = await signIn("Kiran Rao");
    await join(a, g.id, mr, ravi);
    await join(a, g.id, mk, kiran);
    const byRavi = await call("DELETE", `/v1/groups/${g.id}/settlements/${first}`, { token: ravi.token });
    expect(byRavi.status).toBe(403);
    expect(byRavi.body.error.code).toBe("not_allowed");
    expect((await call("DELETE", `/v1/groups/${g.id}/settlements/${first}`, { token: kiran.token })).status).toBe(200);
    expect((await call("DELETE", `/v1/groups/${g.id}/settlements/${second}`, { token: a.token })).status).toBe(200);
  });

  it("a part applied long ago and retried after its key was purged answers applied:false, even after a join, a removal or the cap (4.17 check 12 first)", async () => {
    const a = await signIn("Arjun Mehta");
    const g = await createGroup(a, { friends: [{ name: "Priya" }, { name: "Kiran" }] });
    const ma = g.members[a.id];
    const [mp, mk] = [g.members.Priya, g.members.Kiran];
    const key = uuid();
    const items = [
      { kind: "expense", id: uuid(), ...expenseBody(ma, 900, [ma, mp, mk]), occurredOn: "2025-04-01" },
      { kind: "settlement", id: uuid(), fromMemberId: mk, toMemberId: ma, amountPaise: 300, occurredOn: "2025-04-02" },
    ];
    const send = (body: unknown[], idem = key) => call("POST", `/v1/groups/${g.id}/import`, { token: a.token, body: { items: body }, idem });
    expect((await send(items)).status).toBe(200);
    await rows("DELETE FROM idempotency_keys WHERE user_id = ? AND key = ?", a.id, key); // purged after 7 days (4.13)
    const notApplied = { applied: false, firstVersion: null, lastVersion: null };

    // Kiran, named in both items, is removed (here directly: his net is not 0).
    await rows("UPDATE members SET removed_at = ? WHERE id = ?", Date.now(), mk);
    const afterRemoval = await send(items);
    expect(afterRemoval.status, JSON.stringify(afterRemoval.body)).toBe(200);
    expect(afterRemoval.body).toEqual(notApplied);
    await rows("UPDATE members SET removed_at = NULL WHERE id = ?", mk);

    // The group is at the version cap.
    const v = (await rows("SELECT version FROM groups WHERE id = ?", g.id))[0].version as number;
    await rows("UPDATE groups SET version = 5999 WHERE id = ?", g.id);
    expect((await send(items)).body).toEqual(notApplied);
    await rows("UPDATE groups SET version = ? WHERE id = ?", v, g.id);

    // Priya takes her place.
    await join(a, g.id, mp, await signIn("Priya Nair"));
    const afterJoin = await send(items);
    expect(afterJoin.status, JSON.stringify(afterJoin.body)).toBe(200);
    expect(afterJoin.body).toEqual(notApplied);
    expect(await rows("SELECT * FROM idempotency_keys WHERE user_id = ? AND key = ?", a.id, key)).toHaveLength(0);

    // Anything new is still refused in the documented order: 9 (a friend joined) before 12's 409.
    const mixed = await send([items[0], { ...items[1], id: uuid() }], uuid());
    expect(mixed.status).toBe(403);
    expect(mixed.body.error.code).toBe("not_allowed");
  });
});

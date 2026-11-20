// Settlements (docs/server.md 4.8) and balances (4.9, 6).
import { describe, expect, it } from "vitest";
import { befriend, call, expenseBody, putExpense, signIn, uuid } from "./helpers";

async function setup() {
  const a = await signIn("Arjun");
  const b = await signIn("Rahul");
  const c = await signIn("Chitra");
  const g = await befriend(a, b);
  await befriend(a, c);
  await call("POST", `/v1/groups/${g.id}/members`, { token: a.token, body: { id: uuid(), userId: c.id } });
  const fid = uuid();
  await call("POST", `/v1/groups/${g.id}/members`, { token: a.token, body: { id: fid, name: "Priya" } });
  const pull = await call("GET", `/v1/groups/${g.id}/changes`, { token: a.token });
  const mc = pull.body.members.find((m: any) => m.userId === c.id).id;
  const ma = g.members[a.id];
  const mb = g.members[b.id];
  // a pays 40000 for a, b, c and Priya: everyone else owes a 10000.
  await putExpense(a, g.id, expenseBody(ma, 40000, [ma, mb, mc, fid]));
  return { a, b, c, g, ma, mb, mc, fid };
}

const settle = (from: string, to: string, amountPaise: number, extra: Record<string, unknown> = {}) => ({
  fromMemberId: from,
  toMemberId: to,
  amountPaise,
  method: "CASH",
  matchedFromSms: false,
  occurredOn: "2026-11-16",
  ...extra,
});

describe("settlements", () => {
  it("records, needs the other party to confirm, and counts in balances from creation", async () => {
    const { a, b, g, ma, mb } = await setup();
    const sid = uuid();
    const r = await call("PUT", `/v1/groups/${g.id}/settlements/${sid}`, { token: b.token, body: settle(mb, ma, 10000) });
    expect(r.status).toBe(201);
    expect(r.body).toMatchObject({ id: sid, groupId: g.id, fromMemberId: mb, toMemberId: ma, amountPaise: 10000, method: "CASH", recordedByMemberId: mb, confirmedAt: null, deleted: false });
    const bal = await call("GET", `/v1/groups/${g.id}/balances`, { token: a.token });
    expect(bal.body.nets.find((n: any) => n.memberId === mb).netPaise).toBe(0);
    expect(bal.body.nets.find((n: any) => n.memberId === ma).netPaise).toBe(20000);

    // The recorder cannot confirm; the other party can, once.
    expect((await call("POST", `/v1/groups/${g.id}/settlements/${sid}/confirm`, { token: b.token })).body.error.code).toBe("not_allowed");
    const conf = await call("POST", `/v1/groups/${g.id}/settlements/${sid}/confirm`, { token: a.token });
    expect(conf.status).toBe(200);
    expect(conf.body.confirmedAt).toEqual(expect.any(Number));
    const again = await call("POST", `/v1/groups/${g.id}/settlements/${sid}/confirm`, { token: a.token });
    expect(again.status).toBe(200);
    expect(again.body.version).toBe(conf.body.version);
  });

  it("is confirmed at once for a friend without the app, or when the receiver records an SMS match", async () => {
    const { a, g, ma, mb, fid } = await setup();
    const friend = await call("PUT", `/v1/groups/${g.id}/settlements/${uuid()}`, { token: a.token, body: settle(fid, ma, 10000) });
    expect(friend.body.confirmedAt).toEqual(expect.any(Number));
    const sms = await call("PUT", `/v1/groups/${g.id}/settlements/${uuid()}`, {
      token: a.token,
      body: settle(mb, ma, 10000, { method: "UPI", matchedFromSms: true }),
    });
    expect(sms.body.confirmedAt).toEqual(expect.any(Number));
    const receiverManual = await call("PUT", `/v1/groups/${g.id}/settlements/${uuid()}`, { token: a.token, body: settle(mb, ma, 500) });
    expect(receiverManual.body.confirmedAt).toBeNull();
  });

  it("one payment matched from SMS on both phones is one settlement, confirmed by the receiver's match", async () => {
    const { a, b, g, ma, mb } = await setup();
    // Both phones work out the same id (docs/server.md 9.5); the payer's debit match reaches the server first.
    const sid = uuid();
    const body = settle(mb, ma, 10000, { method: "UPI", matchedFromSms: true });
    const payer = await call("PUT", `/v1/groups/${g.id}/settlements/${sid}`, { token: b.token, body });
    expect(payer.status).toBe(201);
    expect(payer.body.confirmedAt).toBeNull();
    const receiver = await call("PUT", `/v1/groups/${g.id}/settlements/${sid}`, { token: a.token, body });
    expect(receiver.status).toBe(200);
    expect(receiver.body).toMatchObject({ id: sid, recordedByMemberId: mb, confirmedAt: expect.any(Number) });
    expect(receiver.body.version).toBeGreaterThan(payer.body.version);
    // Counted once: b owed a 10000 and owes nothing now.
    const bal = await call("GET", `/v1/groups/${g.id}/balances`, { token: a.token });
    expect(bal.body.nets.find((n: any) => n.memberId === mb).netPaise).toBe(0);
    // The payer sending again changes nothing.
    const again = await call("PUT", `/v1/groups/${g.id}/settlements/${sid}`, { token: b.token, body });
    expect(again.status).toBe(200);
    expect(again.body.version).toBe(receiver.body.version);
  });

  it("one SMS payment named UPI on one phone and OTHER on the other is still one settlement, either way round", async () => {
    const { a, b, c, g, ma, mb } = await setup();
    // The payer's debit after Pay by UPI says UPI; the receiver's credit SMS named no channel (docs/server.md 9.5).
    const upi = settle(mb, ma, 4000, { method: "UPI", matchedFromSms: true });
    const other = settle(mb, ma, 4000, { method: "OTHER", matchedFromSms: true });

    // Payer first: the receiver's record confirms it and keeps the payer's method.
    const s1 = uuid();
    expect((await call("PUT", `/v1/groups/${g.id}/settlements/${s1}`, { token: b.token, body: upi })).status).toBe(201);
    const receiver = await call("PUT", `/v1/groups/${g.id}/settlements/${s1}`, { token: a.token, body: other });
    expect(receiver.status).toBe(200);
    expect(receiver.body).toMatchObject({ id: s1, method: "UPI", recordedByMemberId: mb, confirmedAt: expect.any(Number) });

    // Receiver first (confirmed at once): the payer's record is answered with it, nothing changes.
    const s2 = uuid();
    const first = await call("PUT", `/v1/groups/${g.id}/settlements/${s2}`, { token: a.token, body: other });
    expect(first.status).toBe(201);
    const payer = await call("PUT", `/v1/groups/${g.id}/settlements/${s2}`, { token: b.token, body: upi });
    expect(payer.status).toBe(200);
    expect(payer.body).toMatchObject({ id: s2, method: "OTHER", recordedByMemberId: ma, version: first.body.version });

    // Counted once each: b owed a 10000 and now owes 2000.
    const bal = await call("GET", `/v1/groups/${g.id}/balances`, { token: a.token });
    expect(bal.body.nets.find((n: any) => n.memberId === mb).netPaise).toBe(-2000);

    // Anything else that differs is another payment: 409. So is a method change by someone who is not a party, or
    // without an SMS match on both sides.
    const amount = await call("PUT", `/v1/groups/${g.id}/settlements/${s1}`, { token: a.token, body: { ...other, amountPaise: 4001 } });
    expect(amount.body.error.code).toBe("exists_different");
    const day = await call("PUT", `/v1/groups/${g.id}/settlements/${s1}`, { token: a.token, body: { ...other, occurredOn: "2026-11-15" } });
    expect(day.body.error.code).toBe("exists_different");
    const typed = await call("PUT", `/v1/groups/${g.id}/settlements/${s1}`, { token: a.token, body: { ...other, matchedFromSms: false } });
    expect(typed.body.error.code).toBe("exists_different");
    const stranger = await call("PUT", `/v1/groups/${g.id}/settlements/${s1}`, { token: c.token, body: other });
    expect(stranger.body.error.code).toBe("exists_different");
  });

  it("only from or to may record or delete; same content 200, other content 409", async () => {
    const { b, c, g, ma, mb, mc } = await setup();
    expect((await call("PUT", `/v1/groups/${g.id}/settlements/${uuid()}`, { token: c.token, body: settle(mb, ma, 100) })).body.error.code).toBe("not_allowed");
    const sid = uuid();
    const body = settle(mb, ma, 100);
    expect((await call("PUT", `/v1/groups/${g.id}/settlements/${sid}`, { token: b.token, body })).status).toBe(201);
    expect((await call("PUT", `/v1/groups/${g.id}/settlements/${sid}`, { token: b.token, body })).status).toBe(200);
    const diff = await call("PUT", `/v1/groups/${g.id}/settlements/${sid}`, { token: b.token, body: { ...body, amountPaise: 101 } });
    expect(diff.body.error.code).toBe("exists_different");
    expect((await call("DELETE", `/v1/groups/${g.id}/settlements/${sid}`, { token: c.token })).body.error.code).toBe("not_allowed");
    const del = await call("DELETE", `/v1/groups/${g.id}/settlements/${sid}`, { token: b.token });
    expect(del.status).toBe(200);
    expect((await call("DELETE", `/v1/groups/${g.id}/settlements/${sid}`, { token: b.token })).body).toEqual(del.body);
    void mc;
  });

  it("validates", async () => {
    const { b, g, ma, mb } = await setup();
    const cases: [Record<string, unknown>, string][] = [
      [settle(mb, mb, 100), "toMemberId"],
      [settle(mb, ma, 0), "amountPaise"],
      [settle(mb, ma, 100, { method: "CHEQUE" }), "method"],
      [settle(mb, uuid(), 100), "toMemberId"],
      [settle(mb, ma, 100, { occurredOn: "16-11-2026" }), "occurredOn"],
    ];
    for (const [body, field] of cases) {
      const r = await call("PUT", `/v1/groups/${g.id}/settlements/${uuid()}`, { token: b.token, body });
      expect(r.status, field).toBe(400);
      expect(r.body.error.field).toBe(field);
    }
  });

  it("balances: nets sum to 0 and transfers simplify within the group", async () => {
    const { a, g, ma, mb, mc, fid } = await setup();
    const r = await call("GET", `/v1/groups/${g.id}/balances`, { token: a.token });
    expect(r.status).toBe(200);
    expect(r.body.version).toBeGreaterThan(0);
    expect(r.body.nets.reduce((s: number, n: any) => s + n.netPaise, 0)).toBe(0);
    const from = r.body.transfers.map((t: any) => t.fromMemberId).sort();
    expect(from).toEqual([mb, mc, fid].sort());
    expect(r.body.transfers.every((t: any) => t.toMemberId === ma && t.amountPaise === 10000)).toBe(true);
  });
});

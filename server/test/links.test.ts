// Share links, the public link data and invites (docs/server.md 4.10).
import { describe, expect, it } from "vitest";
import type { Db } from "../src/db/db";
import { testHooks } from "../src/testing";
import { befriend, call, createGroup, env, expenseBody, putExpense, rows, shareToken, signIn, uuid } from "./helpers";

async function setup() {
  const a = await signIn("Arjun Mehta");
  await call("PATCH", "/v1/me", { token: a.token, body: { phone: "+919000000007" } });
  const g = await createGroup(a, { friends: [{ name: "Priya Nair", phone: "+919000000001" }, { name: "Kiran" }] });
  const ma = g.members[a.id];
  const mp = g.members["Priya Nair"];
  const mk = g.members.Kiran;
  const { eid } = await putExpense(a, g.id, expenseBody(ma, 45000, [ma, mp, mk]));
  const token = shareToken();
  const linkId = uuid();
  const put = await call("PUT", `/v1/groups/${g.id}/members/${mp}/links/${linkId}`, { token: a.token, body: { token } });
  expect(put.status).toBe(201);
  return { a, g, ma, mp, mk, eid, token, linkId, put };
}

describe("PUT .../links/{linkId}", () => {
  it("stores only the hash; a lost answer is sent again; other content is 409", async () => {
    const { a, g, mp, token, linkId, put } = await setup();
    expect(put.body).toEqual({
      linkId,
      url: `https://xpense.app/pay/${token}`,
      createdAt: expect.any(Number),
      expiresAt: put.body.createdAt + 90 * 86_400_000,
    });
    const stored = await rows("SELECT * FROM links WHERE id = ?", linkId);
    expect(JSON.stringify(stored)).not.toContain(token);
    const again = await call("PUT", `/v1/groups/${g.id}/members/${mp}/links/${linkId}`, { token: a.token, body: { token } });
    expect(again.status).toBe(200);
    expect(again.body.createdAt).toBe(put.body.createdAt);
    // expiresAt is the stored one (docs/server.md 4.18: a family link lives 7 days, so it is never recomputed).
    expect(again.body.expiresAt).toBe(stored[0].expires_at);
    expect(put.body.expiresAt).toBe(stored[0].expires_at);
    const other = await call("PUT", `/v1/groups/${g.id}/members/${mp}/links/${linkId}`, { token: a.token, body: { token: shareToken() } });
    expect(other.body.error.code).toBe("exists_different");
    const bad = await call("PUT", `/v1/groups/${g.id}/members/${mp}/links/${uuid()}`, { token: a.token, body: { token: "short" } });
    expect(bad.body.error.field).toBe("token");
    // Not a group mutation.
    expect(await rows("SELECT * FROM changes WHERE group_id = ? AND entity_id = ?", g.id, linkId)).toHaveLength(0);
  });

  it("a link turned off or run out is not given out again: 404 link_not_found, and a new link works", async () => {
    const { a, g, mp, token, linkId } = await setup();
    // Turned off from another phone (Turn off link, an undo-join or a removal do the same).
    expect((await call("DELETE", `/v1/groups/${g.id}/members/${mp}/links`, { token: a.token })).status).toBe(204);
    const again = await call("PUT", `/v1/groups/${g.id}/members/${mp}/links/${linkId}`, { token: a.token, body: { token } });
    expect(again.status).toBe(404);
    expect(again.body.error.code).toBe("link_not_found");
    expect((await call("GET", `/v1/links/${token}`)).body.error.code).toBe("link_not_found");

    const t2 = shareToken();
    const l2 = uuid();
    expect((await call("PUT", `/v1/groups/${g.id}/members/${mp}/links/${l2}`, { token: a.token, body: { token: t2 } })).status).toBe(201);
    await env.DB.prepare("UPDATE links SET expires_at = ? WHERE id = ?").bind(Date.now() - 1, l2).run();
    const expired = await call("PUT", `/v1/groups/${g.id}/members/${mp}/links/${l2}`, { token: a.token, body: { token: t2 } });
    expect(expired.body.error.code).toBe("link_not_found");
  });

  it("leave and remove also turn off the links the member made", async () => {
    const { a, g, mp, mk } = await setup();
    const b = await signIn("Rahul Menon");
    await befriend(a, b);
    const mb = uuid();
    expect((await call("POST", `/v1/groups/${g.id}/members`, { token: a.token, body: { id: mb, userId: b.id } })).status).toBe(201);
    // Rahul makes links for both friends without the app; Arjun makes one for Kiran.
    const byRahulForPriya = shareToken();
    const byRahulForKiran = shareToken();
    const byArjunForKiran = shareToken();
    await call("PUT", `/v1/groups/${g.id}/members/${mp}/links/${uuid()}`, { token: b.token, body: { token: byRahulForPriya } });
    await call("PUT", `/v1/groups/${g.id}/members/${mk}/links/${uuid()}`, { token: b.token, body: { token: byRahulForKiran } });
    await call("PUT", `/v1/groups/${g.id}/members/${mk}/links/${uuid()}`, { token: a.token, body: { token: byArjunForKiran } });
    expect((await call("GET", `/v1/links/${byRahulForKiran}`)).status).toBe(200);

    // Rahul leaves (his net is 0): his links stop working, Arjun's does not.
    expect((await call("POST", `/v1/groups/${g.id}/leave`, { token: b.token })).status).toBe(204);
    expect((await call("GET", `/v1/links/${byRahulForPriya}`)).body.error.code).toBe("link_not_found");
    expect((await call("GET", `/v1/links/${byRahulForKiran}`)).body.error.code).toBe("link_not_found");
    expect((await call("GET", `/v1/links/${byArjunForKiran}`)).status).toBe(200);

    // Removed by the owner: the same.
    const c = await signIn("Vikram Shah");
    await befriend(a, c);
    const mc = uuid();
    await call("POST", `/v1/groups/${g.id}/members`, { token: a.token, body: { id: mc, userId: c.id } });
    const byVikram = shareToken();
    await call("PUT", `/v1/groups/${g.id}/members/${mk}/links/${uuid()}`, { token: c.token, body: { token: byVikram } });
    expect((await call("GET", `/v1/links/${byVikram}`)).status).toBe(200);
    expect((await call("DELETE", `/v1/groups/${g.id}/members/${mc}`, { token: a.token })).status).toBe(204);
    expect((await call("GET", `/v1/links/${byVikram}`)).body.error.code).toBe("link_not_found");
    expect((await call("GET", `/v1/links/${byArjunForKiran}`)).status).toBe(200);
    // A stranger holding Vikram's token cannot join in Kiran's place.
    const d = await signIn();
    expect((await call("POST", `/v1/invites/${byVikram}/accept`, { token: d.token })).body.error.code).toBe("link_not_found");
  });
});

describe("GET /v1/links/{token} (public)", () => {
  it("returns the friend's page with no phone numbers and no ids, with the privacy and CORS headers", async () => {
    const { g, mp, mk, ma, token } = await setup();
    const r = await call("GET", `/v1/links/${token}`);
    expect(r.status).toBe(200);
    expect(r.body).toEqual({
      groupName: "Flat 302",
      senderName: "Arjun Mehta",
      friendName: "Priya Nair",
      owes: [{ toName: "Arjun Mehta", amountPaise: 15000, upiId: null }],
      owed: [],
      expenses: [
        {
          description: "Swiggy",
          category: "FOOD",
          occurredOn: expect.any(String),
          amountPaise: 45000,
          yourSharePaise: 15000,
          splitMethod: "EQUAL",
          peopleCount: 3,
          paidByName: "Arjun Mehta",
        },
      ],
      expensesTruncated: false,
    });
    const text = JSON.stringify(r.body);
    for (const secret of ["+919000000001", "+919000000007", g.id, mp, mk, ma]) expect(text).not.toContain(secret);
    expect(r.headers.get("Access-Control-Allow-Origin")).toBe("https://xpense.app");
    expect(r.headers.get("Vary")).toBe("Origin");
    expect(r.headers.get("X-Robots-Tag")).toBe("noindex, nofollow");
    expect(r.headers.get("Referrer-Policy")).toBe("no-referrer");
    expect(r.headers.get("Cache-Control")).toBe("no-store");
    const local = await call("GET", `/v1/links/${token}`, { headers: { Origin: "http://localhost:8788" } });
    expect(local.headers.get("Access-Control-Allow-Origin")).toBe("http://localhost:8788");
    const prod = await call("GET", `/v1/links/${token}`, { headers: { Origin: "http://localhost:8788" }, env: { ENVIRONMENT: "production" } });
    expect(prod.headers.get("Access-Control-Allow-Origin")).toBe("https://xpense.app");
  });

  it("shows the payee's UPI ID once set (the version moves, the cache is recomputed)", async () => {
    const { a, token } = await setup();
    await call("PATCH", "/v1/me", { token: a.token, body: { upiId: "arjun@okhdfc" } });
    const r = await call("GET", `/v1/links/${token}`);
    expect(r.body.owes).toEqual([{ toName: "Arjun Mehta", amountPaise: 15000, upiId: "arjun@okhdfc" }]);
  });

  it("reads at most 5 rows on a cache hit", async () => {
    const { a, g, ma, mp, token } = await setup();
    for (let i = 0; i < 15; i++) await putExpense(a, g.id, expenseBody(ma, 1000 + i, [ma, mp]));
    expect((await call("GET", `/v1/links/${token}`)).status).toBe(200); // computes and stores
    let db: Db | null = null;
    testHooks.onDb = (d) => (db = d);
    expect((await call("GET", `/v1/links/${token}`)).status).toBe(200);
    expect(db!.rowsRead).toBeGreaterThan(0);
    expect(db!.rowsRead).toBeLessThanOrEqual(5);
  });

  it("revoked, expired, unknown, malformed or deleted group: the same 404 link_not_found", async () => {
    const { a, g, mp, token, linkId } = await setup();
    const unknown = await call("GET", `/v1/links/${shareToken()}`);
    expect(unknown.status).toBe(404);
    expect(unknown.body.error.code).toBe("link_not_found");
    expect(unknown.headers.get("Access-Control-Allow-Origin")).toBe("https://xpense.app");
    expect((await call("GET", "/v1/links/short")).body.error.code).toBe("link_not_found");
    const long = await call("GET", `/v1/links/${"x".repeat(300)}`);
    expect(long.body.error.code).toBe("link_not_found");
    expect(long.headers.get("Access-Control-Allow-Origin")).toBe("https://xpense.app");

    await env.DB.prepare("UPDATE links SET expires_at = ? WHERE id = ?").bind(Date.now() - 1, linkId).run();
    expect((await call("GET", `/v1/links/${token}`)).body.error.code).toBe("link_not_found");

    const t2 = shareToken();
    const l2 = uuid();
    await call("PUT", `/v1/groups/${g.id}/members/${mp}/links/${l2}`, { token: a.token, body: { token: t2 } });
    expect((await call("GET", `/v1/links/${t2}`)).status).toBe(200);
    expect((await call("DELETE", `/v1/groups/${g.id}/links/${l2}`, { token: a.token })).status).toBe(204);
    expect((await call("GET", `/v1/links/${t2}`)).body.error.code).toBe("link_not_found");

    const t3 = shareToken();
    await call("PUT", `/v1/groups/${g.id}/members/${mp}/links/${uuid()}`, { token: a.token, body: { token: t3 } });
    expect((await call("DELETE", `/v1/groups/${g.id}/members/${mp}/links`, { token: a.token })).status).toBe(204);
    expect((await call("GET", `/v1/links/${t3}`)).body.error.code).toBe("link_not_found");
  });

  it("preflight answers GET only", async () => {
    const { token } = await setup();
    const ok = await call("OPTIONS", `/v1/links/${token}`, { headers: { Origin: "https://xpense.app", "Access-Control-Request-Method": "GET" } });
    expect(ok.status).toBe(204);
    expect(ok.headers.get("Access-Control-Allow-Methods")).toBe("GET");
    const post = await call("OPTIONS", `/v1/links/${token}`, { headers: { Origin: "https://xpense.app", "Access-Control-Request-Method": "POST" } });
    expect(post.status).toBe(405);
  });

  it("is rate limited per IP", async () => {
    const { token } = await setup();
    testHooks.limiter = async (name) => name !== "RL_PUBLIC";
    const r = await call("GET", `/v1/links/${token}`);
    expect(r.status).toBe(429);
    expect(r.headers.get("Access-Control-Allow-Origin")).toBe("https://xpense.app");
  });
});

describe("invites", () => {
  it("previews, accepts once, announces the join and revokes the friend's links", async () => {
    const { a, g, mp, token } = await setup();
    const b = await signIn("Priya Iyer");
    const prev = await call("GET", `/v1/invites/${token}`, { token: b.token });
    expect(prev.status).toBe(200);
    expect(prev.body).toEqual({
      groupId: g.id,
      groupName: "Flat 302",
      inviterName: "Arjun Mehta",
      // The contract fixes no order (members come by id, which is random here).
      memberNames: expect.arrayContaining(["Arjun", "Kiran"]),
      memberCount: 3,
      youOwePaise: 15000,
      owedToYouPaise: 0,
      expensesThisMonth: 1,
      kind: "group",
      alreadyMember: false,
      alreadyInFamily: false,
    });
    const key = uuid();
    const acc = await call("POST", `/v1/invites/${token}/accept`, { token: b.token, idem: key });
    expect(acc.status).toBe(200);
    expect(acc.body).toEqual({ groupId: g.id, memberId: mp, version: expect.any(Number) });
    const replay = await call("POST", `/v1/invites/${token}/accept`, { token: b.token, idem: key });
    expect(replay.headers.get("Idempotent-Replay")).toBe("true");
    // A repeat with a new key still answers 200 for the person who accepted.
    expect((await call("POST", `/v1/invites/${token}/accept`, { token: b.token })).status).toBe(200);

    const member = await rows("SELECT user_id, phone, joined_at FROM members WHERE id = ?", mp);
    expect(member[0]).toMatchObject({ user_id: b.id, phone: null });
    const join = await rows("SELECT kind, op, entity_id FROM changes WHERE group_id = ? ORDER BY version DESC LIMIT 1", g.id);
    expect(join[0]).toEqual({ kind: "member", op: "join", entity_id: mp });
    // The link no longer works for anyone else.
    const c = await signIn();
    expect((await call("POST", `/v1/invites/${token}/accept`, { token: c.token })).body.error.code).toBe("link_not_found");
    expect((await call("GET", `/v1/links/${token}`)).body.error.code).toBe("link_not_found");
    // The joiner's own name shows from now on.
    const pull = await call("GET", `/v1/groups/${g.id}/changes`, { token: a.token });
    expect(pull.body.members.find((m: any) => m.id === mp)).toMatchObject({ name: "Priya Iyer", onApp: true, userId: b.id });
  });

  it("already_claimed and already_member", async () => {
    const { a, g, mp, mk, token } = await setup();
    const b = await signIn();
    const c = await signIn();
    // A race: the slot was taken while the link still worked.
    await env.DB.prepare("UPDATE members SET user_id = ? WHERE id = ?").bind(b.id, mp).run();
    expect((await call("POST", `/v1/invites/${token}/accept`, { token: c.token })).body.error.code).toBe("already_claimed");
    // b is in the group as Priya; a link for Kiran cannot make b a second member.
    const tk = shareToken();
    await call("PUT", `/v1/groups/${g.id}/members/${mk}/links/${uuid()}`, { token: a.token, body: { token: tk } });
    const prev = await call("GET", `/v1/invites/${tk}`, { token: b.token });
    expect(prev.body.alreadyMember).toBe(true);
    expect((await call("POST", `/v1/invites/${tk}/accept`, { token: b.token })).body.error.code).toBe("already_member");
  });
});

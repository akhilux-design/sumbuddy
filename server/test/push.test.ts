// FCM HTTP v1 (docs/server.md 4.14) with an intercepted fetch: OAuth exchange, message shape, who is skipped.
import { decodeJwt, exportPKCS8, generateKeyPair } from "jose";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { pushConfig, resetPushTokenForTests, sendPush } from "../src/push/fcm";
import { befriend, call, expenseBody, putExpense, rows, shareToken, signIn, today, uuid } from "./helpers";

let serviceAccount = "";
const sent: { url: string; headers: Headers; body: string }[] = [];
let unregistered = new Set<string>();

beforeAll(async () => {
  const { privateKey } = await generateKeyPair("RS256", { extractable: true });
  serviceAccount = JSON.stringify({
    type: "service_account",
    client_email: "fcm-sender@demo-project.iam.gserviceaccount.com",
    private_key: await exportPKCS8(privateKey),
  });
});

beforeEach(() => {
  resetPushTokenForTests();
  sent.length = 0;
  unregistered = new Set();
  const real = globalThis.fetch;
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
    if (url.startsWith("https://oauth2.googleapis.com/") || url.startsWith("https://fcm.googleapis.com/")) {
      sent.push({ url, headers: new Headers(init?.headers), body: String(init?.body ?? "") });
      if (url === "https://oauth2.googleapis.com/token") return Response.json({ access_token: "ya29.test-token", expires_in: 3600 });
      const token = JSON.parse(String(init?.body)).message.token as string;
      if (unregistered.has(token)) {
        return Response.json({ error: { code: 404, status: "NOT_FOUND", details: [{ errorCode: "UNREGISTERED" }] } }, { status: 404 });
      }
      return Response.json({ name: "projects/demo-project/messages/1" });
    }
    return real(input as RequestInfo, init);
  });
});

afterEach(() => vi.restoreAllMocks());

const pushEnv = () => ({ FCM_PROJECT_ID: "demo-project", FCM_SERVICE_ACCOUNT_JSON: serviceAccount });
const fcmSends = () => sent.filter((s) => s.url.includes("fcm.googleapis.com")).map((s) => ({ ...s, json: JSON.parse(s.body) }));

describe("push", () => {
  it("is off without FCM_PROJECT_ID or the service account (the local default)", async () => {
    expect(pushConfig({ FCM_PROJECT_ID: "", FCM_SERVICE_ACCOUNT_JSON: serviceAccount })).toBeNull();
    expect(pushConfig({ FCM_PROJECT_ID: "p", FCM_SERVICE_ACCOUNT_JSON: "" })).toBeNull();
    const a = await signIn();
    const b = await signIn();
    const g = await befriend(a, b);
    await call("PUT", `/v1/devices/${b.installationId}`, { token: b.token, body: { fcmToken: "fcm-b-" + uuid() } });
    await putExpense(a, g.id, expenseBody(g.members[a.id], 100, [g.members[a.id], g.members[b.id]]));
    expect(sent).toHaveLength(0);
  });

  it("signs the OAuth JWT, sends data-only messages, skips the caller's own installation, HIGH only when it notifies", async () => {
    const a = await signIn();
    const b = await signIn();
    const c = await signIn();
    const g = await befriend(a, b);
    await befriend(a, c);
    await call("POST", `/v1/groups/${g.id}/members`, { token: a.token, body: { id: uuid(), userId: c.id } });
    const second = uuid();
    const tok = { a1: "fcm-a1-" + uuid(), a2: "fcm-a2-" + uuid(), b: "fcm-b-" + uuid(), c: "fcm-c-" + uuid() };
    await call("PUT", `/v1/devices/${a.installationId}`, { token: a.token, body: { fcmToken: tok.a1 } });
    await call("PUT", `/v1/devices/${second}`, { token: a.token, body: { fcmToken: tok.a2 } });
    await call("PUT", `/v1/devices/${b.installationId}`, { token: b.token, body: { fcmToken: tok.b } });
    await call("PUT", `/v1/devices/${c.installationId}`, { token: c.token, body: { fcmToken: tok.c } });
    sent.length = 0;

    const ma = g.members[a.id];
    const mb = g.members[b.id];
    const { r } = await call("PUT", `/v1/groups/${g.id}/expenses/${uuid()}`, {
      token: a.token,
      headers: { "X-Installation-Id": a.installationId },
      env: pushEnv(),
      body: expenseBody(ma, 900, [ma, mb]),
    }).then((r) => ({ r }));
    expect(r.status).toBe(201);

    const oauth = sent.find((s) => s.url === "https://oauth2.googleapis.com/token")!;
    const form = new URLSearchParams(oauth.body);
    expect(form.get("grant_type")).toBe("urn:ietf:params:oauth:grant-type:jwt-bearer");
    const claims = decodeJwt(form.get("assertion")!);
    expect(claims).toMatchObject({
      iss: "fcm-sender@demo-project.iam.gserviceaccount.com",
      aud: "https://oauth2.googleapis.com/token",
      scope: "https://www.googleapis.com/auth/firebase.messaging",
    });
    expect(claims.exp! - claims.iat!).toBe(3600);

    const sends = fcmSends();
    expect(sends.every((s) => s.url === "https://fcm.googleapis.com/v1/projects/demo-project/messages:send")).toBe(true);
    expect(sends.every((s) => s.headers.get("Authorization") === "Bearer ya29.test-token")).toBe(true);
    const byToken = Object.fromEntries(sends.map((s) => [s.json.message.token, s.json.message]));
    expect(Object.keys(byToken).sort()).toEqual([tok.a2, tok.b, tok.c].sort()); // not a's own installation
    expect(byToken[tok.b]).toEqual({
      token: tok.b,
      data: { t: "changed", g: g.id, v: String(r.body.version) },
      android: { priority: "HIGH", ttl: "86400s", collapse_key: g.id },
    });
    expect(byToken[tok.a2].android.priority).toBe("HIGH"); // a paid
    expect(byToken[tok.c].android.priority).toBe("NORMAL"); // c has no share: no notification
    // Data only: no names, amounts or text anywhere. Every field is pinned, because the random ids can hold "900".
    for (const s of sends) {
      expect(Object.keys(s.json).sort()).toEqual(["message"]);
      expect(Object.keys(s.json.message).sort()).toEqual(["android", "data", "token"]);
      expect(s.json.message.data).toEqual({ t: "changed", g: g.id, v: String(r.body.version) });
      expect(Object.keys(s.json.message.android).sort()).toEqual(["collapse_key", "priority", "ttl"]);
    }
    expect(JSON.stringify(sends.map((s) => s.json))).not.toMatch(/Swiggy|notification/);

    // The access token is cached: a second write makes no new OAuth call.
    sent.length = 0;
    await call("PUT", `/v1/groups/${g.id}/expenses/${uuid()}`, { token: a.token, env: pushEnv(), body: expenseBody(ma, 100, [ma]) });
    expect(sent.filter((s) => s.url.includes("oauth2"))).toHaveLength(0);
  });

  it("a header naming someone else's installation does not silence them; sockets in the room are skipped", async () => {
    const a = await signIn();
    const b = await signIn();
    const g = await befriend(a, b);
    const tb = "fcm-b-" + uuid();
    await call("PUT", `/v1/devices/${b.installationId}`, { token: b.token, body: { fcmToken: tb } });
    const ma = g.members[a.id];
    await call("PUT", `/v1/groups/${g.id}/expenses/${uuid()}`, {
      token: a.token,
      headers: { "X-Installation-Id": b.installationId },
      env: pushEnv(),
      body: expenseBody(ma, 100, [ma]),
    });
    expect(fcmSends().map((s) => s.json.message.token)).toEqual([tb]);

    sent.length = 0;
    const live = await call("GET", `/v1/groups/${g.id}/live`, { token: b.token, headers: { Upgrade: "websocket", "X-Installation-Id": b.installationId } });
    live.res.webSocket!.accept();
    await call("PUT", `/v1/groups/${g.id}/expenses/${uuid()}`, { token: a.token, env: pushEnv(), body: expenseBody(ma, 100, [ma]) });
    expect(fcmSends()).toHaveLength(0);
  });

  it("a socket opened with someone else's installation id does not silence their push", async () => {
    const a = await signIn();
    const b = await signIn();
    const c = await signIn();
    const g = await befriend(a, b);
    await befriend(a, c);
    await call("POST", `/v1/groups/${g.id}/members`, { token: a.token, body: { id: uuid(), userId: c.id } });
    const tb = "fcm-b-" + uuid();
    await call("PUT", `/v1/devices/${b.installationId}`, { token: b.token, body: { fcmToken: tb } });
    // c holds a socket that claims b's installation id.
    const live = await call("GET", `/v1/groups/${g.id}/live`, { token: c.token, headers: { Upgrade: "websocket", "X-Installation-Id": b.installationId } });
    live.res.webSocket!.accept();
    sent.length = 0;
    const ma = g.members[a.id];
    await call("PUT", `/v1/groups/${g.id}/expenses/${uuid()}`, { token: a.token, env: pushEnv(), body: expenseBody(ma, 100, [ma]) });
    expect(fcmSends().map((s) => s.json.message.token)).toEqual([tb]);
  });

  it("removes devices FCM reports as unregistered; caps a fan-out at 40", async () => {
    const a = await signIn();
    const b = await signIn();
    const g = await befriend(a, b);
    const tb = "fcm-gone-" + uuid();
    await call("PUT", `/v1/devices/${b.installationId}`, { token: b.token, body: { fcmToken: tb } });
    unregistered.add(tb);
    await call("PUT", `/v1/groups/${g.id}/expenses/${uuid()}`, { token: a.token, env: pushEnv(), body: expenseBody(g.members[a.id], 100, [g.members[a.id]]) });
    expect(await rows("SELECT * FROM devices WHERE fcm_token = ?", tb)).toHaveLength(0);

    sent.length = 0;
    const cfg = pushConfig(pushEnv())!;
    const messages = Array.from({ length: 45 }, (_, i) => ({ token: "t" + i, data: { t: "groups" }, priority: "NORMAL" as const }));
    await sendPush(cfg, messages);
    expect(fcmSends()).toHaveLength(40);
  });

  it("reminders push the caller's member id; group creation pushes groups to added users", async () => {
    const a = await signIn();
    const b = await signIn();
    const g = await befriend(a, b);
    const tb = "fcm-b-" + uuid();
    await call("PUT", `/v1/devices/${b.installationId}`, { token: b.token, body: { fcmToken: tb } });
    const ma = g.members[a.id];
    const mb = g.members[b.id];
    await putExpense(a, g.id, expenseBody(ma, 1000, [ma, mb]));
    sent.length = 0;
    await call("POST", `/v1/groups/${g.id}/reminders`, { token: a.token, env: pushEnv(), body: { memberIds: [mb] } });
    expect(fcmSends().map((s) => s.json.message)).toEqual([
      { token: tb, data: { t: "remind", g: g.id, m: ma }, android: { priority: "HIGH", ttl: "86400s" } },
    ]);
    sent.length = 0;
    await call("POST", "/v1/groups", {
      token: a.token,
      env: pushEnv(),
      body: { id: uuid(), kind: "group", name: "New", myMemberId: uuid(), members: [{ id: uuid(), userId: b.id }] },
    });
    expect(fcmSends().map((s) => s.json.message.data)).toEqual([{ t: "groups" }]);
  });

  it("family (docs/server.md 4.18): the join is HIGH for the owner; family expenses and a removal are NORMAL", async () => {
    const a = await signIn("Arjun");
    const b = await signIn("Meera");
    const [fid, ma, mb] = [uuid(), uuid(), uuid()];
    const tok = { a: "fcm-a-" + uuid(), b1: "fcm-b1-" + uuid(), b2: "fcm-b2-" + uuid() };
    await call("PUT", `/v1/devices/${a.installationId}`, { token: a.token, body: { fcmToken: tok.a } });
    await call("PUT", `/v1/devices/${b.installationId}`, { token: b.token, body: { fcmToken: tok.b1 } });
    await call("PUT", `/v1/devices/${uuid()}`, { token: b.token, body: { fcmToken: tok.b2 } });
    // Making a family pushes nothing: nobody else is in it.
    const made = await call("POST", "/v1/groups", {
      token: a.token,
      env: pushEnv(),
      body: { id: fid, kind: "family", name: "Home", myMemberId: ma, members: [{ id: mb, name: "Partner" }] },
    });
    expect(made.status).toBe(201);
    expect(fcmSends()).toHaveLength(0);
    const token = shareToken();
    await call("PUT", `/v1/groups/${fid}/members/${mb}/links/${uuid()}`, { token: a.token, body: { token } });

    // "Meera joined Home": HIGH for the owner; the joiner's other phone gets "groups".
    sent.length = 0;
    const acc = await call("POST", `/v1/invites/${token}/accept`, {
      token: b.token,
      headers: { "X-Installation-Id": b.installationId },
      env: pushEnv(),
    });
    expect(acc.status).toBe(200);
    let byToken = Object.fromEntries(fcmSends().map((s) => [s.json.message.token, s.json.message]));
    expect(Object.keys(byToken).sort()).toEqual([tok.a, tok.b2].sort());
    expect(byToken[tok.a]).toEqual({
      token: tok.a,
      data: { t: "changed", g: fid, v: String(acc.body.version) },
      android: { priority: "HIGH", ttl: "86400s", collapse_key: fid },
    });
    expect(byToken[tok.b2]).toEqual({ token: tok.b2, data: { t: "groups" }, android: { priority: "HIGH", ttl: "86400s", collapse_key: "groups" } });

    // A family expense: NORMAL for the partner's phones (a background sync, no notification).
    sent.length = 0;
    const amount = 234000;
    const r = await call("PUT", `/v1/groups/${fid}/expenses/${uuid()}`, {
      token: a.token,
      headers: { "X-Installation-Id": a.installationId },
      env: pushEnv(),
      body: {
        description: "BigBasket", category: "GROCERIES", amountPaise: amount, occurredOn: today(), paidByMemberId: ma,
        splitMethod: "EQUAL", auto: false, shares: [{ memberId: ma, sharePaise: amount, input: null }], baseVersion: null,
      },
    });
    expect(r.status).toBe(201);
    byToken = Object.fromEntries(fcmSends().map((s) => [s.json.message.token, s.json.message]));
    expect(Object.keys(byToken).sort()).toEqual([tok.b1, tok.b2].sort());
    for (const t of [tok.b1, tok.b2]) {
      expect(byToken[t]).toEqual({ token: t, data: { t: "changed", g: fid, v: String(r.body.version) }, android: { priority: "NORMAL", ttl: "86400s", collapse_key: fid } });
    }
    expect(JSON.stringify(fcmSends())).not.toMatch(/BigBasket|234000/);

    // Removed from the family: "groups" for the removed partner's phones, NORMAL (no notification is designed).
    sent.length = 0;
    const removed = await call("DELETE", `/v1/groups/${fid}/members/${mb}`, {
      token: a.token,
      headers: { "X-Installation-Id": a.installationId },
      env: pushEnv(),
    });
    expect(removed.status).toBe(204);
    expect(fcmSends().map((s) => s.json.message).sort((x, y) => (x.token < y.token ? -1 : 1))).toEqual(
      [tok.b1, tok.b2].sort().map((t) => ({ token: t, data: { t: "groups" }, android: { priority: "NORMAL", ttl: "86400s", collapse_key: "groups" } })),
    );
  });
});

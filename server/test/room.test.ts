// Live room (docs/server.md 4.12): the Durable Object broadcasts "changed" and kicks removed users.
import { describe, expect, it } from "vitest";
import { befriend, call, env, expenseBody, putExpense, rows, signIn, uuid } from "./helpers";

async function connect(token: string, gid: string, installationId: string) {
  const r = await call("GET", `/v1/groups/${gid}/live`, { token, headers: { Upgrade: "websocket", "X-Installation-Id": installationId } });
  expect(r.status).toBe(101);
  const ws = r.res.webSocket!;
  const messages: any[] = [];
  const texts: string[] = [];
  const closed: { code: number | null } = { code: null };
  ws.addEventListener("message", (e) => {
    const data = e.data as string;
    if (data.startsWith("{")) messages.push(JSON.parse(data));
    else texts.push(data);
  });
  ws.addEventListener("close", (e) => {
    closed.code = e.code;
  });
  ws.accept();
  return { ws, messages, texts, closed };
}

async function until(check: () => boolean, ms = 2000) {
  const end = Date.now() + ms;
  while (!check() && Date.now() < end) await new Promise((r) => setTimeout(r, 10));
}

describe("GroupRoom", () => {
  it("broadcasts changed to every socket after a mutation, and kicks a removed user with 4403", async () => {
    const a = await signIn();
    const b = await signIn();
    const g = await befriend(a, b);
    const ma = g.members[a.id];
    const sa = await connect(a.token, g.id, a.installationId);
    const sb = await connect(b.token, g.id, b.installationId);

    const { r } = await putExpense(a, g.id, expenseBody(ma, 500, [ma]));
    await until(() => sa.messages.length > 0 && sb.messages.length > 0);
    expect(sb.messages).toEqual([{ type: "changed", groupId: g.id, version: r.body.version }]);
    expect(sa.messages).toEqual([{ type: "changed", groupId: g.id, version: r.body.version }]);

    // App-level ping is answered by the runtime.
    sa.ws.send("ping");
    await until(() => sa.texts.length > 0);
    expect(sa.texts).toEqual(["pong"]);

    expect((await call("DELETE", `/v1/groups/${g.id}/members/${g.members[b.id]}`, { token: a.token })).status).toBe(204);
    await until(() => sb.closed.code !== null);
    expect(sb.messages.slice(-2)).toEqual([{ type: "changed", groupId: g.id, version: expect.any(Number) }, { type: "revoked" }]);
    expect(sb.closed.code).toBe(4403);
    expect(sa.closed.code).toBeNull();
    // A removed user can no longer connect.
    const again = await call("GET", `/v1/groups/${g.id}/live`, { token: b.token, headers: { Upgrade: "websocket" } });
    expect(again.status).toBe(403);
  });

  it("closes a socket with 4401 once its session ends (sign-out, account deletion), before sending anything", async () => {
    const a = await signIn();
    const b = await signIn();
    const c = await signIn();
    const g = await befriend(a, b);
    await befriend(a, c);
    await call("POST", `/v1/groups/${g.id}/members`, { token: a.token, body: { id: uuid(), userId: c.id } });
    const ma = g.members[a.id];
    const sa = await connect(a.token, g.id, a.installationId);
    const sb = await connect(b.token, g.id, b.installationId);
    const sc = await connect(c.token, g.id, c.installationId);

    expect((await call("POST", "/v1/auth/signout", { token: b.token })).status).toBe(204);
    expect((await call("DELETE", "/v1/me", { token: c.token })).status).toBe(204);
    const { r } = await putExpense(a, g.id, expenseBody(ma, 500, [ma]));
    await until(() => sb.closed.code !== null && sc.closed.code !== null && sa.messages.length > 0);
    expect(sb.closed.code).toBe(4401);
    expect(sc.closed.code).toBe(4401);
    expect(sb.messages).toEqual([]);
    expect(sc.messages).toEqual([]);
    expect(sa.messages).toEqual([{ type: "changed", groupId: g.id, version: r.body.version }]);
    expect(sa.closed.code).toBeNull();
  });

  it("closes a socket whose access token has expired", async () => {
    const a = await signIn();
    const b = await signIn();
    const g = await befriend(a, b);
    const ma = g.members[a.id];
    const sid = (await rows("SELECT id FROM sessions WHERE user_id = ?", b.id))[0].id;
    const stub = env.GROUP_ROOM.get(env.GROUP_ROOM.idFromName(g.id));
    const res = await stub.fetch("https://room/connect", {
      headers: { Upgrade: "websocket", "X-User-Id": b.id, "X-Session-Id": sid, "X-Token-Expires-At": String(Date.now() - 1000), "X-Installation-Id": b.installationId },
    });
    const ws = res.webSocket!;
    const got: string[] = [];
    let code: number | null = null;
    ws.addEventListener("message", (e) => got.push(e.data as string));
    ws.addEventListener("close", (e) => (code = e.code));
    ws.accept();
    await putExpense(a, g.id, expenseBody(ma, 500, [ma]));
    await until(() => code !== null);
    expect(code).toBe(4401);
    expect(got).toEqual([]);
  });

  it("answers notify with the connected (user, installation) pairs and refuses unknown paths", async () => {
    const a = await signIn();
    const b = await signIn();
    const g = await befriend(a, b);
    await connect(b.token, g.id, b.installationId);
    const stub = env.GROUP_ROOM.get(env.GROUP_ROOM.idFromName(g.id));
    const res = await stub.fetch("https://room/notify", { method: "POST", body: JSON.stringify({ groupId: g.id, version: 9 }) });
    expect(await res.json()).toEqual({ connected: [{ userId: b.id, installationId: b.installationId }] });
    expect((await stub.fetch("https://room/other")).status).toBe(404);
    // The Worker never forwards /connect or /notify from outside.
    expect((await call("POST", `/v1/groups/${g.id}/notify`, { token: a.token })).status).toBe(404);
    // A plain GET without the upgrade is refused.
    expect((await call("GET", `/v1/groups/${g.id}/live`, { token: a.token })).status).toBe(400);
  });
});

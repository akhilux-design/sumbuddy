import { createExecutionContext, waitOnExecutionContext } from "cloudflare:test";
import { env } from "cloudflare:workers";
import worker from "../src/index";
import type { Env } from "../src/env";
import { directGroupId, directMemberId } from "../src/ids";

export const BASE = "http://localhost:8787";
export const uuid = () => crypto.randomUUID();
export const today = () => new Date(Date.now() + 5.5 * 3600_000).toISOString().slice(0, 10);

export interface CallOptions {
  body?: unknown;
  token?: string;
  idem?: string | false;
  headers?: Record<string, string>;
  env?: Partial<Env>;
  base?: string;
  /** Wait for ctx.waitUntil work (room notify, push). Default true. */
  wait?: boolean;
}

export interface Result {
  status: number;
  body: any;
  headers: Headers;
  res: Response;
}

const MUTATING = new Set(["POST", "PUT", "PATCH", "DELETE"]);

/** Calls the Worker's fetch handler directly, so a test can pass its own env. */
export async function call(method: string, path: string, o: CallOptions = {}): Promise<Result> {
  const headers: Record<string, string> = { ...(o.headers ?? {}) };
  if (o.token) headers.Authorization = `Bearer ${o.token}`;
  const needsKey =
    MUTATING.has(method) &&
    !path.startsWith("/v1/auth/") &&
    !path.startsWith("/v1/devices/") &&
    !(method === "PUT" && /\/links\/[^/]+$/.test(path));
  if (o.idem !== false && (o.idem || needsKey)) headers["Idempotency-Key"] = o.idem || uuid();
  let body: BodyInit | undefined;
  if (o.body !== undefined) {
    body = typeof o.body === "string" ? o.body : JSON.stringify(o.body);
    headers["Content-Type"] = "application/json";
  }
  const req = new Request((o.base ?? BASE) + path, { method, headers, body });
  const ctx = createExecutionContext();
  const res = await worker.fetch(req, { ...env, ...(o.env ?? {}) } as Env, ctx);
  if (o.wait !== false) await waitOnExecutionContext(ctx);
  if (res.status === 101) return { status: 101, body: null, headers: res.headers, res };
  const text = await res.text();
  let parsed: any = null;
  try {
    parsed = text ? JSON.parse(text) : null;
  } catch {
    parsed = text;
  }
  return { status: res.status, body: parsed, headers: res.headers, res };
}

export interface TestUser {
  id: string;
  name: string;
  token: string;
  refreshToken: string;
  installationId: string;
}

let counter = 0;

/** A fresh development user (unique per call). */
export async function signIn(name = "Asha Rao", devUser?: string): Promise<TestUser> {
  const key = devUser ?? `u${Date.now().toString(36)}${(counter++).toString(36)}${Math.floor(Math.random() * 1e6).toString(36)}`.slice(0, 20);
  const installationId = uuid();
  const r = await call("POST", "/v1/auth/dev", { body: { devUser: key, name, installationId } });
  if (r.status !== 200) throw new Error("dev sign-in failed " + r.status + JSON.stringify(r.body));
  return { id: r.body.user.id, name, token: r.body.accessToken, refreshToken: r.body.refreshToken, installationId };
}

export interface TestGroup {
  id: string;
  /** member id per user id or friend name */
  members: Record<string, string>;
  version: number;
}

/** Creates a group owned by `owner` with the given app users (who must be known) and friends without the app. */
export async function createGroup(owner: TestUser, others: { users?: TestUser[]; friends?: { name: string; phone?: string }[] } = {}, name = "Flat 302"): Promise<TestGroup> {
  const id = uuid();
  const members: Record<string, string> = { [owner.id]: uuid() };
  const list: any[] = [];
  for (const u of others.users ?? []) {
    members[u.id] = uuid();
    list.push({ id: members[u.id], userId: u.id });
  }
  for (const f of others.friends ?? []) {
    members[f.name] = uuid();
    list.push({ id: members[f.name], name: f.name, ...(f.phone ? { phone: f.phone } : {}) });
  }
  const r = await call("POST", "/v1/groups", {
    token: owner.token,
    body: { id, kind: "group", name, myMemberId: members[owner.id], members: list },
  });
  if (r.status !== 201) throw new Error("create group failed " + r.status + JSON.stringify(r.body));
  return { id, members, version: r.body.group.version };
}

/** Makes `b` known to `a`: a group with a friend slot that `b` joins through a link. */
export async function befriend(a: TestUser, b: TestUser): Promise<TestGroup> {
  const grp = await createGroup(a, { friends: [{ name: b.name }] }, "Intro");
  const token = shareToken();
  const linkId = uuid();
  const put = await call("PUT", `/v1/groups/${grp.id}/members/${grp.members[b.name]}/links/${linkId}`, {
    token: a.token,
    body: { token },
  });
  if (put.status !== 201) throw new Error("link failed " + put.status + JSON.stringify(put.body));
  const acc = await call("POST", `/v1/invites/${token}/accept`, { token: b.token });
  if (acc.status !== 200) throw new Error("accept failed " + acc.status + JSON.stringify(acc.body));
  grp.members[b.id] = grp.members[b.name];
  return grp;
}

export function shareToken(): string {
  const bytes = new Uint8Array(18);
  crypto.getRandomValues(bytes);
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_");
}

export function equalShares(amount: number, memberIds: string[]) {
  const sorted = [...memberIds].sort();
  const base = Math.floor(amount / sorted.length);
  const r = amount % sorted.length;
  return sorted.map((m, i) => ({ memberId: m, sharePaise: base + (i < r ? 1 : 0), input: null }));
}

export function expenseBody(paidBy: string, amount: number, people: string[], extra: Record<string, unknown> = {}) {
  return {
    description: "Swiggy",
    category: "FOOD",
    amountPaise: amount,
    occurredOn: today(),
    paidByMemberId: paidBy,
    splitMethod: "EQUAL",
    auto: false,
    shares: equalShares(amount, people),
    baseVersion: null,
    ...extra,
  };
}

export async function putExpense(user: TestUser, gid: string, body: unknown, eid = uuid(), idem?: string) {
  return { eid, r: await call("PUT", `/v1/groups/${gid}/expenses/${eid}`, { token: user.token, body, idem }) };
}

export { directGroupId, directMemberId, env };

export async function rows<T = any>(sql: string, ...params: unknown[]): Promise<T[]> {
  return (await env.DB.prepare(sql).bind(...params).all<T>()).results;
}

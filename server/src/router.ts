// A small hand-written router (docs/server.md 11: no web framework).
import { verifyAccessToken } from "./auth/tokens";
import { IdempotencyRace, type Ctx } from "./context";
import { canonicalJson, sha256Hex } from "./crypto";
import { Db } from "./db/db";
import { idemWriter } from "./db/mutation";
import type { Env } from "./env";
import { ApiError, badRequest, errorResponse, json, notFound, withBaseHeaders } from "./http";
import { UUID_RE, isUuid } from "./ids";
import { logError, logRequest } from "./log";
import { enforce } from "./ratelimit";
import * as auth from "./routes/auth";
import * as groups from "./routes/groups";
import * as imports from "./routes/imports";
import * as ledger from "./routes/ledger";
import * as links from "./routes/links";
import * as meRoutes from "./routes/me";
import { testHooks } from "./testing";

export const MAX_BODY_BYTES = 32 * 1024;

type Handler = (ctx: Ctx) => Promise<Response> | Response;
type Replay = (ctx: Ctx, entityId: string | null, status: number) => Promise<Response>;

interface Route {
  method: string;
  path: string;
  handler: Handler;
  /** Needs a bearer token. */
  auth?: boolean;
  /** Needs an Idempotency-Key (4.3); `replay` answers a repeated request. */
  replay?: Replay;
  /** Counts against RL_WRITE. */
  write?: boolean;
  /**
   * How many times the body counts against RL_WRITE in all (4.15: an import, once per 10 items). The first hit is taken
   * with every write, before the body is read; the rest after the replay lookup, so a replay counts once.
   */
  writeCost?: (body: unknown) => number;
  /** Per-IP limiter before anything else. */
  ipLimit?: "RL_AUTH" | "RL_PUBLIC";
  /** CORS and privacy headers of /v1/links (4.10). */
  linkHeaders?: boolean;
  /** Exists only when devSignInEnabled() (4.4); otherwise the path is unknown. */
  devOnly?: boolean;
}

const g = "/v1/groups/:gid";
const ROUTES: Route[] = [
  { method: "GET", path: "/v1/health", handler: () => json({ ok: true }) },

  { method: "POST", path: "/v1/auth/nonce", handler: auth.nonce, ipLimit: "RL_AUTH" },
  { method: "POST", path: "/v1/auth/google", handler: auth.google, ipLimit: "RL_AUTH" },
  { method: "POST", path: "/v1/auth/dev", handler: auth.dev, ipLimit: "RL_AUTH", devOnly: true },
  { method: "POST", path: "/v1/auth/refresh", handler: auth.refresh },
  { method: "POST", path: "/v1/auth/signout", handler: auth.signout, auth: true },

  { method: "GET", path: "/v1/me", handler: meRoutes.getMe, auth: true },
  { method: "PATCH", path: "/v1/me", handler: meRoutes.patchMe, auth: true, write: true, replay: (c) => meRoutes.getMe(c) },
  { method: "DELETE", path: "/v1/me", handler: meRoutes.deleteMe, auth: true, write: true, replay: groups.replayNoContent },
  { method: "GET", path: "/v1/me/groups", handler: meRoutes.getMyGroups, auth: true },
  { method: "PUT", path: "/v1/devices/:installationId", handler: meRoutes.putDeviceRoute, auth: true, write: true },
  { method: "DELETE", path: "/v1/devices/:installationId", handler: meRoutes.deleteDeviceRoute, auth: true, write: true },

  { method: "POST", path: "/v1/groups", handler: groups.createGroup, auth: true, write: true, replay: groups.replayGroupCreate },
  { method: "PATCH", path: g, handler: groups.renameGroup, auth: true, write: true, replay: groups.replayGroup },
  { method: "DELETE", path: g, handler: groups.deleteGroup, auth: true, write: true, replay: groups.replayNoContent },
  { method: "POST", path: `${g}/leave`, handler: groups.leaveGroup, auth: true, write: true, replay: groups.replayNoContent },
  { method: "POST", path: `${g}/members`, handler: groups.addMember, auth: true, write: true, replay: groups.replayMember },
  { method: "PATCH", path: `${g}/members/:mid`, handler: groups.editMember, auth: true, write: true, replay: groups.replayMember },
  { method: "DELETE", path: `${g}/members/:mid`, handler: groups.removeMember, auth: true, write: true, replay: groups.replayNoContent },
  { method: "POST", path: `${g}/members/:mid/undo-join`, handler: groups.undoJoin, auth: true, write: true, replay: groups.replayMember },
  { method: "PUT", path: `${g}/expenses/:eid`, handler: ledger.putExpense, auth: true, write: true, replay: ledger.replayExpense },
  { method: "DELETE", path: `${g}/expenses/:eid`, handler: ledger.deleteExpense, auth: true, write: true, replay: ledger.replayVersionOfExpense },
  { method: "PUT", path: `${g}/settlements/:sid`, handler: ledger.putSettlement, auth: true, write: true, replay: ledger.replaySettlement },
  { method: "POST", path: `${g}/settlements/:sid/confirm`, handler: ledger.confirmSettlement, auth: true, write: true, replay: ledger.replaySettlement },
  { method: "DELETE", path: `${g}/settlements/:sid`, handler: ledger.deleteSettlement, auth: true, write: true, replay: ledger.replayVersionOfSettlement },
  { method: "POST", path: `${g}/import`, handler: imports.importItems, auth: true, write: true, writeCost: imports.importWriteCost, replay: imports.replayImport },
  { method: "GET", path: `${g}/changes`, handler: ledger.changes, auth: true },
  { method: "GET", path: `${g}/balances`, handler: ledger.getBalances, auth: true },
  { method: "PUT", path: `${g}/members/:mid/links/:linkId`, handler: links.putLink, auth: true, write: true },
  { method: "DELETE", path: `${g}/links/:linkId`, handler: links.revokeLink, auth: true, write: true, replay: groups.replayNoContent },
  { method: "DELETE", path: `${g}/members/:mid/links`, handler: links.revokeMemberLinks, auth: true, write: true, replay: groups.replayNoContent },
  { method: "POST", path: `${g}/reminders`, handler: groups.remind, auth: true, write: true, replay: groups.replayRemind },
  { method: "GET", path: `${g}/live`, handler: links.live, auth: true },

  { method: "GET", path: "/v1/invites/:token", handler: links.getInvite, auth: true },
  { method: "POST", path: "/v1/invites/:token/accept", handler: links.acceptInvite, auth: true, write: true, replay: links.replayAccept },

  { method: "GET", path: "/v1/links/:token", handler: links.publicLink, ipLimit: "RL_PUBLIC", linkHeaders: true },
  { method: "OPTIONS", path: "/v1/links/:token", handler: links.preflight, linkHeaders: true },
];

/** `:token` takes any segment (checked by the handler, so a malformed token gets the same 404 as a revoked one). */
function match(template: string, path: string): Record<string, string> | null {
  const t = template.split("/");
  const p = path.split("/");
  if (t.length !== p.length) return null;
  const params: Record<string, string> = {};
  for (let i = 0; i < t.length; i++) {
    if (t[i].startsWith(":")) {
      const name = t[i].slice(1);
      if (name !== "token" && !UUID_RE.test(p[i])) return null;
      if (p[i].length === 0 || p[i].length > 512) return null;
      params[name] = p[i];
    } else if (t[i] !== p[i]) {
      return null;
    }
  }
  return params;
}

async function readBody(req: Request): Promise<unknown> {
  const declared = Number(req.headers.get("Content-Length") ?? "0");
  if (declared > MAX_BODY_BYTES) throw new ApiError(413, "too_large", "Request body over 32 KB.");
  if (!req.body) return undefined;
  const reader = req.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > MAX_BODY_BYTES) {
      await reader.cancel();
      throw new ApiError(413, "too_large", "Request body over 32 KB.");
    }
    chunks.push(value);
  }
  if (total === 0) return undefined;
  const bytes = new Uint8Array(total);
  let o = 0;
  for (const c of chunks) {
    bytes.set(c, o);
    o += c.byteLength;
  }
  try {
    return JSON.parse(new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(bytes));
  } catch {
    throw badRequest("body", "Body is not valid JSON.");
  }
}

export async function handle(req: Request, env: Env, exec: ExecutionContext): Promise<Response> {
  const started = Date.now();
  const url = new URL(req.url);
  let route: Route | null = null;
  let extra: Record<string, string> = {};
  let res: Response;
  let code: string | null = null;
  try {
    if (!env.SESSION_SECRET || env.SESSION_SECRET.length < 32) {
      throw new ApiError(500, "internal", "Server is not configured.");
    }
    const visible = ROUTES.filter((r) => !r.devOnly || auth.devSignInEnabled(env, url));
    const candidates = visible.map((r) => ({ r, params: match(r.path, url.pathname) })).filter((x) => x.params !== null);
    if (candidates.length === 0) throw notFound();
    const hit = candidates.find((x) => x.r.method === req.method);
    if (!hit) {
      route = candidates[0].r;
      const allow = [...new Set(candidates.map((x) => x.r.method))].join(", ");
      throw new ApiError(405, "method_not_allowed", "Method not allowed.", undefined, { Allow: allow });
    }
    route = hit.r;
    if (route.linkHeaders) extra = links.linkHeaders({ env, req });
    res = await dispatch(route, hit.params!, req, url, env, exec);
  } catch (err) {
    if (err instanceof ApiError) {
      code = err.code;
      res = errorResponse(err, extra);
    } else {
      logError(route?.path ?? "unknown", err);
      code = "internal";
      res = errorResponse(new ApiError(500, "internal", "Internal error."), extra);
    }
  }
  if (res.status !== 101) res = withBaseHeaders(res, extra);
  logRequest(req.method, route?.path ?? "unknown", res.status, code, Date.now() - started);
  return res;
}

async function dispatch(
  route: Route,
  params: Record<string, string>,
  req: Request,
  url: URL,
  env: Env,
  exec: ExecutionContext,
): Promise<Response> {
  const db = new Db(env.DB);
  testHooks.onDb?.(db);
  const installation = req.headers.get("X-Installation-Id");
  const ctx: Ctx = {
    env,
    req,
    url,
    params,
    body: undefined,
    now: Date.now(),
    db,
    exec,
    user: null,
    installationId: isUuid(installation) ? installation : null,
    idem: null,
    ip: req.headers.get("CF-Connecting-IP") ?? "local",
  };
  if (route.ipLimit) await enforce(env, route.ipLimit, ctx.ip);
  if (route.auth) {
    ctx.user = await auth.authenticate(ctx, (t) => verifyAccessToken(env.SESSION_SECRET!, t));
    await enforce(env, "RL_USER", ctx.user.id);
    if (route.write) await enforce(env, "RL_WRITE", ctx.user.id);
  }
  if (req.method === "POST" || req.method === "PUT" || req.method === "PATCH") ctx.body = await readBody(req);

  if (!route.replay) {
    await chargeExtraWrites(route, ctx);
    return route.handler(ctx);
  }

  // Idempotency (4.3): replay before any other check, so a retry of a delete or a leave still gets its answer.
  const key = req.headers.get("Idempotency-Key");
  if (!isUuid(key)) throw badRequest("Idempotency-Key", "Idempotency-Key header must be a lowercase UUID.");
  const hash = await sha256Hex(`${req.method} ${url.pathname}\n${canonicalJson(ctx.body ?? null)}`);
  const userId = ctx.user!.id;
  const replayed = await lookupKey(ctx, route, userId, key, hash);
  if (replayed) return replayed;
  await chargeExtraWrites(route, ctx);
  ctx.idem = idemWriter(db, userId, key, hash, ctx.now);
  try {
    return await route.handler(ctx);
  } catch (err) {
    if (err instanceof IdempotencyRace) {
      const again = await lookupKey(ctx, route, userId, key, hash);
      if (again) return again;
    }
    throw err;
  }
}

/**
 * The RL_WRITE hits past the first that a route's body costs (`writeCost`). After the replay lookup and before the
 * handler: a replay counts once like any replayed write, and a request refused here writes nothing, its key included,
 * so its retry after `Retry-After` is handled as new.
 */
async function chargeExtraWrites(route: Route, ctx: Ctx): Promise<void> {
  if (!route.writeCost || !ctx.user) return;
  await enforce(ctx.env, "RL_WRITE", ctx.user.id, route.writeCost(ctx.body) - 1);
}

async function lookupKey(ctx: Ctx, route: Route, userId: string, key: string, hash: string): Promise<Response | null> {
  const row = await ctx.db.first<{ request_hash: string; status: number; entity_id: string | null }>(
    "SELECT request_hash, status, entity_id FROM idempotency_keys WHERE user_id = ?1 AND key = ?2",
    userId,
    key,
  );
  if (!row) return null;
  if (row.request_hash !== hash) {
    throw new ApiError(422, "idempotency_key_reused", "Idempotency-Key was used for a different request.");
  }
  const res = await route.replay!(ctx, row.entity_id, row.status);
  return withBaseHeaders(res, { "Idempotent-Replay": "true" });
}

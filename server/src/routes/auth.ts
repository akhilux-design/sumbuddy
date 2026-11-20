// Sign-in, refresh and sign-out (docs/server.md 4.4).
import { verifyGoogleIdToken, type GoogleClaims } from "../auth/google";
import {
  REFRESH_RE,
  REFRESH_TTL_MS,
  newNonce,
  newRefreshToken,
  nonceValid,
  signAccessToken,
} from "../auth/tokens";
import { me, type Ctx } from "../context";
import { sha256Hex } from "../crypto";
import {
  findSessionByPrevRefresh,
  findSessionByRefresh,
  getSession,
  rerotateSession,
  revokeSession,
  rotateSession,
  signInUser,
  signOut,
  type SessionRow,
} from "../db/accounts";
import { constraintOn } from "../db/db";
import { userJson } from "../db/shapes";
import { badRequest, json, noContent, unauthorized } from "../http";
import { uuidv4 } from "../ids";
import { enforce } from "../ratelimit";
import { MINUTE } from "../time";
import * as v from "../validate";

/** Hosts from which the development sign-in is reachable (with ENVIRONMENT=development). */
export const LOCAL_HOSTS = new Set(["localhost", "127.0.0.1", "[::1]", "10.0.2.2"]);

export function devSignInEnabled(env: { ENVIRONMENT: string }, url: URL): boolean {
  return env.ENVIRONMENT === "development" && LOCAL_HOSTS.has(url.hostname);
}

const secret = (ctx: Ctx) => ctx.env.SESSION_SECRET!;

async function issue(ctx: Ctx, userId: string, sessionId: string, refreshToken: string, refreshExpiresAt: number) {
  const access = await signAccessToken(secret(ctx), userId, sessionId, ctx.now);
  return {
    accessToken: access.token,
    accessExpiresAt: access.expiresAt,
    refreshToken,
    refreshExpiresAt,
  };
}

export async function nonce(ctx: Ctx): Promise<Response> {
  return json(await newNonce(secret(ctx), ctx.now));
}

async function startSession(
  ctx: Ctx,
  column: "google_sub" | "dev_key",
  key: string,
  name: string,
  nonceHash: string | null,
): Promise<Response> {
  const refreshToken = newRefreshToken();
  const sessionId = uuidv4();
  const expiresAt = ctx.now + REFRESH_TTL_MS;
  try {
    const { user, isNew } = await signInUser(ctx.db, {
      column,
      key,
      newUserId: uuidv4(),
      name,
      sessionId,
      refreshHash: await sha256Hex(refreshToken),
      nonceHash,
      now: ctx.now,
      expiresAt,
    });
    return json({ user: userJson(user), isNew, ...(await issue(ctx, user.id, sessionId, refreshToken, expiresAt)) });
  } catch (err) {
    if (constraintOn(err, "sessions.nonce_hash")) throw unauthorized("nonce_invalid", "Sign-in nonce already used.");
    throw err;
  }
}

export async function google(ctx: Ctx): Promise<Response> {
  const body = v.object(ctx.body);
  const idToken = v.text(body.idToken, "idToken", 1, 8192);
  const nonceText = v.text(body.nonce, "nonce", 1, 200);
  v.uuid(body.installationId, "installationId");
  let claims: GoogleClaims;
  try {
    claims = await verifyGoogleIdToken(idToken, ctx.env.GOOGLE_WEB_CLIENT_ID, ctx.env.GOOGLE_ANDROID_CLIENT_IDS ?? "");
  } catch {
    throw unauthorized("google_token_invalid", "Google ID token rejected.");
  }
  if (claims.nonce !== nonceText || !(await nonceValid(secret(ctx), nonceText, ctx.now))) {
    throw unauthorized("nonce_invalid", "Sign-in nonce rejected.");
  }
  // Email and picture are not read or stored.
  const name = v.cleanName(claims.name, v.cleanName(claims.given_name, "Friend"));
  return startSession(ctx, "google_sub", claims.sub, name, await sha256Hex(nonceText));
}

/** Development only: the router exposes this route only when devSignInEnabled() holds. */
export async function dev(ctx: Ctx): Promise<Response> {
  const body = v.object(ctx.body);
  if (typeof body.devUser !== "string" || !/^[a-z0-9]{1,20}$/.test(body.devUser)) throw badRequest("devUser");
  const name = v.text(body.name, "name", 1, 60);
  v.uuid(body.installationId, "installationId");
  return startSession(ctx, "dev_key", body.devUser, name, null);
}

export async function refresh(ctx: Ctx): Promise<Response> {
  const body = v.object(ctx.body);
  const token = body.refreshToken;
  if (typeof token !== "string" || token.length > 200) throw badRequest("refreshToken");
  const hash = await sha256Hex(token);
  // Keyed by the token, not the IP: carrier NAT puts many phones behind one address (4.15).
  await enforce(ctx.env, "RL_REFRESH", hash);
  const invalid = () => unauthorized("refresh_invalid", "Refresh token unknown, expired or revoked.");
  if (!REFRESH_RE.test(token)) throw invalid();
  const live = (s: SessionRow | null): s is SessionRow => !!s && s.revoked_at === null && s.expires_at > ctx.now;

  for (let attempt = 0; attempt < 2; attempt++) {
    const next = newRefreshToken();
    const nextHash = await sha256Hex(next);
    const expiresAt = ctx.now + REFRESH_TTL_MS;
    const current = await findSessionByRefresh(ctx.db, hash);
    if (current) {
      if (!live(current)) throw invalid();
      if (await rotateSession(ctx.db, current, nextHash, ctx.now, expiresAt)) {
        return json(await issue(ctx, current.user_id, current.id, next, expiresAt));
      }
      continue; // another refresh rotated it meanwhile: look again (it is now the previous token)
    }
    const previous = await findSessionByPrevRefresh(ctx.db, hash);
    if (!previous) throw invalid(); // unknown: revokes nothing
    if (!live(previous)) throw invalid();
    if (previous.rotated_at !== null && ctx.now - previous.rotated_at <= 10 * MINUTE) {
      // The answer to the last refresh was lost on the way: rotate again from the previous token.
      if (await rerotateSession(ctx.db, previous, nextHash, expiresAt)) {
        return json(await issue(ctx, previous.user_id, previous.id, next, expiresAt));
      }
      continue;
    }
    await revokeSession(ctx.db, previous.id, ctx.now); // reuse outside the window: treated as theft
    throw invalid();
  }
  throw invalid();
}

export async function signout(ctx: Ctx): Promise<Response> {
  await signOut(ctx.db, ctx.user!.sid, me(ctx), ctx.installationId, ctx.now);
  return noContent();
}

/** Bearer check for every signed-in route: signature, iss, aud, exp, then the session row (1 row). */
export async function authenticate(
  ctx: Ctx,
  verify: (token: string) => Promise<{ userId: string; sessionId: string; expiresAt: number }>,
) {
  const header = ctx.req.headers.get("Authorization") ?? "";
  const m = /^Bearer ([A-Za-z0-9._-]{10,2048})$/.exec(header);
  if (!m) throw unauthorized();
  const claims = await verify(m[1]);
  const session = await getSession(ctx.db, claims.sessionId);
  if (!session || session.user_id !== claims.userId || session.revoked_at !== null || session.expires_at <= ctx.now) {
    throw unauthorized();
  }
  return { id: claims.userId, sid: claims.sessionId, exp: claims.expiresAt };
}

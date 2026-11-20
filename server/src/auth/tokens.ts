// Access tokens, refresh tokens and sign-in nonces (docs/server.md 4.4).
import { SignJWT, errors, jwtVerify } from "jose";
import { base64url, concatBytes, fromBase64url, hmacSign, hmacVerify, randomBytes, utf8 } from "../crypto";
import { unauthorized } from "../http";
import { DAY, MINUTE } from "../time";

export const ACCESS_TTL_S = 86_400;
export const REFRESH_TTL_MS = 60 * DAY;
export const NONCE_TTL_MS = 5 * MINUTE;
const ISS = "xpense-api";
const AUD = "xpense-app";

const keyFor = (secret: string) => utf8(secret);

export async function signAccessToken(secret: string, userId: string, sessionId: string, nowMs: number) {
  const iat = Math.floor(nowMs / 1000);
  const exp = iat + ACCESS_TTL_S;
  const token = await new SignJWT({ sid: sessionId })
    .setProtectedHeader({ alg: "HS256", typ: "JWT" })
    .setIssuer(ISS)
    .setAudience(AUD)
    .setSubject(userId)
    .setIssuedAt(iat)
    .setExpirationTime(exp)
    .sign(keyFor(secret));
  return { token, expiresAt: exp * 1000 };
}

/** Checks signature, iss, aud and exp. The caller then checks the session row. `expiresAt` is in epoch millis. */
export async function verifyAccessToken(
  secret: string,
  token: string,
): Promise<{ userId: string; sessionId: string; expiresAt: number }> {
  try {
    const { payload } = await jwtVerify(token, keyFor(secret), { issuer: ISS, audience: AUD, algorithms: ["HS256"] });
    if (typeof payload.sub !== "string" || typeof payload.sid !== "string" || typeof payload.exp !== "number") throw unauthorized();
    return { userId: payload.sub, sessionId: payload.sid, expiresAt: payload.exp * 1000 };
  } catch (err) {
    if (err instanceof errors.JWTExpired) throw unauthorized("token_expired", "Access token expired.");
    throw unauthorized();
  }
}

/** 32 random bytes, base64url (43 characters). */
export function newRefreshToken(): string {
  return base64url(randomBytes(32));
}

export const REFRESH_RE = /^[A-Za-z0-9_-]{43}$/;

function u64(n: number): Uint8Array {
  const out = new Uint8Array(8);
  new DataView(out.buffer).setBigUint64(0, BigInt(n));
  return out;
}

/** Stateless nonce: base64url(issuedAtMillis || 16 random bytes || HMAC-SHA256(secret, "nonce" || issuedAt || random)). */
export async function newNonce(secret: string, nowMs: number): Promise<{ nonce: string; expiresAt: number }> {
  const at = u64(nowMs);
  const rnd = randomBytes(16);
  const mac = await hmacSign(secret, concatBytes(utf8("nonce"), at, rnd));
  return { nonce: base64url(concatBytes(at, rnd, mac)), expiresAt: nowMs + NONCE_TTL_MS };
}

/** True when the nonce was made by this server less than 5 minutes ago. Single use is enforced by sessions.nonce_hash. */
export async function nonceValid(secret: string, nonce: string, nowMs: number): Promise<boolean> {
  if (nonce.length > 100) return false;
  const bytes = fromBase64url(nonce);
  if (!bytes || bytes.length !== 56) return false;
  const at = bytes.slice(0, 8);
  const rnd = bytes.slice(8, 24);
  const mac = bytes.slice(24);
  if (!(await hmacVerify(secret, concatBytes(utf8("nonce"), at, rnd), mac))) return false;
  const issued = Number(new DataView(at.buffer).getBigUint64(0));
  return issued <= nowMs + MINUTE && nowMs - issued <= NONCE_TTL_MS;
}

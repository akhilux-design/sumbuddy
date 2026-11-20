// Auth (docs/server.md 4.4): Google ID token with a locally made RSA key and an intercepted JWKS fetch (the URL in
// code stays Google's), nonces, refresh rotation, sign-out, sessions, rate limits.
import { SignJWT, exportJWK, generateKeyPair } from "jose";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { GOOGLE_JWKS_URL, resetGoogleKeysForTests } from "../src/auth/google";
import { signAccessToken } from "../src/auth/tokens";
import { allowed } from "../src/ratelimit";
import { testHooks } from "../src/testing";
import { call, env, rows, signIn, uuid } from "./helpers";

const AUD = env.GOOGLE_WEB_CLIENT_ID; // the test value in vitest.config.ts bindings, not the one in wrangler.toml
let privateKey: CryptoKey;
let jwk: Record<string, unknown>;
let jwksFetches = 0;

beforeAll(async () => {
  const pair = await generateKeyPair("RS256", { extractable: true });
  privateKey = pair.privateKey as CryptoKey;
  jwk = { ...(await exportJWK(pair.publicKey)), kid: "test-key-1", alg: "RS256", use: "sig" };
});

beforeEach(() => {
  resetGoogleKeysForTests();
  jwksFetches = 0;
  const real = globalThis.fetch;
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
    if (url === GOOGLE_JWKS_URL) {
      jwksFetches++;
      return Response.json({ keys: [jwk] });
    }
    return real(input as RequestInfo, init);
  });
});

afterEach(() => vi.restoreAllMocks());

async function idToken(claims: Record<string, unknown>, opts: { key?: CryptoKey; kid?: string; exp?: number } = {}) {
  const now = Math.floor(Date.now() / 1000);
  return new SignJWT({ name: "Arjun Mehta", email: "a@example.com", picture: "https://x/y.png", ...claims })
    .setProtectedHeader({ alg: "RS256", kid: opts.kid ?? "test-key-1", typ: "JWT" })
    .setIssuer((claims.iss as string) ?? "https://accounts.google.com")
    .setAudience((claims.aud as string) ?? AUD)
    .setSubject((claims.sub as string) ?? "google-sub-" + uuid())
    .setIssuedAt(now - 10)
    .setExpirationTime(opts.exp ?? now + 3600)
    .sign(opts.key ?? privateKey);
}

async function nonce(): Promise<string> {
  const r = await call("POST", "/v1/auth/nonce");
  expect(r.status).toBe(200);
  expect(r.body.expiresAt).toBeGreaterThan(Date.now());
  return r.body.nonce;
}

async function google(token: string, n: string, extraEnv = {}) {
  return call("POST", "/v1/auth/google", { body: { idToken: token, nonce: n, installationId: uuid() }, env: extraEnv });
}

describe("POST /v1/auth/google", () => {
  it("signs in a new user, then the same Google account again", async () => {
    const sub = "google-sub-" + uuid();
    const n1 = await nonce();
    const r1 = await google(await idToken({ sub, nonce: n1 }), n1);
    expect(r1.status).toBe(200);
    expect(r1.body.isNew).toBe(true);
    expect(r1.body.user).toEqual({ id: r1.body.user.id, name: "Arjun Mehta", phone: null, upiId: null });
    expect(r1.body.accessExpiresAt).toBeGreaterThan(Date.now() + 86_000_000);
    expect(r1.body.refreshExpiresAt).toBeGreaterThan(Date.now() + 59 * 86_400_000);
    // Email and picture are not stored.
    const stored = await rows("SELECT * FROM users WHERE id = ?", r1.body.user.id);
    expect(JSON.stringify(stored)).not.toContain("a@example.com");
    expect(stored[0].google_sub).toBe(sub);
    expect(stored[0].dev_key).toBeNull();

    const n2 = await nonce();
    const r2 = await google(await idToken({ sub, nonce: n2 }), n2);
    expect(r2.status).toBe(200);
    expect(r2.body.isNew).toBe(false);
    expect(r2.body.user.id).toBe(r1.body.user.id);
    expect(jwksFetches).toBe(1); // the key set is cached

    const me = await call("GET", "/v1/me", { token: r2.body.accessToken });
    expect(me.body.id).toBe(r1.body.user.id);
  });

  it("takes given_name, else Friend, trimmed to 60 characters", async () => {
    let n = await nonce();
    let r = await google(await idToken({ nonce: n, name: undefined, given_name: "Priya" }), n);
    expect(r.body.user.name).toBe("Priya");
    n = await nonce();
    r = await google(await idToken({ nonce: n, name: undefined }), n);
    expect(r.body.user.name).toBe("Friend");
    n = await nonce();
    r = await google(await idToken({ nonce: n, name: "x".repeat(80) }), n);
    expect(r.body.user.name).toHaveLength(60);
  });

  it("a nonce signs in once", async () => {
    const n = await nonce();
    const token = await idToken({ nonce: n });
    expect((await google(token, n)).status).toBe(200);
    const again = await google(token, n);
    expect(again.status).toBe(401);
    expect(again.body.error.code).toBe("nonce_invalid");
  });

  it("rejects a nonce that does not match, is forged or is too old", async () => {
    const n = await nonce();
    const mismatch = await google(await idToken({ nonce: "other" }), n);
    expect(mismatch.body.error.code).toBe("nonce_invalid");
    const forged = "A".repeat(75);
    expect((await google(await idToken({ nonce: forged }), forged)).body.error.code).toBe("nonce_invalid");
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(Date.now() + 6 * 60_000);
    const old = await google(await idToken({ nonce: n }), n);
    vi.useRealTimers();
    expect(old.body.error.code).toBe("nonce_invalid");
  });

  it("rejects wrong audience, issuer, signature, expiry and azp", async () => {
    const other = await generateKeyPair("RS256");
    const cases = [
      await idToken({ aud: "someone-else.apps.googleusercontent.com" }),
      await idToken({ iss: "https://evil.example.com" }),
      await idToken({}, { key: other.privateKey as CryptoKey }),
      await idToken({}, { exp: Math.floor(Date.now() / 1000) - 600 }),
      "not-a-jwt",
    ];
    for (const t of cases) {
      const n = await nonce();
      const r = await google(t, n);
      expect(r.status).toBe(401);
      expect(r.body.error.code).toBe("google_token_invalid");
    }
    const n = await nonce();
    const azp = await google(await idToken({ nonce: n, azp: "wrong.apps.googleusercontent.com" }), n, {
      GOOGLE_ANDROID_CLIENT_IDS: "android-1.apps.googleusercontent.com, android-2.apps.googleusercontent.com",
    });
    expect(azp.body.error.code).toBe("google_token_invalid");
    const n2 = await nonce();
    const ok = await google(await idToken({ nonce: n2, azp: "android-2.apps.googleusercontent.com" }), n2, {
      GOOGLE_ANDROID_CLIENT_IDS: "android-1.apps.googleusercontent.com, android-2.apps.googleusercontent.com",
    });
    expect(ok.status).toBe(200);
  });

  it("validates the body", async () => {
    const r = await call("POST", "/v1/auth/google", { body: { idToken: "x", nonce: "y", installationId: "nope" } });
    expect(r.status).toBe(400);
    expect(r.body.error.field).toBe("installationId");
  });
});

describe("POST /v1/auth/refresh", () => {
  it("rotates; the previous token within 10 minutes rotates again; later it is theft", async () => {
    const u = await signIn();
    const r1 = await call("POST", "/v1/auth/refresh", { body: { refreshToken: u.refreshToken } });
    expect(r1.status).toBe(200);
    expect(r1.body.refreshToken).not.toBe(u.refreshToken);
    expect((await call("GET", "/v1/me", { token: r1.body.accessToken })).status).toBe(200);

    // The answer was lost: the phone sends the old token again.
    const lost = await call("POST", "/v1/auth/refresh", { body: { refreshToken: u.refreshToken } });
    expect(lost.status).toBe(200);
    // The newest token works.
    const r2 = await call("POST", "/v1/auth/refresh", { body: { refreshToken: lost.body.refreshToken } });
    expect(r2.status).toBe(200);

    // The previous token, more than 10 minutes after its rotation, revokes the session.
    await env.DB.prepare("UPDATE sessions SET rotated_at = rotated_at - 11 * 60000 WHERE user_id = ?").bind(u.id).run();
    const theft = await call("POST", "/v1/auth/refresh", { body: { refreshToken: lost.body.refreshToken } });
    expect(theft.status).toBe(401);
    expect(theft.body.error.code).toBe("refresh_invalid");
    const after = await call("POST", "/v1/auth/refresh", { body: { refreshToken: r2.body.refreshToken } });
    expect(after.body.error.code).toBe("refresh_invalid");
    expect((await call("GET", "/v1/me", { token: r2.body.accessToken })).body.error.code).toBe("unauthorized");
  });

  it("an unknown token revokes nothing", async () => {
    const u = await signIn();
    const r = await call("POST", "/v1/auth/refresh", { body: { refreshToken: "A".repeat(43) } });
    expect(r.body.error.code).toBe("refresh_invalid");
    expect((await call("GET", "/v1/me", { token: u.token })).status).toBe(200);
  });

  it("is limited per token, not per IP", async () => {
    const u = await signIn();
    const seen: string[] = [];
    testHooks.limiter = async (name, key) => {
      seen.push(name + ":" + key);
      return name !== "RL_REFRESH";
    };
    const r = await call("POST", "/v1/auth/refresh", { body: { refreshToken: u.refreshToken }, headers: { "CF-Connecting-IP": "203.0.113.9" } });
    expect(r.status).toBe(429);
    expect(r.headers.get("Retry-After")).toBe("60");
    expect(seen.some((s) => s.startsWith("RL_REFRESH:") && /^[0-9a-f]{64}$/.test(s.split(":")[1]))).toBe(true);
    expect(seen.some((s) => s.includes("203.0.113.9"))).toBe(false);
  });
});

describe("sessions", () => {
  it("sign-out revokes the session at once and drops this installation's device", async () => {
    const u = await signIn();
    await call("PUT", `/v1/devices/${u.installationId}`, { token: u.token, body: { fcmToken: "tok-" + uuid() } });
    const r = await call("POST", "/v1/auth/signout", { token: u.token, headers: { "X-Installation-Id": u.installationId } });
    expect(r.status).toBe(204);
    expect((await call("GET", "/v1/me", { token: u.token })).body.error.code).toBe("unauthorized");
    expect(await rows("SELECT * FROM devices WHERE installation_id = ?", u.installationId)).toHaveLength(0);
    expect((await call("POST", "/v1/auth/refresh", { body: { refreshToken: u.refreshToken } })).body.error.code).toBe("refresh_invalid");
  });

  it("expired access token gives token_expired; bad ones unauthorized", async () => {
    const u = await signIn();
    const session = (await rows("SELECT id FROM sessions WHERE user_id = ?", u.id))[0].id;
    const old = await signAccessToken(env.SESSION_SECRET!, u.id, session, Date.now() - 2 * 86_400_000);
    expect((await call("GET", "/v1/me", { token: old.token })).body.error.code).toBe("token_expired");
    const forged = await signAccessToken("another-secret-0123456789abcdef0123", u.id, session, Date.now());
    expect((await call("GET", "/v1/me", { token: forged.token })).body.error.code).toBe("unauthorized");
    expect((await call("GET", "/v1/me")).status).toBe(401);
    const otherUser = await signAccessToken(env.SESSION_SECRET!, uuid(), session, Date.now());
    expect((await call("GET", "/v1/me", { token: otherUser.token })).body.error.code).toBe("unauthorized");
  });

  it("answers 500 to everything without SESSION_SECRET", async () => {
    const r = await call("GET", "/v1/health", { env: { SESSION_SECRET: undefined } });
    expect(r.status).toBe(500);
    expect(r.body.error.code).toBe("internal");
  });
});

describe("rate limits (4.15)", () => {
  it("auth routes are limited per IP with 429 rate_limited and Retry-After", async () => {
    testHooks.limiter = async (name) => name !== "RL_AUTH";
    const r = await call("POST", "/v1/auth/nonce");
    expect(r.status).toBe(429);
    expect(r.body.error.code).toBe("rate_limited");
    expect(r.headers.get("Retry-After")).toBe("60");
  });

  it("signed-in routes are limited per user; mutations also by RL_WRITE", async () => {
    const u = await signIn();
    const seen: string[] = [];
    testHooks.limiter = async (name, key) => {
      seen.push(name + ":" + key);
      return name !== "RL_WRITE";
    };
    expect((await call("GET", "/v1/me", { token: u.token })).status).toBe(200);
    const w = await call("PATCH", "/v1/me", { token: u.token, body: { name: "New" } });
    expect(w.status).toBe(429);
    expect(seen).toContain("RL_USER:" + u.id);
    expect(seen).toContain("RL_WRITE:" + u.id);
  });

  it("falls back to an in-memory window with the same numbers when no binding exists", async () => {
    testHooks.limiter = undefined;
    const noBindings = { ...env, RL_AUTH: undefined } as typeof env;
    const key = "ip-" + uuid();
    const results: boolean[] = [];
    for (let i = 0; i < 22; i++) results.push(await allowed(noBindings, "RL_AUTH", key));
    expect(results.filter(Boolean)).toHaveLength(20);
  });
});

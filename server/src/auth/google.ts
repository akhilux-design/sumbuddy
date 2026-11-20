// Google ID token verification (docs/server.md 4.4). The JWKS URL is fixed in code, never config. jose's remote key
// set caches the keys in isolate memory and refetches only for an unknown `kid` (after a cooldown) or when stale.
import { createRemoteJWKSet, jwtVerify, type JWTPayload } from "jose";

export const GOOGLE_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
const GOOGLE_ISSUERS = ["accounts.google.com", "https://accounts.google.com"];

let jwks: ReturnType<typeof createRemoteJWKSet> | null = null;

function googleKeys() {
  jwks ??= createRemoteJWKSet(new URL(GOOGLE_JWKS_URL), {
    cacheMaxAge: 6 * 60 * 60 * 1000,
    cooldownDuration: 30_000,
    timeoutDuration: 5_000,
  });
  return jwks;
}

/** Tests only: forget the cached key set. */
export function resetGoogleKeysForTests(): void {
  jwks = null;
}

export interface GoogleClaims extends JWTPayload {
  sub: string;
  nonce?: string;
  azp?: string;
  name?: string;
  given_name?: string;
}

/** Throws on any failure (signature, issuer, audience, expiry, algorithm). */
export async function verifyGoogleIdToken(
  idToken: string,
  webClientId: string,
  androidClientIds: string,
): Promise<GoogleClaims> {
  const { payload } = await jwtVerify(idToken, googleKeys(), {
    issuer: GOOGLE_ISSUERS,
    audience: webClientId,
    algorithms: ["RS256"],
    clockTolerance: 60,
  });
  if (typeof payload.sub !== "string" || payload.sub.length === 0 || payload.sub.length > 255) {
    throw new Error("sub");
  }
  const allowed = androidClientIds
    .split(",")
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
  if (allowed.length > 0 && (typeof payload.azp !== "string" || !allowed.includes(payload.azp))) {
    throw new Error("azp");
  }
  return payload as GoogleClaims;
}

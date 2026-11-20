// FCM HTTP v1 sender (docs/server.md 4.14). Data-only messages: ids and a version, never names, amounts or text.
// The OAuth access token comes from a service-account JWT (RS256, signed with WebCrypto through jose) and is cached
// in isolate memory until 5 minutes before it expires.
import { SignJWT, importPKCS8 } from "jose";
import { logError } from "../log";

const TOKEN_URL = "https://oauth2.googleapis.com/token";
const SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
export const MAX_SENDS = 40;

export interface PushConfig {
  projectId: string;
  serviceAccountJson: string;
}

export interface PushMessage {
  token: string;
  data: Record<string, string>;
  priority: "HIGH" | "NORMAL";
  collapseKey?: string;
}

let cachedToken: { email: string; token: string; expiresAt: number } | null = null;

/** Tests only. */
export function resetPushTokenForTests(): void {
  cachedToken = null;
}

/** Push is off (a no-op) when either value is empty: the local default. */
export function pushConfig(env: { FCM_PROJECT_ID?: string; FCM_SERVICE_ACCOUNT_JSON?: string }): PushConfig | null {
  const projectId = (env.FCM_PROJECT_ID ?? "").trim();
  const sa = (env.FCM_SERVICE_ACCOUNT_JSON ?? "").trim();
  return projectId && sa ? { projectId, serviceAccountJson: sa } : null;
}

async function accessToken(cfg: PushConfig, now: number): Promise<string> {
  const sa = JSON.parse(cfg.serviceAccountJson) as { client_email?: string; private_key?: string };
  if (!sa.client_email || !sa.private_key) throw new Error("service account");
  if (cachedToken && cachedToken.email === sa.client_email && cachedToken.expiresAt - 5 * 60_000 > now) {
    return cachedToken.token;
  }
  const key = await importPKCS8(sa.private_key, "RS256");
  const iat = Math.floor(now / 1000);
  const assertion = await new SignJWT({ scope: SCOPE })
    .setProtectedHeader({ alg: "RS256", typ: "JWT" })
    .setIssuer(sa.client_email)
    .setAudience(TOKEN_URL)
    .setIssuedAt(iat)
    .setExpirationTime(iat + 3600)
    .sign(key);
  const res = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }).toString(),
  });
  if (!res.ok) throw new Error("oauth " + res.status);
  const body = (await res.json()) as { access_token: string; expires_in: number };
  cachedToken = { email: sa.client_email, token: body.access_token, expiresAt: now + body.expires_in * 1000 };
  return body.access_token;
}

/**
 * Sends each message (at most 40; the rest are skipped and those phones catch up on their next sync). Returns the
 * device tokens FCM reported as no longer registered, for the caller to delete.
 */
export async function sendPush(cfg: PushConfig, messages: PushMessage[], now = Date.now()): Promise<string[]> {
  if (messages.length === 0) return [];
  const token = await accessToken(cfg, now);
  const url = `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(cfg.projectId)}/messages:send`;
  const gone: string[] = [];
  await Promise.all(
    messages.slice(0, MAX_SENDS).map(async (m) => {
      const android: Record<string, string> = { priority: m.priority, ttl: "86400s" };
      if (m.collapseKey) android.collapse_key = m.collapseKey;
      try {
        const res = await fetch(url, {
          method: "POST",
          headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
          body: JSON.stringify({ message: { token: m.token, data: m.data, android } }),
        });
        if (res.ok) return;
        const text = await res.text();
        if (res.status === 404 || text.includes("UNREGISTERED")) gone.push(m.token);
      } catch (err) {
        logError("fcm", err);
      }
    }),
  );
  return gone;
}

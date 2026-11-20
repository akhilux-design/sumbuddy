import { hex } from "./crypto";

export const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
export const SHARE_TOKEN_RE = /^[A-Za-z0-9_-]{24}$/;

/** Namespace for direct-split ids (docs/server.md 4.6). */
export const DIRECT_NS = "6f1c2a4e-3b8d-4c1e-9a57-2d0e8b6f4a13";

export function isUuid(v: unknown): v is string {
  return typeof v === "string" && UUID_RE.test(v);
}

const UUID_V4_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

/**
 * A random (version 4) UUID. Group and member ids made by phones must be v4, except the deterministic v5 ids of a
 * direct split between two app users, which the server recomputes and checks. Otherwise anyone who can see two user
 * ids could take their direct split's id (or a member id in it) first (docs/server.md 4.6).
 */
export function isUuidV4(v: string): boolean {
  return UUID_V4_RE.test(v);
}

export function uuidv4(): string {
  return crypto.randomUUID();
}

function uuidBytes(uuid: string): Uint8Array {
  const h = uuid.replace(/-/g, "");
  const out = new Uint8Array(16);
  for (let i = 0; i < 16; i++) out[i] = parseInt(h.slice(i * 2, i * 2 + 2), 16);
  return out;
}

function formatUuid(bytes: Uint8Array): string {
  const h = hex(bytes);
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20, 32)}`;
}

/** RFC 4122 version 5 (SHA-1) UUID. */
export async function uuidv5(namespace: string, name: string): Promise<string> {
  const ns = uuidBytes(namespace);
  const nameBytes = new TextEncoder().encode(name);
  const data = new Uint8Array(ns.length + nameBytes.length);
  data.set(ns, 0);
  data.set(nameBytes, ns.length);
  const hash = new Uint8Array(await crypto.subtle.digest("SHA-1", data)).slice(0, 16);
  hash[6] = (hash[6] & 0x0f) | 0x50;
  hash[8] = (hash[8] & 0x3f) | 0x80;
  return formatUuid(hash);
}

export async function directGroupId(userA: string, userB: string): Promise<string> {
  const [lo, hi] = userA < userB ? [userA, userB] : [userB, userA];
  return uuidv5(DIRECT_NS, `direct:${lo}:${hi}`);
}

export async function directMemberId(groupId: string, userId: string): Promise<string> {
  return uuidv5(DIRECT_NS, `member:${groupId}:${userId}`);
}

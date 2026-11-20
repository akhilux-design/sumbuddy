// Rate limits (docs/server.md 4.15). Uses the Workers Rate Limiting binding when present; otherwise an in-memory
// fixed window per isolate with the same numbers. Keys are never stored beyond the window or logged.
import type { Env } from "./env";
import { ApiError } from "./http";
import { testHooks } from "./testing";

export type LimitName = "RL_AUTH" | "RL_REFRESH" | "RL_PUBLIC" | "RL_USER" | "RL_WRITE";

const LIMITS: Record<LimitName, number> = { RL_AUTH: 20, RL_REFRESH: 5, RL_PUBLIC: 30, RL_USER: 120, RL_WRITE: 30 };
const WINDOW_MS = 60_000;
const MAX_KEYS = 10_000;

const windows = new Map<string, { start: number; count: number }>();

function memoryLimit(name: LimitName, key: string, now: number): boolean {
  const k = name + ":" + key;
  const w = windows.get(k);
  if (!w || now - w.start >= WINDOW_MS) {
    if (windows.size >= MAX_KEYS) {
      for (const [old, v] of windows) if (now - v.start >= WINDOW_MS) windows.delete(old);
      if (windows.size >= MAX_KEYS) windows.clear();
    }
    windows.set(k, { start: now, count: 1 });
    return true;
  }
  w.count++;
  return w.count <= LIMITS[name];
}

export async function allowed(env: Env, name: LimitName, key: string): Promise<boolean> {
  if (testHooks.limiter) return testHooks.limiter(name, key);
  const binding = env[name];
  if (binding && typeof binding.limit === "function") {
    try {
      return (await binding.limit({ key })).success;
    } catch {
      // A binding that fails must not take the API down; fall back to the local window.
    }
  }
  return memoryLimit(name, key, Date.now());
}

/** The answer of every limiter: `429 rate_limited` with `Retry-After: 60` (4.15). */
export const rateLimited = () => new ApiError(429, "rate_limited", "Too many requests.", undefined, { "Retry-After": "60" });

/**
 * Takes `cost` hits from the limiter, one at a time, and stops at the first refusal. The binding has no weight, so a
 * request that counts several times (an import, once per 10 items) calls it that many times. A cost below 1 takes
 * nothing.
 */
export async function enforce(env: Env, name: LimitName, key: string, cost = 1): Promise<void> {
  for (let i = 0; i < cost; i++) {
    if (!(await allowed(env, name, key))) throw rateLimited();
  }
}

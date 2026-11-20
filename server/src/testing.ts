// Hooks for tests only. Nothing in production sets them; each is a no-op while undefined.
import type { Db } from "./db/db";

export const testHooks: {
  /** Runs just before a mutation batch, after the handler's own checks (docs/server.md 3.1 guard tests). */
  beforeBatch?: (label: string) => Promise<void>;
  /** Receives each request's database wrapper, to count queries and rows read. */
  onDb?: (db: Db) => void;
  /** Replaces the rate limiter (docs/server.md 4.15: tests inject a fake limiter). */
  limiter?: (name: string, key: string) => Promise<boolean>;
} = {};

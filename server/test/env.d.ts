import type { Env as AppEnv } from "../src/env";
import type { D1Migration } from "cloudflare:test";

declare global {
  namespace Cloudflare {
    interface Env extends AppEnv {
      /** An empty database for test/migration.test.ts (vitest.config.ts). */
      MIGRATION_DB: D1Database;
      TEST_MIGRATIONS: D1Migration[];
      WRANGLER_TOML: string;
    }
  }
}

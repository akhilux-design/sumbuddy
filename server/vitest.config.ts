import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { cloudflareTest, readD1Migrations } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

const here = dirname(fileURLToPath(import.meta.url));

export default defineConfig(async () => {
  const migrations = await readD1Migrations(join(here, "migrations"));
  return {
    plugins: [
      cloudflareTest({
        wrangler: { configPath: "./wrangler.toml" },
        miniflare: {
          // A second, empty database for test/migration.test.ts: it applies 0001 and 0002, fills them, then the rest.
          d1Databases: ["MIGRATION_DB"],
          // Test-only values. Tests that need production behaviour pass their own env to the handler.
          // These win over the [vars] in wrangler.toml, so the suite passes the same before and after the owner fills
          // in the real values (docs/server-setup.md 2-4). Tests read them from env, never from wrangler.toml.
          bindings: {
            ENVIRONMENT: "development",
            SESSION_SECRET: "test-only-session-secret-0123456789abcdef",
            GOOGLE_WEB_CLIENT_ID: "test-web.apps.googleusercontent.com",
            GOOGLE_ANDROID_CLIENT_IDS: "", // empty: azp is not checked unless a test passes its own list
            FCM_PROJECT_ID: "", // empty: push is off unless a test passes its own env
            LINK_HOST: "xpense.app",
            WEB_ORIGIN: "https://xpense.app",
            TEST_MIGRATIONS: migrations,
            WRANGLER_TOML: readFileSync(join(here, "wrangler.toml"), "utf8"),
          },
        },
      }),
    ],
    test: {
      setupFiles: ["./test/setup.ts"],
      testTimeout: 30_000,
    },
  };
});

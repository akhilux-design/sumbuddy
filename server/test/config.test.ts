// Production config guard (docs/server.md 4.4, 10, 11).
import { describe, expect, it } from "vitest";
import { checkProdConfig } from "../scripts/prod-config-rules.mjs";
import { checkRemoteMigrations } from "../scripts/remote-migration-rules.mjs";
import { call, env } from "./helpers";

// The file as it is now: with the placeholders, or with the owner's real values. Every test here passes with both.
const committed = env.WRANGLER_TOML;

const RATE_LIMITS = ["RL_AUTH", "RL_REFRESH", "RL_PUBLIC", "RL_USER", "RL_WRITE"];

const PLACEHOLDERS: Record<string, string> = {
  GOOGLE_WEB_CLIENT_ID: "REPLACE_ME.apps.googleusercontent.com",
  database_id: "00000000-0000-0000-0000-000000000000",
  LINK_HOST: "xpense.app",
  WEB_ORIGIN: "https://xpense.app",
};

const FILLED: Record<string, string> = {
  GOOGLE_WEB_CLIENT_ID: "123-abc.apps.googleusercontent.com",
  database_id: "6b5c0a62-0d1e-4c2b-9e8f-1a2b3c4d5e6f",
  LINK_HOST: "pay-split.pages.dev",
  WEB_ORIGIN: "https://pay-split.pages.dev",
};

/** A copy of the file with these keys set, whatever they held before. */
function withValues(text: string, values: Record<string, string>): string {
  let out = text;
  for (const [key, value] of Object.entries(values)) {
    const line = new RegExp(`^(\\s*${key}\\s*=\\s*)"[^"]*"`, "m");
    if (!line.test(out)) throw new Error(`wrangler.toml has no ${key} line`);
    out = out.replace(line, (_, head: string) => `${head}"${value}"`);
  }
  return out;
}

describe("wrangler.toml", () => {
  it("sets ENVIRONMENT production, has no development value and no [env.*] section", () => {
    expect(committed).toMatch(/^ENVIRONMENT = "production"$/m);
    expect(committed.toLowerCase()).not.toContain("development");
    expect(committed).not.toMatch(/^\s*\[env\./m);
  });

  it("turns invocation logs off and declares the bindings", () => {
    expect(committed).toMatch(/\[observability\.logs\]\s*\ninvocation_logs = false/);
    expect(committed).toContain('new_sqlite_classes = ["GroupRoom"]');
    // All five rate limits, or none: the fallback when Free refuses them (docs/server-setup.md 5).
    // Count only lines that are not comments, so blocks commented out count as deleted.
    const declared = RATE_LIMITS.filter((rl) => new RegExp(`^\\s*name\\s*=\\s*"${rl}"`, "m").test(committed));
    const blocks = committed.match(/^\s*\[\[ratelimits\]\]/gm) ?? [];
    const why = "all five [[ratelimits]] blocks or none (docs/server-setup.md 5)";
    expect([0, RATE_LIMITS.length], why).toContain(declared.length);
    expect(blocks, why).toHaveLength(declared.length);
  });

  it("passes the deploy check apart from the placeholders the owner fills in later", () => {
    expect(checkProdConfig(committed, { allowPlaceholders: true })).toEqual([]);
    // Placeholders may be left, or none at all once the owner is done; nothing else.
    const strict: string[] = checkProdConfig(committed);
    expect(strict.every((p) => /placeholder/.test(p))).toBe(true);
  });

  it("check-prod-config finds each placeholder", () => {
    const unfilled = withValues(committed, PLACEHOLDERS);
    expect(checkProdConfig(unfilled, { allowPlaceholders: true })).toEqual([]);
    expect(checkProdConfig(unfilled)).toHaveLength(3); // LINK_HOST and WEB_ORIGIN share one message
    for (const [key, value] of Object.entries(PLACEHOLDERS)) {
      const one: string[] = checkProdConfig(withValues(committed, { ...FILLED, [key]: value }));
      expect(one).toHaveLength(1);
      expect(one[0]).toMatch(/placeholder/);
    }
  });

  it("the tests run on the vitest.config.ts values, not the ones in wrangler.toml", () => {
    expect(env.GOOGLE_WEB_CLIENT_ID).toBe("test-web.apps.googleusercontent.com");
    expect(env.GOOGLE_ANDROID_CLIENT_IDS).toBe("");
    expect(env.FCM_PROJECT_ID).toBe("");
    expect(env.LINK_HOST).toBe("xpense.app");
    expect(env.WEB_ORIGIN).toBe("https://xpense.app");
  });

  it("check-prod-config fails without invocation_logs = false", () => {
    const without = committed.replace(/invocation_logs = false[^\n]*\n/, "");
    expect(checkProdConfig(without, { allowPlaceholders: true }).join(" ")).toContain("invocation_logs");
    const on = committed.replace(/invocation_logs = false/, "invocation_logs = true");
    expect(checkProdConfig(on, { allowPlaceholders: true }).join(" ")).toContain("invocation_logs");
  });

  it("check-prod-config fails when development appears anywhere or an env section exists", () => {
    const dev = committed.replace('ENVIRONMENT = "production"', 'ENVIRONMENT = "development"');
    expect(checkProdConfig(dev, { allowPlaceholders: true }).length).toBeGreaterThan(0);
    const section = committed + '\n[env.staging]\nname = "x"\n';
    expect(checkProdConfig(section, { allowPlaceholders: true }).join(" ")).toContain("[env.*]");
  });

  it("filled-in config passes", () => {
    expect(checkProdConfig(withValues(committed, FILLED))).toEqual([]);
  });
});

describe("dev sign-in is impossible in production", () => {
  const body = { devUser: "alice", name: "Alice", installationId: crypto.randomUUID() };

  it("answers 404 with ENVIRONMENT=production, as for an unknown path", async () => {
    const r = await call("POST", "/v1/auth/dev", { body, env: { ENVIRONMENT: "production" } });
    expect(r.status).toBe(404);
    expect(r.body.error.code).toBe("not_found");
    const get = await call("GET", "/v1/auth/dev", { env: { ENVIRONMENT: "production" } });
    expect(get.status).toBe(404);
  });

  it("answers 404 from a non-local host even with ENVIRONMENT=development", async () => {
    const r = await call("POST", "/v1/auth/dev", { body, base: "https://xpense-api.example.workers.dev" });
    expect(r.status).toBe(404);
    expect(r.body.error.code).toBe("not_found");
  });

  it("answers 404 for any other ENVIRONMENT value", async () => {
    for (const value of ["", "Development", "dev", "staging"]) {
      const r = await call("POST", "/v1/auth/dev", { body, env: { ENVIRONMENT: value } });
      expect(r.status).toBe(404);
    }
  });

  it("works locally: localhost, 127.0.0.1, [::1] and the emulator's 10.0.2.2", async () => {
    for (const base of ["http://localhost:8787", "http://127.0.0.1:8787", "http://[::1]:8787", "http://10.0.2.2:8787"]) {
      const r = await call("POST", "/v1/auth/dev", { body: { ...body, devUser: "local" + base.length }, base });
      expect(r.status).toBe(200);
    }
  });
});

// The deploy refuses while the live database lacks a migration (scripts/check-remote-migrations.mjs).
describe("check-remote-migrations", () => {
  const banner = " ⛅️ wrangler 4.156.2\n───────────────────\n";

  it("lets the deploy go ahead only when wrangler says nothing is left to apply", () => {
    expect(checkRemoteMigrations(banner + "✅ No migrations to apply!\n", 0)).toEqual([]);
  });

  it("names a migration still to apply", () => {
    const listed = banner + "Migrations to be applied:\n┌────────────────────┐\n│ Name               │\n├────────────────────┤\n│ 0002_undo_join.sql │\n└────────────────────┘\n";
    const problems = checkRemoteMigrations(listed, 0);
    expect(problems).toHaveLength(1);
    expect(problems[0]).toContain("0002_undo_join.sql");
    expect(problems[0]).toContain("migrations apply xpense --remote");
  });

  it("refuses when wrangler failed or said something else", () => {
    expect(checkRemoteMigrations(banner + "✘ [ERROR] You are not logged in.\n", 1)).toHaveLength(1);
    expect(checkRemoteMigrations("", null)).toHaveLength(1);
    expect(checkRemoteMigrations(banner + "✅ No migrations to apply!\n", 1)).toHaveLength(1);
  });

  it("every migration file has a name the rule can read", () => {
    const files = env.TEST_MIGRATIONS.map((m) => m.name);
    expect(files.length).toBeGreaterThanOrEqual(3);
    for (const f of files) expect(f).toMatch(/^\d{4}_[A-Za-z0-9_-]+\.sql$/);
  });

  it("refuses the family Worker until 0003_family.sql is on the live database (docs/server.md 3, 4.18)", () => {
    expect(env.TEST_MIGRATIONS.map((m) => m.name)).toContain("0003_family.sql");
    const table = (names: string[]) =>
      banner + "Migrations to be applied:\n┌─────────────────┐\n│ Name            │\n├─────────────────┤\n" +
      names.map((n) => `│ ${n} │\n`).join("") + "└─────────────────┘\n";
    const only = checkRemoteMigrations(table(["0003_family.sql"]), 0);
    expect(only).toHaveLength(1);
    expect(only[0]).toContain("0003_family.sql");
    const both = checkRemoteMigrations(table(["0002_undo_join.sql", "0003_family.sql"]), 0);
    expect(both[0]).toContain("0002_undo_join.sql, 0003_family.sql");
  });
});

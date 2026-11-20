import { applyD1Migrations } from "cloudflare:test";
import { env } from "cloudflare:workers";
import { beforeEach } from "vitest";
import { testHooks } from "../src/testing";

await applyD1Migrations(env.DB, env.TEST_MIGRATIONS);

beforeEach(() => {
  // Tests inject a fake limiter (docs/server.md 4.15); one test replaces it to check the 429.
  testHooks.limiter = async () => true;
  testHooks.beforeBatch = undefined;
  testHooks.onDb = undefined;
});

// Runs before `wrangler deploy` (npm run deploy), after check-prod-config.mjs. Refuses while the live D1 database
// lacks a migration in migrations/: the Worker would read columns that are not there and every group route would
// answer 500 (docs/server.md "Build notes, undo-join" 1, docs/server-setup.md 4.2), or, without 0003_family.sql, write
// kind 'family' into a table whose CHECK refuses it (docs/server.md 3, "Build notes, family"). Every file in
// migrations/ counts, so a new one blocks the deploy until it is applied --remote. Only the owner runs this: it asks
// Cloudflare about the live database (a read; it changes nothing).
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { checkRemoteMigrations } from "./remote-migration-rules.mjs";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const run = spawnSync("npx", ["wrangler", "d1", "migrations", "list", "xpense", "--remote"], {
  cwd: root,
  encoding: "utf8",
  env: { ...process.env, CI: "1" },
});
const output = `${run.stdout ?? ""}\n${run.stderr ?? ""}`;
const problems = checkRemoteMigrations(output, run.status);
if (problems.length > 0) {
  for (const p of problems) console.error("check-remote-migrations: " + p);
  process.exit(1);
}
console.log("check-remote-migrations: ok");

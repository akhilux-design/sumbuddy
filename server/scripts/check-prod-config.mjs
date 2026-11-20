// Runs before `wrangler deploy` (npm run deploy). Fails when wrangler.toml could enable dev sign-in, would log
// request URLs, or still holds placeholders (docs/server.md 4.4, 10, 11).
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { checkProdConfig } from "./prod-config-rules.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const text = readFileSync(join(here, "..", "wrangler.toml"), "utf8");
const problems = checkProdConfig(text);
if (problems.length > 0) {
  for (const p of problems) console.error("check-prod-config: " + p);
  process.exit(1);
}
console.log("check-prod-config: ok");

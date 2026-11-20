// npm run preview:mock — every page state without the Worker. Builds dist/ with API_BASE=http://localhost:8789,
// starts the mock link API there and serves dist/ with `wrangler pages dev` on http://localhost:8788 (the same
// _headers and _redirects handling as Cloudflare Pages). Open the printed links; Ctrl+C stops both.

import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from '../build.mjs';
import { fixtures } from '../test/fixtures/links.mjs';
import { startMockApi } from './mock-api.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const web = join(here, '..');

await build({
  env: { ...process.env, API_BASE: 'http://localhost:8789', PLAY_STORE_URL: process.env.PLAY_STORE_URL ?? 'https://play.google.com/store/apps/details?id=com.expenses.app' },
});
const api = await startMockApi({ port: 8789 });

// wrangler is a devDependency here: run from server/node_modules, pages dev would pick up the Worker's config.
const local = join(web, 'node_modules', '.bin', 'wrangler');
const pages = spawn(existsSync(local) ? local : 'wrangler', ['pages', 'dev', 'dist', '--ip', '127.0.0.1', '--port', '8788'], {
  cwd: web,
  stdio: 'inherit',
  env: { ...process.env, WRANGLER_SEND_METRICS: 'false' },
});

console.log('\nPage states (open in a browser; use http://localhost, not 127.0.0.1, for CORS):');
for (const [name, fixture] of Object.entries(fixtures)) console.log(`  ${name.padEnd(16)} http://localhost:8788/pay/${fixture.token}`);
console.log(`  ${'badPath'.padEnd(16)} http://localhost:8788/pay/short\n`);

const stop = () => {
  pages.kill('SIGINT');
  api.close();
};
process.on('SIGINT', stop);
process.on('SIGTERM', stop);
pages.on('exit', (code) => {
  api.close();
  process.exit(code ?? 0);
});

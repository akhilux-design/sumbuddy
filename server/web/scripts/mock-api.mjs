// A stand-in for GET /v1/links/{token} (docs/server.md 4.10) that answers the fixtures in test/fixtures/links.mjs,
// with the same CORS and privacy headers as the Worker in development. For looking at every page state without the
// Worker: `npm run preview:mock`. Listens on 127.0.0.1 only.

import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import { fixtureByToken, fixtures } from '../test/fixtures/links.mjs';

const PAGE_ORIGIN = 'http://localhost:8788';

export function startMockApi({ port = 8789 } = {}) {
  const server = createServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    const match = /^\/v1\/links\/([A-Za-z0-9_-]{24})$/.exec(url.pathname);
    const headers = {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
    };
    if (match) {
      Object.assign(headers, {
        'Access-Control-Allow-Origin': PAGE_ORIGIN,
        Vary: 'Origin',
        'X-Robots-Tag': 'noindex, nofollow',
        'Referrer-Policy': 'no-referrer',
      });
    }
    if (match && req.method === 'OPTIONS') {
      res.writeHead(204, { ...headers, 'Access-Control-Allow-Methods': 'GET', 'Access-Control-Max-Age': '600' });
      res.end();
      return;
    }
    if (!match || req.method !== 'GET') {
      res.writeHead(404, headers);
      res.end(JSON.stringify({ error: { code: 'not_found', message: 'Not found' } }));
      return;
    }
    const fixture = fixtureByToken(match[1]) ?? fixtures.notWorking;
    if (fixture.delayMs) await new Promise((resolve) => setTimeout(resolve, fixture.delayMs));
    res.writeHead(fixture.status, headers);
    res.end(JSON.stringify(fixture.body));
  });
  return new Promise((resolve) => server.listen(port, '127.0.0.1', () => resolve(server)));
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  await startMockApi();
  console.log('Mock link API on http://127.0.0.1:8789');
}

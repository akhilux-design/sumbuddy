// npm run seed:local — fills the LOCAL Worker (server: `npm run dev`, http://localhost:8787, ENVIRONMENT=development)
// with groups through the development sign-in and the API of docs/server.md, makes one share link per page state,
// checks each link answer (state, headers, no phone numbers or ids) and prints the page addresses.
// Open them with the page served on http://localhost:8788 (`npm run preview`). Local only: it refuses other hosts.

import { randomBytes, randomUUID } from 'node:crypto';

const API = (process.env.API_BASE ?? 'http://localhost:8787').replace(/\/$/, '');
const PAGE = 'http://localhost:8788';
if (!['localhost', '127.0.0.1'].includes(new URL(API).hostname)) {
  console.error('seed-local.mjs only talks to a local Worker (API_BASE on localhost or 127.0.0.1).');
  process.exit(1);
}

const installationId = randomUUID();
const today = new Date(Date.now() + 330 * 60 * 1000).toISOString().slice(0, 10); // India date
const shareToken = () => randomBytes(18).toString('base64url'); // 24 characters, as the phone makes them

async function call(method, path, { token, body, idempotent = method !== 'GET' } = {}) {
  const headers = { 'X-Installation-Id': installationId };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (token) headers.Authorization = `Bearer ${token}`;
  if (idempotent) headers['Idempotency-Key'] = randomUUID();
  const res = await fetch(API + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  if (!res.ok) {
    const route = path.replace(/[0-9a-f-]{36}/g, ':id').replace(/[A-Za-z0-9_-]{24}$/, ':token');
    throw new Error(`${method} ${route} answered ${res.status}: ${text.slice(0, 300)}`);
  }
  return text ? JSON.parse(text) : null;
}

async function signIn(devUser, name) {
  const answer = await call('POST', '/v1/auth/dev', { body: { devUser, name, installationId }, idempotent: false });
  return answer.accessToken;
}

async function createGroup(token, { kind = 'group', name, friends }) {
  const id = randomUUID();
  const me = randomUUID();
  const members = friends.map((f) => ({ id: randomUUID(), name: f.name, ...(f.phone ? { phone: f.phone } : {}) }));
  await call('POST', '/v1/groups', {
    token,
    body: { id, kind, ...(kind === 'group' ? { name } : {}), myMemberId: me, members },
  });
  return { id, me, ...Object.fromEntries(friends.map((f, i) => [f.key, members[i].id])) };
}

async function addExpense(token, gid, { description, category, amountPaise, paidBy, method = 'EQUAL', shares }) {
  await call('PUT', `/v1/groups/${gid}/expenses/${randomUUID()}`, {
    token,
    body: {
      description, category, amountPaise, occurredOn: today, paidByMemberId: paidBy, splitMethod: method, auto: false,
      shares: shares.map(([memberId, sharePaise]) => ({ memberId, sharePaise, input: method === 'AMOUNTS' ? sharePaise : null })),
      baseVersion: null,
    },
  });
}

async function makeLink(token, gid, memberId) {
  const value = shareToken();
  const linkId = randomUUID();
  await call('PUT', `/v1/groups/${gid}/members/${memberId}/links/${linkId}`, { token, body: { token: value }, idempotent: false });
  return { value, linkId };
}

const results = [];
async function check(name, link, expect) {
  const res = await fetch(`${API}/v1/links/${link}`, { headers: { Origin: PAGE } });
  const body = await res.text();
  const problems = [];
  const header = (h) => res.headers.get(h);
  if (header('access-control-allow-origin') !== PAGE) problems.push(`Access-Control-Allow-Origin is ${header('access-control-allow-origin')}`);
  if (header('x-robots-tag') !== 'noindex, nofollow') problems.push('X-Robots-Tag missing');
  if (header('referrer-policy') !== 'no-referrer') problems.push('Referrer-Policy missing');
  if (!/no-store/.test(header('cache-control') ?? '')) problems.push('Cache-Control is not no-store');
  if (/\+91\d{6,}/.test(body)) problems.push('a phone number is in the answer');
  if (/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/.test(body)) problems.push('an id is in the answer');
  let state = `HTTP ${res.status}`;
  if (res.status === 200) {
    const data = JSON.parse(body);
    state = data.owes.length ? `ready (${data.owes.length} to pay)` : data.owed.length ? 'owed only' : 'all square';
  } else if (res.status === 404) {
    state = 'link no longer works';
  }
  if (!state.startsWith(expect)) problems.push(`expected ${expect}`);
  results.push({ name, url: `${PAGE}/pay/${link}`, state, problems });
}

try {
  await call('GET', '/v1/health', { idempotent: false });
} catch (error) {
  console.error(`No local Worker at ${API}. Start it in server/ with \`npm run db:migrate:local && npm run dev\`.\n${error.message}`);
  process.exit(1);
}

const arjun = await signIn('arjun', 'Arjun');
await call('PATCH', '/v1/me', { token: arjun, body: { upiId: 'arjun@okhdfc' } });

// 1. The design: Swiggy ₹450 split equally by three, Priya owes Arjun ₹150.
const flat = await createGroup(arjun, { name: 'Flat 302', friends: [{ key: 'priya', name: 'Priya', phone: '+919000000001' }, { key: 'rahul', name: 'Rahul' }] });
await addExpense(arjun, flat.id, { description: 'Swiggy', category: 'FOOD', amountPaise: 45000, paidBy: flat.me, shares: [[flat.me, 15000], [flat.priya, 15000], [flat.rahul, 15000]] });
await check('ready, one expense', (await makeLink(arjun, flat.id, flat.priya)).value, 'ready (1');

// 2. Revoked link: same answer as unknown or expired.
const revoked = await makeLink(arjun, flat.id, flat.rahul);
await call('DELETE', `/v1/groups/${flat.id}/links/${revoked.linkId}`, { token: arjun });
await check('link revoked', revoked.value, 'link no longer works');

// 3. Several expenses and three payees: Arjun and Kiran (on the app, with UPI IDs) and Rahul (no app, no UPI ID).
const goa = await createGroup(arjun, { name: 'Goa trip', friends: [{ key: 'priya', name: 'Priya' }, { key: 'rahul', name: 'Rahul' }, { key: 'kiran', name: 'Kiran' }] });
const invite = await makeLink(arjun, goa.id, goa.kiran);
const kiran = await signIn('kiran', 'Kiran');
await call('PATCH', '/v1/me', { token: kiran, body: { upiId: 'kiran.r@oksbi' } });
await call('POST', `/v1/invites/${invite.value}/accept`, { token: kiran });
await addExpense(arjun, goa.id, { description: 'Beach shack dinner', category: 'FOOD', amountPaise: 360000, paidBy: goa.me, shares: [[goa.me, 90000], [goa.priya, 90000], [goa.rahul, 90000], [goa.kiran, 90000]] });
await addExpense(kiran, goa.id, { description: 'Scooter rental', category: 'TRAVEL', amountPaise: 240000, paidBy: goa.kiran, shares: [[goa.kiran, 120000], [goa.priya, 120000]] });
await addExpense(arjun, goa.id, { description: 'Flea market', category: 'SHOPPING', amountPaise: 120000, paidBy: goa.rahul, method: 'AMOUNTS', shares: [[goa.priya, 120000]] });
await check('ready, several payees', (await makeLink(arjun, goa.id, goa.priya)).value, 'ready (3');

// 4. Owed only: Priya paid for the tickets.
const movie = await createGroup(arjun, { name: 'Movie night', friends: [{ key: 'priya', name: 'Priya' }, { key: 'rahul', name: 'Rahul' }] });
await addExpense(arjun, movie.id, { description: 'PVR tickets', category: 'FUN', amountPaise: 60000, paidBy: movie.priya, shares: [[movie.me, 20000], [movie.priya, 20000], [movie.rahul, 20000]] });
await check('owed only', (await makeLink(arjun, movie.id, movie.priya)).value, 'owed only');

// 5. All square: Priya paid her share in cash, recorded by Arjun.
const lunch = await createGroup(arjun, { name: 'Office lunch', friends: [{ key: 'priya', name: 'Priya' }] });
await addExpense(arjun, lunch.id, { description: 'Thali', category: 'FOOD', amountPaise: 30000, paidBy: lunch.me, shares: [[lunch.me, 15000], [lunch.priya, 15000]] });
await call('PUT', `/v1/groups/${lunch.id}/settlements/${randomUUID()}`, {
  token: arjun,
  body: { fromMemberId: lunch.priya, toMemberId: lunch.me, amountPaise: 15000, method: 'CASH', matchedFromSms: false, occurredOn: today },
});
await check('all square', (await makeLink(arjun, lunch.id, lunch.priya)).value, 'all square');

// 6. A direct split with a friend without the app: no group name.
const direct = await createGroup(arjun, { kind: 'direct', friends: [{ key: 'priya', name: 'Priya', phone: '+919000000001' }] });
await addExpense(arjun, direct.id, { description: 'Movie tickets', category: 'FUN', amountPaise: 40000, paidBy: direct.me, shares: [[direct.me, 20000], [direct.priya, 20000]] });
await check('ready, direct split', (await makeLink(arjun, direct.id, direct.priya)).value, 'ready (1');

// 7. Text that looks like HTML.
const odd = await createGroup(arjun, { name: '<b>Flat</b> & co', friends: [{ key: 'priya', name: '<img src=x onerror=alert(1)>' }] });
await addExpense(arjun, odd.id, { description: '<script>alert(2)</script>', category: 'OTHER', amountPaise: 10000, paidBy: odd.me, shares: [[odd.me, 5000], [odd.priya, 5000]] });
await check('names with <script>', (await makeLink(arjun, odd.id, odd.priya)).value, 'ready (1');

let failed = false;
console.log('\nPage states (serve the page with `npm run preview`; use localhost, not 127.0.0.1):');
for (const r of results) {
  console.log(`  ${r.name.padEnd(24)} ${r.state.padEnd(22)} ${r.url}`);
  for (const p of r.problems) {
    failed = true;
    console.log(`    PROBLEM: ${p}`);
  }
}
console.log(`  ${'link no longer works'.padEnd(24)} ${'(bad path)'.padEnd(22)} ${PAGE}/pay/short`);
console.log('  could not load: stop the Worker and reload any of the pages above.\n');
process.exit(failed ? 1 : 0);

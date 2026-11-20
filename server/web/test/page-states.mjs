// Renders public/pay.html with pay.js in happy-dom for every state of docs/server.md section 7, with a fake fetch.
// Run with `npm test` (node --test).

import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { afterEach, describe, it } from 'node:test';
import { Window } from 'happy-dom';
import { start } from '../public/pay.js';
import { fixtures } from './fixtures/links.mjs';

const NOW = Date.parse('2026-11-17T06:00:00Z');
const API = 'http://localhost:8787';
const PLAY = 'https://play.google.com/store/apps/details?id=com.expenses.app';
const DOWNLOAD = 'https://github.com/akhilux-design/sumbuddy/releases/latest/download/sumbuddy.apk';
const CONFIG = Object.freeze({ apiBase: API, playStoreUrl: PLAY, appName: 'SumBuddy' });

const html = await readFile(new URL('../public/pay.html', import.meta.url), 'utf8');
const bodyHtml = html.slice(html.indexOf('<body>') + 6, html.indexOf('</body>'));

const windows = [];
afterEach(async () => {
  while (windows.length) await windows.pop().happyDOM.close();
});

function respond(fixture) {
  return new Response(fixture.body === undefined ? '' : JSON.stringify(fixture.body), {
    status: fixture.status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** Opens the page at /pay/<token> and runs pay.js. Returns the window, the first state and the fetch calls. */
async function open(path, { fetchImpl, config = CONFIG } = {}) {
  const window = new Window({
    url: `http://localhost:8788${path}`,
    settings: { disableJavaScriptFileLoading: true, disableCSSFileLoading: true, disableJavaScriptEvaluation: true },
  });
  windows.push(window);
  window.document.body.innerHTML = bodyHtml;
  const calls = [];
  const states = [];
  const fake = fetchImpl ?? (async (url, init) => {
    const token = url.split('/').pop();
    const fixture = Object.values(fixtures).find((f) => f.token === token);
    return respond(fixture ?? fixtures.notWorking);
  });
  const state = await start({
    window,
    config,
    now: () => NOW,
    onState: (s) => states.push(s),
    fetchImpl: async (url, init) => {
      calls.push({ url, init });
      return fake(url, init);
    },
  });
  return { window, doc: window.document, state, states, calls };
}

const text = (node) => node?.textContent.replace(/\s+/g, ' ').trim();
const texts = (root, selector) => Array.from(root.querySelectorAll(selector), text);

describe('ready, one expense (the design, 03/06)', () => {
  it('shows the design text and the Pay by UPI button', async () => {
    const { doc, state, calls } = await open(`/pay/${fixtures.readyOne.token}`);
    assert.equal(state, 'ready');
    assert.equal(doc.getElementById('main').hasAttribute('aria-busy'), false);
    assert.equal(doc.title, 'Arjun split a bill with you');

    assert.equal(text(doc.querySelector('.from-text')), 'Arjun sent you this');
    assert.equal(text(doc.querySelector('.from .avatar')), 'A');
    assert.ok(doc.querySelector('.from .avatar').classList.contains('is-purple'));
    assert.equal(text(doc.querySelector('h1')), 'Arjun split a bill with you');

    const card = doc.querySelector('.balance-card');
    assert.ok(card.classList.contains('tone-negative'));
    assert.deepEqual(texts(card, 'p'), ['Your share', '₹150', 'Swiggy · 16 Nov · Flat 302']);

    assert.deepEqual(texts(doc, '.details .row-title'), ['Swiggy, ₹450', 'No app or sign-up needed']);
    assert.deepEqual(texts(doc, '.details .row-subtitle'), ['Split equally between 3 people.', 'Pay from any UPI app you already use.']);
    const icon = doc.querySelector('.details img');
    assert.equal(icon.getAttribute('src'), '/icons/food_3d.svg');
    assert.equal(icon.getAttribute('alt'), 'Food');
    assert.equal(doc.querySelector('.section'), null, 'no "Who to pay" list when the sender is the only payee');

    const footer = doc.getElementById('footer');
    assert.ok(footer.classList.contains('is-sticky'));
    const [pay, getApp] = footer.querySelectorAll('a');
    assert.equal(text(pay), 'Pay ₹150 by UPI');
    assert.equal(pay.getAttribute('href'), 'upi://pay?pa=arjun@okhdfc&pn=Arjun&am=150.00&cu=INR&tn=Flat%20302');
    assert.ok(pay.classList.contains('style-primary') && pay.classList.contains('size-large'));
    assert.equal(text(getApp), 'Get SumBuddy (optional)');
    assert.equal(getApp.getAttribute('href'), PLAY);
    assert.ok(getApp.classList.contains('style-ghost'));

    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, `${API}/v1/links/${fixtures.readyOne.token}`);
    assert.equal(calls[0].init.credentials, 'omit');
    assert.equal(calls[0].init.referrerPolicy, 'no-referrer');
    assert.deepEqual(Object.keys(calls[0].init.headers), ['Accept'], 'no Authorization or custom headers (no preflight)');
  });

  it('hides "Get the app" when no download or Play Store link is configured', async () => {
    const { doc } = await open(`/pay/${fixtures.readyOne.token}`, { config: { ...CONFIG, playStoreUrl: '' } });
    assert.deepEqual(texts(doc, '#footer a'), ['Pay ₹150 by UPI']);
  });
});

describe('ready, several expenses and payees (docs/server.md 13.4)', () => {
  it('shows the total, who to pay, the expense list and one action per payee', async () => {
    const { doc, state } = await open(`/pay/${fixtures.readySeveral.token}`);
    assert.equal(state, 'ready');
    assert.equal(text(doc.querySelector('h1')), 'Arjun split bills with you');
    assert.deepEqual(texts(doc, '.balance-card p'), ['You owe', '₹1,900.50', '4 expenses · Goa trip']);

    const [who, expenses] = doc.querySelectorAll('.section');
    assert.equal(text(who.querySelector('h2')), 'Who to pay');
    assert.deepEqual(texts(who, '.row-title'), ['Arjun', 'Kiran', 'Rahul']);
    assert.deepEqual(texts(who, '.row-subtitle'), ['you owe', 'you owe', 'you owe']);
    assert.deepEqual(texts(who, '.amount'), ['-₹1,150', '-₹450.50', '-₹300']);

    assert.equal(text(expenses.querySelector('h2')), 'Expenses');
    assert.deepEqual(texts(expenses, '.row-title'), ['Beach shack dinner', 'Scooter rental', 'Villa', 'Flea market']);
    assert.deepEqual(texts(expenses, '.row-subtitle'), [
      '14 Nov · Arjun paid ₹3,600', '13 Nov · Kiran paid ₹2,400', '12 Nov · Priya paid ₹18,000', '28 Dec 2025 · Rahul paid ₹1,200',
    ]);
    assert.deepEqual(texts(expenses, '.amount'), ['₹900', '₹600.50', '₹4,500', '₹300']);
    assert.deepEqual(texts(expenses, '.meta'), ['your share', 'your share', 'your share', 'your share']);
    assert.deepEqual(Array.from(expenses.querySelectorAll('img'), (i) => i.getAttribute('src')),
      ['/icons/food_3d.svg', '/icons/car_3d.svg', '/icons/home_3d.svg', '/icons/bag_3d.svg']);

    const footer = doc.getElementById('footer');
    assert.equal(footer.classList.contains('is-sticky'), false, 'several actions do not stick to the bottom');
    assert.deepEqual(texts(footer, 'a.style-primary'), ['Pay Arjun ₹1,150 by UPI', 'Pay Kiran ₹450.50 by UPI']);
    assert.equal(footer.querySelectorAll('a.style-primary')[1].getAttribute('href'),
      'upi://pay?pa=kiran.r@oksbi&pn=Kiran&am=450.50&cu=INR&tn=Goa%20trip');
    assert.deepEqual(texts(footer, '.info-row .row-title'), ['Ask Rahul how to pay']);
    assert.deepEqual(texts(footer, '.info-row .row-subtitle'), ['Rahul has not added a UPI ID.']);
  });

  it('names the payee when it is not the sender', async () => {
    const { doc } = await open(`/pay/${fixtures.readyOtherPayee.token}`);
    assert.deepEqual(texts(doc, '.balance-card p'), ['Your share', '₹150', 'Swiggy · 16 Nov · Flat 302']);
    assert.deepEqual(texts(doc, '.section .row-title'), ['Rahul']);
    assert.deepEqual(texts(doc, '#footer a.style-primary'), ['Pay Rahul ₹150 by UPI']);
    assert.ok(doc.getElementById('footer').classList.contains('is-sticky'));
  });

  it('asks how to pay when the payee has no UPI ID', async () => {
    const { doc } = await open(`/pay/${fixtures.readyNoUpi.token}`);
    assert.equal(doc.querySelectorAll('#footer a.style-primary').length, 0);
    assert.deepEqual(texts(doc, '#footer .row-title'), ['Ask Arjun how to pay']);
    assert.deepEqual(texts(doc, '.details .row-title'), ['Swiggy, ₹450'], 'no "Pay from any UPI app" promise');
  });

  it('says when the list is cut at 20', async () => {
    const { doc } = await open(`/pay/${fixtures.readyMany.token}`);
    assert.deepEqual(texts(doc, '.balance-card p'), ['You owe', '₹2,450.75', '20+ expenses · Office lunch']);
    assert.equal(doc.querySelectorAll('.section li').length, 20);
    assert.equal(text(doc.querySelector('.note')), 'Showing the latest 20 expenses.');
  });

  it('works for a direct split without a group name', async () => {
    const { doc } = await open(`/pay/${fixtures.readyDirect.token}`);
    assert.deepEqual(texts(doc, '.balance-card p'), ['Your share', '₹200', 'Movie tickets · 15 Nov']);
    assert.equal(doc.querySelector('#footer a').getAttribute('href'), 'upi://pay?pa=arjun@okhdfc&pn=Arjun&am=200.00&cu=INR');
  });
});

describe('owed only', () => {
  it('says there is nothing to pay and who owes the friend', async () => {
    const { doc, state } = await open(`/pay/${fixtures.owedOnly.token}`);
    assert.equal(state, 'owedOnly');
    const card = doc.querySelector('.balance-card');
    assert.ok(card.classList.contains('tone-positive'));
    assert.deepEqual(texts(card, 'p'), ['You are owed', '₹400', '1 expense · Movie night']);
    assert.deepEqual(texts(doc, '.section .row-title'), ['Arjun', 'Rahul']);
    assert.deepEqual(texts(doc, '.section .row-subtitle'), ['owes you', 'owes you']);
    assert.deepEqual(texts(doc, '.section .amount'), ['+₹200', '+₹200']);
    assert.deepEqual(texts(doc, '.details .row-title'), ['Nothing to pay', 'PVR tickets, ₹600']);
    assert.equal(texts(doc, '.details .row-subtitle')[1], 'Split equally between 3 people. Your share is ₹200.');
    assert.deepEqual(texts(doc, '#footer a'), ['Get SumBuddy (optional)']);
  });
});

describe('all square', () => {
  it('shows ₹0 with the words, not only a colour', async () => {
    const { doc, state } = await open(`/pay/${fixtures.allSquare.token}`);
    assert.equal(state, 'allSquare');
    const card = doc.querySelector('.balance-card');
    assert.ok(card.classList.contains('tone-neutral'));
    assert.deepEqual(texts(card, 'p'), ['You owe', '₹0', 'All settled · Flat 302']);
    assert.equal(doc.querySelectorAll('#footer a.style-primary').length, 0);
  });
});

describe('family invite (06-family/28, docs/server.md 4.18)', () => {
  it('says who invited the friend to which family, and to get the app; no amounts, no Pay button', async () => {
    const { doc, state } = await open(`/pay/${fixtures.familyInvite.token}`);
    assert.equal(state, 'familyInvite');
    assert.equal(doc.title, 'Family invite');
    assert.equal(doc.getElementById('main').hasAttribute('aria-busy'), false);
    const sticker = doc.querySelector('.sticker img');
    assert.equal(sticker.getAttribute('src'), '/icons/heart_3d.svg');
    assert.equal(text(doc.querySelector('h1')), 'Arjun invited you to Home');
    assert.ok(doc.querySelector('h1').classList.contains('type-display-l'));
    assert.equal(text(doc.querySelector('.lede')), 'Home is a family month on SumBuddy: one monthly total for the two of you.');
    assert.deepEqual(texts(doc, '.list-card .row-title'), ['Get the app']);
    assert.deepEqual(texts(doc, '.list-card .row-subtitle'), ['Install SumBuddy on your phone, then open this link again to join.']);
    assert.equal(doc.querySelector('.list-card img').getAttribute('src'), '/icons/user_plus_3d.svg');

    const footer = doc.getElementById('footer');
    const links = footer.querySelectorAll('a');
    assert.equal(links.length, 1);
    assert.equal(text(links[0]), 'Get SumBuddy');
    assert.equal(links[0].getAttribute('href'), PLAY);
    assert.ok(links[0].classList.contains('style-primary') && links[0].classList.contains('size-large'));
    assert.ok(footer.classList.contains('is-sticky'));

    const all = text(doc.body);
    assert.doesNotMatch(all, /₹|Pay|owe|share/i, 'no amounts and no Pay button');
    assert.equal(doc.querySelector('.balance-card'), null);
    assert.equal(doc.querySelectorAll('a[href^="upi:"]').length, 0);
  });

  it('hides "Get SumBuddy" without a download or Play Store link and uses the configured app name', async () => {
    const { doc } = await open(`/pay/${fixtures.familyInvite.token}`, { config: { ...CONFIG, playStoreUrl: '', appName: 'Kharcha' } });
    assert.equal(doc.getElementById('footer').childElementCount, 0);
    assert.equal(text(doc.querySelector('.lede')), 'Home is a family month on Kharcha: one monthly total for the two of you.');
    assert.equal(text(doc.querySelector('.list-card .row-subtitle')), 'Install Kharcha on your phone, then open this link again to join.');
  });

  it('a malformed family answer is "Could not load this page"', async () => {
    for (const body of [fixtures.familyMalformed.body, { family: true, familyName: 'Home' }, { family: true, familyName: 'Home', senderName: 7 }]) {
      const fetchImpl = async () => new Response(JSON.stringify(body), { status: 200 });
      const { doc, state } = await open(`/pay/${fixtures.familyInvite.token}`, { fetchImpl });
      assert.equal(state, 'couldNotLoad', JSON.stringify(body));
      assert.equal(text(doc.querySelector('h1')), 'Could not load this page');
    }
  });

  it('shows names that look like HTML as text', async () => {
    const { doc, state } = await open(`/pay/${fixtures.familyHostile.token}`);
    assert.equal(state, 'familyInvite');
    assert.equal(doc.body.querySelectorAll('script, svg, iframe, b, i, object, embed').length, 0);
    for (const img of doc.body.querySelectorAll('img')) assert.match(img.getAttribute('src'), /^\/icons\/[a-z_0-9]+\.svg$/);
    assert.equal(text(doc.querySelector('h1')), '<img src=x onerror=alert(6)> invited you to <b>Home</b>');
    assert.equal(text(doc.querySelector('.lede')), '<b>Home</b> is a family month on SumBuddy: one monthly total for the two of you.');
  });
});

describe('"Get <app name>" points at the download link (APP_DOWNLOAD_URL) before the Play Store', () => {
  const footerLinks = (doc) => Array.from(doc.querySelectorAll('#footer a'), (a) => [text(a), a.getAttribute('href')]);

  it('on the pay page: the download link alone, or with a Play Store link too', async () => {
    for (const config of [{ ...CONFIG, playStoreUrl: '', appDownloadUrl: DOWNLOAD }, { ...CONFIG, appDownloadUrl: DOWNLOAD }]) {
      const { doc } = await open(`/pay/${fixtures.readyOne.token}`, { config });
      const [, getApp] = doc.querySelectorAll('#footer a');
      assert.deepEqual(footerLinks(doc)[1], ['Get SumBuddy (optional)', DOWNLOAD]);
      assert.equal(getApp.getAttribute('rel'), 'noopener noreferrer');
      assert.ok(getApp.classList.contains('style-ghost'));
    }
  });
  it('on the family invite page: the download link alone, or with a Play Store link too', async () => {
    for (const config of [{ ...CONFIG, playStoreUrl: '', appDownloadUrl: DOWNLOAD }, { ...CONFIG, appDownloadUrl: DOWNLOAD }]) {
      const { doc, state } = await open(`/pay/${fixtures.familyInvite.token}`, { config });
      assert.equal(state, 'familyInvite');
      assert.deepEqual(footerLinks(doc), [['Get SumBuddy', DOWNLOAD]]);
      const link = doc.querySelector('#footer a');
      assert.equal(link.getAttribute('rel'), 'noopener noreferrer');
      assert.ok(link.classList.contains('style-primary') && link.classList.contains('size-large'));
    }
  });
  it('on the owed-only page too', async () => {
    const { doc } = await open(`/pay/${fixtures.owedOnly.token}`, { config: { ...CONFIG, appDownloadUrl: DOWNLOAD } });
    assert.deepEqual(footerLinks(doc), [['Get SumBuddy (optional)', DOWNLOAD]]);
  });
  it('a download link that is not https is not used: the Play Store link, or no button', async () => {
    for (const appDownloadUrl of ['http://github.com/a.apk', 'javascript:alert(1)', 7, null]) {
      const withPlay = await open(`/pay/${fixtures.familyInvite.token}`, { config: { ...CONFIG, appDownloadUrl } });
      assert.deepEqual(footerLinks(withPlay.doc), [['Get SumBuddy', PLAY]], String(appDownloadUrl));
      const alone = await open(`/pay/${fixtures.readyOne.token}`, { config: { ...CONFIG, playStoreUrl: '', appDownloadUrl } });
      assert.deepEqual(texts(alone.doc, '#footer a'), ['Pay ₹150 by UPI'], String(appDownloadUrl));
    }
  });
});

describe('link no longer works', () => {
  it('shows the same page for a 404 (revoked, expired, unknown)', async () => {
    const { doc, state } = await open(`/pay/${fixtures.notWorking.token}`);
    assert.equal(state, 'notWorking');
    assert.equal(text(doc.querySelector('h1')), 'This link no longer works');
    assert.equal(text(doc.querySelector('.state-card .row-subtitle')), 'Ask the person who sent it for a new link.');
    assert.equal(doc.querySelector('.state-card button'), null);
    assert.equal(doc.getElementById('footer').childElementCount, 0);
  });

  it('does not call the API for a path that is not /pay/<24 characters>', async () => {
    for (const path of ['/pay/short', '/pay', `/pay/${fixtures.readyOne.token}/x`]) {
      const { state, calls } = await open(path);
      assert.equal(state, 'notWorking', path);
      assert.equal(calls.length, 0, path);
    }
  });
});

describe('could not load', () => {
  const failures = {
    'a 500': async () => respond(fixtures.couldNotLoad),
    'a 429': async () => respond(fixtures.rateLimited),
    'a network error': async () => { throw new TypeError('Failed to fetch'); },
    'an answer that is not JSON': async () => new Response('<html>Cloudflare</html>', { status: 200 }),
    'an answer with the wrong shape': async () => new Response(JSON.stringify({ hello: 'world' }), { status: 200 }),
  };
  for (const [name, fetchImpl] of Object.entries(failures)) {
    it(`after ${name}`, async () => {
      const { doc, state } = await open(`/pay/${fixtures.readyOne.token}`, { fetchImpl });
      assert.equal(state, 'couldNotLoad');
      assert.equal(text(doc.querySelector('h1')), 'Could not load this page');
      assert.equal(text(doc.querySelector('.state-card .row-subtitle')), 'Check your connection and try again. Nothing is lost.');
      assert.equal(text(doc.querySelector('.state-card button')), 'Try again');
    });
  }

  it('does not fetch when the API base is not configured', async () => {
    const { state, calls } = await open(`/pay/${fixtures.readyOne.token}`, { config: { ...CONFIG, apiBase: '' } });
    assert.equal(state, 'couldNotLoad');
    assert.equal(calls.length, 0);
  });

  it('Try again shows the loading placeholders, then the page', async () => {
    let attempt = 0;
    let release;
    const gate = new Promise((resolve) => { release = resolve; });
    const fetchImpl = async () => {
      attempt += 1;
      if (attempt === 1) throw new TypeError('offline');
      await gate;
      return respond(fixtures.readyOne);
    };
    const { doc, window, states } = await open(`/pay/${fixtures.readyOne.token}`, { fetchImpl });
    assert.deepEqual(states, ['couldNotLoad']);
    doc.querySelector('.state-card button').click();
    const main = doc.getElementById('main');
    assert.equal(main.getAttribute('aria-busy'), 'true');
    assert.ok(main.querySelector('.ph-card'), 'loading placeholders are back');
    assert.equal(text(main.querySelector('[role=status]')), 'Loading what you owe');
    release();
    await new Promise((resolve) => window.setTimeout(resolve, 10));
    assert.deepEqual(states, ['couldNotLoad', 'ready']);
    assert.equal(text(doc.querySelector('h1')), 'Arjun split a bill with you');
    assert.equal(doc.activeElement, doc.querySelector('h1'), 'focus moves to the new heading after Try again');
  });
});

describe('text typed by app users', () => {
  it('is shown as text, never as markup', async () => {
    const { doc, state } = await open(`/pay/${fixtures.hostileText.token}`);
    assert.equal(state, 'ready');
    const body = doc.body;
    assert.equal(body.querySelectorAll('script, svg, iframe, b, i, object, embed').length, 0);
    for (const img of body.querySelectorAll('img')) assert.match(img.getAttribute('src'), /^\/icons\/[a-z_0-9]+\.svg$/);
    for (const node of body.querySelectorAll('*')) {
      for (const attr of Array.from(node.attributes)) assert.doesNotMatch(attr.name, /^on/i, `${node.tagName} ${attr.name}`);
    }
    for (const link of body.querySelectorAll('a')) assert.match(link.getAttribute('href'), /^(upi:\/\/pay\?|https:\/\/play\.google\.com\/)/);

    assert.equal(text(doc.querySelector('.from-text')), '<img src=x onerror=alert(2)> sent you this');
    assert.equal(text(doc.querySelector('h1')), '<img src=x onerror=alert(2)> split bills with you');
    assert.equal(doc.title, '<img src=x onerror=alert(2)> split bills with you');
    assert.ok(text(body).includes('</p><script>alert(5)</script>'));
    const [who, list] = doc.querySelectorAll('.section');
    assert.deepEqual(texts(who, '.row-title'), ['"><svg onload=alert(3)>', 'Rahul']);
    assert.equal(doc.querySelector('.balance-card p:last-child').textContent, '2 expenses · <script>alert(1)</script>');
    // An unknown category gets the Other icon, a malformed date is left out, a bad UPI ID gives no button.
    assert.equal(list.querySelector('img').getAttribute('src'), '/icons/plus_3d.svg');
    assert.equal(text(list.querySelector('.row-subtitle')), '<i>Arjun</i> paid ₹450');
    assert.deepEqual(texts(doc, '#footer a.style-primary'), ['Pay "><svg onload=alert(3)> ₹150 by UPI']);
    assert.equal(doc.querySelector('#footer a.style-primary').getAttribute('href'),
      'upi://pay?pa=arjun@okhdfc&pn=%22%3E%3Csvg%20onload%3Dalert(3)%3E&am=150.00&cu=INR&tn=%3Cscript%3Ealert(1)%3C%2Fscript%3E');
    assert.deepEqual(texts(doc, '#footer .info-row .row-title'), ['Ask Rahul how to pay']);
  });
});

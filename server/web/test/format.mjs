// Pure helpers of pay.js: token from the path, money, dates, the UPI link, parsing the API answer.
// Run with `npm test` (node --test). File names avoid *.test.* so the Worker's vitest in server/ does not pick them up.

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  formatDay, formatRupees, initialOf, parseLinkData, splitSentence, stateOf, tokenFromPath, upiUrl,
} from '../public/pay.js';
import { fixtures } from './fixtures/links.mjs';

const NOW = Date.parse('2026-11-17T06:00:00Z');

describe('tokenFromPath', () => {
  it('accepts /pay/ and exactly 24 base64url characters', () => {
    const token = 'AbCdEfGhIjKlMnOpQrStU_-9';
    assert.equal(token.length, 24);
    assert.equal(tokenFromPath(`/pay/${token}`), token);
    assert.equal(tokenFromPath(`/pay/${token}x`), null); // 25
    assert.equal(tokenFromPath(`/pay/${token.slice(1)}`), null); // 23
  });
  it('refuses anything else', () => {
    for (const path of ['/pay', '/pay/', '/pay/short', '/pay/AbCdEfGhIjKlMnOpQrStU_-9/', '/pay/AbCdEfGhIjKlMnOpQrStU_-9/x',
      '/x/pay/AbCdEfGhIjKlMnOpQrStU_-9', '/pay/AbCdEfGhIjKlMnOpQrStU_+9', '/pay/AbCdEfGhIjKlMnOpQrStU_%39', '/pay.html']) {
      assert.equal(tokenFromPath(path), null, path);
    }
  });
});

describe('formatRupees (same as the app)', () => {
  const cases = [
    [0, '₹0'], [5, '₹0.05'], [100, '₹1'], [15000, '₹150'], [15050, '₹150.50'], [99999, '₹999.99'],
    [100000, '₹1,000'], [190050, '₹1,900.50'], [123456700, '₹12,34,567'], [1000000000, '₹1,00,00,000'], [-15000, '-₹150'],
  ];
  for (const [paise, text] of cases) it(`${paise} → ${text}`, () => assert.equal(formatRupees(paise), text));
});

describe('formatDay', () => {
  it('shows day and month this year in India time, and the year otherwise', () => {
    assert.equal(formatDay('2026-11-16', NOW), '16 Nov');
    assert.equal(formatDay('2026-01-01', NOW), '1 Jan');
    assert.equal(formatDay('2025-12-28', NOW), '28 Dec 2025');
    // 31 Dec 2026 20:00 UTC is already 1 Jan 2027 in India.
    assert.equal(formatDay('2027-01-01', Date.parse('2026-12-31T20:00:00Z')), '1 Jan');
  });
  it('gives null for anything that is not a date', () => {
    for (const value of [null, '', '16 Nov', '2026-13-01', '"><img src=x>']) assert.equal(formatDay(value, NOW), null);
  });
});

describe('upiUrl', () => {
  it('builds the link of docs/server.md section 7', () => {
    assert.equal(
      upiUrl({ upiId: 'arjun@okhdfc', name: 'Arjun', amountPaise: 15000, note: 'Flat 302' }),
      'upi://pay?pa=arjun@okhdfc&pn=Arjun&am=150.00&cu=INR&tn=Flat%20302',
    );
    assert.equal(
      upiUrl({ upiId: 'kiran.r@oksbi', name: 'Kiran & Co', amountPaise: 45005, note: 'Goa #2' }),
      'upi://pay?pa=kiran.r@oksbi&pn=Kiran%20%26%20Co&am=450.05&cu=INR&tn=Goa%20%232',
    );
  });
  it('leaves out tn without a group name', () => {
    assert.equal(upiUrl({ upiId: 'a.b@ybl', name: 'A', amountPaise: 100, note: null }), 'upi://pay?pa=a.b@ybl&pn=A&am=1.00&cu=INR');
  });
  it('gives null for a missing or malformed UPI ID', () => {
    for (const upiId of [null, '', 'arjun', 'arjun@', 'javascript:alert(1)', 'a@b', 'ar jun@okhdfc', 'arjun@ok&x=1']) {
      assert.equal(upiUrl({ upiId, name: 'A', amountPaise: 100, note: null }), null, String(upiId));
    }
  });
});

describe('initialOf', () => {
  it('takes the first letter in upper case', () => {
    assert.equal(initialOf('arjun'), 'A');
    assert.equal(initialOf('  priya'), 'P');
    assert.equal(initialOf('<img>'), '<');
  });
});

describe('parseLinkData and stateOf', () => {
  it('reads every fixture that answers 200', () => {
    assert.equal(stateOf(parseLinkData(fixtures.readyOne.body)), 'ready');
    assert.equal(stateOf(parseLinkData(fixtures.owedOnly.body)), 'owedOnly');
    assert.equal(stateOf(parseLinkData(fixtures.allSquare.body)), 'allSquare');
  });
  it('refuses answers it cannot show', () => {
    const base = fixtures.readyOne.body;
    for (const broken of [null, 'x', {}, { ...base, senderName: '' }, { ...base, owes: null },
      { ...base, owes: [{ toName: 'A', amountPaise: 0, upiId: null }] },
      { ...base, owes: [{ toName: 'A', amountPaise: 1.5, upiId: null }] },
      { ...base, owed: [{ fromName: '', amountPaise: 10 }] },
      { ...base, expenses: [{ ...base.expenses[0], amountPaise: '450' }] }]) {
      assert.throws(() => parseLinkData(broken));
    }
  });
  it('falls back for unknown categories and methods', () => {
    const data = parseLinkData({ ...fixtures.readyOne.body, expenses: [{ ...fixtures.readyOne.body.expenses[0], category: 'PETS', splitMethod: 'DICE' }] });
    assert.equal(data.expenses[0].category, 'OTHER');
    assert.equal(splitSentence(data.expenses[0]), 'Split between 3 people.');
  });
});

describe('splitSentence', () => {
  it('names the method', () => {
    const e = { peopleCount: 3 };
    assert.equal(splitSentence({ ...e, splitMethod: 'EQUAL' }), 'Split equally between 3 people.');
    assert.equal(splitSentence({ ...e, splitMethod: 'AMOUNTS' }), 'Split by amounts between 3 people.');
    assert.equal(splitSentence({ ...e, splitMethod: 'PERCENT' }), 'Split by percentages between 3 people.');
    assert.equal(splitSentence({ ...e, splitMethod: 'SHARES' }), 'Split by shares between 3 people.');
    assert.equal(splitSentence({ peopleCount: 1, splitMethod: 'EQUAL' }), 'Not split.');
  });
});

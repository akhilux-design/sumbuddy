// Sample answers of GET /v1/links/{token} (docs/server.md 4.10), one per page state. Used by the tests and by
// scripts/mock-api.mjs (npm run preview:mock). Tokens are 24 characters like real ones.

const token = (name) => name.padEnd(24, '0');

const swiggy = {
  description: 'Swiggy', category: 'FOOD', occurredOn: '2026-11-16', amountPaise: 45000, yourSharePaise: 15000,
  splitMethod: 'EQUAL', peopleCount: 3, paidByName: 'Arjun',
};

const many = Array.from({ length: 20 }, (_, i) => ({
  description: `Chai and snacks ${i + 1}`, category: i % 2 ? 'FOOD' : 'GROCERIES',
  occurredOn: `2026-10-${String(30 - i).padStart(2, '0')}`, amountPaise: 30000 + i * 125, yourSharePaise: 10000 + i * 41,
  splitMethod: 'EQUAL', peopleCount: 3, paidByName: 'Arjun',
}));

export const fixtures = {
  // The design: one expense, one payee who is the sender, with a UPI ID.
  readyOne: {
    token: token('readyOne'),
    status: 200,
    body: {
      groupName: 'Flat 302', senderName: 'Arjun', friendName: 'Priya',
      owes: [{ toName: 'Arjun', amountPaise: 15000, upiId: 'arjun@okhdfc' }],
      owed: [], expenses: [swiggy], expensesTruncated: false,
    },
  },
  // Several expenses and several payees, one without a UPI ID (copy not in the design, docs/server.md 13.4).
  readySeveral: {
    token: token('readySeveral'),
    status: 200,
    body: {
      groupName: 'Goa trip', senderName: 'Arjun', friendName: 'Priya',
      owes: [
        { toName: 'Arjun', amountPaise: 115000, upiId: 'arjun@okhdfc' },
        { toName: 'Kiran', amountPaise: 45050, upiId: 'kiran.r@oksbi' },
        { toName: 'Rahul', amountPaise: 30000, upiId: null },
      ],
      owed: [],
      expenses: [
        { description: 'Beach shack dinner', category: 'FOOD', occurredOn: '2026-11-14', amountPaise: 360000, yourSharePaise: 90000, splitMethod: 'EQUAL', peopleCount: 4, paidByName: 'Arjun' },
        { description: 'Scooter rental', category: 'TRAVEL', occurredOn: '2026-11-13', amountPaise: 240000, yourSharePaise: 60050, splitMethod: 'AMOUNTS', peopleCount: 4, paidByName: 'Kiran' },
        { description: 'Villa', category: 'RENT_AND_BILLS', occurredOn: '2026-11-12', amountPaise: 1800000, yourSharePaise: 450000, splitMethod: 'SHARES', peopleCount: 4, paidByName: 'Priya' },
        { description: 'Flea market', category: 'SHOPPING', occurredOn: '2025-12-28', amountPaise: 120000, yourSharePaise: 30000, splitMethod: 'PERCENT', peopleCount: 4, paidByName: 'Rahul' },
      ],
      expensesTruncated: false,
    },
  },
  // One payee who is not the sender (simplified debts), so the page names who to pay.
  readyOtherPayee: {
    token: token('readyOtherPayee'),
    status: 200,
    body: {
      groupName: 'Flat 302', senderName: 'Arjun', friendName: 'Priya',
      owes: [{ toName: 'Rahul', amountPaise: 15000, upiId: 'rahul@ybl' }],
      owed: [], expenses: [swiggy], expensesTruncated: false,
    },
  },
  // One payee without a UPI ID.
  readyNoUpi: {
    token: token('readyNoUpi'),
    status: 200,
    body: {
      groupName: 'Flat 302', senderName: 'Arjun', friendName: 'Priya',
      owes: [{ toName: 'Arjun', amountPaise: 15000, upiId: null }],
      owed: [], expenses: [swiggy], expensesTruncated: false,
    },
  },
  // 20 expenses shown of more.
  readyMany: {
    token: token('readyMany'),
    status: 200,
    body: {
      groupName: 'Office lunch', senderName: 'Arjun', friendName: 'Priya',
      owes: [{ toName: 'Arjun', amountPaise: 245075, upiId: 'arjun@okhdfc' }],
      owed: [], expenses: many, expensesTruncated: true,
    },
  },
  // A direct split (no group name).
  readyDirect: {
    token: token('readyDirect'),
    status: 200,
    body: {
      groupName: null, senderName: 'Arjun', friendName: 'Priya',
      owes: [{ toName: 'Arjun', amountPaise: 20000, upiId: 'arjun@okhdfc' }],
      owed: [],
      expenses: [{ description: 'Movie tickets', category: 'FUN', occurredOn: '2026-11-15', amountPaise: 40000, yourSharePaise: 20000, splitMethod: 'EQUAL', peopleCount: 2, paidByName: 'Arjun' }],
      expensesTruncated: false,
    },
  },
  // The friend paid more than their share: nothing to pay.
  owedOnly: {
    token: token('owedOnly'),
    status: 200,
    body: {
      groupName: 'Movie night', senderName: 'Arjun', friendName: 'Priya',
      owes: [], owed: [{ fromName: 'Arjun', amountPaise: 20000 }, { fromName: 'Rahul', amountPaise: 20000 }],
      expenses: [{ description: 'PVR tickets', category: 'FUN', occurredOn: '2026-11-10', amountPaise: 60000, yourSharePaise: 20000, splitMethod: 'EQUAL', peopleCount: 3, paidByName: 'Priya' }],
      expensesTruncated: false,
    },
  },
  allSquare: {
    token: token('allSquare'),
    status: 200,
    body: {
      groupName: 'Flat 302', senderName: 'Arjun', friendName: 'Priya',
      owes: [], owed: [], expenses: [swiggy], expensesTruncated: false,
    },
  },
  // A family invite (docs/server.md 4.18): the family's name and the sender's, nothing else.
  familyInvite: {
    token: token('familyInvite'),
    status: 200,
    body: { family: true, familyName: 'Home', senderName: 'Arjun' },
  },
  // A family answer without a family name: the page cannot use it.
  familyMalformed: {
    token: token('familyMalformed'),
    status: 200,
    body: { family: true, familyName: '  ', senderName: 'Arjun' },
  },
  // Names typed by app users that look like HTML.
  familyHostile: {
    token: token('familyHostile'),
    status: 200,
    body: { family: true, familyName: '<b>Home</b>', senderName: '<img src=x onerror=alert(6)>' },
  },
  notWorking: {
    token: token('notWorking'),
    status: 404,
    body: { error: { code: 'link_not_found', message: 'Link not found' } },
  },
  couldNotLoad: {
    token: token('couldNotLoad'),
    status: 500,
    body: { error: { code: 'internal', message: 'Internal error' } },
  },
  rateLimited: {
    token: token('rateLimited'),
    status: 429,
    body: { error: { code: 'rate_limited', message: 'Too many requests' } },
  },
  // Text typed by app users that looks like HTML must show as text.
  hostileText: {
    token: token('hostileText'),
    status: 200,
    body: {
      groupName: '<script>alert(1)</script>', senderName: '<img src=x onerror=alert(2)>', friendName: 'Priya',
      owes: [
        { toName: '"><svg onload=alert(3)>', amountPaise: 15000, upiId: 'arjun@okhdfc' },
        { toName: 'Rahul', amountPaise: 100, upiId: 'javascript:alert(4)' },
      ],
      owed: [],
      expenses: [
        { description: '</p><script>alert(5)</script>', category: '<b>FOOD</b>', occurredOn: '"><img src=x>', amountPaise: 45000, yourSharePaise: 15000, splitMethod: 'EQUAL"', peopleCount: 3, paidByName: '<i>Arjun</i>' },
        { description: 'Normal', category: 'OTHER', occurredOn: '2026-11-01', amountPaise: 100, yourSharePaise: 100, splitMethod: 'EQUAL', peopleCount: 1, paidByName: 'Rahul' },
      ],
      expensesTruncated: false,
    },
  },
  // Answers the mock API delays, to see the loading state.
  slow: {
    token: token('slow'),
    status: 200,
    delayMs: 4000,
    body: null, // filled below with readyOne's body
  },
};
fixtures.slow.body = fixtures.readyOne.body;

export function fixtureByToken(value) {
  return Object.values(fixtures).find((f) => f.token === value) ?? null;
}

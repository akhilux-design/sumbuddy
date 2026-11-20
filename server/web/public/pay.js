// The friends' web link page: https://<LINK_HOST>/pay/<token> (docs/server.md section 7).
//
// Reads the token from the path, fetches GET {apiBase}/v1/links/{token} without credentials and shows one of seven
// states: loading, ready, owed only, all square, family invite, link no longer works, could not load. Every name,
// description, group and family name was typed by an app user, so text only ever goes into the page through
// textContent (never innerHTML).
// No analytics, no cookies, no storage, no third-party requests.

const TOKEN_PATH = /^\/pay\/([A-Za-z0-9_-]{24})$/;
const UPI_ID = /^[A-Za-z0-9._-]{2,64}@[A-Za-z][A-Za-z0-9]{1,63}$/;
const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const INDIA_OFFSET_MS = (5 * 60 + 30) * 60 * 1000;
const FETCH_TIMEOUT_MS = 15000;

/** Category name (the app's Category enum) to its 3D icon and label, as in app/.../Category.kt. */
const CATEGORIES = {
  FOOD: { icon: 'food_3d', label: 'Food' },
  GROCERIES: { icon: 'cart_3d', label: 'Groceries' },
  TRAVEL: { icon: 'car_3d', label: 'Travel' },
  RENT_AND_BILLS: { icon: 'home_3d', label: 'Rent and bills' },
  SHOPPING: { icon: 'bag_3d', label: 'Shopping' },
  FUN: { icon: 'film_3d', label: 'Fun' },
  OTHER: { icon: 'plus_3d', label: 'Other' },
};

const METHODS = {
  EQUAL: 'equally',
  AMOUNTS: 'by amounts',
  PERCENT: 'by percentages',
  SHARES: 'by shares',
};

// ---------------------------------------------------------------------------------------------------------------
// Pure helpers (exported for the tests)

/** The 24-character token from a path like /pay/<token>, or null. */
export function tokenFromPath(pathname) {
  const match = TOKEN_PATH.exec(pathname);
  return match ? match[1] : null;
}

/** Indian digit grouping: 1234567 becomes "12,34,567". */
function groupIndian(digits) {
  if (digits.length <= 3) return digits;
  const head = digits.slice(0, -3);
  const groups = [];
  for (let i = head.length; i > 0; i -= 2) groups.unshift(head.slice(Math.max(0, i - 2), i));
  return `${groups.join(',')},${digits.slice(-3)}`;
}

/** Same as the app's formatRupees: ₹ with Indian grouping, paise only when not zero. 15050 becomes "₹150.50". */
export function formatRupees(paise) {
  const value = Math.abs(paise);
  const rupees = Math.floor(value / 100);
  const rest = value % 100;
  const sign = paise < 0 ? '-' : '';
  return `${sign}₹${groupIndian(String(rupees))}${rest ? `.${String(rest).padStart(2, '0')}` : ''}`;
}

/** "16 Nov" for a date this year (India time), "16 Nov 2025" otherwise, or null for a malformed date. */
export function formatDay(isoDate, nowMs) {
  const match = ISO_DATE.exec(isoDate ?? '');
  if (!match) return null;
  const month = Number(match[2]);
  if (month < 1 || month > 12) return null;
  const thisYear = new Date(nowMs + INDIA_OFFSET_MS).getUTCFullYear();
  const day = `${Number(match[3])} ${MONTHS[month - 1]}`;
  return Number(match[1]) === thisYear ? day : `${day} ${match[1]}`;
}

/** Rupees with two decimals for the UPI amount: 15000 becomes "150.00". */
function upiAmount(paise) {
  return `${Math.floor(paise / 100)}.${String(paise % 100).padStart(2, '0')}`;
}

/**
 * The UPI intent link for one payee, or null when the UPI ID is missing or not valid.
 * upi://pay?pa={upiId}&pn={name}&am={rupees}&cu=INR&tn={groupName}; tn is left out when there is no group name.
 * '@' stays as is in pa: it is allowed in a query, and some UPI apps do not decode %40.
 */
export function upiUrl({ upiId, name, amountPaise, note }) {
  if (typeof upiId !== 'string' || !UPI_ID.test(upiId)) return null;
  const params = [
    `pa=${encodeURIComponent(upiId).replace(/%40/g, '@')}`,
    `pn=${encodeURIComponent(name)}`,
    `am=${upiAmount(amountPaise)}`,
    'cu=INR',
  ];
  if (note) params.push(`tn=${encodeURIComponent(note)}`);
  return `upi://pay?${params.join('&')}`;
}

/** First letter of a name, upper case ("arjun" gives "A"). */
export function initialOf(name) {
  const first = Array.from(name.trim())[0] ?? '?';
  return first.toLocaleUpperCase('en-IN');
}

function has(object, key) {
  return typeof key === 'string' && Object.prototype.hasOwnProperty.call(object, key);
}

function isText(value) {
  return typeof value === 'string' && value.trim().length > 0;
}

function isPaise(value, { allowZero = false } = {}) {
  return Number.isSafeInteger(value) && (allowZero ? value >= 0 : value > 0);
}

/**
 * Checks the API answer (docs/server.md 4.10; a family invite, 4.18) and keeps only the fields the page uses. Throws
 * when it is not usable.
 */
export function parseLinkData(json) {
  if (!json || typeof json !== 'object') throw new Error('not an object');
  if (json.family === true) {
    if (!isText(json.familyName)) throw new Error('familyName');
    if (!isText(json.senderName)) throw new Error('senderName');
    return { family: true, familyName: json.familyName.trim(), senderName: json.senderName.trim() };
  }
  const { senderName, friendName, groupName, owes, owed, expenses, expensesTruncated } = json;
  if (!isText(senderName)) throw new Error('senderName');
  if (!Array.isArray(owes) || !Array.isArray(owed) || !Array.isArray(expenses)) throw new Error('lists');
  return {
    senderName: senderName.trim(),
    friendName: isText(friendName) ? friendName.trim() : null,
    groupName: isText(groupName) ? groupName.trim() : null,
    owes: owes.map((row) => {
      if (!row || !isText(row.toName) || !isPaise(row.amountPaise)) throw new Error('owes');
      return { toName: row.toName.trim(), amountPaise: row.amountPaise, upiId: typeof row.upiId === 'string' ? row.upiId : null };
    }),
    owed: owed.map((row) => {
      if (!row || !isText(row.fromName) || !isPaise(row.amountPaise)) throw new Error('owed');
      return { fromName: row.fromName.trim(), amountPaise: row.amountPaise };
    }),
    expenses: expenses.map((row) => {
      if (!row || !isText(row.description) || !isPaise(row.amountPaise) || !isPaise(row.yourSharePaise, { allowZero: true })) {
        throw new Error('expenses');
      }
      return {
        description: row.description.trim(),
        category: has(CATEGORIES, row.category) ? row.category : 'OTHER',
        occurredOn: typeof row.occurredOn === 'string' ? row.occurredOn : null,
        amountPaise: row.amountPaise,
        yourSharePaise: row.yourSharePaise,
        splitMethod: has(METHODS, row.splitMethod) ? row.splitMethod : null,
        peopleCount: Number.isSafeInteger(row.peopleCount) && row.peopleCount > 0 ? row.peopleCount : null,
        paidByName: isText(row.paidByName) ? row.paidByName.trim() : null,
      };
    }),
    expensesTruncated: expensesTruncated === true,
  };
}

/** Which of the four loaded states the data is in. */
export function stateOf(data) {
  if (data.family === true) return 'familyInvite';
  if (data.owes.length > 0) return 'ready';
  if (data.owed.length > 0) return 'owedOnly';
  return 'allSquare';
}

function sum(rows) {
  return rows.reduce((total, row) => total + row.amountPaise, 0);
}

/** "Split equally between 3 people." */
export function splitSentence(expense) {
  if (expense.peopleCount === 1) return 'Not split.';
  const how = expense.splitMethod ? METHODS[expense.splitMethod] : null;
  const between = expense.peopleCount ? ` between ${expense.peopleCount} people` : '';
  return how ? `Split ${how}${between}.` : `Split${between}.`;
}

// ---------------------------------------------------------------------------------------------------------------
// Rendering. Every string goes in through textContent or an attribute set with setAttribute.

function make(doc, tag, className, text) {
  const node = doc.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined && text !== null) node.textContent = text;
  return node;
}

function icon(doc, name, alt = '') {
  const img = make(doc, 'img');
  img.setAttribute('src', `/icons/${name}.svg`);
  img.setAttribute('alt', alt);
  img.setAttribute('width', '30');
  img.setAttribute('height', '30');
  return img;
}

function avatar(doc, name, purple) {
  const node = make(doc, 'div', `avatar type-title${purple ? ' is-purple' : ''}`, initialOf(name));
  node.setAttribute('aria-hidden', 'true');
  return node;
}

function infoRow(doc, { iconName, iconAlt, title, subtitle }) {
  const row = make(doc, 'div', 'info-row');
  const bubble = make(doc, 'div', 'bubble');
  bubble.append(icon(doc, iconName, iconAlt));
  const text = make(doc, 'div', 'row-text');
  text.append(make(doc, 'p', 'row-title type-title', title));
  if (subtitle) text.append(make(doc, 'p', 'row-subtitle type-body-m', subtitle));
  row.append(bubble, text);
  return row;
}

function balanceCard(doc, { tone, label, amount, caption }) {
  const card = make(doc, 'div', `balance-card tone-${tone}`);
  card.append(make(doc, 'p', 'type-label', label), make(doc, 'p', 'type-amount-xl', amount));
  if (caption) card.append(make(doc, 'p', 'type-body-m-strong', caption));
  return card;
}

function button(doc, { label, href, style, size, hug }) {
  const node = href ? make(doc, 'a') : make(doc, 'button');
  if (href) node.setAttribute('href', href);
  else node.setAttribute('type', 'button');
  node.className = `button style-${style} size-${size}${hug ? ' is-hug' : ''}`;
  node.append(make(doc, 'span', 'button-face type-button', label));
  return node;
}

function section(doc, title, rows) {
  const wrap = make(doc, 'section', 'section');
  wrap.append(make(doc, 'h2', 'section-label type-label', title));
  const list = make(doc, 'ul', 'list');
  list.append(...rows);
  wrap.append(list);
  return wrap;
}

/** Balance Row: avatar, name, detail, amount coloured by direction (the detail words say the same). */
function personRow(doc, { name, detail, amount, tone, purple }) {
  const row = make(doc, 'li', 'list-row');
  const text = make(doc, 'div', 'row-text');
  text.append(make(doc, 'p', 'row-title type-title', name), make(doc, 'p', 'row-subtitle type-caption', detail));
  const end = make(doc, 'div', 'row-end');
  end.append(make(doc, 'p', `amount type-amount-m is-${tone}`, amount));
  row.append(avatar(doc, name, purple), text, end);
  return row;
}

/** Expense Row (Shared): icon, description, "16 Nov · Arjun paid ₹450", your share and "your share". */
function expenseRow(doc, expense, nowMs) {
  const row = make(doc, 'li', 'list-row');
  const category = CATEGORIES[expense.category];
  const bubble = make(doc, 'div', 'bubble');
  bubble.append(icon(doc, category.icon, category.label));
  const total = formatRupees(expense.amountPaise);
  const paid = expense.paidByName ? `${expense.paidByName} paid ${total}` : `${total} in all`;
  const subtitle = [formatDay(expense.occurredOn, nowMs), paid].filter(Boolean).join(' · ');
  const text = make(doc, 'div', 'row-text');
  text.append(make(doc, 'p', 'row-title type-title', expense.description), make(doc, 'p', 'row-subtitle type-caption', subtitle));
  const end = make(doc, 'div', 'row-end');
  end.append(make(doc, 'p', 'amount type-amount-m', formatRupees(expense.yourSharePaise)), make(doc, 'p', 'meta type-caption', 'your share'));
  row.append(bubble, text, end);
  return row;
}

function countText(data) {
  const n = data.expenses.length;
  if (n === 0) return null;
  if (data.expensesTruncated) return `${n}+ expenses`;
  return n === 1 ? '1 expense' : `${n} expenses`;
}

/** The expense part: one Info Row as in the design, or a list when there are several (docs/server.md 13.4). */
function expenseDetails(doc, data, nowMs, { showShare }) {
  if (data.expenses.length === 0) return [];
  if (data.expenses.length === 1 && !data.expensesTruncated) {
    const expense = data.expenses[0];
    const category = CATEGORIES[expense.category];
    let subtitle = splitSentence(expense);
    if (showShare) subtitle += ` Your share is ${formatRupees(expense.yourSharePaise)}.`;
    return [infoRow(doc, {
      iconName: category.icon,
      iconAlt: category.label,
      title: `${expense.description}, ${formatRupees(expense.amountPaise)}`,
      subtitle,
    })];
  }
  const parts = [section(doc, 'Expenses', data.expenses.map((expense) => expenseRow(doc, expense, nowMs)))];
  if (data.expensesTruncated) parts.push(make(doc, 'p', 'note type-body-m', `Showing the latest ${data.expenses.length} expenses.`));
  return parts;
}

function header(doc, data) {
  const from = make(doc, 'div', 'from');
  from.append(avatar(doc, data.senderName, true), make(doc, 'p', 'from-text type-body-m-strong', `${data.senderName} sent you this`));
  const title = data.expenses.length > 1 ? `${data.senderName} split bills with you` : `${data.senderName} split a bill with you`;
  const heading = make(doc, 'h1', 'heading type-display-l', title);
  heading.setAttribute('tabindex', '-1');
  return { nodes: [from, heading], title };
}

/**
 * Where "Get <app name>" points: the download link (APP_DOWNLOAD_URL, the APK on GitHub Releases) when set, else the
 * Play Store link, else nowhere ('': no button). Only https links count.
 */
function getAppUrl(config) {
  for (const url of [config.appDownloadUrl, config.playStoreUrl]) {
    if (typeof url === 'string' && url.startsWith('https://')) return url;
  }
  return '';
}

function getAppButton(doc, config) {
  const url = getAppUrl(config);
  if (!url) return null;
  const link = button(doc, { label: `Get ${config.appName || 'the app'} (optional)`, href: url, style: 'ghost', size: 'medium' });
  link.setAttribute('rel', 'noopener noreferrer');
  return link;
}

/**
 * A family invite (06-family/28): who invited the friend to which family, and that it needs the app. No amounts, no
 * names besides the sender's, no Pay button. "Get <app name>" only when a download or Play Store link is configured.
 */
function renderFamilyInvite(view, data, config) {
  const { doc } = view;
  const appName = config.appName || 'the app';
  const sticker = make(doc, 'div', 'sticker');
  sticker.append(icon(doc, 'heart_3d'));
  sticker.setAttribute('aria-hidden', 'true');
  const heading = make(doc, 'h1', 'heading type-display-l', `${data.senderName} invited you to ${data.familyName}`);
  heading.setAttribute('tabindex', '-1');
  const card = make(doc, 'div', 'list-card');
  card.append(infoRow(doc, {
    iconName: 'user_plus_3d',
    title: 'Get the app',
    subtitle: `Install ${appName} on your phone, then open this link again to join.`,
  }));
  const body = [
    sticker,
    heading,
    make(doc, 'p', 'lede type-body-m', `${data.familyName} is a family month on ${appName}: one monthly total for the two of you.`),
    card,
  ];
  const footer = [];
  const url = getAppUrl(config);
  if (url) {
    const link = button(doc, { label: `Get ${appName}`, href: url, style: 'primary', size: 'large' });
    link.setAttribute('rel', 'noopener noreferrer');
    footer.push(link);
  }
  show(view, { title: 'Family invite', body, footer, sticky: true });
  return 'familyInvite';
}

function renderLoaded(view, data, config, nowMs) {
  const { doc } = view;
  const state = stateOf(data);
  if (state === 'familyInvite') return renderFamilyInvite(view, data, config);
  const top = header(doc, data);
  const body = [...top.nodes];
  const footer = [];
  let actions = 0;

  if (state === 'ready') {
    const total = sum(data.owes);
    const only = data.expenses.length === 1 && !data.expensesTruncated ? data.expenses[0] : null;
    const exactShare = only && data.owes.length === 1 && only.yourSharePaise === total;
    const caption = exactShare
      ? [only.description, formatDay(only.occurredOn, nowMs), data.groupName].filter(Boolean).join(' · ')
      : [countText(data), data.groupName].filter(Boolean).join(' · ');
    body.push(balanceCard(doc, { tone: 'negative', label: exactShare ? 'Your share' : 'You owe', amount: formatRupees(total), caption }));

    const payeeIsSender = data.owes.length === 1 && data.owes[0].toName === data.senderName;
    if (!payeeIsSender) {
      body.push(section(doc, 'Who to pay', data.owes.map((row) => personRow(doc, {
        name: row.toName,
        detail: 'you owe',
        amount: formatRupees(-row.amountPaise),
        tone: 'negative',
        purple: row.toName === data.senderName,
      }))));
    }

    const details = make(doc, 'div', 'details');
    details.append(...expenseDetails(doc, data, nowMs, { showShare: !exactShare }));
    let payable = false;
    for (const row of data.owes) {
      const href = upiUrl({ upiId: row.upiId, name: row.toName, amountPaise: row.amountPaise, note: data.groupName });
      if (href) {
        payable = true;
        const label = payeeIsSender
          ? `Pay ${formatRupees(row.amountPaise)} by UPI`
          : `Pay ${row.toName} ${formatRupees(row.amountPaise)} by UPI`;
        footer.push(button(doc, { label, href, style: 'primary', size: 'large' }));
      } else {
        footer.push(infoRow(doc, {
          iconName: 'cash_3d',
          title: `Ask ${row.toName} how to pay`,
          subtitle: `${row.toName} has not added a UPI ID.`,
        }));
      }
      actions += 1;
    }
    if (payable) {
      details.append(infoRow(doc, {
        iconName: 'user_plus_3d',
        title: 'No app or sign-up needed',
        subtitle: 'Pay from any UPI app you already use.',
      }));
    }
    if (details.childElementCount) body.push(details);
  } else if (state === 'owedOnly') {
    body.push(balanceCard(doc, {
      tone: 'positive',
      label: 'You are owed',
      amount: formatRupees(sum(data.owed)),
      caption: [countText(data), data.groupName].filter(Boolean).join(' · '),
    }));
    body.push(section(doc, 'Who owes you', data.owed.map((row) => personRow(doc, {
      name: row.fromName,
      detail: 'owes you',
      amount: `+${formatRupees(row.amountPaise)}`,
      tone: 'positive',
      purple: row.fromName === data.senderName,
    }))));
    const details = make(doc, 'div', 'details');
    details.append(
      infoRow(doc, { iconName: 'sparkle_3d', title: 'Nothing to pay', subtitle: 'You paid more than your share, so you get money back.' }),
      ...expenseDetails(doc, data, nowMs, { showShare: true }),
    );
    body.push(details);
  } else {
    body.push(balanceCard(doc, {
      tone: 'neutral',
      label: 'You owe',
      amount: formatRupees(0),
      caption: ['All settled', data.groupName].filter(Boolean).join(' · '),
    }));
    const details = make(doc, 'div', 'details');
    details.append(...expenseDetails(doc, data, nowMs, { showShare: true }));
    if (details.childElementCount) body.push(details);
  }

  const getApp = getAppButton(doc, config);
  if (getApp) footer.push(getApp);
  show(view, { title: top.title, body, footer, sticky: actions <= 1, state });
  return state;
}

function renderMessage(view, { iconName, title, text, retry }) {
  const { doc } = view;
  const card = make(doc, 'div', 'state-card');
  const img = icon(doc, iconName);
  img.setAttribute('width', '40');
  img.setAttribute('height', '40');
  const heading = make(doc, 'h1', 'heading type-heading-l', title);
  heading.setAttribute('tabindex', '-1');
  card.append(img, heading, make(doc, 'p', 'row-subtitle type-body-m', text));
  if (retry) {
    const again = button(doc, { label: 'Try again', style: 'primary', size: 'medium', hug: true });
    again.addEventListener('click', retry);
    card.append(again);
  }
  show(view, { title, body: [card], footer: [], sticky: false });
}

function renderNotWorking(view) {
  renderMessage(view, {
    iconName: 'link_3d',
    title: 'This link no longer works',
    text: 'Ask the person who sent it for a new link.',
  });
  return 'notWorking';
}

function renderCouldNotLoad(view, retry) {
  renderMessage(view, {
    iconName: 'alert_3d',
    title: 'Could not load this page',
    text: 'Check your connection and try again. Nothing is lost.',
    retry,
  });
  return 'couldNotLoad';
}

function renderLoading(view) {
  view.main.setAttribute('aria-busy', 'true');
  view.main.replaceChildren(...view.skeleton.map((node) => node.cloneNode(true)));
  view.footer.replaceChildren();
}

function show(view, { title, body, footer, sticky }) {
  view.doc.title = title;
  view.main.replaceChildren(...body);
  view.main.removeAttribute('aria-busy');
  view.footer.replaceChildren(...footer);
  view.footer.classList.toggle('is-sticky', sticky);
  if (view.focusHeading) {
    view.main.querySelector('h1')?.focus();
    view.focusHeading = false;
  }
}

// ---------------------------------------------------------------------------------------------------------------
// Loading

/** Fetches the link data. Resolves to { kind: 'data', data } | { kind: 'gone' } | { kind: 'error' }. */
export async function fetchLink({ window, apiBase, token, fetchImpl }) {
  if (typeof apiBase !== 'string' || !/^https?:\/\//.test(apiBase)) return { kind: 'error' };
  const controller = new window.AbortController();
  const timer = window.setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS);
  try {
    const response = await fetchImpl(`${apiBase}/v1/links/${token}`, {
      method: 'GET',
      credentials: 'omit',
      cache: 'no-store',
      referrerPolicy: 'no-referrer',
      headers: { Accept: 'application/json' },
      signal: controller.signal,
    });
    if (response.status === 404) return { kind: 'gone' };
    if (!response.ok) return { kind: 'error' };
    return { kind: 'data', data: parseLinkData(await response.json()) };
  } catch {
    return { kind: 'error' };
  } finally {
    window.clearTimeout(timer);
  }
}

/**
 * Runs the page. Resolves to the first state shown: ready, owedOnly, allSquare, familyInvite, notWorking or
 * couldNotLoad.
 * onState is called with every state shown, including after Try again. Tests pass their own window, config, fetch
 * and clock.
 */
export async function start({ window, config = window.XPENSE_CONFIG, fetchImpl, now = () => Date.now(), onState = () => {} }) {
  const doc = window.document;
  const main = doc.getElementById('main');
  const view = {
    doc,
    main,
    footer: doc.getElementById('footer'),
    skeleton: Array.from(main.childNodes, (node) => node.cloneNode(true)),
    focusHeading: false,
  };
  const token = tokenFromPath(window.location.pathname);
  if (!token) {
    const state = renderNotWorking(view);
    onState(state);
    return state;
  }
  const doFetch = fetchImpl ?? window.fetch.bind(window);

  const run = async () => {
    const result = await fetchLink({ window, apiBase: config?.apiBase, token, fetchImpl: doFetch });
    let state;
    if (result.kind === 'gone') state = renderNotWorking(view);
    else if (result.kind === 'error') state = renderCouldNotLoad(view, retry);
    else state = renderLoaded(view, result.data, config ?? {}, now());
    onState(state);
    return state;
  };
  function retry() {
    renderLoading(view);
    view.focusHeading = true;
    run();
  }
  return run();
}

if (typeof window !== 'undefined' && window.document && window.XPENSE_CONFIG) {
  start({ window });
}

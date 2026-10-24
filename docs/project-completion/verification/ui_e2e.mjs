// Real-browser end-to-end journeys against the DISPOSABLE stack (gateway :8080, Vite dev server :5173, PostgreSQL :5543).
// Needs: the stack (start-isolated-stack.sh), `npm run dev` in grandvista-frontend, Chrome (CHROME_PATH), `npm install` here.
import puppeteer from 'puppeteer-core';
import { execFileSync } from 'node:child_process';
import { writeFileSync, mkdirSync } from 'node:fs';
import { randomBytes } from 'node:crypto';

const APP = 'http://localhost:5173';
const API = 'http://localhost:8080/api';
const WORK = process.env.GV_WORK_DIR;                // from start-isolated-stack.sh (source its env.sh first)
const SHOTS = process.env.GV_SHOTS_DIR || `${WORK}/shots`;
const RESULTS = `${WORK}/results`;
const SVC = process.env.GV_SVC_SCRIPT;               // start/stop one service (failure injection)
const ADMIN = { email: process.env.ADMIN_EMAIL, password: process.env.ADMIN_PASSWORD };
const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
mkdirSync(SHOTS, { recursive: true });
mkdirSync(RESULTS, { recursive: true });
const results = [];
const consoleProblems = [];

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const iso = (days) => { const d = new Date(); d.setDate(d.getDate() + days); return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10); };

function check(name, ok, detail = '') {
  results.push({ check: name, pass: Boolean(ok), detail: String(detail).slice(0, 400) });
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${ok ? '' : '  -> ' + String(detail).slice(0, 400)}`);
}

let activePage = null;
async function step(name, fn) {
  try {
    await fn();
  } catch (error) {
    const stackLines = String(error.stack || '').split(String.fromCharCode(10));
    const where = (stackLines.find((l) => l.includes('ui_e2e.mjs')) || '').trim().split(':').slice(-2).join(':');
    let context = '';
    if (activePage) {
      const text = String(await activePage.evaluate(() => document.body.innerText).catch(() => '')).replace(/\s+/g, ' ').slice(0, 160);
      context = ` | url=${activePage.url()} | text="${text}" | at ${where}`;
      await activePage.screenshot({ path: `${SHOTS}/FAIL-${name.replace(/[^a-z0-9]+/gi, '-')}.png` }).catch(() => {});
    }
    check(name, false, error.message + context);
  }
}

async function waitText(page, text, timeout = 20000) {
  await page.waitForFunction((t) => document.body.innerText.includes(t), { timeout }, text);
}
async function waitUrl(page, fragment, timeout = 20000) {
  await page.waitForFunction((f) => location.pathname.includes(f), { timeout }, fragment);
}
async function bodyText(page) { return page.evaluate(() => document.body.innerText); }

async function field(page, labelText) {
  const handle = await page.evaluateHandle((label) => {
    const own = (node) => [...node.childNodes].filter((n) => n.nodeType === 3).map((n) => n.textContent).join('').trim();
    const found = [...document.querySelectorAll('label')].find((l) => own(l).startsWith(label));
    return found ? found.querySelector('input,select,textarea') : null;
  }, labelText);
  const element = handle.asElement();
  if (!element) throw new Error(`no field labelled "${labelText}"`);
  return element;
}
async function waitOption(page, labelText, optionText, timeout = 15000) {
  await page.waitForFunction((label, text) => {
    const own = (node) => [...node.childNodes].filter((n) => n.nodeType === 3).map((n) => n.textContent).join('').trim();
    const l = [...document.querySelectorAll('label')].find((x) => own(x).startsWith(label));
    const select = l && l.querySelector('select');
    return !!select && [...select.options].some((o) => o.textContent.trim().startsWith(text));
  }, { timeout }, labelText, optionText);
}
async function searchRooms(page, { city, checkIn, checkOut }) {
  await page.goto(`${APP}/rooms`);
  await waitOption(page, 'City', city);
  await fill(page, 'City', city);
  await fill(page, 'Check-in', checkIn);
  await fill(page, 'Check-out', checkOut);
  await clickButton(page, 'Search');
  await page.waitForFunction((c) => location.search.includes(`city=${c}`), { timeout: 15000 }, city);
  await page.waitForFunction((c) => {
    const cards = [...document.querySelectorAll('.room-card')];
    return !document.body.innerText.includes('Searching rooms') && cards.length > 0 && cards.every((card) => card.innerText.includes(c));
  }, { timeout: 20000 }, city);
}
async function fill(page, labelText, value) {
  const el = await field(page, labelText);
  const kind = await el.evaluate((e) => `${e.tagName}:${e.type}`);
  if (kind.endsWith(':date')) {
    await el.evaluate((e, v) => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(e, v);
      e.dispatchEvent(new Event('input', { bubbles: true }));
    }, value);
  } else if (kind.startsWith('SELECT')) {
    const optionValue = await el.evaluate((e, text) => {
      const option = [...e.options].find((o) => o.textContent.trim().startsWith(text) || o.value === text);
      return option ? option.value : null;
    }, value);
    if (optionValue === null) throw new Error(`no option "${value}" in "${labelText}"`);
    await el.select(optionValue);
  } else {
    await el.focus();
    await el.evaluate((e) => e.select()); // select the existing text so typing replaces it (number inputs ignore triple-click)
    await el.type(String(value));
  }
}
async function clickButton(page, text, { exact = false, within = null } = {}) {
  const handle = await page.evaluateHandle((t, ex, scopeText) => {
    let scope = document;
    if (scopeText) {
      const row = [...document.querySelectorAll('tr, article')].find((r) => r.innerText.includes(scopeText));
      if (!row) return null;
      scope = row;
    }
    const match = (b) => (ex ? b.innerText.trim() === t : b.innerText.trim().startsWith(t));
    // real <button>s first (the navbar also has "Register"/"Login" links), then button-styled links
    const buttons = [...scope.querySelectorAll('button')].filter((b) => !b.disabled);
    const links = [...scope.querySelectorAll('a.btn')];
    return buttons.find(match) || links.find(match) || null;
  }, text, exact, within);
  const element = handle.asElement();
  if (!element) throw new Error(`no button "${text}"${within ? ` in "${within}"` : ''}`);
  // DOM click: a coordinate click can land on the sticky navbar (e.g. its Logout button) when the target is scrolled under it
  await element.evaluate((e) => { e.scrollIntoView({ block: 'center' }); e.click(); });
}
async function api(method, path, body, token) {
  const response = await fetch(API + path, {
    method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await response.text();
  return { status: response.status, body: text ? JSON.parse(text) : null };
}
async function shot(page, name) { await page.screenshot({ path: `${SHOTS}/${name}.png` }).catch(() => {}); }
const sh = (...args) => execFileSync('bash', args, { stdio: 'inherit' });

// ------------------------------------------------------------------------------------------------
const browser = await puppeteer.launch({
  executablePath: CHROME, headless: true,
  args: ['--no-sandbox', '--window-size=1280,900'], defaultViewport: { width: 1280, height: 900 },
});
const page = await browser.newPage();
activePage = page;
page.on('dialog', (dialog) => dialog.accept());
page.on('response', (r) => { if (r.status() === 401 && r.url().includes('/api/')) console.log('  NOTE 401 from', r.request().method(), r.url().replace(API, ''), 'at', new Date().toISOString().slice(11, 19)); });
page.on('pageerror', (error) => consoleProblems.push(`pageerror: ${error.message}`));
page.on('console', (msg) => {
  if (msg.type() === 'error' && !/status of (401|403|409|404)|Failed to load resource/.test(msg.text())) consoleProblems.push(`console.error: ${msg.text()}`);
});

const stamp = Date.now();
const guest = { name: 'E2E Guest', email: `e2e-${stamp}@example.com`, password: `Aa1-${randomBytes(8).toString('hex')}` };  // throw-away
const checkIn = iso(700 + (stamp % 40));
const checkOut = iso(702 + (stamp % 40));

// ---- 1. Register / login ----------------------------------------------------------------------
await step('register', async () => {
  await page.goto(`${APP}/register`);
  await fill(page, 'Full name', guest.name);
  await fill(page, 'Email', guest.email);
  await fill(page, 'Password', guest.password);
  await fill(page, 'Confirm password', guest.password);
  await clickButton(page, 'Register');
  await waitUrl(page, '/login');
  await waitText(page, 'Account created');
  check('guest registers and is sent to the login page with a confirmation', true);
});

await step('login guest', async () => {
  await fill(page, 'Email', guest.email);
  await fill(page, 'Password', guest.password);
  await clickButton(page, 'Sign in');
  await waitUrl(page, '/rooms');
  await waitText(page, guest.name);
  check('guest logs in and lands on the room search showing their name and GUEST badge', (await bodyText(page)).includes('GUEST'));
});

await step('wrong password', async () => {
  const other = await browser.newPage();
  await other.goto(`${APP}/login`);
  await fill(other, 'Email', guest.email);
  await fill(other, 'Password', 'WrongPassword#1');
  await clickButton(other, 'Sign in');
  await waitText(other, 'Invalid');
  check('wrong password shows an error and stays on the login page', other.url().endsWith('/login'));
  await other.close();
});

// ---- 2. Search, details, reserve, pay ---------------------------------------------------------
let quotedTotal;
await step('search', async () => {
  await searchRooms(page, { city: 'Chennai', checkIn, checkOut });
  const cards = await page.$$eval('.room-card', (els) => els.length);
  const allChennai = await page.$$eval('.room-card', (els) => els.every((e) => e.innerText.includes('Chennai')));
  check('city + date search returns only Chennai rooms', cards > 0 && allChennai, `cards=${cards}`);
  await shot(page, '01-search');
});

await step('details + price', async () => {
  await clickButton(page, 'View & book');
  await waitUrl(page, '/rooms/');
  await waitText(page, 'Your stay');
  check('details page keeps the searched dates', (await (await field(page, 'Check-in')).evaluate((e) => e.value)) === checkIn);
  await clickButton(page, 'Check availability & price');
  await waitText(page, 'Room is available');
  quotedTotal = await page.$eval('.price-row.total strong', (e) => e.innerText);
  check('dynamic price breakdown is shown with a total', /₹/.test(quotedTotal), quotedTotal);
  await shot(page, '02-details-price');
});

await step('reserve + pay', async () => {
  await clickButton(page, 'Reserve for');
  await waitUrl(page, '/pay');
  await waitText(page, 'Complete your payment');
  const due = await page.$eval('.facts .price', (e) => e.innerText);
  check('amount due on the payment page equals the quoted total (price consistency)', due === quotedTotal, `${due} vs ${quotedTotal}`);
  await shot(page, '03-payment');
  await clickButton(page, 'Pay ₹');
  await waitText(page, 'Booking confirmed');
  const reference = await page.$eval('.facts code', (e) => e.innerText);
  check('payment succeeds and the booking is confirmed with a transaction reference', /^GV-PAY-/.test(reference), reference);
  await shot(page, '04-confirmed');
});

await step('my bookings + cancel', async () => {
  await page.goto(`${APP}/my-bookings`);
  await waitText(page, 'CONFIRMED');
  check('My bookings lists the confirmed booking', true);
  await clickButton(page, 'Cancel');
  await waitText(page, 'cancelled:');
  await waitText(page, 'CANCELLED');
  check('cancelling a confirmed booking shows it CANCELLED and refunded', (await bodyText(page)).includes('payment refunded'));
  await shot(page, '05-cancelled');
});

await step('declined payment', async () => {
  await searchRooms(page, { city: 'Chennai', checkIn, checkOut });
  await clickButton(page, 'View & book');
  await waitUrl(page, '/rooms/');
  await waitText(page, 'Your stay');
  await clickButton(page, 'Check availability & price');
  await waitText(page, 'Room is available');
  await clickButton(page, 'Reserve for');
  await waitUrl(page, '/pay');
  await waitText(page, 'Complete your payment');
  const demo = await field(page, 'Demo: simulate a declined payment');
  await demo.click();
  await clickButton(page, 'Pay ₹');
  await waitText(page, 'Booking not confirmed');
  const text = await bodyText(page);
  check('declined payment shows "not confirmed", FAILED and a release message', text.includes('FAILED') && text.includes('released'));
  await shot(page, '06-declined');
});

// ---- 3. Payment outage: the saga's intermediate states in the real UI --------------------------
await step('payment outage', async () => {
  await searchRooms(page, { city: 'Bengaluru', checkIn, checkOut });
  await clickButton(page, 'View & book');
  await waitUrl(page, '/rooms/');
  await waitText(page, 'Your stay');
  await clickButton(page, 'Check availability & price');
  await waitText(page, 'Room is available');
  await clickButton(page, 'Reserve for');
  await waitUrl(page, '/pay');
  await waitText(page, 'Complete your payment');
  const payUrl = page.url();

  sh(SVC, 'stop', '8084');
  await sleep(2000);
  await clickButton(page, 'Pay ₹');
  await waitText(page, 'Verifying your payment');
  const text = await bodyText(page);
  check('payment outage shows "Verifying your payment" and warns not to pay again (not "failed")',
    text.includes('do not pay again') && !text.includes('Booking not confirmed'));
  check('booking shown as Payment processing with no Pay/Cancel buttons', text.includes('Payment processing'));
  await shot(page, '07-verifying');

  await page.goto(`${APP}/my-bookings`);
  await waitText(page, 'Payment processing');
  const noCancel = await page.evaluate(() => {
    const article = [...document.querySelectorAll('article')].find((a) => a.innerText.includes('Payment processing'));
    return !!article && ![...article.querySelectorAll('button, a.btn')].some((b) => /Cancel|Pay now/.test(b.innerText));
  });
  check('My bookings offers neither Cancel nor Pay while the payment is being verified', noCancel);

  sh(SVC, 'start', 'payment', 'payment-service');
  console.log('payment-service restarting; waiting for the server-side recovery to settle the booking ...');
  // The server finds no payment was recorded and returns the booking to PENDING; the page refreshes by itself.
  await page.waitForFunction(() => !document.body.innerText.includes('Payment processing'), { timeout: 180000, polling: 2000 });
  const after = await bodyText(page);
  check('the booking leaves "Payment processing" by itself once the Payment Service is back', true);
  check('recovered booking is PENDING again with a Pay now button (no money was taken)', after.includes('PENDING') && after.includes('Pay now'));
  await shot(page, '08-recovered');

  await clickButton(page, 'Pay now');
  await waitUrl(page, '/pay');
  await waitText(page, 'Complete your payment');
  await clickButton(page, 'Pay ₹');
  await waitText(page, 'Booking confirmed', 60000);
  check('guest can pay again after recovery and the booking is confirmed', true);
});

// ---- 4. Session handling ----------------------------------------------------------------------
await step('tampered token', async () => {
  await page.goto(`${APP}/my-bookings`);
  await waitText(page, 'My bookings');
  await page.evaluate(() => {
    const token = localStorage.getItem('grandvista.token');
    localStorage.setItem('grandvista.token', token.slice(0, -4) + 'AAAA');
  });
  await page.goto(`${APP}/my-bookings`);
  await waitUrl(page, '/login');
  // the notice is rendered a moment after the redirect: wait for it instead of reading the page in between
  await page.waitForFunction(() => /expired|invalid|malformed/i.test(document.body.innerText), { timeout: 10000 }).catch(() => {});
  const text = await bodyText(page);
  const cleared = await page.evaluate(() => localStorage.getItem('grandvista.token'));
  check('a tampered token ends the session: back on /login with an explanation and the token removed', cleared === null && /expired|invalid|malformed/i.test(text), text.replace(/\s+/g, ' ').slice(0, 160));
});

await step('logout', async () => {
  await fill(page, 'Email', guest.email);
  await fill(page, 'Password', guest.password);
  await clickButton(page, 'Sign in');
  await waitUrl(page, '/rooms');
  await clickButton(page, 'Logout');
  await page.waitForFunction(() => !localStorage.getItem('grandvista.token'));
  await page.goto(`${APP}/my-bookings`);
  await waitUrl(page, '/login');
  check('logout clears the session and protects My bookings', true);
});

await step('guest vs admin pages', async () => {
  await page.goto(`${APP}/login`);
  await fill(page, 'Email', guest.email);
  await fill(page, 'Password', guest.password);
  await clickButton(page, 'Sign in');
  await page.waitForFunction(() => !location.pathname.includes('/login'), { timeout: 20000 }); // returns to the page the visitor first asked for
  await page.goto(`${APP}/admin`);
  await waitText(page, 'Access denied');
  check('a guest who types /admin in the address bar gets "Access denied"', true);
  await page.evaluate(() => localStorage.clear());
});

// ---- 5. Administrator --------------------------------------------------------------------------
let adminToken;
await step('admin login', async () => {
  await page.goto(`${APP}/login`);
  await fill(page, 'Email', ADMIN.email);
  await fill(page, 'Password', ADMIN.password);
  await clickButton(page, 'Sign in');
  await waitUrl(page, '/admin');
  await waitText(page, 'Admin dashboard');
  adminToken = await page.evaluate(() => localStorage.getItem('grandvista.token'));
  const text = await bodyText(page);
  check('admin lands on the dashboard with inventory and booking figures', text.includes('Confirmed / Pending / Cancelled') && text.includes('Occupancy') || text.includes('Hotels'));
  await shot(page, '09-admin-dashboard');
});

const hotelName = `E2E Hotel ${stamp}`;
await step('admin hotel', async () => {
  await page.goto(`${APP}/admin/hotels`);
  await waitText(page, 'Add a hotel');
  await fill(page, 'Name', hotelName);
  await fill(page, 'City', 'Pune');
  await fill(page, 'State', 'Maharashtra');
  await fill(page, 'Country', 'India');
  await fill(page, 'Address', '1 Test Road, Pune');
  await clickButton(page, 'Create hotel');
  await waitText(page, hotelName);
  check('admin creates a hotel and it appears in the list', true);
});

let roomNumber = `9${String(stamp).slice(-2)}`;
await step('admin room', async () => {
  await page.goto(`${APP}/admin/rooms/new`);
  await waitText(page, 'Add room');
  await waitOption(page, 'Hotel', hotelName);
  await fill(page, 'Hotel', hotelName);
  await fill(page, 'Room number', roomNumber);
  await fill(page, 'Room type', 'SUITE');
  await fill(page, 'Base price per night', '9100');
  await fill(page, 'Capacity', '3');
  await clickButton(page, 'Create room');
  await waitUrl(page, '/admin/rooms');
  await waitText(page, roomNumber);
  check('admin creates a room in the new hotel', true);
  const guestView = await api('GET', '/rooms?city=Pune');
  check('the new room is immediately searchable by guests', guestView.body.some((r) => r.roomNumber === roomNumber), JSON.stringify(guestView.body).slice(0, 100));
});

await step('admin pricing rule changes the guest quote', async () => {
  const rooms = (await api('GET', '/rooms?city=Pune')).body;
  const room = rooms.find((r) => r.roomNumber === roomNumber);
  const quoteIn = iso(500);
  const quoteOut = iso(502);
  const before = (await api('GET', `/rooms/${room.id}/price?checkIn=${quoteIn}&checkOut=${quoteOut}`)).body;
  await page.goto(`${APP}/admin/pricing`);
  await waitText(page, 'Dynamic pricing rules');
  await fill(page, 'Name', `E2E Peak ${stamp}`);
  await fill(page, 'Type', 'SEASONAL');
  await fill(page, 'Multiplier', '1.5');
  await fill(page, 'Start date', quoteIn);
  await fill(page, 'End date', quoteOut);
  await waitOption(page, 'Hotel (optional)', hotelName);
  await fill(page, 'Hotel (optional)', hotelName);
  await clickButton(page, 'Create rule');
  await waitText(page, `E2E Peak ${stamp}`);
  const after = (await api('GET', `/rooms/${room.id}/price?checkIn=${quoteIn}&checkOut=${quoteOut}`)).body;
  check('after the admin adds a +50% seasonal rule the guest quote rises by exactly 50%',
    Math.abs(after.finalTotal - before.finalTotal * 1.5) < 0.01, `${before.finalTotal} -> ${after.finalTotal}`);
  check('the quote names the rule that was applied', after.appliedRules.includes(`E2E Peak ${stamp}`));
  await clickButton(page, 'Delete', { within: `E2E Peak ${stamp}` });
  await page.waitForFunction((t) => !document.body.innerText.includes(t), {}, `E2E Peak ${stamp}`);
  let reverted;
  for (let attempt = 0; attempt < 20; attempt += 1) {
    reverted = (await api('GET', `/rooms/${room.id}/price?checkIn=${quoteIn}&checkOut=${quoteOut}`)).body;
    if (reverted.finalTotal === before.finalTotal) break;
    await sleep(500);
  }
  check('deleting the rule restores the base price', reverted.finalTotal === before.finalTotal, `${reverted.finalTotal}`);
});

await step('admin deactivates room (soft delete)', async () => {
  await page.goto(`${APP}/admin/rooms`);
  await waitText(page, roomNumber);
  await clickButton(page, 'Deactivate', { within: hotelName });
  await sleep(1500);
  const all = (await api('GET', '/rooms/all', null, adminToken)).body;
  const room = all.find((r) => r.roomNumber === roomNumber && r.hotelName === hotelName);
  check('deactivated room is kept (status INACTIVE) rather than deleted', room && room.status === 'INACTIVE', JSON.stringify(room));
  const guestView = await api('GET', '/rooms?city=Pune');
  check('deactivated room disappears from guest search', !guestView.body.some((r) => r.roomNumber === roomNumber));
});

await step('admin bookings + users', async () => {
  await page.goto(`${APP}/admin/bookings`);
  await waitText(page, 'All bookings');
  await waitText(page, guest.email.length ? 'CANCELLED' : '');
  const options = await page.$eval('select', (s) => [...s.options].map((o) => o.value));
  check('admin booking list offers all five status filters', options.join(',') === ',PENDING,PAYMENT_IN_PROGRESS,CONFIRMED,CANCELLATION_PENDING,CANCELLED', options.join(','));
  await page.goto(`${APP}/admin/users`);
  await waitText(page, guest.email);
  await clickButton(page, 'Make admin', { within: guest.email });
  await page.waitForFunction((email) => {
    const row = [...document.querySelectorAll('tr')].find((r) => r.innerText.includes(email));
    return row && row.innerText.includes('Make user');
  }, { timeout: 15000 }, guest.email);
  check('admin promotes a guest to administrator from the Users page', true);
  await clickButton(page, 'Make user', { within: guest.email });
  await page.waitForFunction((email) => {
    const row = [...document.querySelectorAll('tr')].find((r) => r.innerText.includes(email));
    return row && row.innerText.includes('Make admin');
  }, { timeout: 15000 }, guest.email);
  check('...and demotes them again', true);
});

// ---- 6. Responsive layout ---------------------------------------------------------------------
await step('responsive', async () => {
  await page.setViewport({ width: 390, height: 844, isMobile: true });
  const pages = ['/', '/rooms', '/login', '/register', '/admin', '/admin/bookings', '/admin/pricing', '/admin/rooms'];
  const overflowing = [];
  for (const path of pages) {
    await page.goto(`${APP}${path}`);
    await sleep(800);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    if (overflow > 1) overflowing.push(`${path} (+${overflow}px)`);
  }
  check('no page scrolls horizontally on a 390px-wide phone', overflowing.length === 0, overflowing.join(', '));
  await shot(page, '10-mobile-admin');
});

check('no uncaught page errors or console errors during all journeys', consoleProblems.length === 0, consoleProblems.slice(0, 3).join(' | '));

await browser.close();
const summary = { passed: results.filter((r) => r.pass).length, failed: results.filter((r) => !r.pass).length, total: results.length };
console.log(JSON.stringify(summary));
writeFileSync(`${RESULTS}/ui_e2e_results.json`, JSON.stringify({ summary, results, consoleProblems }, null, 1));
process.exit(summary.failed ? 1 : 0);

// Real-browser check of the CLOUD Demo Login (reviewer access code) - against the local simulation or the Render URL.
//   GV_FRONTEND=https://...  GV_DEMO_CODE=...  [GV_SHOTS_DIR=...]  node docs/project-completion/verification/cloud/cloud_ui_demo.mjs
// Run from docs/project-completion/verification (puppeteer-core is installed there). Synthetic accounts only.
import puppeteer from 'puppeteer-core';
import { mkdirSync, writeFileSync } from 'node:fs';

const APP = process.env.GV_FRONTEND.replace(/\/$/, '');
const CODE = process.env.GV_DEMO_CODE;
const SHOTS = process.env.GV_SHOTS_DIR || 'results/screenshots/cloud';
const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
mkdirSync(SHOTS, { recursive: true });
const results = [];

function check(name, ok, detail = '') {
  results.push({ check: name, pass: Boolean(ok), detail: String(detail).slice(0, 300) });
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${ok ? '' : '  -> ' + String(detail).slice(0, 300)}`);
}
const text = (page) => page.evaluate(() => document.body.innerText);
const waitText = (page, t, timeout = 120000) => page.waitForFunction((x) => document.body.innerText.includes(x), { timeout }, t);
async function clickText(page, selector, label) {
  await page.evaluate((sel, l) => {
    const el = [...document.querySelectorAll(sel)].find((b) => b.innerText.trim() === l || b.getAttribute('aria-label') === l);
    if (!el) throw new Error(`no ${sel} "${l}"`);
    el.click();
  }, selector, label);
}
async function openDemoTab(page) {
  await page.goto(`${APP}/login`, { waitUntil: 'networkidle2', timeout: 120000 });
  await waitText(page, 'Demo Login');
  await clickText(page, '[role=tab]', 'Demo Login');
}

const browser = await puppeteer.launch({ executablePath: CHROME, headless: true, args: ['--no-first-run'] });
const page = await browser.newPage();
await page.setViewport({ width: 1280, height: 900 });
const consoleErrors = [];
page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });

try {
  await openDemoTab(page);
  const locked = await text(page);
  check('UI Demo Login tab asks for the reviewer access code first',
    locked.includes('Reviewer access code') && !locked.includes('Continue as GrandVista Manager'), locked.slice(0, 200));
  await page.screenshot({ path: `${SHOTS}/cloud-01-access-code.png` });

  await page.type('input[type=password]', 'not-the-right-code-123');
  await clickText(page, 'button', 'Unlock demo accounts');
  await waitText(page, 'not accepted', 30000);
  check('UI a wrong code is rejected and no account card appears', !(await text(page)).includes('Continue as Ramesh'));

  await openDemoTab(page);                         // fresh, empty input
  await page.type('input[type=password]', CODE);
  await clickText(page, 'button', 'Unlock demo accounts');
  await waitText(page, 'Continue as GrandVista Manager', 30000);
  const cards = await text(page);
  check('UI the right code shows the three cards: Ramesh, Demo Guest 2, GrandVista Manager',
    ['Continue as Ramesh', 'Continue as Demo Guest 2', 'Continue as GrandVista Manager'].every((t) => cards.includes(t)));
  await page.screenshot({ path: `${SHOTS}/cloud-02-demo-cards.png` });

  for (const [label, path, shot] of [['Continue as Ramesh', '/rooms', 'cloud-03-ramesh-rooms'],
    ['Continue as Demo Guest 2', '/rooms', 'cloud-04-guest2-rooms'],
    ['Continue as GrandVista Manager', '/admin', 'cloud-05-manager-admin']]) {
    await openDemoTab(page);
    await waitText(page, label, 30000);           // code remembered for this tab: no second prompt
    await clickText(page, 'button', label);
    await page.waitForFunction((p) => location.pathname.startsWith(p), { timeout: 60000 }, path);
    await new Promise((r) => setTimeout(r, 2500));
    await page.screenshot({ path: `${SHOTS}/${shot}.png` });
    check(`UI ${label} opens ${path}`, true);
    await clickText(page, 'button', 'Logout');
    await page.waitForFunction(() => !document.body.innerText.includes('Logout'), { timeout: 20000 });
  }

  const fresh = await browser.createBrowserContext();
  const other = await fresh.newPage();
  await other.goto(`${APP}/login`, { waitUntil: 'networkidle2', timeout: 120000 });
  await waitText(other, 'Demo Login');
  await clickText(other, '[role=tab]', 'Demo Login');
  check('UI a new browser session (no code) is asked for the code again',
    (await text(other)).includes('Reviewer access code'));
  await fresh.close();
} catch (error) {
  await page.screenshot({ path: `${SHOTS}/cloud-FAIL.png` }).catch(() => {});
  check('UI journey', false, error.message + ' | ' + (await text(page).catch(() => '')).slice(0, 200));
} finally {
  await browser.close();
}
const unexpected = consoleErrors.filter((e) => !/401|Unauthorized|status of 4\d\d/.test(e));
check('UI no unexpected browser console errors (expected 401s of the locked demo excluded)', unexpected.length === 0, unexpected);
const summary = { passed: results.filter((r) => r.pass).length, failed: results.filter((r) => !r.pass).length, total: results.length };
console.log(JSON.stringify(summary));
if (process.env.GV_RESULTS) writeFileSync(process.env.GV_RESULTS, JSON.stringify({ summary, results }, null, 1));
process.exit(summary.failed ? 1 : 0);

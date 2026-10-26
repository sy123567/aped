const { chromium } = require('C:/Users/26219/.workbuddy/binaries/node/workspace/node_modules/playwright-core');
const OUT = 'C:/Users/26219/WorkBuddy/软件建模实验/aepds/shots/';
(async () => {
  const fs = require('fs');
  fs.mkdirSync(OUT, { recursive: true });
  const b = await chromium.launch({ executablePath: 'C:/Users/26219/AppData/Local/ms-playwright/chromium-1228/chrome-win64/chrome.exe' });
  const p = await b.newPage({ viewport: { width: 1440, height: 900 } });
  const probs = [];
  p.on('pageerror', (e) => probs.push(e.message));
  p.on('console', (m) => { if (m.type() === 'error') probs.push(m.text()); });

  const shot = async (name) => {
    await p.waitForTimeout(700);
    await p.screenshot({ path: OUT + name + '.png' });
    console.log('shot:', name);
  };
  await p.goto('http://127.0.0.1:3001/#/', { waitUntil: 'load' }); await shot('p_dashboard');
  await p.goto('http://127.0.0.1:3001/#/orders', { waitUntil: 'load' }); await shot('p_orders');
  await p.goto('http://127.0.0.1:3001/#/pickup', { waitUntil: 'load' }); await shot('p_pickup');
  await p.goto('http://127.0.0.1:3001/#/delivery', { waitUntil: 'load' }); await shot('p_delivery');
  await p.goto('http://127.0.0.1:3001/#/tracking', { waitUntil: 'load' });
  await p.fill('input[placeholder="8801234567890"]', '8801234567890');
  await p.click('button:has-text("查询轨迹")');
  await shot('p_tracking');
  await p.goto('http://127.0.0.1:3001/#/exceptions', { waitUntil: 'load' }); await shot('p_exceptions');
  await p.goto('http://127.0.0.1:3001/#/billing', { waitUntil: 'load' }); await shot('p_billing');
  await p.goto('http://127.0.0.1:3001/#/performance', { waitUntil: 'load' }); await shot('p_performance');

  await b.close();
  console.log('errors:', probs.length); probs.forEach((x) => console.log(x));
})().catch((e) => { console.error('FATAL', e.message); process.exit(1); });

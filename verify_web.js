const { pathToFileURL } = require('url');
const { chromium } = require('C:/Users/26219/.workbuddy/binaries/node/workspace/node_modules/playwright-core');
(async()=>{
  const b = await chromium.launch({executablePath:'C:/Users/26219/AppData/Local/ms-playwright/chromium-1228/chrome-win64/chrome.exe'});
  const p = await b.newPage({viewport:{width:1400,height:900}});
  const problems=[];
  p.on('pageerror',e=>problems.push('PAGE ERROR: '+e.message));
  p.on('console',m=>{if(m.type()==='error')problems.push('CONSOLE: '+m.text());});
  const base='http://127.0.0.1:3001';
  await p.goto(base+'/#/',{waitUntil:'load'});
  await p.waitForTimeout(900);
  const boardText=await p.$eval('.board',e=>e.innerText.replace(/\s+/g,' ')).catch(()=>'NO BOARD');
  console.log('board:',boardText.slice(0,200));
  await p.screenshot({path:'C:/Users/26219/WorkBuddy/软件建模实验/aepds/shot_dashboard.png'});

  // 下单
  await p.click('nav a[href="#/orders"]');
  await p.waitForTimeout(400);
  await p.fill('.form-row input >> nth=0','测试用户');
  await p.fill('.form-row input >> nth=1','收件人A');
  await p.fill('.form-row input >> nth=2','13600000000');
  await p.fill('.form-row input >> nth=3','杭州市滨江区测试路1号');
  await p.click('button:has-text("提交下单")');
  await p.waitForTimeout(600);
  console.log('order toast:',await p.$eval('.note.ok',e=>e.innerText).catch(()=>'NO TOAST'));
  await p.screenshot({path:'C:/Users/26219/WorkBuddy/软件建模实验/aepds/shot_orders.png'});

  // 揽收页
  await p.click('nav a[href="#/pickup"]');
  await p.waitForTimeout(500);
  console.log('pickup rows:',await p.$$eval('table.doc tbody tr',e=>e.length));
  await p.screenshot({path:'C:/Users/26219/WorkBuddy/软件建模实验/aepds/shot_pickup.png'});

  // 派送页
  await p.click('nav a[href="#/delivery"]');
  await p.waitForTimeout(500);
  await p.screenshot({path:'C:/Users/26219/WorkBuddy/软件建模实验/aepds/shot_delivery.png'});

  // 跟踪
  await p.click('nav a[href="#/tracking"]');
  await p.fill('input[placeholder="8801234567890"]','8801234567890');
  await p.click('button:has-text("查询轨迹")');
  await p.waitForTimeout(600);
  console.log('track events:',await p.$$eval('.grid2 table.doc tbody tr',e=>e.length));
  await p.screenshot({path:'C:/Users/26219/WorkBuddy/软件建模实验/aepds/shot_tracking.png'});

  // 绩效
  await p.click('nav a[href="#/performance"]');
  await p.waitForTimeout(500);
  console.log('perf rows:',await p.$$eval('table.doc tbody tr',e=>e.length));
  await p.screenshot({path:'C:/Users/26219/WorkBuddy/软件建模实验/aepds/shot_performance.png'});

  await b.close();
  console.log('errors =',problems.length);
  problems.forEach(x=>console.log(x));
})().catch(e=>{console.error('FATAL',e.message);process.exit(1);});

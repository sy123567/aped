// server.js — 航空快递揽收与派送管理系统 后端入口
// 服务划分：订单 / 计费 / 揽派调度 / 异常理赔 / 绩效统计
import express from 'express';
import cors from 'cors';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import './db.js'; // 建表 + 列迁移 + 种子数据（必须最先执行）

import { requireAuth, allow } from './core/auth.js';
import authService from './services/auth.js';
import orderService from './services/order.js';
import billingService from './services/billing.js';
import dispatchService from './services/dispatch.js';
import claimService from './services/claim.js';
import performanceService from './services/performance.js';

const app = express();
app.use(cors());
app.use(express.json({ limit: '2mb' }));

// 1) 登录接口与健康检查：不需要登录态
app.use('/api', authService);
app.get('/api/health', (_req, res) =>
  res.json({
    ok: true,
    data: { ts: new Date().toISOString(), services: ['订单服务', '计费服务', '揽派调度', '异常理赔', '绩效统计'] },
  })
);

// 2) 以下全部需要登录；按角色限定模块
app.use('/api', requireAuth);
app.use('/api', orderService);                                      // 订单/客户/轨迹：登录即可（写操作在路由内部再限）
app.use('/api', allow('finance', 'customer'), billingService);       // 计费与账单（客户只读自己的，写操作在路由内部再限）
app.use('/api', allow('dispatcher', 'courier'), dispatchService);   // 揽派调度
app.use('/api', allow('claims'), claimService);                     // 异常理赔
app.use('/api', performanceService);                                // 绩效报表（读接口内部再限）

// 静态托管前端构建产物（若已 build）
const distDir = fileURLToPath(new URL('../frontend/dist', import.meta.url));
if (fs.existsSync(distDir)) {
  app.use(express.static(distDir));
  app.get('*', (req, res, next) =>
    req.path.startsWith('/api') ? next() : res.sendFile(distDir + '/index.html')
  );
}

const PORT = process.env.PORT ?? 3001;

// 统一错误处理：业务规则错误返回 400，其余 500，永远返回 JSON
app.use((err, _req, res, _next) => {
  console.error('[api] error:', err.message);
  res.status(err.biz ? 400 : 500).json({
    ok: false,
    error: err.biz ? err.message : '服务器内部错误：' + err.message,
  });
});

const server = app.listen(PORT, () => console.log(`[api] http://localhost:${PORT}`));

server.on('error', (e) => {
  if (e.code === 'EADDRINUSE') {
    console.error(`\n[api] 端口 ${PORT} 已被占用 —— 大概率是上一次的服务没关干净。`);
    console.error(`      查进程：netstat -ano | findstr :${PORT}`);
    console.error('      杀进程：taskkill /PID <上面查到的PID> /F');
    console.error(`      或换端口：$env:PORT=3002; npm start（前端代理也要跟着改 vite.config.js）\n`);
    process.exit(1);
  }
  throw e;
});

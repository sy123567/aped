// scripts/free-port.js —— npm start 前自动释放端口
// 只杀「占用该端口且进程名是 node.exe」的进程，别的一律不动
import { execSync } from 'node:child_process';
import net from 'node:net';

const PORT = Number(process.env.PORT ?? 3001);

function isBusy(port) {
  return new Promise((resolve) => {
    const srv = net.createServer();
    srv.once('error', (e) => resolve(e.code === 'EADDRINUSE'));
    srv.once('listening', () => srv.close(() => resolve(false)));
    srv.listen(port);
  });
}

function findListenPids(port) {
  const pids = new Set();
  let out = '';
  try {
    out = execSync('netstat -ano', { encoding: 'utf8' });
  } catch {
    return [];
  }
  for (const line of out.split(/\r?\n/)) {
    const f = line.trim().split(/\s+/);
    // TCP  <local>  <foreign>  LISTENING  <pid>
    if (f[0] !== 'TCP') continue;
    if (!f[1] || !f[1].endsWith(`:${port}`)) continue;
    if (!/LISTENING/i.test(f[3] ?? '')) continue;
    if (f[4]) pids.add(f[4]);
  }
  return [...pids];
}

function processName(pid) {
  try {
    // CSV 列顺序：Image Name, PID, Session Name, Session#, Mem Usage
    const out = execSync(`tasklist /FI "PID eq ${pid}" /FO CSV /NH`, { encoding: 'utf8' });
    const first = out.split(',')[0] ?? '';
    return first.replaceAll('"', '').trim();
  } catch {
    return '';
  }
}

const busy = await isBusy(PORT);
if (!busy) {
  // 端口空闲，什么都不用做
  process.exit(0);
}

const pids = findListenPids(PORT);
if (!pids.length) {
  console.log(`[free-port] 端口 ${PORT} 被占用，但查不到监听进程（可能需要管理员权限）。请手动处理。`);
  process.exit(1);
}

let killed = 0;
for (const pid of pids) {
  const name = processName(pid);
  if (name.toLowerCase() !== 'node.exe') {
    console.log(`[free-port] 端口 ${PORT} 被 ${name || '未知进程'}(PID ${pid}) 占用，不是 node，未自动结束。`);
    console.log(`            确认可以关闭后执行：taskkill /PID ${pid} /F`);
    process.exit(1);
  }
  try {
    execSync(`taskkill /PID ${pid} /F`, { stdio: 'ignore' });
    console.log(`[free-port] 已结束上次未退出的服务 node.exe (PID ${pid})，端口 ${PORT} 释放。`);
    killed += 1;
  } catch {
    console.log(`[free-port] 结束 PID ${pid} 失败，请手动执行：taskkill /PID ${pid} /F`);
    process.exit(1);
  }
}

if (!killed) process.exit(1);
// 给系统一点时间回收端口
await new Promise((r) => setTimeout(r, 300));
process.exit(0);

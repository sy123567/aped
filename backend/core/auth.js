// core/auth.js — 口令哈希、会话、鉴权中间件
// 不引第三方库：用 node:crypto 的 scrypt 做加盐哈希（比 md5/sha1 抗暴力得多）
import crypto from 'node:crypto';
import { q, fail, fmtDT } from './helpers.js';

const TTL_HOURS = 12;

export function hashPassword(pwd, salt = crypto.randomBytes(16).toString('hex')) {
  return { salt, hash: crypto.scryptSync(String(pwd), salt, 32).toString('hex') };
}

export function verifyPassword(pwd, salt, hash) {
  const h = crypto.scryptSync(String(pwd), salt, 32).toString('hex');
  const a = Buffer.from(h, 'hex');
  const b = Buffer.from(String(hash), 'hex');
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

export function createSession(user) {
  const token = crypto.randomBytes(24).toString('hex');
  const expires = fmtDT(new Date(Date.now() + TTL_HOURS * 3600e3));
  q.run('INSERT INTO sessions (token,user_id,role,created_at,expires_at) VALUES (?,?,?,?,?)',
    token, user.id, user.role, fmtDT(new Date()), expires);
  return { token, expires_at: expires };
}

export function tokenOf(req) {
  const h = req.headers.authorization ?? '';
  if (h.startsWith('Bearer ')) return h.slice(7).trim();
  // 报表导出走 window.open，带不了请求头，允许查询串传 token
  if (req.query && typeof req.query.token === 'string') return req.query.token;
  return '';
}

export function currentUser(req) {
  const token = tokenOf(req);
  if (!token) return null;
  const s = q.get('SELECT * FROM sessions WHERE token = ?', token);
  if (!s) return null;
  if (String(s.expires_at) <= fmtDT(new Date())) {
    q.run('DELETE FROM sessions WHERE token = ?', token);
    return null;
  }
  const u = q.get('SELECT id,username,role,employee_id,customer_id,display_name,active FROM users WHERE id = ?', s.user_id);
  if (!u || !u.active) return null;
  return u;
}

export function requireAuth(req, res, next) {
  const u = currentUser(req);
  if (!u) return fail(res, '未登录或登录已过期', 401);
  req.user = u;
  next();
}

/** 允许指定角色；admin 恒通过（便于管理端维护） */
export function allow(...roles) {
  return (req, res, next) => {
    if (!req.user) return fail(res, '未登录', 401);
    if (req.user.role === 'admin' || roles.includes(req.user.role)) return next();
    return fail(res, `当前角色（${req.user.role}）无此操作权限`, 403);
  };
}

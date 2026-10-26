// services/auth.js — 登录 / 当前用户 / 退出
import { Router } from 'express';
import { q, ok, fail } from '../core/helpers.js';
import { verifyPassword, createSession, requireAuth, hashPassword, tokenOf } from '../core/auth.js';

const router = Router();

/** 七种角色与它能看到的功能模块（前端按这个渲染菜单，后端按同样的表鉴权） */
export const ROLE_MODULES = {
  admin: ['*'],
  customer: ['my', 'orders', 'tracking'],                       // 客户：我的账户 / 下单 / 运单跟踪
  dispatcher: ['board', 'orders', 'dispatch', 'pickup', 'transit', 'delivery', 'tracking'],
  courier: ['board', 'pickup', 'delivery', 'tracking'],
  claims: ['board', 'exceptions', 'tracking'],
  finance: ['board', 'billing', 'customers', 'reports'],
  ops: ['board', 'performance', 'reports', 'tracking'],
};

export const ROLE_LABEL = {
  admin: '系统管理员',
  customer: '寄件客户',
  dispatcher: '调度员',
  courier: '快递员',
  claims: '理赔专员',
  finance: '财务人员',
  ops: '运营管理员',
};

router.post('/auth/login', (req, res) => {
  const { username, password } = req.body ?? {};
  if (!username || !password) return fail(res, '请输入账号和密码');
  const u = q.get('SELECT * FROM users WHERE username = ?', String(username).trim());
  if (!u || !u.active || !verifyPassword(password, u.salt || '', u.pwd_hash || '')) {
    return fail(res, '账号或密码错误', 401);
  }
  const s = createSession(u);
  ok(res, {
    token: s.token,
    expires_at: s.expires_at,
    user: {
      id: u.id, username: u.username, role: u.role, role_label: ROLE_LABEL[u.role] ?? u.role,
      display_name: u.display_name, employee_id: u.employee_id, customer_id: u.customer_id ?? null,
      modules: ROLE_MODULES[u.role] ?? [],
    },
  });
});

router.get('/auth/me', requireAuth, (req, res) => {
  ok(res, { ...req.user, role_label: ROLE_LABEL[req.user.role] ?? req.user.role, modules: ROLE_MODULES[req.user.role] ?? [] });
});

router.post('/auth/logout', requireAuth, (req, res) => {
  q.run('DELETE FROM sessions WHERE token = ?', tokenOf(req));
  ok(res, {});
});

router.post('/auth/password', requireAuth, (req, res) => {
  const { old_password, new_password } = req.body ?? {};
  const u = q.get('SELECT * FROM users WHERE id = ?', req.user.id);
  if (!u) return fail(res, '账号不存在', 404);
  if (!verifyPassword(old_password ?? '', u.salt || '', u.pwd_hash || '')) return fail(res, '原密码不正确');
  if (!new_password || String(new_password).length < 6) return fail(res, '新密码至少 6 位');
  const { salt, hash } = hashPassword(new_password);
  q.run('UPDATE users SET salt=?, pwd_hash=? WHERE id=?', salt, hash, u.id);
  q.run('DELETE FROM sessions WHERE user_id=?', u.id); // 改密后强制重新登录
  ok(res, { ok: true });
});

export default router;

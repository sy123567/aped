const BASE = '/api';
const TOKEN_KEY = 'aepds.token';
const USER_KEY = 'aepds.user';

export const session = {
  get token() { return localStorage.getItem(TOKEN_KEY) || ''; },
  set token(v) { v ? localStorage.setItem(TOKEN_KEY, v) : localStorage.removeItem(TOKEN_KEY); },
  get user() { try { return JSON.parse(localStorage.getItem(USER_KEY) || 'null'); } catch { return null; } },
  set user(u) { u ? localStorage.setItem(USER_KEY, JSON.stringify(u)) : localStorage.removeItem(USER_KEY); },
  clear() { this.token = ''; this.user = null; },
};

async function call(path, opts = {}) {
  const headers = {};
  if (opts.body) headers['Content-Type'] = 'application/json';
  if (session.token) headers.Authorization = 'Bearer ' + session.token;

  const r = await fetch(BASE + path, {
    method: opts.method ?? 'GET',
    headers,
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  });

  if (r.status === 401) {
    session.clear();
    if (!location.hash.includes('/login')) location.hash = '#/login';
    throw new Error('登录已过期，请重新登录');
  }
  const j = await r.json().catch(() => ({ ok: false, error: '服务无响应' }));
  if (!j.ok) throw new Error(j.error ?? '请求失败');
  return j.data;
}

export const api = {
  // 账号
  login: (username, password) => call('/auth/login', { method: 'POST', body: { username, password } }),
  me: () => call('/auth/me'),
  logout: () => call('/auth/logout', { method: 'POST' }),
  changePassword: (old_password, new_password) =>
    call('/auth/password', { method: 'POST', body: { old_password, new_password } }),

  // 工作台 / 规则
  board: () => call('/board'),
  health: () => call('/health'),
  dispatchRule: () => call('/dispatch/rule'),
  capacity: () => call('/dispatch/capacity'),

  // 订单服务
  orders: () => call('/orders'),
  createOrder: (b) => call('/orders', { method: 'POST', body: b }),
  quote: (b) => call('/orders/quote', { method: 'POST', body: b }),
  cancelOrder: (id, reason) => call(`/orders/${id}/cancel`, { method: 'POST', body: { reason } }),
  customers: () => call('/customers'),
  customer: (id) => call('/customers/' + id),
  serviceAreas: (addr) => call('/service-areas?addr=' + encodeURIComponent(addr ?? '')),
  tracking: (no) => call('/tracking/' + encodeURIComponent(no)),
  waybills: () => call('/waybills'),

  // 计费服务
  rates: () => call('/rates'),
  pricingRules: () => call('/pricing/rules'),
  bills: () => call('/billing'),
  bill: (id) => call('/billing/' + id),
  generateBill: (b) => call('/billing/generate', { method: 'POST', body: b }),
  settleBill: (b) => call('/billing/settle', { method: 'POST', body: b }),
  freezeItem: (id, frozen) => call(`/billing/items/${id}/freeze`, { method: 'POST', body: { frozen } }),

  // 揽派调度
  employees: (role) => call('/employees' + (role ? '?role=' + role : '')),
  flights: () => call('/flights'),
  pickupTasks: () => call('/pickup/tasks'),
  pickupCandidates: (orderId) => call('/pickup/candidates?order_id=' + orderId),
  deliveryCandidates: (no) => call('/delivery/candidates?waybill_no=' + encodeURIComponent(no)),
  assignPickup: (b) => call('/pickup/assign', { method: 'POST', body: b }),
  acceptPickup: (id) => call('/pickup/accept', { method: 'POST', body: { id } }),
  rollbackPickup: (id, reason) => call('/pickup/rollback', { method: 'POST', body: { id, reason } }),
  pickup: (b) => call('/pickup', { method: 'POST', body: b }),
  transitList: (no) => call('/transit' + (no ? '?waybill_no=' + encodeURIComponent(no) : '')),
  transitSuggest: (no) => call('/transit/suggest?waybill_no=' + encodeURIComponent(no)),
  transit: (b) => call('/transit', { method: 'POST', body: b }),
  deliveryTasks: () => call('/delivery/tasks'),
  assignDelivery: (b) => call('/delivery/assign', { method: 'POST', body: b }),
  sign: (b) => call('/delivery/sign', { method: 'POST', body: b }),
  failDelivery: (b) => call('/delivery/fail', { method: 'POST', body: b }),

  // 异常理赔
  exceptions: () => call('/exceptions'),
  exception: (id) => call('/exceptions/' + id),
  createException: (b) => call('/exceptions', { method: 'POST', body: b }),
  closeException: (id, reason) => call(`/exceptions/${id}/close`, { method: 'POST', body: { reason } }),
  assessException: (id, severity) =>
    call('/exceptions/' + id + '/assess' + (severity ? '?severity=' + encodeURIComponent(severity) : '')),
  claims: () => call('/claims'),
  createClaim: (b) => call('/claims', { method: 'POST', body: b }),
  approveClaim: (b) => call('/claims/approve', { method: 'POST', body: b }),

  // 绩效统计
  performance: (period) => call('/performance' + (period ? '?period=' + period : '')),
  summary: (period) => call('/reports/summary' + (period ? '?period=' + period : '')),
  preview: (type, period) => call(`/reports/preview?type=${type}&period=${period ?? ''}`),
  exportUrl: (type, format, period) =>
    `${BASE}/reports/export?type=${type}&format=${format}&period=${encodeURIComponent(period ?? '')}&token=${encodeURIComponent(session.token)}`,
};

export const fmt = (n) => '¥' + Number(n ?? 0).toFixed(2);
export const STATUS = {
  待派单: 'wait', 待揽收: 'wait', 待接单: 'wait', 已接单: 'transit',
  已揽收: 'transit', 转运中: 'transit', 派送中: 'transit', 待派送: 'wait',
  已签收: 'done', 已理赔: 'done', 已结清: 'done', 已关闭: 'done',
  已取消: 'fail', 已回滚: 'fail', 派送异常: 'fail', 异常: 'fail',
  已驳回: 'fail', 待处理: 'wait', 待审批: 'wait', 部分核销: 'transit', 待确认: 'wait',
};
export const tag = (s) => `<span class="tag ${STATUS[s] ?? 'wait'}">${s}</span>`;

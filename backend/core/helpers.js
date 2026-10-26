// core/helpers.js — 响应封装、事务、状态机、计费引擎、编号生成
import { db } from '../db.js';

export const ok = (res, data) => res.json({ ok: true, data });
export const fail = (res, msg, code = 400) => res.status(code).json({ ok: false, error: msg });

/** 业务规则错误：抛给统一错误处理，返回 400 而不是 500 */
export const biz = (msg) => Object.assign(new Error(msg), { biz: true });

const p2 = (n) => String(n).padStart(2, '0');
export const fmtDT = (d) => {
  const x = d instanceof Date ? d : new Date(d);
  return `${x.getFullYear()}-${p2(x.getMonth() + 1)}-${p2(x.getDate())} ${p2(x.getHours())}:${p2(x.getMinutes())}:${p2(x.getSeconds())}`;
};
export const now = () => fmtDT(new Date());

export const q = {
  all: (sql, ...p) => db.prepare(sql).all(...p),
  get: (sql, ...p) => db.prepare(sql).get(...p),
  run: (sql, ...p) => db.prepare(sql).run(...p),
};

/** 事务：多表写入必须包起来，否则中途失败会留脏数据 */
export function tx(fn) {
  db.exec('BEGIN');
  try {
    const r = fn();
    db.exec('COMMIT');
    return r;
  } catch (e) {
    try { db.exec('ROLLBACK'); } catch { /* ignore */ }
    throw e;
  }
}

/** 业务编号：前缀 + yymmdd + 定长流水，唯一性靠回查保证 */
export function nextNo(prefix, table, col, width = 5) {
  const d = new Date();
  const base = `${prefix}${String(d.getFullYear()).slice(2)}${p2(d.getMonth() + 1)}${p2(d.getDate())}`;
  const c = q.get(`SELECT COUNT(*) c FROM ${table} WHERE ${col} LIKE ?`, base + '%')?.c ?? 0;
  let n = c + 1;
  let no = base + String(n).padStart(width, '0');
  while (q.get(`SELECT 1 x FROM ${table} WHERE ${col} = ?`, no)) {
    n += 1;
    no = base + String(n).padStart(width, '0');
  }
  return no;
}

/** 轨迹事件：运单维度或订单维度（下单时还没有运单号） */
export function event(waybill_no, node, desc_text, order_no = null) {
  q.run(
    'INSERT INTO events (waybill_no,order_no,node,desc_text,created_at) VALUES (?,?,?,?,?)',
    waybill_no ?? '', order_no, node, desc_text, now()
  );
}

// ---------------- 计费引擎 ----------------
const FALLBACK = {
  name: '隔日达', coef: 1.0, promise_hours: 48,
  first_kg: 1, first_price: 12, cont_price: 4, vol_divisor: 6000,
};

export function getRates() {
  try {
    const rows = q.all('SELECT * FROM products WHERE active=1 ORDER BY sort');
    return rows.length ? rows : [FALLBACK];
  } catch {
    return [FALLBACK];
  }
}

export function findRate(product) {
  const rates = getRates();
  return rates.find((r) => r.name === product) ?? rates.find((r) => r.name === '隔日达') ?? FALLBACK;
}

/**
 * 首重续重 + 产品时效系数 + 体积重量折算 + 保价费
 * 体积重量沿用界面约定：体积(L) ÷ 体积除数
 */
export function calcFee({ product, weight, volumeL, insured }) {
  const r = findRate(product);
  const w = Number(weight) || 0;
  const vl = Number(volumeL) || 0;
  const volWeight = vl && r.vol_divisor ? vl / r.vol_divisor : 0;
  const chargeKg = Math.max(w, volWeight, 0.01);
  const contKg = Math.max(0, Math.ceil(chargeKg - r.first_kg));
  const freight = (r.first_price + contKg * r.cont_price) * r.coef;
  const insuredFee = (Number(insured) || 0) * 0.003;
  const fee = +(freight + insuredFee).toFixed(2);
  const detail =
    `首重${r.first_kg}kg×¥${r.first_price}` +
    (contKg ? ` + 续重${contKg}kg×¥${r.cont_price}` : '') +
    ` ×时效系数${r.coef}` +
    (volWeight > w ? `（体积重量${volWeight.toFixed(2)}kg）` : '') +
    (insured ? ` + 保价费¥${insuredFee.toFixed(2)}` : '');
  return { fee, detail, chargeKg: +chargeKg.toFixed(2), product: r.name, coef: r.coef };
}

export function promiseTime(product) {
  const r = findRate(product);
  return fmtDT(new Date(Date.now() + r.promise_hours * 3600e3));
}

// ---------------- 运单状态机 ----------------
export const WB_FLOW = {
  待揽收: ['已揽收', '已关闭'],
  已揽收: ['转运中', '异常', '已关闭'],
  转运中: ['派送中', '异常'],
  派送中: ['已签收', '异常'],
  异常: ['派送中', '已签收', '已关闭'],
  已签收: [],
  已关闭: [],
};

export const canTransit = (from, to) => (WB_FLOW[from] ?? []).includes(to);

/** 运单状态唯一入口：非法转移直接拒绝 */
export function setWaybillStatus(no, to, extraSql = '', ...extraArgs) {
  const w = q.get('SELECT status FROM waybills WHERE waybill_no=?', no);
  if (!w) return { ok: false, error: '运单不存在' };
  if (w.status === to) return { ok: true };
  if (!canTransit(w.status, to)) {
    return { ok: false, error: `运单状态不能从「${w.status}」变更为「${to}」` };
  }
  q.run(`UPDATE waybills SET status=?${extraSql} WHERE waybill_no=?`, to, ...extraArgs, no);
  return { ok: true };
}

// ---------------- 服务范围 ----------------
/**
 * 宽松校验：能匹配到区县记录且 supported=0 才拒绝；
 * 匹配不到视为「未收录」，放行并在返回值里标注，避免误杀演示数据。
 */
export function checkArea(addr) {
  if (!addr) return { supported: true, checked: false, note: '' };
  try {
    const rows = q.all('SELECT province,city,district,supported FROM service_areas');
    const hit = rows.find((r) => addr.includes(r.district) || addr.includes(r.city));
    if (hit && !hit.supported) {
      return {
        supported: false,
        checked: true,
        note: `${hit.city}${hit.district} 暂不支持上门揽收，建议就近网点自寄`,
      };
    }
    return { supported: true, checked: !!hit, note: hit ? `${hit.city}${hit.district} 在服务范围内` : '' };
  } catch {
    return { supported: true, checked: false, note: '' };
  }
}

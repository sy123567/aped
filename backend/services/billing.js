// services/billing.js — 计费服务
// ② 运单生成与首重续重计费（产品时效差异）　⑧ 客户月结账单管理
import { Router } from 'express';
import { ok, fail, now, q, tx, nextNo, getRates, promiseTime, findRate } from '../core/helpers.js';
import { computeFee, pricingMeta, getRules } from '../core/pricing.js';
import { allow } from '../core/auth.js';

const router = Router();

/* ---------- 运价表（可配置，改价不用改代码） ---------- */
router.get('/rates', (_req, res) => ok(res, getRates()));

router.put('/rates/:code', allow('finance'), (req, res) => {
  const r = q.get('SELECT * FROM products WHERE code=?', req.params.code);
  if (!r) return fail(res, '产品不存在', 404);
  const b = req.body ?? {};
  const num = (v, d) => (v == null || v === '' ? d : Number(v));
  q.run(
    `UPDATE products SET coef=?, first_kg=?, first_price=?, cont_price=?, vol_divisor=?, promise_hours=? WHERE code=?`,
    num(b.coef, r.coef), num(b.first_kg, r.first_kg), num(b.first_price, r.first_price),
    num(b.cont_price, r.cont_price), num(b.vol_divisor, r.vol_divisor), num(b.promise_hours, r.promise_hours),
    r.code
  );
  ok(res, q.get('SELECT * FROM products WHERE code=?', r.code));
});

/* ---------- 计费规则：分区系数 / 燃油 / 最低收费 / 阶梯 ---------- */
router.get('/pricing/rules', (_req, res) => ok(res, pricingMeta()));

router.put('/pricing/rules/:key', allow('finance'), (req, res) => {
  const key = req.params.key;
  const cur = q.get('SELECT * FROM pricing_rules WHERE key=?', key);
  if (!cur) return fail(res, '规则不存在', 404);
  const v = Number(req.body?.value);
  if (!Number.isFinite(v)) return fail(res, 'value 必须是数字');
  q.run('UPDATE pricing_rules SET value=? WHERE key=?', v, key);
  ok(res, { key, value: v, note: cur.note });
});

/* ---------- ⑧ 月结账单 ---------- */
router.get('/billing', (req, res) => {
  const own = req.user.role === 'customer';
  ok(res, q.all(`SELECT b.*, c.name AS customer_name, c.monthly
                 FROM bills b JOIN customers c ON c.id=b.customer_id
                 ${own ? 'WHERE b.customer_id = ?' : ''}
                 ORDER BY b.id DESC`, ...(own ? [req.user.customer_id] : [])));
});

router.post('/billing/generate', allow('finance'), (req, res) => {
  const { customer_id, period } = req.body ?? {};
  if (!customer_id || !period) return fail(res, 'customer_id 与 period 必填');
  const c = q.get('SELECT * FROM customers WHERE id=?', customer_id);
  if (!c) return fail(res, '客户不存在', 404);
  if (!c.monthly) return fail(res, '该客户未开通月结协议');

  const dup = q.get('SELECT * FROM bills WHERE customer_id=? AND period=?', customer_id, period);
  if (dup) return fail(res, `该账期已生成账单 ${dup.bill_no}，请勿重复出账`);

  const rows = q.all(
    `SELECT w.waybill_no, w.fee, w.product, w.signed_at FROM waybills w JOIN orders o ON o.id=w.order_id
     WHERE o.customer_id=? AND substr(w.created_at,1,7)=? AND w.status='已签收'`,
    customer_id, period
  );
  if (!rows.length) return fail(res, '该账期内没有已签收的计费运单');

  const amount = +rows.reduce((s, r) => s + (r.fee ?? 0), 0).toFixed(2);
  const bill_no = nextNo('BILL', 'bills', 'bill_no', 4);

  tx(() => {
    const r = q.run(
      `INSERT INTO bills (bill_no,customer_id,period,amount,item_count,status,paid_amount,receipt_count)
       VALUES (?,?,?,?,?,?,0,0)`,
      bill_no, customer_id, period, amount, rows.length, '待确认'
    );
    const billId = r.lastInsertRowid;
    const ins = q.run;
    for (const it of rows) {
      ins('INSERT INTO bill_items (bill_id,waybill_no,product,fee,signed_at) VALUES (?,?,?,?,?)',
        billId, it.waybill_no, it.product ?? '', it.fee ?? 0, it.signed_at ?? null);
    }
  });

  const bill = q.get('SELECT * FROM bills WHERE bill_no=?', bill_no);
  ok(res, { bill_no, amount, item_count: rows.length, bill_id: bill.id });
});

/* ---------- 账单详情：含运单级明细（对账依据） ---------- */
router.get('/billing/:id', (req, res) => {
  const b = q.get('SELECT b.*, c.name AS customer_name FROM bills b JOIN customers c ON c.id=b.customer_id WHERE b.id=?', req.params.id);
  if (!b) return fail(res, '账单不存在', 404);
  if (req.user.role === 'customer' && b.customer_id !== req.user.customer_id) {
    return fail(res, '只能查看自己的账单', 403);
  }
  ok(res, {
    bill: b,
    items: q.all('SELECT * FROM bill_items WHERE bill_id=? ORDER BY id', b.id),
    receipts: q.all('SELECT * FROM receipts WHERE bill_id=? ORDER BY id', b.id),
    frozen_amount: +q.get('SELECT COALESCE(SUM(fee),0) s FROM bill_items WHERE bill_id=? AND frozen=1', b.id).s.toFixed(2),
  });
});

/* ---------- 费用争议：冻结/解冻单条明细并重算账单金额 ---------- */
router.post('/billing/items/:id/freeze', allow('finance'), (req, res) => {
  const it = q.get('SELECT * FROM bill_items WHERE id=?', req.params.id);
  if (!it) return fail(res, '明细不存在', 404);
  const bill0 = q.get('SELECT * FROM bills WHERE id=?', it.bill_id);
  if (bill0?.status === '已结清') return fail(res, '账单已结清，不能再冻结/解冻明细');
  const frozen = req.body?.frozen === false ? 0 : 1;
  tx(() => {
    q.run('UPDATE bill_items SET frozen=? WHERE id=?', frozen, it.id);
    const s = q.get('SELECT COALESCE(SUM(fee),0) s FROM bill_items WHERE bill_id=? AND frozen=0', it.bill_id);
    q.run('UPDATE bills SET amount=? WHERE id=?', +s.s.toFixed(2), it.bill_id);
  });
  const b = q.get('SELECT * FROM bills WHERE id=?', it.bill_id);
  ok(res, { bill_id: b.id, amount: b.amount, frozen: !!frozen, waybill_no: it.waybill_no });
});

/* ---------- 回款核销：写流水 + 累计已收 ---------- */
router.post('/billing/settle', allow('finance'), (req, res) => {
  const { id, amount, paid: paidAlias, voucher, operator } = req.body ?? {};
  const b = q.get('SELECT * FROM bills WHERE id=?', id);
  if (!b) return fail(res, '账单不存在', 404);
  if (b.status === '已结清') return fail(res, '该账单已结清');

  const remain = +(b.amount - (b.paid_amount ?? 0)).toFixed(2);
  const inAmount = amount ?? paidAlias; // paid 为旧前端字段名，保留兼容
  const paid = inAmount == null || inAmount === '' ? remain : Number(inAmount);
  if (!(paid > 0)) return fail(res, '核销金额须大于 0');
  if (paid > remain + 1e-6) return fail(res, `核销金额不能超过待收余额 ¥${remain}`);

  const nextPaid = +((b.paid_amount ?? 0) + paid).toFixed(2);
  const status = nextPaid >= b.amount - 1e-6 ? '已结清' : '部分核销';

  tx(() => {
    q.run('INSERT INTO receipts (bill_id,amount,paid_at,voucher,operator) VALUES (?,?,?,?,?)',
      b.id, paid, now(), voucher ?? '', operator ?? '财务');
    q.run('UPDATE bills SET paid_amount=?, receipt_count=COALESCE(receipt_count,0)+1, status=? WHERE id=?',
      nextPaid, status, b.id);
  });

  ok(res, { status, paid_amount: nextPaid, remaining: +(b.amount - nextPaid).toFixed(2) });
});

router.get('/billing/:id/receipts', (req, res) => {
  ok(res, q.all('SELECT * FROM receipts WHERE bill_id=? ORDER BY id', req.params.id));
});

/* ---------- 供揽收登记调用：生成运单并计费（分区 + 阶梯续重 + 附加费 + 协议折扣） ---------- */
export function createWaybillForOrder({ orderId, product, weight, volumeL, insured, length = 0, width = 0, height = 0, pack = false }) {
  const order = q.get('SELECT * FROM orders WHERE id=?', orderId);
  const cust = order?.customer_id ? q.get('SELECT * FROM customers WHERE id=?', order.customer_id) : null;

  const f = computeFee({
    product, weight, volumeL, insured,
    destAddr: order?.recv_addr ?? '', length, width, height, pack, customer: cust,
  });
  const rate = findRate(f.product);
  const waybill_no = nextNo('88', 'waybills', 'waybill_no', 5);
  const pt = promiseTime(f.product);

  q.run(
    `INSERT INTO waybills (waybill_no,order_id,product,first_kg,first_price,cont_price,vol_divisor,insured,
                           fee,fee_detail,status,promise_time,fee_snapshot,dest_zone)
     VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
    waybill_no, orderId, f.product, rate.first_kg, rate.first_price, rate.cont_price, rate.vol_divisor,
    Number(insured) || 0, f.total, f.detail, '已揽收', pt,
    JSON.stringify({
      chargeKg: f.chargeKg, rawKg: f.rawKg, volWeight: f.volWeight, dims: f.dims,
      zone: f.zone, zone_label: f.zone_label, zone_coef: f.zone_coef,
      product: f.product, weight: Number(weight), volumeL: Number(volumeL) || 0,
      insured: Number(insured) || 0, items: f.items, total: f.total,
      rules_version: 'pricing-v3-dims',
    }),
    f.zone
  );

  return { waybill_no, fee: f.total, detail: f.detail, promise_time: pt, items: f.items, zone: f.zone, chargeKg: f.chargeKg, volWeight: f.volWeight, dims: f.dims };
}

export default router;

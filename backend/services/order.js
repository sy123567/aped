// services/order.js — 订单服务
// ① 快递下单与取件预约登记　⑦ 运单全程轨迹查询　⑧ 客户账户
import { Router } from 'express';
import { ok, fail, now, q, event, nextNo, tx, checkArea, promiseTime } from '../core/helpers.js';
import { computeFee } from '../core/pricing.js';
import { allow } from '../core/auth.js';

const router = Router();

/* ---------- 运费试算（分区 + 阶梯续重 + 附加费 + 协议折扣） ---------- */
router.post('/orders/quote', (req, res) => {
  const b = req.body ?? {};
  if (!(Number(b.weight) > 0)) return fail(res, '请填写预估重量');
  const customer = b.customer_id ? q.get('SELECT * FROM customers WHERE id=?', b.customer_id) : null;
  const f = computeFee({
    product: b.product, weight: b.weight, volumeL: b.volumeL ?? 0, insured: b.insured ?? 0,
    destAddr: b.dest_addr ?? b.recv_addr ?? '', length: b.length ?? 0, pack: !!b.pack, customer,
  });
  ok(res, { ...f, promise_time: promiseTime(f.product) });
});

/* ---------- ① 下单与取件预约登记 ---------- */
router.post('/orders', allow('dispatcher', 'customer'), (req, res) => {
  const b = req.body ?? {};
  // 客户自己下单：客户身份锁定为本人；后台（调度）可代客下单
  const customerId = req.user.role === 'customer' ? req.user.customer_id : (b.customer_id ?? null);
  if (!b.sender || !b.recv_name || !b.recv_addr) return fail(res, '寄件人、收件人、收件地址必填');
  const hasWeight = b.weight != null && b.weight !== '';
  if (hasWeight && (Number(b.weight) <= 0 || Number(b.weight) > 1000)) {
    return fail(res, '预估重量须在 0.01~1000kg');
  }
  if (!b.slot) return fail(res, '请选择上门取件时段');

  const area = checkArea(b.recv_addr);
  if (!area.supported) return fail(res, area.note);

  const cust = customerId ? q.get('SELECT * FROM customers WHERE id=?', customerId) : null;
  const est = computeFee({ product: b.product, weight: b.weight ?? 1, destAddr: b.recv_addr, customer: cust });
  const order_no = nextNo('SO', 'orders', 'order_no');

  const id = tx(() => {
    const r = q.run(
      `INSERT INTO orders (order_no,customer_id,sender,recv_name,recv_phone,recv_addr,category,weight,slot,status,est_fee,area_note)
       VALUES (?,?,?,?,?,?,?,?,?,?,?,?)`,
      order_no, customerId, b.sender, b.recv_name, b.recv_phone ?? '', b.recv_addr,
      b.category ?? '', b.weight ?? null, b.slot, '待派单', est.total, area.note
    );
    event('', '下单受理',
      `${b.recv_addr}，预估运费 ¥${est.total}（${est.zone_label}），取件时段 ${b.slot}`, order_no);
    return r.lastInsertRowid;
  });

  ok(res, {
    id, order_no, est_fee: est.total, est_detail: est.detail,
    zone: est.zone, zone_label: est.zone_label, items: est.items, area_note: area.note,
  });
});

router.get('/orders', (req, res) => {
  const own = req.user.role === 'customer';
  ok(res, q.all(`SELECT o.*, c.name AS customer_name, c.monthly
                 FROM orders o LEFT JOIN customers c ON c.id=o.customer_id
                 ${own ? 'WHERE o.customer_id = ?' : ''}
                 ORDER BY o.id DESC`, ...(own ? [req.user.customer_id] : [])));
});

/* ---------- ① 备选流：客户取消 ---------- */
router.post('/orders/:id/cancel', allow('dispatcher', 'customer'), (req, res) => {
  const o = q.get('SELECT * FROM orders WHERE id=?', req.params.id);
  if (!o) return fail(res, '订单不存在', 404);
  if (req.user.role === 'customer' && o.customer_id !== req.user.customer_id) {
    return fail(res, '只能取消自己的订单', 403);
  }
  if (o.status === '已取消') return fail(res, '该订单已取消');
  if (!['待派单', '待揽收'].includes(o.status)) return fail(res, `订单已${o.status}，不能取消`);
  const reason = req.body?.reason || '客户主动取消';
  tx(() => {
    q.run(`UPDATE orders SET status='已取消', cancel_reason=?, cancelled_at=? WHERE id=?`, reason, now(), o.id);
    q.run(`UPDATE pickup_tasks SET status='已回滚', rollback_reason='订单取消' WHERE order_id=? AND status IN ('待接单')`, o.id);
    event('', '订单取消', reason, o.order_no);
  });
  ok(res, { order_no: o.order_no, status: '已取消' });
});

/* ---------- 运单列表 ---------- */
router.get('/waybills', (req, res) => {
  const own = req.user.role === 'customer';
  ok(res, q.all(`SELECT w.*, o.order_no, o.recv_name, o.recv_addr, o.sender, o.customer_id
                 FROM waybills w LEFT JOIN orders o ON o.id=w.order_id
                 ${own ? 'WHERE o.customer_id = ?' : ''}
                 ORDER BY w.id DESC`, ...(own ? [req.user.customer_id] : [])));
});

/* ---------- ⑦ 运单全程轨迹查询（支持运单号 / 订单号） ---------- */
router.get('/tracking/:no', (req, res) => {
  const no = req.params.no;
  let w = q.get('SELECT * FROM waybills WHERE waybill_no=?', no);
  const order = w
    ? q.get('SELECT * FROM orders WHERE id=?', w.order_id)
    : q.get('SELECT * FROM orders WHERE order_no=?', no);
  if (!w && !order) return fail(res, '未找到该运单号或订单号的跟踪记录', 404);
  if (req.user.role === 'customer' && order && order.customer_id !== req.user.customer_id) {
    return fail(res, '只能查询自己的运单', 403);
  }

  const keyOrder = order?.order_no ?? no;
  const evs = q.all(
    'SELECT node, desc_text, created_at FROM events WHERE waybill_no=? OR order_no=? ORDER BY id',
    w?.waybill_no ?? no, keyOrder
  );
  const onTime = w?.signed_at && w?.promise_time
    ? w.signed_at <= w.promise_time
    : null;
  ok(res, { waybill: w ?? null, order: order ?? null, events: evs, on_time: onTime });
});

/* ---------- ⑧ 客户账户 ---------- */
router.get('/customers', (req, res) => {
  const own = req.user.role === 'customer';
  ok(res, q.all(`
    SELECT c.*,
      (SELECT COUNT(*) FROM orders o WHERE o.customer_id=c.id AND o.status!='已取消') AS order_cnt,
      (SELECT COALESCE(SUM(w.fee),0) FROM waybills w JOIN orders o ON o.id=w.order_id WHERE o.customer_id=c.id) AS total_fee,
      (SELECT COUNT(*) FROM bills b WHERE b.customer_id=c.id) AS bill_cnt,
      (SELECT COALESCE(SUM(b.amount-b.paid_amount),0) FROM bills b
        WHERE b.customer_id=c.id AND b.status!='已结清') AS unpaid
    FROM customers c ${own ? 'WHERE c.id = ?' : ''} ORDER BY c.id`,
    ...(own ? [req.user.customer_id] : [])));
});

router.get('/customers/:id', (req, res) => {
  if (req.user.role === 'customer' && Number(req.params.id) !== req.user.customer_id) {
    return fail(res, '只能查看自己的账户', 403);
  }
  const c = q.get('SELECT * FROM customers WHERE id=?', req.params.id);
  if (!c) return fail(res, '客户不存在', 404);
  ok(res, {
    customer: c,
    orders: q.all('SELECT * FROM orders WHERE customer_id=? ORDER BY id DESC', c.id),
    waybills: q.all(`SELECT w.*, o.order_no FROM waybills w JOIN orders o ON o.id=w.order_id
                     WHERE o.customer_id=? ORDER BY w.id DESC`, c.id),
    bills: q.all('SELECT * FROM bills WHERE customer_id=? ORDER BY id DESC', c.id),
  });
});

/* ---------- 服务范围查询 ---------- */
router.get('/service-areas', (req, res) => {
  const addr = req.query.addr ?? '';
  ok(res, { ...checkArea(addr), areas: q.all('SELECT * FROM service_areas ORDER BY id') });
});

export default router;

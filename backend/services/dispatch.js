// services/dispatch.js — 揽派调度服务
// ③ 揽收任务分配与上门揽收登记　④ 分拣中转登记管理　⑤ 派件任务分配与签收管理
import { Router } from 'express';
import { ok, fail, now, q, event, tx, setWaybillStatus, biz } from '../core/helpers.js';
import { createWaybillForOrder } from './billing.js';
import { assignOrders, pickCourierForAddr, candidatesForAddr, courierLoad, reasonOf, RULE_SQL, CAP } from '../core/assign.js';
import { planRoute, suggestForWaybill } from '../core/routing.js';

const router = Router();
const MAX_INFLIGHT = CAP; // 与分配引擎共用同一个上限

/* ---------- 运力看板（派单页顶部指标 + 快递员负载） ---------- */
router.get('/dispatch/capacity', (_req, res) => {
  const couriers = q.all(`SELECT id, code, name, grid_code FROM employees WHERE role='courier'`)
    .map((e) => {
      const load = courierLoad(e.id);
      return { ...e, load, capacity: CAP, full: load >= CAP, util: +(load / CAP * 100).toFixed(0) };
    })
    .sort((a, b) => b.load - a.load);
  const pending = q.get(`SELECT COUNT(*) c FROM orders WHERE status='待派单'`)?.c ?? 0;
  const todayAssigned = q.get(
    `SELECT COUNT(*) c FROM pickup_tasks WHERE date(assigned_at)=date('now','localtime')`
  )?.c ?? 0;
  const totalCap = couriers.length * CAP;
  const totalLoad = couriers.reduce((s, x) => s + x.load, 0);
  ok(res, {
    couriers,
    pending_orders: pending,
    today_assigned: todayAssigned,
    total_capacity: totalCap,
    total_load: totalLoad,
    utilization: totalCap ? +(totalLoad / totalCap * 100).toFixed(1) : 0,
  });
});

/* ---------- 分配规则说明（规则就是一条 SQL，不藏逻辑） ---------- */
router.get('/dispatch/rule', (_req, res) => {
  ok(res, {
    name: '同片区优先 · 负载最低优先',
    summary: '在未满载的快递员里，先看是不是他的片区，再比谁手上单少，最后按工号定序。',
    capacity: CAP,
    sql: RULE_SQL,
    steps: [
      '① 只取在途 < 20 单的快递员（硬约束）',
      '② 订单地址按「区县 → 网格码」匹配，同片区排前面',
      '③ 同片区里挑在途最少的；都不匹配片区就挑全局在途最少的',
      '④ 派完立刻落库，下一单重新查，负载自然递增，不会堆给同一个人',
    ],
  });
});

/* ---------- 作业基础数据 ---------- */
router.get('/employees', (req, res) => {
  const role = req.query.role;
  ok(res, role
    ? q.all('SELECT * FROM employees WHERE role=? ORDER BY id', role)
    : q.all('SELECT * FROM employees ORDER BY id'));
});

router.get('/flights', (_req, res) => ok(res, q.all('SELECT * FROM flights ORDER BY flight_no')));

/* ---------- ③ 揽收任务 ---------- */
router.get('/pickup/tasks', (req, res) => {
  const where = req.query.status ? 'WHERE t.status=?' : '';
  const args = req.query.status ? [req.query.status] : [];
  ok(res, q.all(`SELECT t.id, o.order_no, t.status, t.assigned_at, t.accepted_at, t.rollback_reason,
                        e.name AS courier, e.grid_code, o.sender, o.recv_addr, o.slot, o.weight, o.category
                 FROM pickup_tasks t JOIN orders o ON o.id=t.order_id
                 LEFT JOIN employees e ON e.id=t.courier_id ${where} ORDER BY t.id DESC`, ...args));
});

/* ---------- ③ 候选快递员：由数据库视图排序，前端只展示 ---------- */
router.get('/pickup/candidates', (req, res) => {
  const order = q.get('SELECT * FROM orders WHERE id=?', req.query.order_id);
  if (!order) return fail(res, '订单不存在', 404);
  // 待派单走视图（含排名 rn）；其他状态退化为按地址直接排
  let list = q.all('SELECT * FROM v_assign_pickup WHERE order_id=? ORDER BY rn', order.id);
  if (list.length) {
    list = list.map((c) => ({
      id: c.courier_id, code: c.courier_code, name: c.courier_name, grid_code: c.grid_code,
      load: c.load, capacity: c.capacity, rank: c.rn, same_area: !!c.same_area,
      reason: reasonOf(c),
    }));
  } else {
    list = candidatesForAddr(order.recv_addr ?? '').map((c, i) => ({
      ...c, rank: i + 1, reason: reasonOf(c),
    }));
  }
  ok(res, {
    order_no: order.order_no, recv_addr: order.recv_addr, slot: order.slot,
    order_grid: list[0]?.grid_code ?? null, capacity: CAP, candidates: list,
  });
});

/* ---------- ③ 揽收任务分配：SQL 定人，不问人 ---------- */
router.post('/pickup/assign', (req, res) => {
  const b = req.body ?? {};
  const ids = Array.isArray(b.order_ids) ? b.order_ids.map(Number).filter(Boolean) : [];
  if (!ids.length) return fail(res, 'order_ids 必填');

  const result = tx(() => assignOrders(ids, {
    onAssign: (o, c) => {
      q.run(`UPDATE orders SET status='待揽收' WHERE id=?`, o.id);
      q.run('INSERT INTO pickup_tasks (order_id,courier_id,status,assigned_at) VALUES (?,?,?,?)',
        o.id, c.id, '待接单', now());
      event('', '揽收任务已分配', `自动分配 → ${c.name}｜${reasonOf(c)}`, o.order_no);
    },
  }));

  if (!result.assignments.length) {
    return fail(res, result.unassigned[0]?.why ?? '没有可分配的订单');
  }
  ok(res, { assigned: result.assignments.length, assignments: result.assignments, unassigned: result.unassigned });
});

router.post('/pickup/accept', (req, res) => {
  const t = q.get('SELECT * FROM pickup_tasks WHERE id=?', req.body?.id);
  if (!t) return fail(res, '任务不存在', 404);
  if (t.status !== '待接单') return fail(res, `任务状态为「${t.status}」，不能接单`);
  q.run(`UPDATE pickup_tasks SET status='已接单', accepted_at=? WHERE id=?`, now(), t.id);
  ok(res, {});
});

/* ---------- ③ 备选流：超时未接单回滚 ---------- */
router.post('/pickup/rollback', (req, res) => {
  const t = q.get(`SELECT t.*, o.order_no FROM pickup_tasks t JOIN orders o ON o.id=t.order_id WHERE t.id=?`, req.body?.id);
  if (!t) return fail(res, '任务不存在', 404);
  if (t.status !== '待接单') return fail(res, `任务状态为「${t.status}」，不能回滚`);
  tx(() => {
    q.run(`UPDATE pickup_tasks SET status='已回滚', rollback_reason=? WHERE id=?`, req.body?.reason ?? '超时未接单', t.id);
    q.run(`UPDATE orders SET status='待派单' WHERE id=?`, t.order_id);
    event('', '揽收任务已回滚', req.body?.reason ?? '超时未接单', t.order_no);
  });
  ok(res, { order_no: t.order_no, status: '已回滚' });
});

/* ---------- ③+② 上门揽收登记（称重 → 生成运单 → 计费） ---------- */
router.post('/pickup', (req, res) => {
  const b = req.body ?? {};
  const task = q.get(
    `SELECT t.*, o.order_no, o.customer_id FROM pickup_tasks t JOIN orders o ON o.id=t.order_id WHERE t.id=?`,
    b.task_id
  );
  if (!task) return fail(res, '揽收任务不存在', 404);
  if (task.status === '已揽收') return fail(res, '该任务已揽收');
  if (!['已接单', '待接单'].includes(task.status)) return fail(res, `任务状态为「${task.status}」，不能揽收`);

  const weight = Number(b.weight);
  if (!(weight > 0) || weight > 1000) return fail(res, '重量须在 0.01~1000kg');

  const result = tx(() => {
    const w = createWaybillForOrder({
      orderId: task.order_id,
      product: b.product ?? '隔日达',
      weight,
      volumeL: Number(b.volumeL) || 0,
      insured: Number(b.insured) || 0,
      length: Number(b.length) || 0,
      width: Number(b.width) || 0,
      height: Number(b.height) || 0,
      pack: !!b.pack,
    });
    q.run(`UPDATE orders SET status='已揽收' WHERE id=?`, task.order_id);
    q.run(`UPDATE pickup_tasks SET status='已揽收' WHERE id=?`, task.id);
    event(w.waybill_no, '揽收点', `快件已揽收，运费 ¥${w.fee}（${w.detail}），承诺送达 ${w.promise_time}`, task.order_no);
    return w;
  });
  ok(res, { waybill_no: result.waybill_no, fee: result.fee, detail: result.detail, promise_time: result.promise_time, chargeKg: result.chargeKg, volWeight: result.volWeight, items: result.items });
});

/* ---------- ④ 分拣中转登记 ---------- */
router.get('/transit', (req, res) => {
  const where = req.query.waybill_no ? 'WHERE t.waybill_no=?' : '';
  const args = req.query.waybill_no ? [req.query.waybill_no] : [];
  ok(res, q.all(`SELECT t.*, e.name AS operator, w.status AS waybill_status, w.product
                 FROM transit_logs t LEFT JOIN employees e ON e.id=t.operator_id
                 LEFT JOIN waybills w ON w.waybill_no=t.waybill_no ${where} ORDER BY t.id DESC`, ...args));
});

/* ---------- ④ 分拨路由建议（三段码近似：下一跳 / 空运陆运 / ETA） ---------- */
router.get('/transit/suggest', (req, res) => {
  const no = req.query.waybill_no;
  if (!no) return fail(res, 'waybill_no 必填');
  const s = suggestForWaybill(no);
  if (!s) return fail(res, '运单不存在', 404);
  ok(res, s);
});

router.post('/transit', (req, res) => {
  const { waybill_no, node, next_hop, flight, operator_id, security } = req.body ?? {};
  if (!waybill_no || !node) return fail(res, '运单号与当前节点必填');
  const w = q.get(
    'SELECT w.*, o.recv_addr FROM waybills w LEFT JOIN orders o ON o.id=w.order_id WHERE w.waybill_no=?',
    waybill_no
  );
  if (!w) return fail(res, '运单不存在', 404);
  if (flight) {
    const f = q.get('SELECT * FROM flights WHERE flight_no=?', flight);
    if (!f) return fail(res, `航班 ${flight} 不在航班主数据中，请先在基础数据中维护`);
  }
  if (!['已揽收', '转运中'].includes(w.status)) {
    return fail(res, `运单状态为「${w.status}」，不能做分拣中转登记`);
  }

  // 算法路由：未填下一跳时按目的地自动判定（同城直发 / 省内分拨 / 枢纽空陆运）
  const route = planRoute({ destAddr: w.recv_addr ?? '', product: w.product ?? '' });
  const hop = next_hop || route.next_hop;

  const r = tx(() => {
    const ins = q.run(
      'INSERT INTO transit_logs (waybill_no,node,next_hop,flight,operator_id,security,created_at) VALUES (?,?,?,?,?,?,?)',
      waybill_no, node, hop ?? '', flight ?? '', operator_id ?? null, security ? 1 : 0, now()
    );
    event(
      waybill_no,
      node,
      `分拣中转登记，下一跳：${hop || '—'}${flight ? '，航班 ' + flight : ''}${security ? '，已过安检' : ''}` +
        `（算法路由：${route.mode_label}｜${route.zone_label}｜ETA ${route.eta_hours ?? '—'}h）`
    );
    if (w.status === '已揽收') {
      const s = setWaybillStatus(waybill_no, '转运中');
      if (!s.ok) throw biz(s.error);
    }
    return ins;
  });
  ok(res, { id: r.lastInsertRowid, next_hop: hop, route });
});

/* ---------- ⑤ 派件任务分配 ---------- */
router.get('/delivery/tasks', (_req, res) => {
  ok(res, q.all(`SELECT d.*, e.name AS courier,
                        w.promise_time, w.product
                 FROM delivery_tasks d
                 LEFT JOIN employees e ON e.id=d.courier_id
                 LEFT JOIN waybills w ON w.waybill_no=d.waybill_no
                 ORDER BY d.id DESC`));
});

/* ---------- ⑤ 派件候选：同样由 SQL 排序（同片区 → 负载低） ---------- */
router.get('/delivery/candidates', (req, res) => {
  const w = q.get(
    `SELECT w.*, o.recv_addr, o.recv_name, o.order_no FROM waybills w LEFT JOIN orders o ON o.id=w.order_id
     WHERE w.waybill_no=?`, req.query.waybill_no);
  if (!w) return fail(res, '运单不存在', 404);
  const list = candidatesForAddr(w.recv_addr ?? '').map((c, i) => ({ ...c, rank: i + 1, reason: reasonOf(c) }));
  ok(res, {
    waybill_no: w.waybill_no, recv_addr: w.recv_addr, promise_time: w.promise_time,
    capacity: CAP, candidates: list,
  });
});

router.post('/delivery/assign', (req, res) => {
  const { waybill_nos } = req.body ?? {};
  if (!Array.isArray(waybill_nos) || !waybill_nos.length) return fail(res, 'waybill_nos 必填');

  const done = [];
  const skipped = [];
  tx(() => {
    for (const no of waybill_nos) {
      const w = q.get(
        `SELECT w.*, o.recv_name, o.recv_addr, o.order_no FROM waybills w JOIN orders o ON o.id=w.order_id WHERE w.waybill_no=?`, no);
      if (!w || w.status !== '转运中') { skipped.push(no); continue; }

      const c = pickCourierForAddr(w.recv_addr ?? '');
      if (!c) { skipped.push(`${no}（无可用运力）`); continue; }

      q.run('INSERT INTO delivery_tasks (waybill_no,courier_id,receiver,addr,status) VALUES (?,?,?,?,?)',
        no, c.id, w.recv_name ?? '', w.recv_addr ?? '', '待派送');
      const s = setWaybillStatus(no, '派送中');
      if (!s.ok) throw biz(s.error);
      event(no, '目的网点', `派件任务单已生成 → ${c.name}｜${reasonOf(c)}`, w.order_no);
      done.push({ waybill_no: no, courier: c.name, grid_code: c.grid_code, reason: reasonOf(c) });
    }
  });
  ok(res, { assigned: done.length, assignments: done, skipped });
});

/* ---------- ⑤ 签收管理 ---------- */
router.post('/delivery/sign', (req, res) => {
  const { id, signer, sign_way } = req.body ?? {};
  const t = q.get('SELECT * FROM delivery_tasks WHERE id=?', id);
  if (!t) return fail(res, '派件任务不存在', 404);
  if (t.status === '已签收') return fail(res, '该任务已签收');
  if (!['待派送', '派送中'].includes(t.status)) return fail(res, `任务状态为「${t.status}」，不能签收`);
  if (!signer) return fail(res, '请填写签收人');

  tx(() => {
    q.run(`UPDATE delivery_tasks SET status='已签收', signer=?, sign_way=?, signed_at=? WHERE id=?`,
      signer, sign_way ?? '本人签收', now(), id);
    const w = q.get('SELECT * FROM waybills WHERE waybill_no=?', t.waybill_no);
    const s = setWaybillStatus(t.waybill_no, '已签收', ', signed_at=?', now());
    if (!s.ok) throw biz(s.error);
    const late = w?.promise_time && now() > w.promise_time ? '（超承诺时效）' : '';
    event(t.waybill_no, '收件地址', `快件已签收，签收人：${signer}（${sign_way ?? '本人签收'}）${late}`);
  });
  ok(res, {});
});

/* ---------- ⑤ 派送异常转异常件 ---------- */
router.post('/delivery/fail', (req, res) => {
  const t = q.get('SELECT * FROM delivery_tasks WHERE id=?', req.body?.id);
  if (!t) return fail(res, '派件任务不存在', 404);
  if (['已签收', '派送异常'].includes(t.status)) return fail(res, `任务状态为「${t.status}」，不能再登记派送异常`);
  const type = req.body?.type ?? '拒收';
  if (!['拒收', '破损', '丢失'].includes(type)) return fail(res, '异常类型须为 拒收 / 破损 / 丢失（联系不上请改约派送，不要登记为异常件）');
  tx(() => {
    q.run(`UPDATE delivery_tasks SET status='派送异常' WHERE id=?`, t.id);
    const r = q.run('INSERT INTO exceptions (waybill_no,type,desc_text,status,created_at) VALUES (?,?,?,?,?)',
      t.waybill_no, type, req.body?.desc ?? '派送异常登记', '待处理', now());
    const s = setWaybillStatus(t.waybill_no, '异常');
    if (!s.ok) throw biz(s.error);
    event(t.waybill_no, '派送异常', `类型：${type}${req.body?.desc ? '，' + req.body.desc : ''}`);
    return r.lastInsertRowid;
  });
  ok(res, {});
});

export default router;

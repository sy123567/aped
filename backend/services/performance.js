// services/performance.js — 绩效统计服务
// ⑨ 快递员揽派绩效统计排名　⑩ 运营统计报表导出
import { Router } from 'express';
import { ok, fail, q } from '../core/helpers.js';
import { computeKpi } from '../core/kpi.js';
import { allow } from '../core/auth.js';

const router = Router();
const p2 = (n) => String(n).padStart(2, '0');
const monthOf = (d = new Date()) => `${d.getFullYear()}-${p2(d.getMonth() + 1)}`;

/* ---------- ⑨ 绩效统计排名（KPI 引擎：归一化 + 权重 + 小样本剔除） ---------- */
function buildPerformance(period) {
  const k = computeKpi();
  const rows = k.rows.map((r) => ({
    rank: r.rank ?? null,
    code: r.code,
    courier: r.courier,
    grid_code: r.grid_code,
    pickups: r.metrics.pickups,
    deliveries: r.metrics.deliveries,
    exceptions: r.extras.exceptions,
    on_time_cnt: r.extras.on_time_cnt,
    on_time_rate: r.metrics.on_time,
    exception_rate: r.metrics.exception,
    onTime: r.metrics.on_time,        // 兼容旧前端字段名
    exceptionRate: r.metrics.exception,
    total: r.extras.total,
    score: r.score,
    grade: r.grade,
    sample_ok: r.sample_ok,
    exclude_reason: r.exclude_reason ?? null,
    breakdown: r.breakdown,
  }));
  return {
    period: period ?? monthOf(),
    rows,
    weights: k.weights,
    method: k.method,
    min_sample: k.min_sample,
    ranked_count: k.ranked_count,
  };
}

router.get('/performance', allow('ops', 'finance'), (req, res) => ok(res, buildPerformance(req.query.period)));

/* ---------- ⑩ 运营统计汇总 ---------- */
router.get('/reports/summary', allow('ops', 'finance'), (req, res) => {
  const period = req.query.period ?? monthOf();
  const wb = q.get(`SELECT COUNT(*) cnt, COALESCE(SUM(fee),0) revenue FROM waybills WHERE substr(created_at,1,7)=?`, period);
  const signed = q.get(`SELECT COUNT(*) cnt FROM waybills WHERE substr(created_at,1,7)=? AND status='已签收'`, period);
  const onTime = q.get(`SELECT COUNT(*) cnt FROM waybills WHERE substr(created_at,1,7)=? AND status='已签收'
                        AND promise_time IS NOT NULL AND signed_at IS NOT NULL AND signed_at <= promise_time`, period);
  const late = q.get(`SELECT COUNT(*) cnt FROM waybills WHERE substr(created_at,1,7)=? AND status='已签收'
                      AND promise_time IS NOT NULL AND signed_at IS NOT NULL AND signed_at > promise_time`, period);
  const ex = q.get(`SELECT COUNT(*) cnt FROM exceptions WHERE substr(created_at,1,7)=?`, period);
  const claim = q.get(`SELECT COUNT(*) cnt, COALESCE(SUM(amount),0) amount FROM claims
                       WHERE status='已赔付' AND substr(paid_at,1,7)=?`, period);
  const billRow = q.get(`SELECT COUNT(*) cnt, COALESCE(SUM(amount),0) amount, COALESCE(SUM(paid_amount),0) paid
                         FROM bills WHERE period=?`, period);

  ok(res, {
    period,
    waybills: wb.cnt,
    revenue: +wb.revenue.toFixed(2),
    signed: signed.cnt,
    on_time: onTime.cnt,
    late: late.cnt,
    on_time_rate: signed.cnt ? +((onTime.cnt / signed.cnt) * 100).toFixed(1) : null,
    exceptions: ex.cnt,
    claims_paid: claim.cnt,
    claims_amount: +claim.amount.toFixed(2),
    bills: billRow.cnt,
    bill_amount: +billRow.amount.toFixed(2),
    bill_paid: +billRow.paid.toFixed(2),
    by_product: q.all(`SELECT product, COUNT(*) cnt, ROUND(SUM(fee),2) fee FROM waybills
                       WHERE substr(created_at,1,7)=? GROUP BY product ORDER BY cnt DESC`, period),
    by_status: q.all(`SELECT status, COUNT(*) cnt FROM waybills GROUP BY status ORDER BY cnt DESC`),
    by_exception: q.all(`SELECT type, COUNT(*) cnt FROM exceptions WHERE substr(created_at,1,7)=? GROUP BY type`, period),
  });
});

/* ---------- ⑩ 报表数据集（导出与预览共用） ---------- */
function dataset(type, period) {
  switch (type) {
    case 'performance': {
      const { rows } = buildPerformance(period);
      return {
        title: '快递员揽派绩效排名',
        cols: ['排名', '工号', '快递员', '片区', '揽收数', '签收数', '异常数', '准时率(%)', '综合得分'],
        rows: rows.map((r) => [r.rank, r.code, r.courier, r.grid_code ?? '', r.pickups, r.deliveries,
          r.exceptions, r.on_time_rate ?? '', r.score]),
      };
    }
    case 'waybills':
      return {
        title: '运单明细',
        cols: ['运单号', '订单号', '产品', '状态', '收件人', '收件地址', '运费', '承诺送达', '签收时间', '是否准时'],
        rows: q.all(`SELECT w.waybill_no, o.order_no, w.product, w.status, o.recv_name, o.recv_addr,
                            w.fee, w.promise_time, w.signed_at
                     FROM waybills w LEFT JOIN orders o ON o.id=w.order_id
                     WHERE (?='' OR substr(w.created_at,1,7)=?) ORDER BY w.id DESC`, period ?? '', period ?? '')
          .map((r) => [r.waybill_no, r.order_no ?? '', r.product ?? '', r.status, r.recv_name ?? '',
            r.recv_addr ?? '', r.fee ?? 0, r.promise_time ?? '', r.signed_at ?? '',
            r.signed_at && r.promise_time ? (r.signed_at <= r.promise_time ? '准时' : '超时') : '—']),
      };
    case 'bills':
      return {
        title: '月结账单',
        cols: ['账单号', '客户', '账期', '金额', '已收', '待收', '状态'],
        rows: q.all(`SELECT b.bill_no, c.name, b.period, b.amount, COALESCE(b.paid_amount,0) paid, b.status
                     FROM bills b JOIN customers c ON c.id=b.customer_id
                     WHERE (?='' OR b.period=?) ORDER BY b.id DESC`, period ?? '', period ?? '')
          .map((r) => [r.bill_no, r.name, r.period, r.amount, r.paid, +(r.amount - r.paid).toFixed(2), r.status]),
      };
    case 'exceptions':
      return {
        title: '异常件与理赔',
        cols: ['异常单号', '运单号', '类型', '异常状态', '描述', '理赔金额', '理赔状态', '登记时间'],
        rows: q.all(`SELECT e.id, e.waybill_no, e.type, e.status, e.desc_text, c.amount, c.status AS cstatus, e.created_at
                     FROM exceptions e LEFT JOIN claims c ON c.exception_id=e.id
                     WHERE (?='' OR substr(e.created_at,1,7)=?) ORDER BY e.id DESC`, period ?? '', period ?? '')
          .map((r) => [r.id, r.waybill_no, r.type, r.status, r.desc_text ?? '', r.amount ?? '',
            r.cstatus ?? '', r.created_at]),
      };
    case 'transit':
      return {
        title: '分拣中转登记',
        cols: ['运单号', '节点', '下一跳', '航班', '操作员', '登记时间'],
        rows: q.all(`SELECT t.waybill_no, t.node, t.next_hop, t.flight, e.name, t.created_at
                     FROM transit_logs t LEFT JOIN employees e ON e.id=t.operator_id
                     WHERE (?='' OR substr(t.created_at,1,7)=?) ORDER BY t.id DESC`, period ?? '', period ?? '')
          .map((r) => [r.waybill_no, r.node, r.next_hop ?? '', r.flight ?? '', r.name ?? '', r.created_at]),
      };
    default:
      return null;
  }
}

router.get('/reports/preview', allow('ops', 'finance'), (req, res) => {
  const d = dataset(req.query.type ?? 'performance', req.query.period);
  if (!d) return fail(res, '未知报表类型');
  ok(res, { ...d, total: d.rows.length });
});

/* ---------- ⑩ 导出 CSV / Excel ---------- */
const csvCell = (v) => {
  const s = String(v ?? '');
  return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
};
const esc = (v) => String(v ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

router.get('/reports/export', allow('ops', 'finance'), (req, res) => {
  const type = req.query.type ?? 'performance';
  const format = (req.query.format ?? 'csv').toLowerCase();
  const d = dataset(type, req.query.period);
  if (!d) return fail(res, '未知报表类型');

  const stamp = new Date().toISOString().slice(0, 10);
  const fname = `${type}-${stamp}`;

  if (format === 'xls' || format === 'excel') {
    // 不引入依赖：输出 Excel 可直接打开的 HTML 表格
    res.setHeader('Content-Type', 'application/vnd.ms-excel; charset=utf-8');
    res.setHeader('Content-Disposition', `attachment; filename="${fname}.xls"`);
    const thead = d.cols.map((c) => `<th>${esc(c)}</th>`).join('');
    const tbody = d.rows.map((r) => `<tr>${r.map((c) => `<td>${esc(c)}</td>`).join('')}</tr>`).join('');
    res.end(`\uFEFF<html><head><meta charset="utf-8"><style>table{border-collapse:collapse}
td,th{border:1px solid #999;padding:4px 8px;font-family:sans-serif;font-size:12px}
th{background:#eef2f7}</style></head><body><h3>${esc(d.title)}</h3>
<table>${`<thead><tr>${thead}</tr></thead>`}<tbody>${tbody}</tbody></table></body></html>`);
    return;
  }

  res.setHeader('Content-Type', 'text/csv; charset=utf-8');
  res.setHeader('Content-Disposition', `attachment; filename="${fname}.csv"`);
  res.write('\uFEFF' + d.cols.map(csvCell).join(',') + '\n');
  d.rows.forEach((r) => res.write(r.map(csvCell).join(',') + '\n'));
  res.end();
});

/* ---------- 工作台 ---------- */
router.get('/board', (_req, res) => {
  const one = (sql) => q.get(sql)?.c ?? 0;
  ok(res, {
    todayPickups: one("SELECT COUNT(*) c FROM pickup_tasks WHERE status='已揽收' AND date(assigned_at)=date('now','localtime')"),
    todaySigns: one("SELECT COUNT(*) c FROM delivery_tasks WHERE status='已签收' AND date(signed_at)=date('now','localtime')"),
    openExceptions: one("SELECT COUNT(*) c FROM exceptions WHERE status IN ('待处理','待审批')"),
    inTransit: one("SELECT COUNT(*) c FROM waybills WHERE status IN ('转运中','派送中')"),
    pendingOrders: one("SELECT COUNT(*) c FROM orders WHERE status IN ('待派单','待揽收')"),
    unpaidBills: one("SELECT COUNT(*) c FROM bills WHERE status!='已结清'"),
    revenue: +(q.get("SELECT COALESCE(SUM(fee),0) c FROM waybills")?.c ?? 0).toFixed(2),
    onTimeRate: (() => {
      const s = q.get("SELECT COUNT(*) c FROM waybills WHERE status='已签收' AND promise_time IS NOT NULL AND signed_at IS NOT NULL")?.c ?? 0;
      const o = q.get("SELECT COUNT(*) c FROM waybills WHERE status='已签收' AND promise_time IS NOT NULL AND signed_at IS NOT NULL AND signed_at <= promise_time")?.c ?? 0;
      return s ? +((o / s) * 100).toFixed(1) : null;
    })(),
  });
});

export default router;

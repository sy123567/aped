// core/kpi.js — 绩效评分引擎
// 成熟做法：① 多指标 ② 指标量纲不同先归一化 ③ 权重可配 ④ 逆向指标取反 ⑤ 样本不足不参与排名
// 不用「绝对分」的原因：业务量有季节波动，同样的表现旺季分数会虚高，横向不可比
import { q } from './helpers.js';

const DEFAULT_WEIGHTS = [
  { metric: 'pickups', label: '揽收量', weight: 0.2, direction: 1 },
  { metric: 'deliveries', label: '签收量', weight: 0.3, direction: 1 },
  { metric: 'on_time', label: '准时率', weight: 0.35, direction: 1 },
  { metric: 'exception', label: '异常率', weight: 0.15, direction: -1 },
];

/** 少于该样本量不参与排名（避免 1 单 100% 准时率排第一） */
const MIN_SAMPLE = 3;

export function getWeights() {
  try {
    const rows = q.all('SELECT * FROM kpi_weights ORDER BY weight DESC');
    if (!rows.length) return DEFAULT_WEIGHTS;
    const sum = rows.reduce((s, r) => s + Number(r.weight || 0), 0);
    // 权重和不为 1 时按比例归一到 1
    return rows.map((r) => ({ ...r, weight: sum > 0 ? Number(r.weight) / sum : 1 / rows.length }));
  } catch {
    return DEFAULT_WEIGHTS;
  }
}

function rawRows() {
  return q.all(`
    SELECT e.id, e.code, e.name AS courier, e.grid_code,
      (SELECT COUNT(*) FROM pickup_tasks t WHERE t.courier_id=e.id AND t.status='已揽收') AS pickups,
      (SELECT COUNT(*) FROM delivery_tasks d WHERE d.courier_id=e.id AND d.status='已签收') AS deliveries,
      (SELECT COUNT(*) FROM delivery_tasks d WHERE d.courier_id=e.id AND d.status='派送异常') AS exceptions,
      (SELECT COUNT(*) FROM delivery_tasks d JOIN waybills w ON w.waybill_no=d.waybill_no
         WHERE d.courier_id=e.id AND d.status='已签收'
           AND w.promise_time IS NOT NULL AND w.signed_at IS NOT NULL) AS measured,
      (SELECT COUNT(*) FROM delivery_tasks d JOIN waybills w ON w.waybill_no=d.waybill_no
         WHERE d.courier_id=e.id AND d.status='已签收'
           AND w.promise_time IS NOT NULL AND w.signed_at IS NOT NULL
           AND w.signed_at <= w.promise_time) AS on_time_cnt
    FROM employees e WHERE e.role='courier'`);
}

/** min-max 归一化；direction = -1 表示逆向指标（越小越好） */
function normalize(rows, key, direction) {
  const vals = rows.map((r) => r.metrics[key]).filter((v) => v != null && !Number.isNaN(v));
  if (!vals.length) return;
  const min = Math.min(...vals);
  const max = Math.max(...vals);
  for (const r of rows) {
    const v = r.metrics[key];
    if (v == null || Number.isNaN(v)) { r.scores[key] = null; continue; }
    let s = max === min ? 60 : ((v - min) / (max - min)) * 100;
    if (direction === -1) s = 100 - s;
    r.scores[key] = +s.toFixed(1);
  }
}

const grade = (s) => (s == null ? '—' : s >= 85 ? 'A' : s >= 70 ? 'B' : s >= 55 ? 'C' : 'D');

/**
 * 计算绩效
 * 每个指标先归一到 0~100（消除量纲与业务量波动），再按权重加权
 */
export function computeKpi() {
  const weights = getWeights();
  const raws = rawRows();

  const rows = raws.map((r) => {
    const total = (r.pickups ?? 0) + (r.deliveries ?? 0);
    const measured = r.measured ?? 0;
    return {
      id: r.id,
      code: r.code,
      courier: r.courier,
      grid_code: r.grid_code,
      metrics: {
        pickups: r.pickups ?? 0,
        deliveries: r.deliveries ?? 0,
        on_time: measured ? +(((r.on_time_cnt ?? 0) / measured) * 100).toFixed(1) : null,
        exception: total ? +((((r.exceptions ?? 0) / total) * 100).toFixed(1)) : null,
      },
      extras: {
        exceptions: r.exceptions ?? 0,
        measured,
        on_time_cnt: r.on_time_cnt ?? 0,
        total,
      },
      scores: {},
      sample_ok: total >= MIN_SAMPLE,
    };
  });

  for (const w of weights) normalize(rows, w.metric, Number(w.direction) || 1);

  for (const r of rows) {
    let sumW = 0;
    let sumS = 0;
    for (const w of weights) {
      const s = r.scores[w.metric];
      if (s == null) continue;
      sumW += Number(w.weight);
      sumS += s * Number(w.weight);
    }
    r.score = sumW > 0 ? +(sumS / sumW).toFixed(1) : null;
    r.grade = grade(r.score);
    r.breakdown = weights.map((w) => ({
      metric: w.metric, label: w.label,
      raw: r.metrics[w.metric],
      score: r.scores[w.metric] ?? null,
      weight: +Number(w.weight).toFixed(3),
      contrib: r.scores[w.metric] == null ? null : +(r.scores[w.metric] * Number(w.weight)).toFixed(1),
    }));
  }

  const ranked = rows
    .filter((r) => r.sample_ok && r.score != null)
    .sort((a, b) => b.score - a.score)
    .map((r, i) => ({ rank: i + 1, ...r }));

  const excluded = rows
    .filter((r) => !r.sample_ok || r.score == null)
    .map((r) => ({ ...r, rank: null, exclude_reason: `作业样本 ${r.extras.total} 单，低于 ${MIN_SAMPLE} 单不参与排名` }));

  return {
    weights: weights.map((w) => ({ metric: w.metric, label: w.label, weight: +Number(w.weight).toFixed(3), direction: Number(w.direction) })),
    rows: [...ranked, ...excluded],
    ranked_count: ranked.length,
    min_sample: MIN_SAMPLE,
    method: [
      '各指标先做 min-max 归一化到 0~100，消除量纲差异与业务量季节波动',
      '逆向指标（异常率）取 100 − 归一化值后参与计算',
      '加权得分 = Σ(指标得分 × 权重) ÷ Σ权重，缺失指标不计入分母',
      `作业量低于 ${MIN_SAMPLE} 单的快递员不参与排名，避免小样本失真`,
    ],
  };
}

// core/assign.js — 派单分配规则（**决策全部在 SQL 里**，JS 只负责执行）
//
// 规则一句话：在「未满载」的快递员中，优先同片区，其次在途最少，再按工号定序。
//
// 为什么不用打分引擎：五因子加权 + 四种策略 + 权重表，对这套业务是过度设计。
// 真正决定派单对错的只有两个硬条件——① 是不是他的片区 ② 他忙不忙。
// 排序逻辑写进 SQL 视图 `v_assign_pickup` / 查询里，改规则只改 SQL，不用碰业务代码。
import { q } from './helpers.js';

export const CAP = 20;

/** 派单 SQL 原文（给前端/文档展示"规则就是这条语句"） */
export const RULE_SQL = `SELECT c.id
FROM v_courier_load c
WHERE c.load < c.capacity
ORDER BY (c.grid_code = (SELECT g.grid_code FROM grids g
                         WHERE :addr LIKE '%'||g.district||'%' LIMIT 1)) DESC,
         c.load ASC, c.id ASC
LIMIT 1`;

const SELECT_BASE = `
  SELECT c.id, c.code, c.name, c.grid_code, c.load, c.capacity,
         CASE WHEN c.grid_code = (SELECT g.grid_code FROM grids g
                                  WHERE ? LIKE '%'||g.district||'%' LIMIT 1)
              THEN 1 ELSE 0 END AS same_area
  FROM v_courier_load c
  WHERE c.load < c.capacity`;

/** 候选排名：同片区优先 → 在途少优先 → 工号定序（数据库完成排序） */
export function candidatesForAddr(addr, limit = 10) {
  return q.all(`${SELECT_BASE}
    ORDER BY same_area DESC, c.load ASC, c.id ASC
    LIMIT ?`, addr ?? '', limit);
}

/** 定人：候选第一名。返回 null 表示没有可用运力 */
export function pickCourierForAddr(addr) {
  const row = q.get(`${SELECT_BASE}
    ORDER BY same_area DESC, c.load ASC, c.id ASC
    LIMIT 1`, addr ?? '');
  return row ?? null;
}

export function courierLoad(id) {
  return q.get('SELECT load FROM v_courier_load WHERE id = ?', id)?.load ?? 0;
}

/** 把 SQL 选人的结果翻成一句人话，写进轨迹和界面 */
export function reasonOf(c) {
  if (!c) return '无可用运力（全部满载）';
  return c.same_area
    ? `同片区（${c.grid_code}），在途 ${c.load}/${c.capacity}`
    : `跨区调度，在途 ${c.load}/${c.capacity}（最低）`;
}

/**
 * 批量分配：逐单取 SQL 第一名。
 * 注意：每单执行后立刻落库，下一单查到的 load 已经 +1，所以不会被"全部压给同一个人"。
 * 因此不提供"只算不落库"的预览——预览用的是同一份负载快照，结果和真实执行不一致，反而误导。
 */
export function assignOrders(orderIds, { onAssign } = {}) {
  const done = [];
  const unassigned = [];
  for (const id of orderIds) {
    const o = q.get('SELECT * FROM orders WHERE id = ?', id);
    if (!o) { unassigned.push({ order_id: id, why: '订单不存在' }); continue; }
    if (o.status !== '待派单') { unassigned.push({ order_id: id, order_no: o.order_no, why: `状态为「${o.status}」，不在待派单池` }); continue; }

    const c = pickCourierForAddr(o.recv_addr ?? '');
    if (!c) { unassigned.push({ order_id: id, order_no: o.order_no, why: '无可用运力（全部满载）' }); continue; }

    if (onAssign) onAssign(o, c);
    done.push({
      order_id: o.id, order_no: o.order_no, recv_addr: o.recv_addr, slot: o.slot,
      courier_id: c.id, courier: c.name, courier_code: c.code, grid_code: c.grid_code,
      same_area: !!c.same_area, load_before: c.load, capacity: c.capacity,
      reason: reasonOf(c),
    });
  }
  return { assignments: done, unassigned };
}

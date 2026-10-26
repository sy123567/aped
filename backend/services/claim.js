// services/claim.js — 异常理赔服务
// ⑥ 异常件处理流程（拒收 / 破损 / 丢失登记与理赔）
import { Router } from 'express';
import { ok, fail, now, q, event, tx, setWaybillStatus, biz } from '../core/helpers.js';
import { assessForException, assessClaim, SEVERITY } from '../core/claim-engine.js';

const router = Router();
const TYPES = ['拒收', '破损', '丢失'];

router.get('/exceptions', (req, res) => {
  const where = req.query.waybill_no ? 'WHERE e.waybill_no=?' : '';
  const args = req.query.waybill_no ? [req.query.waybill_no] : [];
  ok(res, q.all(`SELECT e.*, c.id AS claim_id, c.amount, c.status AS claim_status, c.rule, c.approved_by
                 FROM exceptions e LEFT JOIN claims c ON c.exception_id=e.id ${where} ORDER BY e.id DESC`, ...args));
});

router.get('/exceptions/:id', (req, res) => {
  const e = q.get('SELECT * FROM exceptions WHERE id=?', req.params.id);
  if (!e) return fail(res, '异常单不存在', 404);
  ok(res, {
    exception: e,
    waybill: q.get('SELECT * FROM waybills WHERE waybill_no=?', e.waybill_no) ?? null,
    claims: q.all('SELECT * FROM claims WHERE exception_id=? ORDER BY id DESC', e.id),
    events: q.all('SELECT * FROM events WHERE waybill_no=? ORDER BY id', e.waybill_no),
  });
});

/* ---------- ⑥ 异常登记 ---------- */
router.post('/exceptions', (req, res) => {
  const b = req.body ?? {};
  if (!b.waybill_no || !b.type) return fail(res, '运单号与异常类型必填');
  if (!TYPES.includes(b.type)) return fail(res, `异常类型须为 ${TYPES.join(' / ')}`);
  const w = q.get('SELECT * FROM waybills WHERE waybill_no=?', b.waybill_no);
  if (!w) return fail(res, '运单不存在', 404);
  if (w.status === '已关闭') return fail(res, '运单已关闭，不能登记异常');

  const id = tx(() => {
    const r = q.run(
      'INSERT INTO exceptions (waybill_no,type,photos,desc_text,status,created_at) VALUES (?,?,?,?,?,?)',
      b.waybill_no, b.type, b.photos ?? '', b.desc ?? '', '待处理', now()
    );
    if (['已揽收', '转运中', '派送中'].includes(w.status)) {
      const s = setWaybillStatus(b.waybill_no, '异常');
      if (!s.ok) throw biz(s.error);
    }
    event(b.waybill_no, '异常登记', `异常类型：${b.type}${b.desc ? '，' + b.desc : ''}`);
    return r.lastInsertRowid;
  });
  ok(res, { id });
});

/* ---------- ⑥ 定责与赔付试算（不落库，供专员核对） ---------- */
router.get('/exceptions/:id/assess', (req, res) => {
  const a = assessForException(req.params.id, { severity: req.query.severity });
  if (!a) return fail(res, '异常单不存在', 404);
  ok(res, {
    ...a,
    severity_options: Object.entries(SEVERITY).map(([k, v]) => ({ key: k, ratio: v.ratio, label: v.label })),
  });
});

/* ---------- ⑥ 备选流：仅拒收无损失 → 直接退回关闭，不走理赔 ---------- */
router.post('/exceptions/:id/close', (req, res) => {
  const e = q.get('SELECT * FROM exceptions WHERE id=?', req.params.id);
  if (!e) return fail(res, '异常单不存在', 404);
  if (['已理赔', '已关闭'].includes(e.status)) return fail(res, `异常单已${e.status}`);
  const reason = req.body?.reason ?? (e.type === '拒收' ? '收件人拒收，原件退回' : '无需理赔，直接关闭');

  tx(() => {
    q.run(`UPDATE exceptions SET status='已关闭', closed_at=? WHERE id=?`, now(), e.id);
    const s = setWaybillStatus(e.waybill_no, '已关闭');
    if (!s.ok) throw biz(s.error);
    event(e.waybill_no, '异常关闭', reason);
  });
  ok(res, { status: '已关闭', reason });
});

/* ---------- ⑥ 理赔单 ---------- */
router.get('/claims', (_req, res) => {
  ok(res, q.all(`SELECT c.*, e.waybill_no, e.type, e.status AS exception_status
                 FROM claims c LEFT JOIN exceptions e ON e.id=c.exception_id ORDER BY c.id DESC`));
});

router.post('/claims', (req, res) => {
  const { exception_id, amount, severity, evidence, manual } = req.body ?? {};
  const ex = q.get('SELECT * FROM exceptions WHERE id=?', exception_id);
  if (!ex) return fail(res, '异常单不存在', 404);
  if (['已理赔', '已关闭'].includes(ex.status)) return fail(res, `异常单已${ex.status}，不能再发起理赔`);
  if (q.get('SELECT 1 x FROM claims WHERE exception_id=? AND status IN (?,?)', ex.id, '待审批', '已赔付')) {
    return fail(res, '该异常单已存在待审批或已赔付的理赔单');
  }

  // 定责 + 核定：责任方、折损分档、赔付上限全部由规则引擎给出
  const a = assessForException(ex.id, { severity, photos: ex.photos });
  if (!a) return fail(res, '核定失败', 500);

  let amt = a.amount;
  let ruleList = a.rules.slice();
  // 只有显式 manual=true 才允许人工改编金额（需在审批里写明理由）
  if (manual && amount != null && Number(amount) > 0) {
    amt = Number(amount);
    ruleList.push(`人工核定调整至 ¥${amt}`);
  }
  if (!(amt > 0)) {
    return fail(res, a.liable === 'customer'
      ? '该异常属非承运责任，赔付金额为 0；如确需赔付请传 manual=true 人工核定'
      : '核定金额为 0，请检查保价额与折损程度');
  }
  if (a.cap > 0 && amt > a.cap) return fail(res, `核定金额 ¥${amt} 超过上限 ¥${a.cap}`);

  const id = tx(() => {
    q.run('UPDATE exceptions SET severity=? WHERE id=?', a.severity, ex.id);
    const r = q.run('INSERT INTO claims (exception_id,amount,rule,status,evidence) VALUES (?,?,?,?,?)',
      ex.id, +amt.toFixed(2), ruleList.join('；'), '待审批', evidence ?? ex.photos ?? '');
    q.run(`UPDATE exceptions SET status='待审批' WHERE id=?`, ex.id);
    event(ex.waybill_no, '理赔受理',
      `${a.liable_label}｜${a.severity_label}｜核定 ¥${amt.toFixed(2)}`);
    return r.lastInsertRowid;
  });

  ok(res, {
    claim_id: id, amount: +amt.toFixed(2),
    liable: a.liable, liable_label: a.liable_label,
    severity: a.severity, severity_label: a.severity_label, ratio: a.ratio,
    cap: a.cap, rules: ruleList,
    need_evidence: a.need_evidence, need_second_approval: a.need_second_approval,
  });
});

router.post('/claims/approve', (req, res) => {
  const { id, approved, by } = req.body ?? {};
  const c = q.get('SELECT * FROM claims WHERE id=?', id);
  if (!c) return fail(res, '理赔单不存在', 404);
  if (c.status !== '待审批') return fail(res, `理赔单已${c.status}，不能重复审批`);
  const ex = q.get('SELECT * FROM exceptions WHERE id=?', c.exception_id);

  tx(() => {
    if (approved === false) {
      q.run(`UPDATE claims SET status='已驳回', approved_by=? WHERE id=?`, by ?? '理赔专员', c.id);
      q.run(`UPDATE exceptions SET status='待处理' WHERE id=?`, c.exception_id);
      event(ex.waybill_no, '理赔驳回', `审批人：${by ?? '理赔专员'}`);
    } else {
      q.run(`UPDATE claims SET status='已赔付', approved_by=?, paid_at=? WHERE id=?`, by ?? '理赔专员', now(), c.id);
      q.run(`UPDATE exceptions SET status='已理赔' WHERE id=?`, c.exception_id);
      const s = setWaybillStatus(ex.waybill_no, '已关闭');
      if (!s.ok) throw biz(s.error);
      event(ex.waybill_no, '理赔赔付', `赔付 ¥${c.amount}，审批人：${by ?? '理赔专员'}`);
    }
  });
  ok(res, { status: approved === false ? '已驳回' : '已赔付' });
});

export default router;

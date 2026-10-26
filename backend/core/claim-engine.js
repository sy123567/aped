// core/claim-engine.js — 定责与赔付核定规则引擎
// 成熟做法：先判「谁的责任」，再按「折损程度分档 × 保价额」算钱，未保价有法定/惯例上限
import { q } from './helpers.js';

/** 折损分档（行业通行：按程度给系数，而不是让专员随手填金额） */
export const SEVERITY = {
  轻微: { ratio: 0.2, label: '轻微（外观损伤、可修复）' },
  中度: { ratio: 0.4, label: '中度（部分功能受损）' },
  严重: { ratio: 0.7, label: '严重（基本不可用）' },
  全损: { ratio: 1.0, label: '全损（完全灭失）' },
};

/** 未保价赔偿上限倍数（《快递暂行条例》精神：按实际损失赔，实务多用运费倍数封顶） */
const UNINSURED_FEE_MULTIPLE = 3;
const UNINSURED_HARD_CAP = 300;
/** 超过该金额需要二级审批 */
const SECOND_APPROVAL_THRESHOLD = 500;

/**
 * 定责 + 核定
 * @param {object} p
 * @param {'拒收'|'破损'|'丢失'} p.type
 * @param {'轻微'|'中度'|'严重'|'全损'} [p.severity]
 * @param {number} p.insured  保价额
 * @param {number} p.fee      实付运费
 * @param {string} [p.photos] 照片/证据
 * @param {boolean} [p.securityPassed] 是否已过航空安检
 */
export function assessClaim({ type, severity = '中度', insured = 0, fee = 0, photos = '', securityPassed = false } = {}) {
  const rules = [];
  const sev = SEVERITY[severity] ?? SEVERITY.中度;
  const hasEvidence = !!(photos && String(photos).trim());

  // ---------- 1. 定责 ----------
  let liable = 'carrier';
  let liableLabel = '承运方责任';

  if (type === '拒收') {
    liable = 'customer';
    liableLabel = '非承运责任';
    rules.push('拒收属收件人意愿，不构成承运方赔偿事由');
  } else if (type === '丢失') {
    liable = 'carrier';
    liableLabel = '承运方责任（全程灭失）';
    rules.push('快件已揽收但未签收，保管期内灭失，承运方承担全责');
  } else if (type === '破损') {
    if (securityPassed) {
      liable = 'carrier';
      liableLabel = '承运方责任（已过安检，破损发生在承运环节）';
      rules.push('快件已通过安检，破损可排除航空运输前置原因，由承运方承担');
    } else {
      liable = 'carrier';
      liableLabel = '承运方主责（未过安检，按操作不当认定）';
      rules.push('未登记安检，无法排除收运环节问题，按承运方主责认定');
    }
  } else {
    rules.push(`未知异常类型「${type}」，转人工核定`);
    return { liable: 'unknown', liable_label: '待人工定责', ratio: 0, amount: 0, cap: 0, severity, severity_label: sev.label, rules, need_evidence: true, need_second_approval: false };
  }

  // ---------- 2. 折损系数 ----------
  rules.push(`折损程度「${severity}」→ 赔偿系数 ${sev.ratio}（${sev.label}）`);

  // ---------- 3. 计算金额 ----------
  let amount = 0;
  let cap = 0;

  if (liable === 'customer') {
    amount = 0;
    cap = 0;
    rules.push('非承运责任，赔付金额为 0');
  } else if (Number(insured) > 0) {
    cap = Number(insured);
    amount = +(Number(insured) * sev.ratio).toFixed(2);
    rules.push(`已保价 ¥${insured}：赔付 = 保价额 × 折损系数 = ¥${amount}（上限 ¥${cap}）`);
  } else {
    const byFee = +((Number(fee) || 0) * UNINSURED_FEE_MULTIPLE).toFixed(2);
    cap = Math.min(byFee, UNINSURED_HARD_CAP);
    amount = +Math.min(cap, (Number(fee) || 0) * UNINSURED_FEE_MULTIPLE * sev.ratio).toFixed(2);
    rules.push(`未保价：按运费 ${UNINSURED_FEE_MULTIPLE} 倍且不超 ¥${UNINSURED_HARD_CAP} 封顶 → 上限 ¥${cap}`);
    rules.push(`未保价赔付 = min(上限, 运费×${UNINSURED_FEE_MULTIPLE}×折损系数) = ¥${amount}`);
  }

  // ---------- 4. 证据与审批门槛 ----------
  let needEvidence = false;
  if (!hasEvidence && amount > 0) {
    needEvidence = true;
    rules.push('缺少照片/证明材料：需补齐后才能赔付');
  }
  const needSecondApproval = amount > SECOND_APPROVAL_THRESHOLD;
  if (needSecondApproval) rules.push(`金额 ¥${amount} 超过 ¥${SECOND_APPROVAL_THRESHOLD}，需二级审批`);

  return {
    liable,
    liable_label: liableLabel,
    severity,
    severity_label: sev.label,
    ratio: sev.ratio,
    insured: Number(insured) || 0,
    cap: +cap.toFixed(2),
    amount: +amount.toFixed(2),
    need_evidence: needEvidence,
    need_second_approval: needSecondApproval,
    rules,
  };
}

/** 给核定接口用：把异常单 + 运单信息喂进引擎 */
export function assessForException(exceptionId, { severity, photos } = {}) {
  const ex = q.get('SELECT * FROM exceptions WHERE id=?', exceptionId);
  if (!ex) return null;
  const w = q.get('SELECT * FROM waybills WHERE waybill_no=?', ex.waybill_no);
  // 安检判定：transit_logs 没有 desc_text 列（旧写法查该列直接抛错导致 500）。
  // 现行口径：中转登记时写入 transit_logs.security=1；老数据再用 events 描述文本兜底。
  const securityPassed = !!q.get(
    `SELECT 1 x FROM transit_logs WHERE waybill_no=? AND security=1 LIMIT 1`,
    ex.waybill_no
  ) || !!q.get(`SELECT 1 x FROM events WHERE waybill_no=? AND desc_text LIKE '%安检%' LIMIT 1`, ex.waybill_no);

  return {
    exception: ex,
    ...assessClaim({
      type: ex.type,
      severity: severity ?? ex.severity ?? '中度',
      insured: w?.insured ?? 0,
      fee: w?.fee ?? 0,
      photos: photos ?? ex.photos ?? '',
      securityPassed,
    }),
  };
}

// core/pricing.js — 计费引擎
// 成熟做法：基础运费（首重 + 阶梯续重）× 产品时效 × 区域系数 + 附加费 − 协议折扣，且不低于最低一票
// 关键：每一项都要能单独列出来，账单才能逐项对账（而不是"一段文字说明"）
import { q, findRate } from './helpers.js';

const DEFAULT_RULES = {
  min_charge: 12, fuel_rate: 0.08, insured_rate: 0.003, pack_fee: 2,
  overweight_kg: 30, overweight_fee: 15, oversize_cm: 100, oversize_fee: 20,
  monthly_discount: 0.95,
  zone_same_city: 0.9, zone_same_province: 1.0, zone_region: 1.15, zone_cross: 1.35, zone_remote: 1.8,
};

export function getRules() {
  try {
    const rows = q.all('SELECT key,value FROM pricing_rules');
    const m = { ...DEFAULT_RULES };
    for (const r of rows) if (r.key in m) m[r.key] = Number(r.value);
    return m;
  } catch {
    return { ...DEFAULT_RULES };
  }
}

/* ---------------- 区域划分 ---------------- */
const PROVINCES = ['北京', '天津', '河北', '山西', '内蒙古', '辽宁', '吉林', '黑龙江', '上海', '江苏', '浙江',
  '安徽', '福建', '江西', '山东', '河南', '湖北', '湖南', '广东', '广西', '海南', '重庆', '四川', '贵州',
  '云南', '西藏', '陕西', '甘肃', '青海', '宁夏', '新疆'];
const EAST = ['上海', '江苏', '浙江', '安徽', '福建', '江西', '山东'];
const REMOTE = ['西藏', '新疆', '青海', '宁夏', '内蒙古', '甘肃'];
const ORIGIN_PROVINCE = '浙江';
const ORIGIN_CITY = '杭州';

export const provinceOf = (addr = '') => PROVINCES.find((p) => addr.includes(p)) ?? null;

/** 区域分档：同城 / 省内 / 同大区 / 跨大区 / 偏远（真实系统用分拣中心距离矩阵） */
export function classifyZone(addr) {
  const p = provinceOf(addr);
  if (!p) return { zone: 'cross', label: '未识别省份，按跨大区计', coefKey: 'zone_cross' };
  if (p === ORIGIN_PROVINCE && (addr || '').includes(ORIGIN_CITY)) {
    return { zone: 'same_city', label: `同城（${ORIGIN_CITY}）`, coefKey: 'zone_same_city' };
  }
  if (p === ORIGIN_PROVINCE) return { zone: 'same_province', label: '省内', coefKey: 'zone_same_province' };
  if (REMOTE.includes(p)) return { zone: 'remote', label: `偏远（${p}）`, coefKey: 'zone_remote' };
  if (EAST.includes(p)) return { zone: 'region', label: `同大区（华东·${p}）`, coefKey: 'zone_region' };
  return { zone: 'cross', label: `跨大区（${p}）`, coefKey: 'zone_cross' };
}

/* ---------------- 阶梯续重（分段累进，不是一刀切） ---------------- */
const CONT_TIERS = [[10, 1.0], [30, 0.9], [100, 0.75], [Infinity, 0.6]];

function contCharged(chargeKg, firstKg, contPrice) {
  let remain = Math.max(0, chargeKg - firstKg);
  let cursor = firstKg;
  let cost = 0;
  const segs = [];
  for (const [up, coef] of CONT_TIERS) {
    if (remain <= 0) break;
    const span = Math.min(remain, Math.max(0, up - cursor));
    if (span > 0) {
      const amount = +(span * contPrice * coef).toFixed(2);
      cost += amount;
      segs.push({ from: cursor, to: +(cursor + span).toFixed(2), coef, amount });
      remain -= span;
      cursor += span;
    }
  }
  return { cost: +cost.toFixed(2), segs };
}

const sumItems = (items) => +items.reduce((s, i) => s + i.amount, 0).toFixed(2);

/**
 * 计费主函数
 * @returns { product, chargeKg, zone, items[{code,label,amount,rule}], total, detail }
 */
export function computeFee({
  product, weight, volumeL = 0, insured = 0, destAddr = '',
  length = 0, pack = false, customer = null,
} = {}) {
  const r = getRules();
  const rate = findRate(product);

  const volWeight = volumeL && rate.vol_divisor ? volumeL / rate.vol_divisor : 0;
  const rawKg = Math.max(Number(weight) || 0, volWeight, 0.01);
  const chargeKg = Math.max(1, Math.ceil(rawKg - 1e-9)); // 进位到整公斤计费

  const zone = classifyZone(destAddr);
  const zoneCoef = r[zone.coefKey] ?? 1;

  const items = [];

  // 1) 基础运费 = (首重价 + 阶梯续重) × 时效系数 × 区域系数
  const cont = contCharged(chargeKg, rate.first_kg, rate.cont_price);
  const base = +((rate.first_price + cont.cost) * rate.coef * zoneCoef).toFixed(2);
  const tierNote = cont.segs.length
    ? cont.segs.map((s) => `${s.from}~${s.to === Infinity ? '∞' : s.to}kg×¥${rate.cont_price}×${s.coef}`).join(' + ')
    : '无续重';
  items.push({
    code: 'freight', label: `基础运费（${rate.name}）`, amount: base,
    rule: `首重${rate.first_kg}kg×¥${rate.first_price} + 续重(${tierNote}) ×时效${rate.coef} ×区域${zoneCoef}${volWeight > (Number(weight) || 0) ? `（体积重${volWeight.toFixed(2)}kg 取大）` : ''}`,
  });

  // 2) 燃油附加费
  const fuel = +(base * r.fuel_rate).toFixed(2);
  if (fuel > 0) items.push({ code: 'fuel', label: '燃油附加费', amount: fuel, rule: `基础运费 × ${(r.fuel_rate * 100).toFixed(1)}%` });

  // 3) 保价费
  const insFee = insured > 0 ? +(insured * r.insured_rate).toFixed(2) : 0;
  if (insFee > 0) items.push({ code: 'insured', label: '保价费', amount: insFee, rule: `保价额 ¥${insured} × ${(r.insured_rate * 1000).toFixed(1)}‰` });

  // 4) 包装服务费
  if (pack) items.push({ code: 'pack', label: '包装服务费', amount: r.pack_fee, rule: '按票固定收费' });

  // 5) 超重附加
  if (chargeKg > r.overweight_kg) {
    items.push({ code: 'overweight', label: '超重附加费', amount: r.overweight_fee, rule: `计费重 ${chargeKg}kg 超过 ${r.overweight_kg}kg` });
  }

  // 6) 超长附加
  if (length && length > r.oversize_cm) {
    items.push({ code: 'oversize', label: '超长附加费', amount: r.oversize_fee, rule: `单边 ${length}cm 超过 ${r.oversize_cm}cm` });
  }

  const subtotal = sumItems(items);

  // 7) 月结协议折扣（只对运费类打折，不对保价费打折）
  let total = subtotal;
  const monthly = customer?.monthly ?? 0;
  if (monthly && r.monthly_discount < 1) {
    const discountBase = sumItems(items.filter((i) => ['freight', 'fuel'].includes(i.code)));
    const cut = +(discountBase * (1 - r.monthly_discount)).toFixed(2);
    if (cut > 0) {
      items.push({ code: 'discount', label: '月结协议折扣', amount: -cut, rule: `运费与燃油 × ${((1 - r.monthly_discount) * 100).toFixed(0)}% 折让` });
      total = +sumItems(items).toFixed(2);
    }
  }

  // 8) 最低一票
  if (total < r.min_charge) {
    items.push({ code: 'min_charge', label: '最低一票补差', amount: +(r.min_charge - total).toFixed(2), rule: `最低收费 ¥${r.min_charge}` });
    total = r.min_charge;
  }

  return {
    product: rate.name,
    chargeKg,
    rawKg: +rawKg.toFixed(2),
    zone: zone.zone,
    zone_label: zone.label,
    zone_coef: zoneCoef,
    items,
    total: +total.toFixed(2),
    detail: items.map((i) => `${i.label} ¥${i.amount}`).join(' + '),
  };
}

/** 阶梯表给前端展示用 */
export function pricingMeta() {
  const r = getRules();
  return {
    rules: r,
    tiers: CONT_TIERS.map(([up, coef]) => ({ up_to_kg: up === Infinity ? null : up, coef })),
    zones: [
      { key: 'same_city', label: '同城', coef: r.zone_same_city },
      { key: 'same_province', label: '省内', coef: r.zone_same_province },
      { key: 'region', label: '同大区', coef: r.zone_region },
      { key: 'cross', label: '跨大区', coef: r.zone_cross },
      { key: 'remote', label: '偏远', coef: r.zone_remote },
    ],
    note: '基础运费 =（首重 + 阶梯续重）× 时效系数 × 区域系数；再叠加燃油、保价、包装、超重超长，最后按协议折扣并兜底最低一票。',
  };
}

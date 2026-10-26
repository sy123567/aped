// core/routing.js — 分拨路由（三段码思想的近似实现）
// 真实三段码：一段=目的分拨中心(3位) 二段=目的网点(3位) 三段=派送段道(2位)
// 这里做到「决策」这一层：下一跳去哪、走空运还是陆运、预计多久，并给出命中规则
import { q } from './helpers.js';
import { classifyZone, provinceOf } from './pricing.js';

export function getHubs() {
  try { return q.all('SELECT * FROM routing_hubs ORDER BY code'); } catch { return []; }
}

const CITY_OF_PROVINCE = {
  浙江: '杭州', 上海: '上海', 北京: '北京', 江苏: '南京', 广东: '广州', 四川: '成都',
  陕西: '西安', 湖北: '武汉', 湖南: '长沙', 山东: '济南', 福建: '福州', 安徽: '合肥',
};

const ETA = { direct: 4, land: 36, air: 10 };

/**
 * 决定下一跳与运输方式
 * 规则优先级（命中即停，rules 里能看到完整判定链）：
 *   1. 同城 → 直发目的网点
 *   2. 省内 → 省内分拨中心（陆运）
 *   3. 偏远 → 陆运枢纽（不排空运）
 *   4. 经济件 → 优先陆运（控成本）
 *   5. 次日达/隔日达 → 必须空运（时效硬约束）
 *   6. 其余 → 覆盖该省的空运枢纽，没有再退陆运
 */
export function planRoute({ destAddr = '', product = '', fromCity = '杭州' }) {
  const zone = classifyZone(destAddr);
  const prov = provinceOf(destAddr) ?? '未知';
  const destCity = CITY_OF_PROVINCE[prov] ?? prov;
  const rules = [];
  const hubs = getHubs();

  const pickHub = (type) => {
    const hit = hubs.filter((h) => h.type === type && (h.coverage ?? '').split(',').includes(prov));
    return hit[0] ?? null;
  };

  // 1. 同城
  if (destAddr.includes(fromCity)) {
    rules.push(`目的地在 ${fromCity}，同城无需中转`);
    return {
      zone: zone.zone, zone_label: zone.label,
      mode: 'direct', mode_label: '同城直发',
      next_hop: `${destCity}目的网点`, hub_code: null,
      eta_hours: ETA.direct, three_seg_hint: `${destCity}-网点-段道`,
      rules,
    };
  }

  // 2. 省内
  if (zone.zone === 'same_province') {
    rules.push(`省内（${prov}），走省内分拨中心陆运`);
    return {
      zone: zone.zone, zone_label: zone.label,
      mode: 'land', mode_label: '省内陆运',
      next_hop: `${prov}省内分拨中心`, hub_code: null,
      eta_hours: 12, three_seg_hint: `${prov}-分拨-网点`,
      rules,
    };
  }

  // 3~6. 跨省
  let mode = 'air';
  if (zone.zone === 'remote') {
    mode = 'land';
    rules.push(`目的省份 ${prov} 属偏远，不排空运，走陆运枢纽`);
  } else if (product === '经济件') {
    mode = 'land';
    rules.push('产品为经济件，优先陆运以控制成本');
  } else if (product === '次日达' || product === '隔日达') {
    mode = 'air';
    rules.push(`产品为${product}，时效硬约束 → 必须空运`);
  } else {
    mode = 'air';
    rules.push('未指定产品，默认空运');
  }

  let hub = pickHub(mode);
  if (!hub) {
    const other = mode === 'air' ? 'land' : 'air';
    hub = pickHub(other);
    if (hub) rules.push(`${mode === 'air' ? '空运' : '陆运'}枢纽不覆盖 ${prov}，降级为${other === 'air' ? '空运' : '陆运'}枢纽 ${hub.code}`);
  }
  if (!hub) {
    rules.push(`无覆盖 ${prov} 的枢纽，需人工指定路由`);
    return {
      zone: zone.zone, zone_label: zone.label,
      mode, mode_label: mode === 'air' ? '空运' : '陆运',
      next_hop: '待人工指定', hub_code: null, eta_hours: null,
      three_seg_hint: null, rules,
    };
  }

  rules.push(`命中枢纽 ${hub.code}（${hub.name}，${hub.type === 'air' ? '空运' : '陆运'}）`);
  const eta = (mode === 'air' ? ETA.air : ETA.land) + (zone.zone === 'remote' ? 36 : 0);

  return {
    zone: zone.zone, zone_label: zone.label,
    mode, mode_label: mode === 'air' ? '空运' : '陆运',
    next_hop: hub.name, hub_code: hub.code,
    eta_hours: eta,
    three_seg_hint: `${hub.code}-${destCity}-段道`,
    rules,
  };
}

/** 运单当前状态下的路由建议（中转登记页用） */
export function suggestForWaybill(waybillNo) {
  const w = q.get(
    `SELECT w.*, o.recv_addr, o.recv_name FROM waybills w LEFT JOIN orders o ON o.id=w.order_id WHERE w.waybill_no=?`,
    waybillNo
  );
  if (!w) return null;
  const r = planRoute({ destAddr: w.recv_addr ?? '', product: w.product ?? '' });
  return { waybill_no: w.waybill_no, product: w.product, dest_addr: w.recv_addr, ...r };
}

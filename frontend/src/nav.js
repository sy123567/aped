/* ============ 单一数据源（v8 · S1/M9 修复） ============
   全站导航与机场地理只此一份定义：
   - App.vue 顶部坞、Dashboard.vue 底部坞都从 NAV 派生，同一套 filterNav 过滤规则；
   - 机场表 AIRPORTS 以真实经纬度为源，SVG 节点坐标由投影函数生成，
     名称→代号反查表由本表派生，不再手抄两份。 */

/* ---------- 机场主数据（经纬度为真实值，来源：各机场公开坐标） ---------- */
export const AIRPORTS = [
  { code: 'PEK', name: '北京首都', lon: 116.58, lat: 40.08 },
  { code: 'XIY', name: '西安咸阳', lon: 108.75, lat: 34.44 },
  { code: 'CTU', name: '成都双流', lon: 103.95, lat: 30.58 },
  { code: 'HGH', name: '杭州萧山', lon: 120.43, lat: 30.23, hub: true },
  { code: 'SHA', name: '上海虹桥', lon: 121.34, lat: 31.20 },
  { code: 'CAN', name: '广州白云', lon: 113.30, lat: 23.39 },
  { code: 'SZX', name: '深圳宝安', lon: 113.81, lat: 22.64 },
];

/* 等距圆柱近似投影 → 1600×900 画布坐标（经度拉伸 60/30 = 2.0，
   近似补偿中纬度经线收敛；整体东移避开左上标题块，南部节点 y 上限 629，
   其代号标签（+30）仍在底部坞上沿之上，留安全边） */
const proj = (lon, lat) => ({
  x: Math.round(300 + (lon - 102) * 60),
  y: Math.round(84 + (40.8 - lat) * 30),
});

export const NODES = Object.fromEntries(
  AIRPORTS.map((a) => [a.code, { ...proj(a.lon, a.lat), name: a.name, hub: !!a.hub }])
);
/* 名称 → 代号（由 AIRPORTS 派生，禁止再手抄一份） */
export const AIRPORT_CODE = Object.fromEntries(AIRPORTS.map((a) => [a.name, a.code]));

/* ---------- 全站导航（唯一定义：路由 / 代号 / 中文名 / 所属模块 / 图标） ---------- */
export const NAV = [
  { to: '/my', code: 'MY', zh: '我的账户', mod: 'my', desc: '个人信息 · 我的订单与账单', icon: 'M4 5h16v14H4zM4 9h16M8 14h4' },
  { to: '/', code: 'BOARD', zh: '工作台', mod: 'board', desc: '全网运行总览 · 出港信息板', icon: 'M3 12l9-9 9 9M5 10v10h14V10' },
  { to: '/orders', code: 'ORD', zh: '客户下单', mod: 'orders', desc: '新建寄件订单与取件预约', icon: 'M4 4h16v4H4zM4 12h16v8H4z' },
  { to: '/dispatch', code: 'DSP', zh: '派单调度', mod: 'dispatch', desc: '待派单池 · 按片区与负载定人', icon: 'M4 7h11l-2-2m2 2l-2 2M20 17H9l2-2m-2 2l2 2' },
  { to: '/pickup', code: 'PKU', zh: '揽收登记', mod: 'pickup', desc: '上门揽收 · 计费生成运单', icon: 'M12 2l3 7h7l-5.5 4.5L18 21l-6-4-6 4 1.5-7.5L2 9h7z' },
  { to: '/transit', code: 'TRN', zh: '分拣中转', mod: 'transit', desc: '中转登记 · 航线与交接', icon: 'M4 6h16M4 18h16M8 6v12M16 6v12' },
  { to: '/delivery', code: 'DLV', zh: '派送签收', mod: 'delivery', desc: '派件任务 · 签收与异常回传', icon: 'M2 7h11v9H2zM13 10h4l3 3v3h-7zM6 18a1.5 1.5 0 100-3 1.5 1.5 0 000 3zM17 18a1.5 1.5 0 100-3 1.5 1.5 0 000 3z' },
  { to: '/tracking', code: 'TRK', zh: '运单跟踪', mod: 'tracking', desc: '凭运单号查全程轨迹', icon: 'M12 2a7 7 0 017 7c0 5-7 13-7 13S5 14 5 9a7 7 0 017-7zm0 4.5a2.5 2.5 0 100 5 2.5 2.5 0 000-5z' },
  { to: '/exceptions', code: 'EXC', zh: '异常理赔', mod: 'exceptions', desc: '异常件处理 · 定损与理赔审批', icon: 'M12 2L1 21h22L12 2zm1 14h-2v2h2v-2zm0-7h-2v5h2V9z' },
  { to: '/customers', code: 'CUS', zh: '客户账户', mod: 'customers', desc: '客户档案 · 月结账户明细', icon: 'M12 12a4 4 0 100-8 4 4 0 000 8zM4 21a8 8 0 0116 0' },
  { to: '/billing', code: 'BIL', zh: '月结账单', mod: 'billing', desc: '账单生成 · 核销与结清', icon: 'M4 3h16v18l-4-2-4 2-4-2-4 2V3zm2 4h12v2H6V7zm0 4h12v2H6v-2z' },
  { to: '/performance', code: 'KPI', zh: '绩效排名', mod: 'performance', desc: '揽派绩效 · 加权评分排名', icon: 'M4 20h16v2H4zM6 16h3V8H6zm5 0h3V4h-3zm5 0h3v-6h-3z' },
  { to: '/reports', code: 'RPT', zh: '运营报表', mod: 'reports', desc: '运营统计 · 报表预览与导出', icon: 'M4 19V5m0 14h16M8 15v-4m4 4V8m4 7v-6' },
];

/* 唯一的权限过滤规则（顶部坞与底部坞共用）：
   未登录 → 空；模块信息缺失 → 全显示（后端仍会拦，宁可显示不可空白）；'*' → 管理员全量 */
export function filterNav(user) {
  if (!user) return [];
  const mods = user.modules;
  if (!mods || !mods.length) return NAV;
  if (mods.includes('*')) return NAV;
  return NAV.filter((n) => mods.includes(n.mod));
}

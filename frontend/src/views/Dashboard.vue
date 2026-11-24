<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue';
import { api, session } from '../api.js';
import { filterNav, NODES, AIRPORT_CODE } from '../nav.js';
import BoardClock from '../components/BoardClock.vue';

/* ================= 数据三态（v8 · S3） =================
   loading：骨架占位（数字显示 ···，舞台有加载提示）
   error：明确报错 + 重试按钮（不再 b=null 后永久显示 – 与加载不可区分）
   ok：正常渲染；今日指标全 0 走空态引导，而不是一面“0 的墙” */
const b = ref(null);
const flights = ref([]);
const orders = ref(null);
const loadState = ref('loading');       // board：loading | ok | error
const flightsState = ref('loading');    // flights：loading | ok | error
const user = ref(session.user);
const stageRef = ref(null);
const ready = computed(() => loadState.value === 'ok');
const dash = (v) => (v == null ? '–' : v);

const isEmpty = computed(() => {
  const v = b.value;
  return ready.value && v && v.pendingOrders === 0 && v.todayPickups === 0 && v.todaySigns === 0 && v.inTransit === 0;
});
/* 空态 CTA 指向下单页：无 orders 模块的角色（如快递员）不显示，避免点了被守卫踢走 */
const canOrder = computed(() => {
  const m = user.value?.modules;
  return !m || !m.length || m.includes('*') || m.includes('orders');
});

/* ---------- 数字滚动入场（v8 · M4：减动效/非首次进入直接落定，不再重复征税） ---------- */
const introDone = ref(typeof sessionStorage !== 'undefined' && sessionStorage.getItem('aepds.dashIntro') === '1');
const reduceMotion = typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
const disp = ref({ pendingOrders: 0, todayPickups: 0, todaySigns: 0, inTransit: 0, openExceptions: 0, unpaidBills: 0, revenue: 0, onTimeRate: 0 });
let countRaf = null;
function countUp(target) {
  if (countRaf) cancelAnimationFrame(countRaf);
  if (reduceMotion || introDone.value) { disp.value = { ...target }; return; }
  const from = { ...disp.value };
  const start = performance.now(), dur = 720;
  const step = (t) => {
    const p = Math.min(1, (t - start) / dur);
    const e = p === 1 ? 1 : 1 - Math.pow(2, -10 * p);
    for (const k in target) disp.value[k] = from[k] + (target[k] - from[k]) * e;
    if (p < 1) countRaf = requestAnimationFrame(step);
  };
  countRaf = requestAnimationFrame(step);
}
const num = (k) => {
  if (loadState.value === 'loading') return '···';
  if (loadState.value === 'error') return '—';
  return String(Math.round(disp.value[k]));
};
const revenueTxt = computed(() => {
  if (loadState.value === 'loading') return '···';
  if (loadState.value === 'error') return '—';
  return `¥${Math.round(disp.value.revenue).toLocaleString('en-US')}`;
});
const onTimeTxt = computed(() => {
  if (!ready.value) return '···';
  return b.value.onTimeRate != null ? `${Math.round(disp.value.onTimeRate)}%` : '暂无样本';
});

/* ---------- 分层景深（v8 · M5：视差只作用于幽灵字层与地图层，标注层保持静态；幅度减半） ---------- */
let px = 0, py = 0, tx = 0, ty = 0, parRaf = null;
function parTick() {
  px += (tx - px) * 0.06;
  py += (ty - py) * 0.06;
  const el = stageRef.value;
  if (el) {
    el.style.setProperty('--px', px.toFixed(4));
    el.style.setProperty('--py', py.toFixed(4));
  }
  if (Math.abs(tx - px) > 0.001 || Math.abs(ty - py) > 0.001) parRaf = requestAnimationFrame(parTick);
  else parRaf = null;
}
function kickPar() { if (!parRaf && !reduceMotion) parRaf = requestAnimationFrame(parTick); }
function onMove(e) {
  const el = stageRef.value;
  if (!el || reduceMotion) return;
  const r = el.getBoundingClientRect();
  tx = ((e.clientX - r.left) / r.width - 0.5) * 2;
  ty = ((e.clientY - r.top) / r.height - 0.5) * 2;
  kickPar();
}
function onLeave() { tx = 0; ty = 0; kickPar(); }

/* ================= 航线几何（v8 · M2/S6/M9） =================
   节点坐标由 nav.js 的真实经纬投影生成；弧线统一向北隆起（消除朝向不一致）；
   标签位置用统一规则（弧上 45% 处沿外法线偏移），不再 per-flight 硬编码；
   弧线采样点供 rAF 光点引擎定位（pointAt），一套几何两处用。 */
const BACKBONE = [['HGH', 'CAN'], ['HGH', 'CTU'], ['HGH', 'XIY'], ['SHA', 'CAN'], ['CTU', 'XIY']];

function arcPath(a, b, lift = 0.24) {
  const mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2;
  const dx = b.x - a.x, dy = b.y - a.y;
  const dist = Math.hypot(dx, dy) || 1;
  /* 法线方向统一取“北侧”：东向航线用 (dy,-dx)，西向用 (-dy,dx)，保证 y 分量 ≤ 0 */
  let nx = -dy / dist, ny = dx / dist;
  if (ny > 0) { nx = -nx; ny = -ny; }
  const cx = mx + nx * dist * lift;
  const cy = my + ny * dist * lift;
  const pts = [];
  let len = 0;
  for (let i = 0; i <= 24; i++) {
    const t = i / 24, u = 1 - t;
    const x = u * u * a.x + 2 * u * t * cx + t * t * b.x;
    const y = u * u * a.y + 2 * u * t * cy + t * t * b.y;
    if (i > 0) len += Math.hypot(x - pts[i - 1].x, y - pts[i - 1].y);
    pts.push({ x, y });
  }
  const mid = pointAt(pts, 0.45);
  /* 标签偏移统一规则：沿外法线 30；短弧（弦长<150，如 HGH→SHA）加大到 54，避免标签压在节点上 */
  const loff = dist < 150 ? 54 : 30;
  return {
    d: `M${a.x} ${a.y} Q ${cx.toFixed(1)} ${cy.toFixed(1)} ${b.x} ${b.y}`,
    pts, len,
    lx: mid.x + nx * loff, ly: mid.y + ny * loff,
  };
}
function pointAt(pts, t) {
  const f = Math.min(0.9999, Math.max(0, t)) * (pts.length - 1);
  const i = Math.floor(f), r = f - i;
  const p = pts[i], q = pts[Math.min(i + 1, pts.length - 1)];
  return { x: p.x + (q.x - p.x) * r, y: p.y + (q.y - p.y) * r };
}
const backboneArcs = computed(() => BACKBONE.map(([f, t]) => ({ key: f + t, ...arcPath(NODES[f], NODES[t], 0.16) })));

/* ---------- 航班状态机（v8 · M3）：光点只编码“在途” ----------
   数据模型只有 etd/atd 两列（atd 在种子数据中即到达时刻，与 etd 构成起讫区间）。
   状态按当前时刻与起讫区间判定：now<etd 待起飞（静态待机点）；
   etd≤now<atd 在途（光点巡航）；now≥atd 已到达（无光点）。atd 缺失时以 etd 判起飞。 */
const nowMin = ref((() => { const d = new Date(); return d.getHours() * 60 + d.getMinutes(); })());
const hm = (s) => { const m = /^(\d{1,2}):(\d{2})/.exec(s ?? ''); return m ? (+m[1]) * 60 + (+m[2]) : null; };
function flightState(f) {
  const etd = hm(f.etd), atd = hm(f.atd);
  if (etd == null) return 'enroute';
  if (nowMin.value < etd) return 'scheduled';
  if (atd != null && nowMin.value >= atd) return 'arrived';
  return 'enroute';
}
const STATE_TXT = { scheduled: '待起飞', enroute: '在途', arrived: '已到达' };

const flightArcs = computed(() => flights.value.map((f, i) => {
  const from = NODES[AIRPORT_CODE[f.dep]], to = NODES[AIRPORT_CODE[f.arr]];
  if (!from || !to) return null;
  const a = arcPath(from, to);
  /* 目标速度 ≈105 单位/秒；时长钳制 [3.2s, 8.5s] 防过慢/过快（被钳制时不再严格等速，L4 已注明） */
  const dur = Math.min(8.5, Math.max(3.2, a.len / 105));
  const drawDelay = 0.35 + i * 0.18;
  return {
    no: f.flight_no, etd: f.etd, atd: f.atd, from: AIRPORT_CODE[f.dep], to: AIRPORT_CODE[f.arr],
    state: flightState(f),
    ...a,
    pid: `fp-${f.flight_no}`,
    dur,
    phase: (i * 0.37) % 1,
    drawDelay: `${drawDelay.toFixed(2)}s`,
    cometDelay: `${(drawDelay + 1.0).toFixed(2)}s`,
    labelDelay: `${(drawDelay + 0.7).toFixed(2)}s`,
  };
}).filter(Boolean));
/* 脉冲语义收敛为一种：在途航班的起讫节点（hub 脉冲仍绑 inTransit>0） */
const endpointSet = computed(() => new Set(flightArcs.value.filter((a) => a.state === 'enroute').flatMap((a) => [a.from, a.to])));
const nodeList = computed(() => Object.entries(NODES).map(([code, n]) => ({ code, ...n, south: code === 'CAN' || code === 'SZX' })));

/* ================= 光点引擎（v8 · S6：SMIL → rAF 统一时钟） =================
   一个 rAF 循环驱动全部在途光点：位置由 pointAt(采样点) 计算后写 transform；
   页面隐藏时自动停摆（visibilitychange + rAF 天然暂停），减动效时不启动。
   主点相位 p、拖尾在 p-0.03 / p-0.06（同一路径位置的滞后采样，代负 begin hack）。 */
const cometEls = new Map();
function registerComet(no, g) {
  if (!g) { cometEls.delete(no); return; }
  cometEls.set(no, {
    core: g.querySelector('.comet-core'),
    glow: g.querySelector('.comet-glow'),
    t1: g.querySelector('.comet-trail'),
    t2: g.querySelector('.comet-trail.t2'),
  });
}
let cometRaf = null, cometT0 = 0;
function cometTick(t) {
  if (!cometT0) cometT0 = t;
  const sec = (t - cometT0) / 1000;
  for (const a of flightArcs.value) {
    if (a.state !== 'enroute') continue;
    const els = cometEls.get(a.no);
    if (!els) continue;
    const p = (sec / a.dur + a.phase) % 1;
    const set = (el, tt) => { if (el) { const pt = pointAt(a.pts, tt); el.setAttribute('transform', `translate(${pt.x.toFixed(1)} ${pt.y.toFixed(1)})`); } };
    set(els.core, p); set(els.glow, p);
    set(els.t1, (p - 0.03 + 1) % 1); set(els.t2, (p - 0.06 + 1) % 1);
  }
  cometRaf = requestAnimationFrame(cometTick);
}
function startComets() {
  if (cometRaf || reduceMotion || document.hidden) return;
  cometT0 = 0;
  cometRaf = requestAnimationFrame(cometTick);
}
function stopComets() { if (cometRaf) cancelAnimationFrame(cometRaf); cometRaf = null; }
function onVis() { document.hidden ? stopComets() : startComets(); }

/* ================= 标注定位（v8 · S4：与节点同一坐标系） =================
   舞台在地图模式下锁定 16:9（去掉 min/max-height 钳制），SVG slice 不再裁切；
   标注的 left/top 由节点画布坐标换算 %（x/1600、y/900），与节点同源同系。 */
const pct = (x, y) => ({ left: `${(x / 16).toFixed(2)}%`, top: `${(y / 9).toFixed(2)}%` });
const annoPos = {
  pending: pct(NODES.HGH.x - 170, 296),
  transit: pct(1155, 548),
  signs: pct(648, 566),
  pickups: pct(490, 512),
  exc: pct(1568, 92),
  rev: pct(1568, 628),
};

/* ================= v9 · 3D 空域场景（Three.js 地球，懒加载 + 2D 回退） =================
   GlobeScene 动态导入（three 独立分包）；组件首帧就绪后 use3D=true 切入并停掉 2D 光点引擎；
   WebGL 不可用/加载失败 → globeFailed，永久保留 v8 的 2D SVG 地图，绝不白屏。
   主角由数据决定：在途弧线即主角，其中进度最接近中途的一条为第一主角（琥珀光点头）；
   v6/v8 锚在 HGH 的单光源与幽灵字已清除——HGH 不再有任何特殊待遇。 */
const GlobeScene = ref(null);
const globeFailed = ref(false);
const use3D = ref(false);
const anchorMap = ref(null);
function onAnchors(m) { anchorMap.value = m; }
watch(use3D, (v) => { if (v) stopComets(); });

/* 航班真实进度（etd→atd 区间内当前时刻的位置）：选第一主角与减动效静置都用它 */
function flightProgress(a) {
  const etd = hm(a.etd), atd = hm(a.atd);
  if (etd == null || atd == null || atd <= etd) return null;
  return Math.min(1, Math.max(0, (nowMin.value - etd) / (atd - etd)));
}
const heroNo = computed(() => {
  let best = null, bd = Infinity;
  for (const a of flightArcs.value) {
    if (a.state !== 'enroute') continue;
    const d = Math.abs((flightProgress(a) ?? 0.3) - 0.5);
    if (d < bd) { bd = d; best = a.no; }
  }
  return best;
});
const globeArcs = computed(() => [
  ...BACKBONE.map(([f, t]) => ({ key: `bb-${f}${t}`, from: f, to: t, state: 'backbone' })),
  ...flightArcs.value.map((a) => ({
    key: a.no, from: a.from, to: a.to, state: a.state,
    hero: a.no === heroNo.value, progress: flightProgress(a), dur: a.dur, phase: a.phase,
  })),
]);
/* 待处理标注：3D 模式锚定 HGH 节点的屏幕投影（球摆动时逐帧跟随）；2D 模式用 v8 的同源坐标 */
const pendingStyle = computed(() => {
  const a = use3D.value ? anchorMap.value?.HGH : null;
  /* 锚点向左让位 96px：PEK 恰在 HGH 正北 ~85px 处，标注的巨号若贴锚点会与 PEK 标签/英雄弧相撞；
     该标注是全网指标（标签已写作 NETWORK），锚点只示意方位，左移后与节点保持视觉呼应又不压字 */
  if (a && a.vis) return { left: `calc(${a.x.toFixed(2)}% - 96px)`, top: `calc(${a.y.toFixed(2)}% - 6px)`, '--d': '.8s' };
  return { ...annoPos.pending, '--d': '.8s' };
});

/* ---------- 底部光轨：运单流水线（v8 · S2：每站真实指标，互不冒充） ----------
   下单=今日新增订单、派单=待派单订单（均由 orders 列表实算，不再借 pendingOrders）；
   揽收/中转/签收取 board。pendingOrders 全屏只在 HGH 标注出现一次。 */
const todayStr = (() => { const d = new Date(); const p = (n) => String(n).padStart(2, '0'); return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`; })();
const orderStats = computed(() => {
  if (!orders.value) return { todayNew: null, awaiting: null };
  return {
    todayNew: orders.value.filter((o) => (o.created_at ?? '').slice(0, 10) === todayStr).length,
    awaiting: orders.value.filter((o) => o.status === '待派单').length,
  };
});
const flow = computed(() => {
  const v = b.value, s = orderStats.value;
  return [
    { zh: '下单', code: 'ORD', val: dash(s.todayNew), note: '今日新增', pos: 8 },
    { zh: '派单', code: 'DSP', val: dash(s.awaiting), note: '待派单', pos: 29 },
    { zh: '揽收', code: 'PKU', val: dash(v?.todayPickups), note: '今日', pos: 50 },
    { zh: '中转', code: 'TRN', val: dash(v?.inTransit), note: '在途', pos: 71 },
    { zh: '签收', code: 'DLV', val: dash(v?.todaySigns), note: '今日', pos: 92, done: v?.todaySigns > 0 },
  ];
});

/* ---------- 底部模块坞（v8 · S1：与顶部坞同源同过滤，纯导航不带计数） ---------- */
const dockModules = computed(() => filterNav(user.value));

/* 磁吸放大：按指针到各项中心的距离算 0..1 的亲近度（smoothstep），写进 --m 供 CSS 做 scale/上浮/名称浮现 */
const dockRef = ref(null);
let dockRaf = null;
function onDockMove(e) {
  if (reduceMotion || dockRaf) return;
  const clientX = e.clientX;
  dockRaf = requestAnimationFrame(() => {
    dockRaf = null;
    const dock = dockRef.value;
    if (!dock) return;
    dock.querySelectorAll('.md-item').forEach((el) => {
      const r = el.getBoundingClientRect();
      const dist = Math.abs(clientX - (r.left + r.width / 2));
      let m = Math.max(0, 1 - dist / 125);
      m = m * m * (3 - 2 * m);
      el.style.setProperty('--m', m.toFixed(3));
    });
  });
}
function onDockLeave() {
  dockRef.value?.querySelectorAll('.md-item').forEach((el) => el.style.setProperty('--m', '0'));
}

const STATUS = [
  { cls: 'wait', name: '待派单', desc: '客户已下单，等待调度员分配' },
  { cls: 'wait', name: '待揽收', desc: '任务已派给快递员，等待上门揽收' },
  { cls: 'transit', name: '转运中', desc: '分拣中转登记完成，在途运输' },
  { cls: 'transit', name: '派送中', desc: '派件任务已生成，快递员派送中' },
  { cls: 'done', name: '已签收', desc: '签收登记完成，运单闭环' },
  { cls: 'fail', name: '派送异常', desc: '进入异常件处理流程' },
];
const RULES = [
  { k: '计费', v: '（首重 + 阶梯续重）× 时效系数 × 区域系数 + 燃油 / 保价等附加费' },
  { k: '体积重', v: '长×宽×高（cm）÷ 6000，与实重取大计费' },
  { k: '保价', v: '保价费 = 保价额 × 3‰；理赔上限 = 保价额' },
  { k: '绩效分', v: '揽收 / 签收 / 准时率 / 异常率归一化后按权重加权（样本 <3 单不排名）' },
];

/* ---------- 数据加载（三态齐全，失败可重试） ---------- */
async function loadData() {
  loadState.value = 'loading';
  flightsState.value = 'loading';
  try {
    b.value = await api.board();
    loadState.value = 'ok';
    countUp({
      pendingOrders: b.value.pendingOrders ?? 0,
      todayPickups: b.value.todayPickups ?? 0,
      todaySigns: b.value.todaySigns ?? 0,
      inTransit: b.value.inTransit ?? 0,
      openExceptions: b.value.openExceptions ?? 0,
      unpaidBills: b.value.unpaidBills ?? 0,
      revenue: b.value.revenue ?? 0,
      onTimeRate: b.value.onTimeRate ?? 0,
    });
  } catch { b.value = null; loadState.value = 'error'; }
  try {
    flights.value = await api.flights();
    flightsState.value = 'ok';
    /* M9：不在机场表中的航班不再静默丢弃——明确告警（弧线仍跳过，避免画错位置） */
    for (const f of flights.value) {
      if (!AIRPORT_CODE[f.dep] || !AIRPORT_CODE[f.arr]) {
        console.warn(`[board] 航班 ${f.flight_no} 的航点「${f.dep} → ${f.arr}」不在机场主数据中，已跳过绘图`);
      }
    }
  } catch { flights.value = []; flightsState.value = 'error'; }
  try { orders.value = await api.orders(); } catch { orders.value = null; }
}

let stateTimer;
onMounted(async () => {
  /* v9：懒加载 3D 场景（three 独立分包，不进首屏主包）；加载失败即标记回退，2D 地图常驻 */
  import('../components/GlobeScene.vue')
    .then((m) => { GlobeScene.value = m.default; })
    .catch(() => { globeFailed.value = true; });
  /* 校正本地缓存的用户模块（与 App 同策略），保证底部坞过滤依据是最新的 */
  if (session.token) {
    try { const u = await api.me(); session.user = u; user.value = u; } catch { user.value = session.user; }
  }
  await loadData();
  sessionStorage.setItem('aepds.dashIntro', '1');
  if (!use3D.value) startComets();
  document.addEventListener('visibilitychange', onVis);
  stateTimer = setInterval(() => { const d = new Date(); nowMin.value = d.getHours() * 60 + d.getMinutes(); }, 30000);
});
onUnmounted(() => {
  clearInterval(stateTimer);
  if (countRaf) cancelAnimationFrame(countRaf);
  if (parRaf) cancelAnimationFrame(parRaf);
  if (dockRaf) cancelAnimationFrame(dockRaf);
  stopComets();
  document.removeEventListener('visibilitychange', onVis);
});
</script>

<template>
  <div class="v6">
    <!-- ══════════ 首屏：一整张活地图画布（无面板、无边框、full-bleed） ══════════ -->
    <section ref="stageRef" class="stage" :class="{ 'intro-done': introDone }" @pointermove="onMove" @pointerleave="onLeave">
      <!-- v9：幽灵大字「HGH」已删除（美术评价：被模块坞切半如污渍；且与“不钦定 HGH”原则冲突）；
           锚在 HGH 的单光源同样清除——3D 模式的光来自地球大气层本身，2D 模式改为舞台中心漫射光 -->

      <!-- 地图层：3D 空域场景（就绪后切入） / 2D SVG 地图（回退与加载中常驻） -->
      <div class="layer layer-map" :class="{ 'has-3d': use3D }" aria-hidden="true">
        <component v-if="GlobeScene && !globeFailed" :is="GlobeScene" variant="board" :arcs="globeArcs"
          @ready="use3D = true" @fallback="globeFailed = true" @anchors="onAnchors" />
        <div v-if="!use3D" class="map-glow"></div>
        <svg v-if="!use3D" class="map-svg" viewBox="0 0 1600 900" preserveAspectRatio="xMidYMid slice">
          <path v-for="(a, i) in backboneArcs" :key="a.key" :d="a.d" class="arc-bg" :style="{ animationDelay: `${(0.15 + i * 0.07).toFixed(2)}s` }" />
          <g v-for="a in flightArcs" :key="a.no">
            <path :d="a.d" class="arc-halo" :style="{ animationDelay: a.drawDelay }" />
            <path :id="a.pid" :d="a.d" pathLength="1" class="arc-live" :style="{ animationDelay: a.drawDelay }" />
            <text :x="a.lx" :y="a.ly" class="arc-label" :style="{ animationDelay: a.labelDelay }">{{ a.no }} · {{ a.etd }}</text>
            <!-- v8 · 在途光点：rAF 引擎驱动（见脚本 cometTick），只给 enroute 航班；
                 待起飞航班在始发节点显示静态待机点；已到达不显示任何光点 -->
            <g v-if="a.state === 'enroute'" class="comet" :style="{ animationDelay: a.cometDelay }" :ref="(el) => registerComet(a.no, el)">
              <circle r="8" class="comet-glow" />
              <circle r="2" class="comet-trail" />
              <circle r="1.3" class="comet-trail t2" />
              <circle r="2.8" class="comet-core" />
            </g>
            <circle v-else-if="a.state === 'scheduled'" :cx="NODES[a.from].x" :cy="NODES[a.from].y" r="5.5" class="standby-dot" />
          </g>
          <g v-for="n in nodeList" :key="n.code">
            <circle v-if="n.hub && b?.inTransit > 0" :cx="n.x" :cy="n.y" r="14" class="hub-pulse" />
            <circle v-else-if="endpointSet.has(n.code)" :cx="n.x" :cy="n.y" r="10" class="node-pulse" />
            <circle :cx="n.x" :cy="n.y" :r="n.hub ? 7 : 4.5" :class="n.hub ? 'node-hub' : 'node'" />
            <!-- 南部节点（CAN/SZX）标签翻转：名在上、代号在下，避免被底部坞/光轨吞没（S4） -->
            <text :x="n.x" :y="n.y + (n.south ? 30 : -16)" class="node-code" :class="{ hub: n.hub }">{{ n.code }}</text>
            <text :x="n.x" :y="n.y + (n.south ? -18 : 28)" class="node-name">{{ n.name }}</text>
          </g>
        </svg>
      </div>

      <div class="stage-grid" aria-hidden="true"></div>

      <!-- 标注层（v8 · M5：本层不再参与视差，保持静态以保证小字锐利） -->
      <div class="layer layer-anno">
        <header class="st-title">
          <p class="st-kicker"><i></i>DEPARTURES · LIVE OPS BOARD</p>
          <h1>
            <span class="rl"><span class="rl-in d1">出港</span></span>
            <span class="rl"><span class="rl-in d2 outline">信息板</span></span>
          </h1>
          <BoardClock />
          <p class="st-ontime">ON-TIME 准时率 <b>{{ onTimeTxt }}</b></p>
        </header>

        <!-- KPI 自由标注：位置由节点坐标换算（annoPos，与 SVG 同一坐标系，S4） -->
        <div class="anno a-pending" :style="pendingStyle">
          <span class="a-lab">NETWORK · 待处理订单</span>
          <span class="a-num">{{ num('pendingOrders') }}</span>
          <span class="a-sub">待派单 / 待揽收</span>
        </div>

        <div class="anno a-transit" :style="{ ...annoPos.transit, '--d': '.95s' }">
          <span class="a-lab">EN ROUTE · 在途运单</span>
          <span class="a-num">{{ num('inTransit') }}</span>
          <span class="a-sub">转运中 / 派送中</span>
        </div>

        <div class="anno a-signs" :style="{ ...annoPos.signs, '--d': '1.05s' }">
          <span class="a-lab">SIGNED · 今日签收</span>
          <span class="a-num">{{ num('todaySigns') }}</span>
          <span class="a-sub">派送闭环</span>
        </div>

        <div class="anno a-pickups" :style="{ ...annoPos.pickups, '--d': '1.15s' }">
          <span class="a-lab">PICKUPS · 今日揽收</span>
          <span class="a-num">{{ num('todayPickups') }}</span>
          <span class="a-sub">已完成上门揽收</span>
        </div>

        <div class="anno a-exc" :class="{ alert: b?.openExceptions > 0 }" :style="{ ...annoPos.exc, '--d': '1.25s' }">
          <span class="a-lab">EXCEPTIONS · 未闭环异常</span>
          <span class="a-num" :class="{ alert: b?.openExceptions > 0 }">{{ num('openExceptions') }}</span>
          <span class="a-sub">待处理 / 待审批</span>
        </div>

        <div class="anno a-rev" :style="{ ...annoPos.rev, '--d': '1.35s' }">
          <span class="a-lab">REVENUE · 累计运费收入</span>
          <span class="a-num xl">{{ revenueTxt }}</span>
          <span class="a-sub">待结算账单 {{ loadState === 'ok' ? dash(b?.unpaidBills) : '···' }}</span>
        </div>

        <!-- 空态引导（v8 · S3）：今日尚无业务数据时，给下一步行动而不是满屏巨字 0 -->
        <RouterLink v-if="isEmpty && canOrder" to="/orders" class="empty-cta glass">
          <span class="ec-dot"></span>今日暂无业务数据 · 录入第一单 <b>去客户下单 →</b>
        </RouterLink>

        <!-- 今日航班：贴左下空域的小字列表（带状态语义 + 图例，M3） -->
        <div class="st-flights">
          <div v-for="a in flightArcs" :key="a.no" class="sf-row">
            <b>{{ a.no }}</b><span>{{ a.from }} → {{ a.to }}</span>
            <span class="sf-t">{{ a.etd }} — {{ a.atd }}</span>
            <span class="sf-state" :class="a.state" :title="STATE_TXT[a.state]"><i></i><span class="sr-only">{{ STATE_TXT[a.state] }}</span></span>
          </div>
          <div v-if="flightsState === 'error'" class="sf-row"><span class="sf-err">航班数据加载失败</span></div>
          <div v-else-if="flightsState === 'ok' && !flights.length" class="sf-row"><span>暂无航班数据</span></div>
          <div class="sf-legend">光点巡航 = 在途 · 空心点 = 待起飞 · 灰点 = 已到达</div>
        </div>

        <span class="st-vertical" aria-hidden="true">AIR EXPRESS NETWORK — LIVE OPS</span>
      </div>

      <!-- 三态覆盖层：加载提示 / 错误重试（S3） -->
      <div v-if="loadState === 'loading'" class="stage-loading" aria-live="polite"><span class="sl-dot"></span>数据加载中 · LOADING</div>
      <div v-if="loadState === 'error'" class="stage-alert" role="alert">
        <span>工作台数据加载失败，请检查网络后重试</span>
        <button class="sa-btn" @click="loadData">重试</button>
      </div>

      <!-- 底部光轨：流水线是地图上的一条光 -->
      <div class="rail" aria-label="运单流水线">
        <div class="rail-line" aria-hidden="true"></div>
        <div v-for="(f, i) in flow" :key="f.code" class="r-station" :style="{ left: `${f.pos}%`, '--d': `${(0.95 + i * 0.12).toFixed(2)}s` }">
          <span class="r-cnt">{{ f.val }}</span>
          <span class="r-dot" :class="{ done: f.done }"></span>
          <span class="r-lab">{{ f.zh }}<small>{{ f.code }} · {{ f.note }}</small></span>
        </div>
      </div>

      <!-- v8 · 模块导航坞：与顶部坞同源（nav.js）同过滤（filterNav），纯导航不带计数（S1/S2） -->
      <nav ref="dockRef" class="mod-dock glass" aria-label="模块导航" @pointermove="onDockMove" @pointerleave="onDockLeave">
        <RouterLink v-for="(m, i) in dockModules" :key="m.to" :to="m.to" class="md-item" :title="m.zh" :aria-label="m.zh" :style="{ animationDelay: `${(0.55 + i * 0.05).toFixed(2)}s` }">
          <span class="md-in">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="m.icon" /></svg>
            <span class="md-name">{{ m.zh }}</span>
          </span>
        </RouterLink>
      </nav>

      <!-- 技法 5 · 颗粒：全画布唯一纹理系统 -->
      <div class="stage-grain" aria-hidden="true"></div>

      <!-- 读屏数据摘要（v8 · M7 部分）：视觉标注的文本替代 -->
      <p class="sr-only" v-if="ready">工作台数据：待处理订单 {{ b.pendingOrders }}，今日揽收 {{ b.todayPickups }}，今日签收 {{ b.todaySigns }}，在途运单 {{ b.inTransit }}，未闭环异常 {{ b.openExceptions }}，累计运费收入 {{ b.revenue }} 元。</p>
    </section>

    <!-- ══════════ 画布之下：状态与口径（去框，纯文字行 + 发丝线） ══════════ -->
    <section class="below">
      <div class="below-col">
        <div class="below-head"><span class="bh-no">01</span><h3>运单状态</h3><span class="bh-sub">STATUS</span></div>
        <div v-for="s in STATUS" :key="s.name" class="brow">
          <span class="tag" :class="s.cls">{{ s.name }}</span>
          <span class="brow-desc">{{ s.desc }}</span>
        </div>
      </div>
      <div class="below-col">
        <div class="below-head"><span class="bh-no">02</span><h3>业务口径</h3><span class="bh-sub">RULES</span></div>
        <div v-for="r in RULES" :key="r.k" class="brow">
          <span class="rule-k">{{ r.k }}</span>
          <span class="brow-desc">{{ r.v }}</span>
        </div>
      </div>
      <p class="foot-note">先派单，再揽收：调度员把「待派单」订单分配给快递员，快递员上门揽收时系统自动计费生成运单 · 运单全程轨迹在「运单跟踪」开放查询，客户只需运单号。</p>
    </section>
  </div>
</template>

<style scoped>
.v6 { --px: 0; --py: 0; }

/* ---------- 画布本体（v8 · S4：地图模式锁定 16:9，去掉 min/max-height 钳制，
   舞台宽高比与 viewBox 恒等，slice 不再裁切，% 标注与 SVG 节点同系对齐） ---------- */
.stage {
  position: relative; width: 100%;
  aspect-ratio: 16 / 9;
  overflow: hidden; background: var(--bg-0);
  isolation: isolate;
}
.layer { position: absolute; inset: 0; }
.layer-map {
  z-index: 1; will-change: transform;
  transform: translate3d(calc(var(--px) * -4px), calc(var(--py) * -3px), 0) scale(1.02);
}
/* v9 · 3D 模式：地图层去掉 CSS 视差/缩放（视差改由 3D 镜头承担），
   保证 GlobeScene 回传的投影锚点与标注层 % 坐标严格同系；画布就绪后淡入 */
.layer-map.has-3d { transform: none; will-change: auto; }
.layer-map .globe3d { opacity: 0; transition: opacity .7s ease; }
.layer-map.has-3d .globe3d { opacity: 1; }
/* 2D 回退模式的漫射光：位于舞台中心（场景光），不再锚定任何枢纽 */
.map-glow {
  position: absolute; left: 60%; top: 46%; width: 1080px; height: 1080px;
  transform: translate(-50%, -50%); border-radius: 50%; pointer-events: none;
  background: radial-gradient(circle, rgba(94, 162, 255, .10) 0%, rgba(94, 162, 255, .035) 38%, transparent 66%);
}
.map-svg { position: absolute; inset: 0; width: 100%; height: 100%; display: block; }
.arc-bg { fill: none; stroke: rgba(94, 162, 255, .15); stroke-width: 1.1; stroke-dasharray: 2 6; opacity: 0; animation: fadeIn .9s var(--ease) both; }
/* 入场编排：航线逐条画出（pathLength=1 归一化，dashoffset 1→0），光晕/标签随后浮现 */
.arc-halo { fill: none; stroke: rgba(94, 162, 255, .16); stroke-width: 7; stroke-linecap: round; opacity: 0; animation: fadeIn .6s var(--ease) forwards; }
.arc-live { fill: none; stroke: var(--acc); stroke-width: 1.8; opacity: .95; stroke-dasharray: 1; stroke-dashoffset: 1; animation: drawIn 1.05s cubic-bezier(.6, 0, .2, 1) forwards; }
@keyframes drawIn { to { stroke-dashoffset: 0; } }
.arc-label { fill: var(--acc); font-family: var(--mono); font-size: 14px; letter-spacing: .06em; text-anchor: middle; paint-order: stroke; stroke: rgba(7, 9, 13, .85); stroke-width: 4px; opacity: 0; animation: fadeIn .5s var(--ease) forwards; }
/* 在途光点组：画线完成后淡入，位置由 rAF 引擎逐帧写 transform（S6） */
.comet { opacity: 0; animation: fadeIn .5s ease-out forwards; }
.comet-glow { fill: rgba(94, 162, 255, .18); }
.comet-core { fill: #e3efff; filter: drop-shadow(0 0 5px rgba(140, 190, 255, .95)) drop-shadow(0 0 14px rgba(94, 162, 255, .55)); }
.comet-trail { fill: rgba(188, 216, 255, .55); }
.comet-trail.t2 { fill: rgba(188, 216, 255, .3); }
/* 待起飞航班的静态待机点：空心小点，与巡航光点形态明确区分（M3） */
.standby-dot { fill: var(--bg-0); stroke: var(--tx-2); stroke-width: 1.6; opacity: .85; }
.node { fill: var(--bg-0); stroke: #94a3b4; stroke-width: 1.6; }
.node-hub { fill: var(--acc); stroke: rgba(94, 162, 255, .45); stroke-width: 6; }
.hub-pulse { fill: none; stroke: rgba(94, 162, 255, .55); stroke-width: 1.2; animation: pulse 2.4s var(--ease) infinite; transform-origin: center; transform-box: fill-box; }
/* 在途航班起讫节点：慢速呼吸脉冲（语义与图例一致，M3） */
.node-pulse { fill: none; stroke: rgba(94, 162, 255, .4); stroke-width: 1.1; animation: pulse 3.4s var(--ease) infinite; transform-origin: center; transform-box: fill-box; }
@keyframes pulse { 0% { transform: scale(.55); opacity: .9; } 70% { transform: scale(2); opacity: 0; } 100% { transform: scale(2); opacity: 0; } }
.node-code { fill: var(--tx-1); font-family: var(--mono); font-size: 15px; font-weight: 600; letter-spacing: .1em; text-anchor: middle; paint-order: stroke; stroke: rgba(7, 9, 13, .7); stroke-width: 3px; }
.node-code.hub { fill: var(--acc); }
.node-name { fill: var(--tx-2); font-size: 12.5px; text-anchor: middle; paint-order: stroke; stroke: rgba(7, 9, 13, .7); stroke-width: 3px; }

/* 网格瓷砖：只作底纹，径向淡出，不成“板” */
.stage-grid {
  position: absolute; inset: 0; z-index: 2; pointer-events: none; opacity: .5;
  background-image:
    linear-gradient(rgba(94, 162, 255, .05) 1px, transparent 1px),
    linear-gradient(90deg, rgba(94, 162, 255, .05) 1px, transparent 1px);
  background-size: 72px 72px;
  -webkit-mask-image: radial-gradient(ellipse 95% 85% at 50% 45%, black 35%, transparent 100%);
  mask-image: radial-gradient(ellipse 95% 85% at 50% 45%, black 35%, transparent 100%);
}

/* 技法 5 · 颗粒：feTurbulence 噪点，全画布唯一纹理（v8：z-index 降到标注之下，不再盖字，S5） */
.stage-grain {
  position: absolute; inset: 0; z-index: 3; pointer-events: none;
  opacity: .05; mix-blend-mode: overlay;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='240' height='240'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='2' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='240' height='240' filter='url(%23n)' opacity='0.6'/%3E%3C/svg%3E");
}

/* ---------- 标注层（静态，不参与视差，M5） ---------- */
.layer-anno { z-index: 5; pointer-events: none; }
.layer-anno a { pointer-events: auto; }

/* 技法 2 · 超尺度标题压图（第二行描边字） + 技法 1 · 逐行遮罩升起 */
.st-title { position: absolute; left: 3.2%; top: 10.5%; }
.st-kicker {
  display: flex; align-items: center; gap: 10px;
  font-family: var(--mono); font-size: 11px; letter-spacing: .34em; color: var(--acc);
  margin-bottom: 18px; text-shadow: 0 1px 10px rgba(7, 9, 13, .9);
}
.st-kicker i { width: 42px; height: 1px; background: var(--acc); box-shadow: 0 0 8px rgba(94, 162, 255, .8); }
.st-title h1 { font-size: clamp(48px, 5.6vw, 88px); font-weight: 700; line-height: 1.04; letter-spacing: -.02em; }
.rl { display: block; overflow: hidden; padding-bottom: .06em; margin-bottom: -.06em; }
.rl-in { display: block; animation: lineUp .8s cubic-bezier(.19, 1, .22, 1) both; text-shadow: 0 2px 30px rgba(7, 9, 13, .65); }
.rl-in.d2 { animation-delay: .09s; }
.rl-in.outline { color: transparent; -webkit-text-stroke: 1.5px rgba(233, 238, 244, .85); text-shadow: none; }
@keyframes lineUp { from { transform: translateY(108%); } to { transform: translateY(0); } }
.st-ontime { margin-top: 8px; font-family: var(--mono); font-size: 11px; letter-spacing: .18em; color: var(--tx-3); }
.st-ontime b { color: var(--acc); font-size: 13px; margin-left: 4px; }

/* KPI 自由标注：位置由 JS 按节点坐标注入（left/top %），此处只管排版与对齐 */
.anno { position: absolute; display: flex; flex-direction: column; gap: 1px; animation: fadeIn .55s var(--ease) var(--d, .25s) both; }
.a-lab {
  display: flex; align-items: center; gap: 7px;
  font-family: var(--mono); font-size: 11.5px; letter-spacing: .2em; color: var(--tx-3);
  text-shadow: 0 1px 8px rgba(7, 9, 13, .95); white-space: nowrap;
}
.a-lab::before { content: ""; width: 5px; height: 5px; border-radius: 50%; background: var(--acc); box-shadow: 0 0 7px rgba(94, 162, 255, .9); flex: none; }
.a-num {
  font-family: var(--mono); font-size: clamp(38px, 3.6vw, 56px); font-weight: 600; line-height: 1.08;
  letter-spacing: -.02em; font-variant-numeric: tabular-nums; color: var(--tx-1);
  text-shadow: 0 2px 22px rgba(7, 9, 13, .8);
}
.a-num.xl { font-size: clamp(44px, 4.4vw, 68px); }
/* v9 · 异常 = 信号琥珀（第二声部）：数字与锚点同亮，全屏琥珀 ≤3 处之一 */
.a-num.alert { color: var(--amber); }
.a-exc.alert .a-lab::before { background: var(--amber); box-shadow: 0 0 7px rgba(237, 163, 60, .9); }
.a-sub { font-size: 11.5px; color: var(--tx-2); text-shadow: 0 1px 8px rgba(7, 9, 13, .95); white-space: nowrap; }
@keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }

.a-pending { transform: translate(-100%, -100%); align-items: flex-end; text-align: right; }
.a-pending .a-lab { flex-direction: row-reverse; }
.a-transit { transform: translateY(-50%); }
.a-exc { transform: translateX(-100%); align-items: flex-end; text-align: right; }
.a-exc .a-lab { flex-direction: row-reverse; }
.a-rev { transform: translateX(-100%); align-items: flex-end; text-align: right; }
.a-rev .a-lab { flex-direction: row-reverse; }

/* 空态引导（S3）：玻璃小片，贴在标题块下方空域 */
.empty-cta {
  position: absolute; left: 3.2%; top: 66%;
  display: inline-flex; align-items: center; gap: 9px;
  padding: 9px 16px; border-radius: 999px;
  font-size: 13px; color: var(--tx-2);
  animation: fadeIn .5s var(--ease) .3s both;
  transition: color .15s var(--ease), border-color .15s var(--ease);
}
.empty-cta:hover { color: var(--tx-1); border-color: rgba(94, 162, 255, .5); }
.empty-cta b { color: var(--acc); font-weight: 600; }
.ec-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--warn); box-shadow: 0 0 8px rgba(217, 161, 63, .9); flex: none; }

/* 三态覆盖层（S3） */
.stage-loading {
  position: absolute; left: 50%; top: 92px; transform: translateX(-50%); z-index: 12;
  display: flex; align-items: center; gap: 9px;
  font-family: var(--mono); font-size: 11px; letter-spacing: .22em; color: var(--tx-2);
  background: rgba(7, 9, 13, .55); padding: 6px 14px; border-radius: 999px;
  backdrop-filter: blur(8px); -webkit-backdrop-filter: blur(8px);
}
.sl-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--acc); animation: fsBlink 1s ease-in-out infinite; }
@keyframes fsBlink { 0%, 100% { opacity: 1; } 50% { opacity: .3; } }
.stage-alert {
  position: absolute; left: 50%; top: 92px; transform: translateX(-50%); z-index: 12;
  display: flex; align-items: center; gap: 14px;
  font-size: 13px; color: #f0a49e;
  background: rgba(60, 16, 14, .72); border: 1px solid rgba(229, 83, 75, .5);
  padding: 8px 10px 8px 16px; border-radius: 999px;
  backdrop-filter: blur(8px); -webkit-backdrop-filter: blur(8px);
}
.sa-btn {
  background: rgba(229, 83, 75, .18); border: 1px solid rgba(229, 83, 75, .55); color: #ffd9d5;
  font-size: 12.5px; padding: 4px 14px; border-radius: 999px;
}
.sa-btn:hover { background: rgba(229, 83, 75, .3); }

/* 模块导航坞：悬浮玻璃坞，逐个弹入 + 磁吸放大（纯导航，无计数，S1） */
.mod-dock {
  position: absolute; left: 50%; bottom: 112px; transform: translateX(-50%);
  z-index: 9; display: flex; gap: 2px; align-items: flex-end;
  max-width: 96%; padding: 10px 12px 9px; border-radius: 24px;
}
.md-item {
  --m: 0; display: block; border-radius: 16px;
  animation: dockPop .55s cubic-bezier(.34, 1.45, .64, 1) both;
}
@keyframes dockPop { from { opacity: 0; transform: translateY(24px) scale(.5); } to { opacity: 1; transform: translateY(0) scale(1); } }
.md-in {
  position: relative;
  display: flex; flex-direction: column; align-items: center; gap: 3px;
  padding: 8px 11px 7px; border-radius: 16px;
  transform: translateY(calc(var(--m) * -8px)) scale(calc(1 + var(--m) * .48));
  transform-origin: 50% 100%;
  transition: transform .16s var(--ease);
  will-change: transform;
}
/* v10 · 底部坞激活标记与顶部坞同语法：短横光线 + 名称变航空蓝（代 v7 的纯高亮） */
.md-item.router-link-exact-active .md-in::after {
  content: ""; position: absolute; left: 50%; bottom: 1px; transform: translateX(-50%);
  width: 18px; height: 2px; border-radius: 2px;
  background: linear-gradient(90deg, transparent, var(--acc) 30%, var(--acc) 70%, transparent);
  box-shadow: 0 0 7px rgba(94, 162, 255, .9), 0 0 13px rgba(94, 162, 255, .4);
}
.md-item.router-link-exact-active .md-name { color: var(--acc); }
.md-item:hover .md-in { background: rgba(94, 162, 255, .07); }
.md-in svg {
  width: 23px; height: 23px; color: var(--tx-2);
  opacity: calc(.6 + var(--m) * .4);
  transition: color .15s var(--ease), opacity .15s var(--ease);
}
.md-item:hover svg { color: var(--acc); filter: drop-shadow(0 0 7px rgba(94, 162, 255, .65)); }
.md-item.router-link-exact-active svg { color: var(--acc); opacity: 1; }
.md-name {
  font-size: 11px; font-weight: 500; line-height: 1.35; color: var(--tx-2); white-space: nowrap;
  opacity: calc(.42 + var(--m) * .58);
  transition: opacity .15s var(--ease), color .15s var(--ease);
}
.md-item:hover .md-name, .md-item.router-link-exact-active .md-name { color: var(--tx-1); opacity: 1; }

/* 今日航班小字列表 + 状态 + 图例（v8 目检修订：由右上移至左下空域，避开待处理标注与弧线标签） */
.st-flights { position: absolute; left: 3.2%; top: 51%; font-family: var(--mono); font-size: 11.5px; color: var(--tx-2); font-variant-numeric: tabular-nums; animation: fadeIn .5s var(--ease) 1.3s both; }
.sf-row { display: flex; gap: 14px; padding: 3px 0; justify-content: flex-start; align-items: baseline; text-shadow: 0 1px 8px rgba(7, 9, 13, .95); }
.sf-row b { color: var(--tx-1); font-weight: 600; min-width: 56px; }
.sf-t { color: var(--tx-3); }
.sf-err { color: var(--bad); }
.sf-state { display: inline-flex; align-items: center; }
.sf-state i { width: 6px; height: 6px; border-radius: 50%; background: var(--tx-3); flex: none; }
.sf-state.scheduled i { background: transparent; border: 1.5px solid var(--tx-3); width: 5px; height: 5px; }
.sf-state.enroute { color: var(--acc); }
.sf-state.enroute i { background: var(--acc); box-shadow: 0 0 7px rgba(94, 162, 255, .9); }
.sf-state.arrived { color: var(--tx-2); }
.sf-state.arrived i { background: var(--tx-2); }
.sf-legend { margin-top: 8px; padding-top: 7px; border-top: 1px solid rgba(94, 162, 255, .12); font-size: 11px; color: var(--tx-3); text-align: left; letter-spacing: .04em; }

/* 技法 4 · 右缘竖排字 */
.st-vertical {
  position: absolute; right: 12px; top: 50%; transform: translateY(-50%);
  writing-mode: vertical-rl; font-family: var(--mono); font-size: 11px;
  letter-spacing: .52em; color: rgba(118, 135, 156, .8); user-select: none;
}

/* ---------- 底部光轨 ---------- */
.rail { position: absolute; left: 4.5%; right: 4.5%; bottom: 20px; height: 78px; z-index: 6; pointer-events: none; }
.rail-line {
  position: absolute; left: 0; right: 0; top: 37px; height: 2px;
  background: linear-gradient(90deg, transparent, rgba(94, 162, 255, .28) 7%, rgba(94, 162, 255, .9) 50%, rgba(94, 162, 255, .28) 93%, transparent);
  box-shadow: 0 0 14px rgba(94, 162, 255, .5);
}
.rail-line::after {
  content: ""; position: absolute; left: 0; right: 0; top: 5px; height: 5px;
  background: repeating-linear-gradient(90deg, rgba(94, 162, 255, .25) 0 2px, transparent 2px 16px);
}
/* 光轨流光：一束光沿流水线轨道持续巡游（数据之光，三处持续动效之一） */
.rail-line::before {
  content: ""; position: absolute; top: -1px; height: 4px; width: 150px; z-index: 1;
  background: linear-gradient(90deg, transparent, rgba(190, 218, 255, .95), transparent);
  filter: blur(.6px); opacity: 0; pointer-events: none;
  animation: railSweep 5.5s linear 1.6s infinite;
}
@keyframes railSweep {
  0% { left: -160px; opacity: 0; }
  7% { opacity: 1; }
  88% { opacity: 1; }
  100% { left: 100%; opacity: 0; }
}
.r-station { position: absolute; top: 0; height: 100%; transform: translateX(-50%); display: flex; flex-direction: column; align-items: center; animation: fadeIn .5s var(--ease) var(--d, 1s) both; }
.r-cnt {
  font-family: var(--mono); font-size: 20px; font-weight: 600; line-height: 1; height: 24px;
  font-variant-numeric: tabular-nums; text-shadow: 0 1px 10px rgba(7, 9, 13, .9);
}
.r-dot {
  position: absolute; top: 37px; left: 50%; transform: translate(-50%, -50%);
  width: 11px; height: 11px; border-radius: 50%;
  background: var(--bg-0); border: 2px solid var(--acc);
  box-shadow: 0 0 10px rgba(94, 162, 255, .75), 0 0 0 4px rgba(7, 9, 13, .85);
}
.r-dot.done { border-color: var(--b-200); box-shadow: 0 0 10px rgba(143, 191, 255, .85), 0 0 0 4px rgba(7, 9, 13, .85); }
.r-lab {
  position: absolute; top: 50px; left: 50%; transform: translateX(-50%);
  font-size: 12.5px; font-weight: 600; white-space: nowrap; display: flex; align-items: baseline; gap: 7px;
  text-shadow: 0 1px 8px rgba(7, 9, 13, .95);
}
.r-lab small { font-family: var(--mono); font-size: 11px; font-weight: 400; letter-spacing: .1em; color: var(--tx-3); }

/* ---------- 画布之下（去框文字区） ---------- */
.below {
  max-width: 1280px; margin: 0 auto; width: 100%;
  padding: 58px 40px 0;
  display: grid; grid-template-columns: 1fr 1fr; gap: 24px 72px;
}
.below-head { display: flex; align-items: baseline; gap: 12px; padding-bottom: 12px; border-bottom: 1px solid var(--line); margin-bottom: 6px; }
.bh-no { font-family: var(--mono); font-size: 12px; color: var(--acc); letter-spacing: .1em; }
.below-head h3 { font-size: 15px; font-weight: 600; }
.bh-sub { margin-left: auto; font-family: var(--mono); font-size: 11px; letter-spacing: .2em; color: var(--tx-3); }
.brow { display: flex; align-items: center; gap: 14px; padding: 9px 0; border-bottom: 1px solid rgba(94, 162, 255, .07); }
.brow:last-child { border-bottom: none; }
.brow-desc { font-size: 13px; color: var(--tx-2); }
.rule-k { flex: none; width: 52px; font-size: 13px; font-weight: 600; }
.foot-note {
  grid-column: 1 / -1; margin-top: 26px; padding-bottom: 76px;
  font-family: var(--mono); font-size: 12px; color: var(--tx-3); letter-spacing: .03em; line-height: 1.9;
}

.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }

/* ---------- v8 · M4：非首次进入——编排只播一次，路由返回直接落定 ---------- */
.stage.intro-done .rl-in,
.stage.intro-done .anno,
.stage.intro-done .st-flights,
.stage.intro-done .r-station,
.stage.intro-done .md-item,
.stage.intro-done .arc-bg,
.stage.intro-done .arc-halo,
.stage.intro-done .arc-label,
.stage.intro-done .comet,
.stage.intro-done .empty-cta { animation-delay: 0s !important; animation-duration: .18s !important; }
.stage.intro-done .arc-live { animation-delay: 0s !important; animation-duration: .18s !important; }

/* ---------- 窄屏：画布退化为纵向流，标注回位 ---------- */
@media (max-width: 980px) {
  .stage { aspect-ratio: auto; padding: 118px 20px 108px; display: flex; flex-direction: column; gap: 30px; }
  .layer-map { opacity: .45; }
  .st-vertical { display: none; }
  .layer-anno { position: relative; inset: auto; display: flex; flex-direction: column; gap: 26px; pointer-events: auto; }
  .st-title, .anno, .st-flights { position: static; transform: none; text-align: left; align-items: flex-start; }
  .anno .a-lab { flex-direction: row; }
  .empty-cta { position: static; }
  .mod-dock { position: relative; left: auto; bottom: auto; transform: none; margin: 4px auto 0; flex-wrap: wrap; justify-content: center; max-width: 100%; }
  .rail { left: 20px; right: 20px; bottom: 14px; }
  .r-lab small { display: none; }
  .below { grid-template-columns: 1fr; padding: 40px 20px 0; }
}
@media (prefers-reduced-motion: reduce) {
  .rl-in, .anno, .st-flights, .r-station, .md-item, .arc-bg, .arc-halo, .arc-label { animation: none; opacity: 1; }
  .arc-live { animation: none; stroke-dashoffset: 0; }
  .comet { display: none; }
  .rail-line::before { display: none; }
  .hub-pulse, .node-pulse { animation: none; opacity: 0; }
  .md-in { transition: none; }
}
</style>

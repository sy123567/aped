<script setup>
/* ================= v10 · 全屏目录（INDEX） =================
   编辑式索引：超大编号 + 中文大字 + 代号 + 职责短语 + 实时计数，纵向排布如杂志目录/机场代码表。
   数据与权限：与双坞完全同源——NAV 定义 + filterNav 过滤（nav.js 单一数据源），
   本组件不复制任何模块清单；计数只为「当前用户可见」的模块拉取，失败记 null 显示 —，不冒充。
   编号口径：按可见项连续重排（01…N）——目录是给当前读者的索引，留空号既像缺页，
   也会泄露无权模块的数量与位置；原号顺序即 NAV 顺序，重排不改变相对次序。 */
import { ref, computed, watch, onUnmounted } from 'vue';
import { useRouter } from 'vue-router';
import { filterNav } from '../nav.js';
import { api } from '../api.js';

const props = defineProps({
  open: { type: Boolean, default: false },
  user: { type: Object, default: null },
});
const emit = defineEmits(['close']);
const router = useRouter();

const items = computed(() => filterNav(props.user));
const active = ref(-1);
const closing = ref(false);
const counts = ref({}); // mod -> { v:number, unit:string } | null

/* ---------- 实时计数：每个模块一个取数口径，只拉可见项，互不冒充 ---------- */
const ym = () => new Date().toISOString().slice(0, 7);
const COUNTERS = {
  my: async (u) => (u?.customer_id ? { v: (await api.customer(u.customer_id)).orders?.length ?? 0, unit: '单' } : null),
  board: async () => ({ v: (await api.board()).pendingOrders ?? 0, unit: '待处理' }),
  orders: async () => ({ v: (await api.orders()).length, unit: '单' }),
  dispatch: async () => ({ v: (await api.orders()).filter((o) => o.status === '待派单').length, unit: '待派' }),
  pickup: async () => ({ v: (await api.pickupTasks()).length, unit: '任务' }),
  transit: async () => ({ v: (await api.transitList()).length, unit: '记录' }),
  delivery: async () => ({ v: (await api.deliveryTasks()).length, unit: '任务' }),
  tracking: async () => ({ v: (await api.waybills()).length, unit: '运单' }),
  exceptions: async () => ({ v: (await api.exceptions()).length, unit: '件' }),
  customers: async () => ({ v: (await api.customers()).length, unit: '户' }),
  billing: async () => ({ v: (await api.bills()).length, unit: '张' }),
  performance: async () => ({ v: ((await api.performance(ym())).rows ?? []).length, unit: '人' }),
  reports: async () => ({ v: 5, unit: '类报表' }),
};
async function loadCounts() {
  counts.value = {};
  const vis = items.value;
  await Promise.allSettled(vis.map(async (n) => {
    const fn = COUNTERS[n.mod];
    if (!fn) return;
    try { counts.value[n.mod] = await fn(props.user); }
    catch { counts.value[n.mod] = null; }
  }));
}
const countTxt = (mod) => {
  const c = counts.value[mod];
  if (c === undefined) return '···';
  if (c === null) return '—';
  return String(c.v);
};
const countUnit = (mod) => counts.value[mod]?.unit ?? '';

/* ---------- 3D 地球弱化背景（与登录页同一组件、同一世界；失败静默，只剩网格底） ---------- */
const GlobeScene = ref(null);
import('./GlobeScene.vue').then((m) => { GlobeScene.value = m.default; }).catch(() => {});

/* ---------- 开合与键盘 ---------- */
let savedOverflow = '';
watch(() => props.open, (v) => {
  if (v) {
    closing.value = false;
    active.value = -1;
    savedOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    loadCounts();
    window.addEventListener('keydown', onKey);
  } else {
    document.body.style.overflow = savedOverflow;
    window.removeEventListener('keydown', onKey);
  }
});
onUnmounted(() => {
  document.body.style.overflow = savedOverflow;
  window.removeEventListener('keydown', onKey);
});

function onKey(e) {
  if (e.key === 'Escape') { e.preventDefault(); close(); }
  else if (e.key === 'ArrowDown') { e.preventDefault(); active.value = Math.min(items.value.length - 1, active.value + 1); }
  else if (e.key === 'ArrowUp') { e.preventDefault(); active.value = Math.max(0, active.value - 1); }
  else if (e.key === 'Enter' && active.value >= 0) { e.preventDefault(); go(items.value[active.value]); }
}
function close() {
  if (closing.value) return;
  closing.value = true;
  setTimeout(() => emit('close'), 190);
}
function go(item) {
  if (!item || closing.value) return;
  closing.value = true;
  /* 先让目录退场（遮罩淡出+内容下沉），再切路由——新页的入场编排接续其后，不叠加不抢戏 */
  setTimeout(() => {
    emit('close');
    if (router.currentRoute.value.path !== item.to) router.push(item.to);
  }, 190);
}
const pad = (i) => String(i + 1).padStart(2, '0');
</script>

<template>
  <div v-if="open" class="dir-overlay" :class="{ closing }" role="dialog" aria-modal="true" aria-label="模块目录">
    <!-- 背景分层：网格瓷砖 → 弱化 3D 地球（右半屏，压暗）→ 径向暗角，内容在其上 -->
    <div class="dir-bg gridbg" aria-hidden="true"></div>
    <div class="dir-globe" aria-hidden="true">
      <component v-if="GlobeScene" :is="GlobeScene" variant="login" :arcs="[]" :show-labels="false" />
    </div>
    <div class="dir-shade" aria-hidden="true"></div>

    <div class="dir-frame">
      <header class="dir-head">
        <div class="dh-left">
          <span class="dh-kicker">INDEX · 模块目录</span>
          <span class="dh-brand">AEPDS — 航空快递揽收与派送</span>
        </div>
        <div class="dh-right">
          <span class="dh-user">{{ user?.display_name || '' }} · {{ user?.role_label || '' }}</span>
          <button class="dh-close" @click="close" aria-label="关闭目录">ESC ✕</button>
        </div>
      </header>

      <nav class="dir-list" :class="{ 'has-active': active >= 0 }" aria-label="全部模块" @mouseleave="active = -1">
        <button v-for="(n, i) in items" :key="n.to" class="dir-row" :class="{ 'is-active': active === i }"
          :style="{ '--i': i }" @mouseenter="active = i" @focus="active = i" @click="go(n)">
          <span class="dr-no">{{ pad(i) }}</span>
          <span class="dr-main">
            <span class="dr-zh">{{ n.zh }}</span>
            <span class="dr-desc">{{ n.desc }}</span>
          </span>
          <span class="dr-code">{{ n.code }}</span>
          <svg class="dr-glyph" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.1"
            stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="n.icon" /></svg>
          <span class="dr-count"><b>{{ countTxt(n.mod) }}</b><small>{{ countUnit(n.mod) }}</small></span>
          <span class="dr-arrow" aria-hidden="true">→</span>
          <span class="dr-line" aria-hidden="true"></span>
        </button>
      </nav>

      <footer class="dir-foot">
        <span>共 {{ items.length }} 个模块 · 按当前账号权限显示</span>
        <span class="df-hint">↑ ↓ 选择 · ENTER 进入 · ESC 关闭</span>
      </footer>
    </div>
  </div>
</template>

<style scoped>
.dir-overlay {
  position: fixed; inset: 0; z-index: 200;
  animation: dirIn .28s var(--ease) both;
}
.dir-overlay.closing { animation: dirOut .19s var(--ease) both; }
@keyframes dirIn { from { opacity: 0; } to { opacity: 1; } }
@keyframes dirOut { to { opacity: 0; } }

.dir-bg {
  position: absolute; inset: 0; background-color: rgba(7, 9, 13, .955);
  backdrop-filter: blur(16px) saturate(1.2); -webkit-backdrop-filter: blur(16px) saturate(1.2);
}
.dir-globe {
  position: absolute; top: 0; right: -12%; width: 62%; height: 100%;
  opacity: .34; pointer-events: none;
  -webkit-mask-image: radial-gradient(ellipse 90% 80% at 60% 50%, black 30%, transparent 78%);
  mask-image: radial-gradient(ellipse 90% 80% at 60% 50%, black 30%, transparent 78%);
}
.dir-shade {
  position: absolute; inset: 0; pointer-events: none;
  background:
    radial-gradient(ellipse 70% 60% at 30% 42%, rgba(94, 162, 255, .07), transparent 65%),
    linear-gradient(180deg, rgba(7, 9, 13, .25), rgba(7, 9, 13, .55));
}

.dir-frame {
  position: relative; z-index: 2; height: 100%;
  max-width: 1180px; margin: 0 auto; padding: 30px 44px 22px;
  display: flex; flex-direction: column;
}
.dir-overlay.closing .dir-frame { animation: frameSink .19s var(--ease) both; }
@keyframes frameSink { to { opacity: 0; transform: translateY(14px); } }

/* ---------- 头 ---------- */
.dir-head {
  display: flex; justify-content: space-between; align-items: flex-start; gap: 20px;
  padding-bottom: 16px; border-bottom: 1px solid var(--line);
  animation: rowUp .45s var(--ease) both;
}
.dh-left { display: flex; flex-direction: column; gap: 3px; }
.dh-kicker { font-family: var(--mono); font-size: 12px; letter-spacing: .34em; color: var(--acc); }
.dh-brand { font-size: 13px; color: var(--tx-2); letter-spacing: .06em; }
.dh-right { display: flex; align-items: center; gap: 16px; }
.dh-user { font-family: var(--mono); font-size: 11.5px; color: var(--tx-3); letter-spacing: .08em; white-space: nowrap; }
.dh-close {
  background: rgba(94, 162, 255, .07); border: 1px solid var(--line); color: var(--tx-2);
  font-family: var(--mono); font-size: 11.5px; letter-spacing: .14em;
  padding: 7px 14px; border-radius: 999px; transition: all .13s var(--ease);
}
.dh-close:hover { color: var(--tx-1); border-color: var(--line-strong); }

/* ---------- 索引行 ---------- */
.dir-list { flex: 1; min-height: 0; overflow-y: auto; padding: 10px 0 4px; scrollbar-width: none; }
.dir-list::-webkit-scrollbar { display: none; }
.dir-row {
  position: relative; width: 100%;
  display: grid; grid-template-columns: 74px 1fr auto 92px 30px; align-items: center; gap: 18px;
  padding: 0.95vh 10px; background: none; border: none; text-align: left; color: var(--tx-1);
  border-bottom: 1px solid rgba(94, 162, 255, .07);
  cursor: pointer; overflow: hidden;
  /* fill 只许 backwards：both 的 forwards 会把 opacity:1 钉死，压掉聚光灯的退暗（.3） */
  animation: rowUp .55s cubic-bezier(.19, 1, .22, 1) backwards;
  animation-delay: calc(70ms + var(--i) * 42ms);
  transition: opacity .18s var(--ease);
}
@keyframes rowUp { from { opacity: 0; transform: translateY(22px); } to { opacity: 1; transform: translateY(0); } }
/* 聚光灯：有激活行时，其余行退暗，只留被指的那一行在光里 */
.dir-list.has-active .dir-row:not(.is-active) { opacity: .3; }
.dir-row.is-active { opacity: 1; }

.dr-no {
  font-family: var(--mono); font-size: 15px; font-weight: 500; color: var(--tx-3);
  letter-spacing: .08em; font-variant-numeric: tabular-nums;
  transition: color .16s var(--ease);
}
.dir-row.is-active .dr-no { color: var(--acc); }
.dr-main { display: flex; align-items: baseline; gap: 18px; min-width: 0; position: relative; z-index: 1; }
.dr-zh {
  font-size: clamp(20px, 2.9vh, 29px); font-weight: 600; line-height: 1.15; white-space: nowrap;
  letter-spacing: .01em;
  transition: letter-spacing .22s var(--ease), color .16s var(--ease), transform .22s var(--ease);
}
.dir-row.is-active .dr-zh { letter-spacing: .06em; color: #fff; transform: translateX(6px); }
.dr-desc { font-size: 12.5px; color: var(--tx-3); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; transition: color .16s var(--ease); }
.dir-row.is-active .dr-desc { color: var(--tx-2); }
.dr-code {
  font-family: var(--mono); font-size: 12px; letter-spacing: .3em; color: var(--tx-3);
  position: relative; z-index: 1; transition: color .16s var(--ease);
}
.dir-row.is-active .dr-code { color: var(--acc); }
/* 代表图形：该模块图标放大成水印，只在激活行浮现（ghost graphic） */
.dr-glyph {
  position: absolute; right: 150px; top: 50%; width: 92px; height: 92px;
  transform: translateY(-50%) rotate(-6deg) scale(.82);
  color: var(--acc); opacity: 0; pointer-events: none;
  transition: opacity .22s var(--ease), transform .3s var(--ease);
}
.dir-row.is-active .dr-glyph { opacity: .14; transform: translateY(-50%) rotate(-6deg) scale(1); }
/* 激活行的微光：径向光晕贴在行后，随聚光灯一起亮 */
.dir-row::before {
  content: ""; position: absolute; inset: 0; pointer-events: none; opacity: 0;
  background: radial-gradient(ellipse 55% 130% at 32% 50%, rgba(94, 162, 255, .13), transparent 70%);
  transition: opacity .22s var(--ease);
}
.dir-row.is-active::before { opacity: 1; }
/* 激活行底光线：自左向右展开的短横光 */
.dr-line {
  position: absolute; left: 10px; right: 10px; bottom: -1px; height: 1px;
  background: linear-gradient(90deg, var(--acc), rgba(94, 162, 255, .35) 55%, transparent);
  transform: scaleX(0); transform-origin: left; opacity: 0;
  transition: transform .3s var(--ease), opacity .2s var(--ease);
}
.dir-row.is-active .dr-line { transform: scaleX(1); opacity: 1; }
.dr-count { display: flex; align-items: baseline; justify-content: flex-end; gap: 7px; position: relative; z-index: 1; white-space: nowrap; }
.dr-count b {
  font-family: var(--mono); font-size: 20px; font-weight: 600; color: var(--tx-1);
  font-variant-numeric: tabular-nums; letter-spacing: -.01em; line-height: 1.1;
}
.dr-count small { font-size: 11px; color: var(--tx-3); letter-spacing: .1em; }
.dir-row.is-active .dr-count b { color: var(--acc); }
.dr-arrow {
  font-size: 17px; color: var(--acc); opacity: 0; transform: translateX(-8px);
  transition: opacity .18s var(--ease), transform .22s var(--ease); position: relative; z-index: 1;
}
.dir-row.is-active .dr-arrow { opacity: 1; transform: translateX(0); }

/* ---------- 脚 ---------- */
.dir-foot {
  display: flex; justify-content: space-between; gap: 16px;
  padding-top: 13px; border-top: 1px solid var(--line);
  font-family: var(--mono); font-size: 11px; letter-spacing: .14em; color: var(--tx-3);
  animation: rowUp .45s var(--ease) .12s both;
}
.df-hint { color: var(--tx-3); }

@media (max-width: 900px) {
  .dir-frame { padding: 20px 18px 14px; }
  .dir-row { grid-template-columns: 46px 1fr 64px 22px; gap: 10px; }
  .dr-code { display: none; }
  .dr-desc { display: none; }
  .dr-glyph { right: 90px; }
  .dh-user { display: none; }
}
@media (prefers-reduced-motion: reduce) {
  .dir-overlay, .dir-row, .dir-head, .dir-foot { animation: none !important; }
  .dr-zh, .dr-glyph, .dr-line, .dr-arrow { transition: none !important; }
}
</style>

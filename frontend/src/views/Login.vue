<script setup>
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { api, session } from '../api.js';
import { AIRPORT_CODE } from '../nav.js';

const router = useRouter();
const form = ref({ username: '', password: '' });
const err = ref('');
const loading = ref(false);

/* v8 · M10：航班表不再写死副本——挂载时尝试拉真实航班（登录页无 token 时接口不可用则保留种子兜底） */
const FLIGHT_FALLBACK = [
  { no: 'MU5101', from: 'HGH', to: 'SHA', t: '08:20 — 09:15' },
  { no: 'CA1832', from: 'HGH', to: 'PEK', t: '10:40 — 13:05' },
  { no: 'ZH9502', from: 'SHA', to: 'SZX', t: '14:10 — 16:35' },
];
const flightRows = ref(FLIGHT_FALLBACK);
const rawFlights = ref(null);
onMounted(async () => {
  try {
    const rows = await api.flights();
    if (Array.isArray(rows) && rows.length) {
      rawFlights.value = rows;
      flightRows.value = rows.map((f) => ({
        no: f.flight_no,
        from: AIRPORT_CODE[f.dep] ?? f.dep,
        to: AIRPORT_CODE[f.arr] ?? f.arr,
        t: `${f.etd} — ${f.atd}`,
      }));
    }
  } catch { /* 无 token/接口不可用：保留兜底 */ }
});

/* ================= v9 · 登录页 3D 背景（与工作台同一个品牌世界） =================
   GlobeScene 弱化版（慢转、压暗、无标注）懒加载；就绪前/失败时保留下方 SVG 航线图兜底。
   弧线状态用与工作台相同的状态机（etd/atd × 当前时刻）：拉到真实航班才画状态，
   兜底种子只画 decor 装饰弧，不冒充在途。 */
const GlobeScene = ref(null);
const globeFailed = ref(false);
const globeReady = ref(false);
onMounted(async () => {
  try { GlobeScene.value = (await import('../components/GlobeScene.vue')).default; }
  catch { globeFailed.value = true; }
});
const hm = (s) => { const m = /^(\d{1,2}):(\d{2})/.exec(s ?? ''); return m ? (+m[1]) * 60 + (+m[2]) : null; };
const loginArcs = computed(() => {
  const now = (() => { const d = new Date(); return d.getHours() * 60 + d.getMinutes(); })();
  if (rawFlights.value?.length) {
    return rawFlights.value.map((f) => {
      const from = AIRPORT_CODE[f.dep], to = AIRPORT_CODE[f.arr];
      if (!from || !to) return null;
      const etd = hm(f.etd), atd = hm(f.atd);
      const state = etd == null ? 'enroute' : now < etd ? 'scheduled' : (atd != null && now >= atd) ? 'arrived' : 'enroute';
      const progress = etd != null && atd != null && atd > etd ? Math.min(1, Math.max(0, (now - etd) / (atd - etd))) : null;
      const dur = 7;
      return { key: f.flight_no, from, to, state, hero: false, progress, dur, phase: (f.flight_no.charCodeAt(2) % 10) / 10 };
    }).filter(Boolean);
  }
  return FLIGHT_FALLBACK.map((f) => ({ key: f.no, from: f.from, to: f.to, state: 'decor' }));
});

const ROLE_TEXT = {
  admin: '全部模块',
  customer: '我的账户 · 下单 · 运单跟踪',
  dispatcher: '下单 · 派单 · 揽收 · 中转 · 派送',
  courier: '揽收 · 派送 · 轨迹',
  claims: '异常 · 理赔',
  finance: '账单 · 客户 · 报表',
  ops: '绩效 · 报表',
};
const ACCOUNTS = [
  { u: 'zhangsan', r: 'customer' }, { u: 'lisi', r: 'customer' }, { u: 'wangwu', r: 'customer' },
  { u: 'admin', r: 'admin' }, { u: 'dispatch', r: 'dispatcher' }, { u: 'courier', r: 'courier' },
  { u: 'claims', r: 'claims' }, { u: 'finance', r: 'finance' }, { u: 'ops', r: 'ops' },
];

async function submit() {
  err.value = '';
  if (!form.value.username || !form.value.password) { err.value = '请输入账号和密码'; return; }
  loading.value = true;
  try {
    const d = await api.login(form.value.username.trim(), form.value.password);
    session.token = d.token;
    session.user = d.user;
    router.push('/');
  } catch (e) {
    err.value = e.message;
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="login gridbg">
    <!-- v9 · 背景层：3D 地球弱化版（懒加载，就绪后淡入）；就绪前/失败时用下方 SVG 航线图兜底
         （v8 · M10：节点按真实相对地理——PEK 在北、SHA 在 HGH 东北、SZX 在 CAN 东南；几何收在右侧，不穿主标题） -->
    <component v-if="GlobeScene && !globeFailed" :is="GlobeScene" variant="login" :arcs="loginArcs" :show-labels="false"
      :class="{ on: globeReady }" @ready="globeReady = true" @fallback="globeFailed = true" />
    <svg v-if="!globeReady" class="bg-routes" viewBox="0 0 1440 900" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
      <g fill="none" stroke="rgba(94,162,255,.20)" stroke-width="1">
        <path d="M1150 345 C 1180 240, 1230 190, 1290 150" />
        <path d="M1150 345 C 1210 322, 1270 314, 1335 315" stroke-dasharray="3 6" />
        <path d="M1335 315 C 1300 470, 1180 580, 1075 650" />
        <path d="M1150 345 C 1100 460, 1050 540, 1010 610" stroke-dasharray="3 6" />
      </g>
      <g fill="#07090d" stroke="#5ea2ff" stroke-width="1.5">
        <circle cx="1290" cy="150" r="4.5" /><circle cx="1335" cy="315" r="4" />
        <circle cx="1150" cy="345" r="4.5" /><circle cx="1010" cy="610" r="4" />
        <circle cx="1075" cy="650" r="4" />
      </g>
      <g fill="#76879c" font-family="ui-monospace, monospace" font-size="14" letter-spacing="3">
        <text x="1272" y="126">PEK</text><text x="1312" y="292">SHA</text>
        <text x="1128" y="322">HGH</text><text x="986" y="646">CAN</text>
        <text x="1052" y="686">SZX</text>
      </g>
    </svg>
    <div class="glow glow-a" aria-hidden="true"></div>
    <div class="glow glow-b" aria-hidden="true"></div>

    <!-- 顶部细条：品牌 + 状态 -->
    <header class="lg-top">
      <span class="lg-brand"><span class="lg-logo">✈</span>AEPDS</span>
      <span class="lg-status"><i></i>NETWORK LIVE · HGH HUB</span>
    </header>

    <!-- 主构图：左巨字标题，右玻璃卡悬浮（错位，不居中分栏） -->
    <div class="lg-stage">
      <section class="lg-hero">
        <p class="lg-kicker">AIR EXPRESS PICKUP &amp; DELIVERY SYSTEM</p>
        <h1>航空快递<br><span class="h-outline">揽收与派送</span></h1>
        <p class="lg-sub">下单、派单、揽收、中转、派送、理赔与月结——<br>一条流水线，一个系统。</p>
        <div class="lg-flights mono">
          <div v-for="f in flightRows" :key="f.no"><b>{{ f.no }}</b><span>{{ f.from }} → {{ f.to }}</span><span>{{ f.t }}</span></div>
        </div>
      </section>

      <main class="lg-card glass">
        <h2>登录</h2>
        <p class="csub">SIGN IN · 初始密码 123456</p>

        <div v-if="err" class="ferr">{{ err }}</div>

        <label class="flabel" for="lg-user">账号</label>
        <input id="lg-user" v-model="form.username" class="finput" placeholder="admin / dispatch / courier / claims / finance / ops" autocomplete="username" />
        <label class="flabel" for="lg-pass">密码</label>
        <input id="lg-pass" v-model="form.password" class="finput" type="password" placeholder="请输入密码" autocomplete="current-password" @keyup.enter="submit" />

        <button class="btn fbtn" :disabled="loading" @click="submit">{{ loading ? '登录中…' : '登录 →' }}</button>

        <div class="accounts">
          <div class="a-head">账号与可见模块</div>
          <div v-for="a in ACCOUNTS" :key="a.u" class="a-row">
            <span class="a-user">{{ a.u }}</span>
            <span class="a-role">{{ ROLE_TEXT[a.r] }}</span>
          </div>
        </div>
      </main>
    </div>

    <footer class="lg-foot mono">AEPDS v1.0 · 体积重 ÷6000 · 保价费率 3‰ · 理赔上限 = 保价额</footer>
  </div>
</template>

<style scoped>
.login {
  min-height: 100vh; position: relative; overflow: hidden;
  background-color: var(--bg-0); color: var(--tx-1);
  display: flex; flex-direction: column;
}
.mono { font-family: var(--mono); font-variant-numeric: tabular-nums; }
/* v9 · 3D 背景淡入（就绪前不可见，SVG 兜底在下面垫着） */
.login .globe3d { opacity: 0; transition: opacity .8s ease; }
.login .globe3d.on { opacity: .6; }
.bg-routes { position: absolute; inset: 0; width: 100%; height: 100%; opacity: .8; pointer-events: none; }
.glow { position: absolute; border-radius: 50%; pointer-events: none; }
.glow-a {
  width: 720px; height: 720px; right: -180px; top: -240px;
  background: radial-gradient(circle, rgba(94, 162, 255, .17) 0%, transparent 62%);
}
.glow-b {
  width: 560px; height: 560px; left: -200px; bottom: -260px;
  background: radial-gradient(circle, rgba(94, 162, 255, .10) 0%, transparent 62%);
}

.lg-top {
  position: relative; z-index: 2;
  display: flex; justify-content: space-between; align-items: center;
  padding: 22px 40px 0;
}
.lg-brand { display: flex; align-items: center; gap: 10px; font-family: var(--mono); font-size: 14px; font-weight: 600; letter-spacing: .24em; }
.lg-logo {
  width: 34px; height: 34px; border-radius: 9px; display: grid; place-items: center; font-size: 16px;
  background: var(--bg-3); border: 1px solid var(--line-strong); color: var(--acc);
  box-shadow: 0 0 18px -4px rgba(94, 162, 255, .45);
}
.lg-status { display: flex; align-items: center; gap: 8px; font-family: var(--mono); font-size: 11px; letter-spacing: .18em; color: var(--tx-3); }
.lg-status i { width: 6px; height: 6px; border-radius: 50%; background: var(--ok); box-shadow: 0 0 8px rgba(63, 178, 127, .9); }

.lg-stage {
  position: relative; z-index: 2; flex: 1;
  display: grid; grid-template-columns: minmax(0, 1fr) 400px; align-items: center;
  gap: 48px; max-width: 1240px; width: 100%; margin: 0 auto; padding: 24px 40px 8px;
}
.lg-hero { animation: rise .3s var(--ease) both; }
.lg-kicker { font-family: var(--mono); font-size: 11px; letter-spacing: .34em; color: var(--acc); margin-bottom: 22px; }
.lg-hero h1 {
  font-size: clamp(56px, 7.2vw, 104px); font-weight: 600;
  letter-spacing: -.02em; line-height: 1.08; color: var(--tx-1);
  text-shadow: 0 0 44px rgba(94, 162, 255, .22);
}
/* v9 · 标题语法与工作台统一：第一行实心 + 第二行描边空心（虚实对照，两页同一套） */
.h-outline { color: transparent; -webkit-text-stroke: 1.5px rgba(233, 238, 244, .85); text-shadow: none; }
.lg-sub { margin-top: 26px; font-size: 15px; line-height: 1.9; color: var(--tx-2); }
.lg-flights { margin-top: 40px; max-width: 430px; border-top: 1px solid var(--line); }
.lg-flights div { display: flex; gap: 18px; align-items: baseline; padding: 9px 0; border-bottom: 1px solid var(--line); font-size: 12.5px; color: var(--tx-2); }
.lg-flights b { color: var(--tx-1); font-weight: 600; min-width: 58px; }
.lg-flights span:last-child { margin-left: auto; color: var(--tx-3); }

.lg-card {
  /* v6 漂浮化：边框压到几乎不可见，靠强模糊 + 一团枢纽光晕 + 深影立住，不再是“框” */
  border-radius: 20px; padding: 30px 30px 24px;
  transform: translateY(-14px);
  animation: rise .3s var(--ease) .06s both;
  background: rgba(13, 18, 26, .38);
  border: 1px solid rgba(94, 162, 255, .1);
  backdrop-filter: blur(26px) saturate(1.45);
  -webkit-backdrop-filter: blur(26px) saturate(1.45);
  box-shadow:
    0 34px 90px -18px rgba(0, 0, 0, .75),
    0 0 110px -12px rgba(94, 162, 255, .35),
    inset 0 1px 0 rgba(233, 238, 244, .07);
}
.lg-card h2 { font-size: 22px; font-weight: 600; letter-spacing: -.02em; }
.csub { font-family: var(--mono); font-size: 11px; color: var(--tx-3); letter-spacing: .16em; margin: 4px 0 22px; }
.ferr { background: rgba(229, 83, 75, .1); border: 1px solid rgba(229, 83, 75, .4); color: #f0a49e; padding: 9px 12px; font-size: 13px; border-radius: 8px; margin-bottom: 16px; }
.flabel { display: block; font-size: 12px; color: var(--tx-2); margin: 0 0 6px; }
.finput {
  width: 100%; height: 40px; padding: 0 12px; margin-bottom: 16px; font: inherit; font-size: 14px;
  background: rgba(7, 9, 13, .55); border: 1px solid var(--line); border-radius: 8px; color: var(--tx-1);
  transition: border-color .12s var(--ease), box-shadow .12s var(--ease);
}
.finput::placeholder { color: var(--tx-3); font-size: 12.5px; }
.finput:hover { border-color: var(--line-strong); }
.finput:focus-visible { outline: none; border-color: var(--acc); box-shadow: 0 0 0 3px rgba(94, 162, 255, .16); }
.fbtn { width: 100%; height: 42px; margin-top: 4px; font-size: 14px; }
.accounts { margin-top: 24px; padding-top: 14px; border-top: 1px solid var(--line); }
.a-head { font-family: var(--mono); font-size: 11px; letter-spacing: .14em; color: var(--tx-3); margin-bottom: 6px; }
.a-row { display: flex; justify-content: space-between; gap: 12px; padding: 2.5px 0; font-size: 12px; }
.a-user { font-family: var(--mono); color: var(--tx-2); }
.a-role { color: var(--tx-3); text-align: right; }

.lg-foot { position: relative; z-index: 2; padding: 0 40px 20px; font-size: 11px; letter-spacing: .14em; color: var(--tx-3); }

@keyframes rise { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: translateY(0); } }
.lg-card { animation-name: riseCard; }
@keyframes riseCard { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: translateY(-14px); } }

@media (max-width: 980px) {
  .lg-stage { grid-template-columns: 1fr; gap: 36px; padding: 20px 22px 8px; }
  .lg-hero h1 { font-size: 52px; }
  .lg-flights { display: none; }
  .lg-card { transform: none; animation: rise .3s var(--ease) both; }
  .lg-top, .lg-foot { padding-left: 22px; padding-right: 22px; }
}
@media (prefers-reduced-motion: reduce) { .lg-hero, .lg-card { animation: none; } }
</style>

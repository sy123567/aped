<script setup>
import { RouterLink, useRoute, useRouter } from 'vue-router';
import { ref, computed, watch, onMounted, onUnmounted } from 'vue';
import { api, session } from './api.js';

const router = useRouter();
const route = useRoute();
const user = ref(session.user);

const clock = ref('');
let timer;
const fmtClock = () => {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  clock.value = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
};
onMounted(() => {
  fmtClock();
  timer = setInterval(fmtClock, 1000);
  // 已登录则校正一份最新的角色与可见模块（防止本地缓存的角色信息过期）
  if (session.token) refreshUser();
});
onUnmounted(() => clearInterval(timer));

const nav = [
  { to: '/my', t: '我的账户', mod: 'my', icon: 'M4 5h16v14H4zM4 9h16M8 14h4' },
  { to: '/', t: '工作台', mod: 'board', icon: 'M3 12l9-9 9 9M5 10v10h14V10' },
  { to: '/orders', t: '客户下单', mod: 'orders', icon: 'M4 4h16v4H4zM4 12h16v8H4z' },
  { to: '/dispatch', t: '派单调度', mod: 'dispatch', icon: 'M4 7h11l-2-2m2 2l-2 2M20 17H9l2-2m-2 2l2 2' },
  { to: '/pickup', t: '揽收登记', mod: 'pickup', icon: 'M12 2l3 7h7l-5.5 4.5L18 21l-6-4-6 4 1.5-7.5L2 9h7z' },
  { to: '/transit', t: '分拣中转', mod: 'transit', icon: 'M4 6h16M4 18h16M8 6v12M16 6v12' },
  { to: '/delivery', t: '派送签收', mod: 'delivery', icon: 'M2 7h11v9H2zM13 10h4l3 3v3h-7zM6 18a1.5 1.5 0 100-3 1.5 1.5 0 000 3zM17 18a1.5 1.5 0 100-3 1.5 1.5 0 000 3z' },
  { to: '/tracking', t: '运单跟踪', mod: 'tracking', icon: 'M12 2a7 7 0 017 7c0 5-7 13-7 13S5 14 5 9a7 7 0 017-7zm0 4.5a2.5 2.5 0 100 5 2.5 2.5 0 000-5z' },
  { to: '/exceptions', t: '异常理赔', mod: 'exceptions', icon: 'M12 2L1 21h22L12 2zm1 14h-2v2h2v-2zm0-7h-2v5h2V9z' },
  { to: '/customers', t: '客户账户', mod: 'customers', icon: 'M12 12a4 4 0 100-8 4 4 0 000 8zM4 21a8 8 0 0116 0' },
  { to: '/billing', t: '月结账单', mod: 'billing', icon: 'M4 3h16v18l-4-2-4 2-4-2-4 2V3zm2 4h12v2H6V7zm0 4h12v2H6v-2z' },
  { to: '/performance', t: '绩效排名', mod: 'performance', icon: 'M4 20h16v2H4zM6 16h3V8H6zm5 0h3V4h-3zm5 0h3v-6h-3z' },
  { to: '/reports', t: '运营报表', mod: 'reports', icon: 'M4 19V5m0 14h16M8 15v-4m4 4V8m4 7v-6' },
];

const isLogin = computed(() => route.path === '/login');
const visibleNav = computed(() => {
  if (!user.value) return [];                    // 未登录：不显示菜单
  const mods = user.value.modules;
  if (!mods || !mods.length) return nav;         // 模块信息缺失：宁可全显示，也别让菜单空白（后端仍会拦）
  if (mods.includes('*')) return nav;            // 管理员
  return nav.filter((n) => mods.includes(n.mod));
});

// 关键：App 在登录页就已挂载，登录成功只是切路由、不会重新挂载。
// 所以必须在每次路由变化后重新读一次登录用户，否则菜单永远是空的。
watch(
  () => route.fullPath,
  () => {
    user.value = session.user;
    if (session.token && !user.value) refreshUser();
  }
);

async function refreshUser() {
  try {
    const u = await api.me();
    session.user = u;
    user.value = u;
  } catch {
    user.value = session.user;
  }
}

async function doLogout() {
  try { await api.logout(); } catch { /* 会话可能已失效，忽略 */ }
  session.clear();
  user.value = null;
  router.push('/login');
}
</script>

<template>
  <RouterView v-if="isLogin" />

  <div class="shell" v-else>
    <aside class="side">
      <div class="brand">
        <div class="logo">✈</div>
        <div>
          <div class="zh">航空快递<br>揽收与派送</div>
          <div class="en">AEPDS v1.0</div>
        </div>
      </div>
      <nav aria-label="主导航">
        <RouterLink v-for="n in visibleNav" :key="n.to" :to="n.to">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path :d="n.icon" />
          </svg>
          <span class="t">{{ n.t }}</span>
        </RouterLink>
      </nav>
      <div class="foot">
        <div style="color:#cfd6e2">{{ user?.display_name || '未登录' }}</div>
        <div style="margin-top:2px;font-size:11px;color:#7c8697">{{ user?.role_label || '' }}</div>
        <div style="margin-top:6px">{{ clock }}</div>
        <button @click="doLogout"
                style="margin-top:8px;background:transparent;border:1px solid #2c3546;color:#9aa4b2;font-size:11px;padding:3px 8px;cursor:pointer;">
          退出登录
        </button>
      </div>
    </aside>
    <main class="main">
      <RouterView />
    </main>
  </div>
</template>

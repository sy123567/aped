<script setup>
import { RouterLink, useRoute, useRouter } from 'vue-router';
import { ref, computed, watch, onMounted, onUnmounted } from 'vue';
import { api, session } from './api.js';
import { NAV, filterNav } from './nav.js';
import Directory from './components/Directory.vue';

const router = useRouter();
const route = useRoute();
const user = ref(session.user);
/* v10 · 全屏目录开关（各页可达：顶部坞首的 INDEX 按钮） */
const dirOpen = ref(false);

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

/* v8 · S1：导航定义与过滤规则收敛到 nav.js 单一数据源，与 Dashboard 底部坞同源同规则 */
const nav = NAV;
const isLogin = computed(() => route.path === '/login');
const currentNav = computed(() => nav.find((n) => n.to === route.path) || nav[1]);
const visibleNav = computed(() => filterNav(user.value));

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
    <!-- v6 外壳：去框悬浮。品牌贴左上、玻璃胶囊导航坞浮在顶部中央、用户胶囊贴右上、状态贴左下，内容不被任何横带切开 -->
    <div class="float-brand">
      <div class="logo">✈</div>
      <div class="btx">
        <div class="zh">航空快递揽收与派送</div>
        <div class="en">AEPDS · AIR EXPRESS OPS</div>
      </div>
    </div>

    <nav class="dock glass" aria-label="主导航">
      <button class="dock-item dir-open" @click="dirOpen = true" title="打开模块目录" aria-label="打开模块目录" :style="{ '--i': 0 }">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z" />
        </svg>
        <small>INDEX</small>
      </button>
      <RouterLink v-for="(n, i) in visibleNav" :key="n.to" :to="n.to" class="dock-item" :title="n.zh" :aria-label="n.zh" :style="{ '--i': i + 1 }">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path :d="n.icon" />
        </svg>
        <small>{{ n.code }}</small>
        <span class="act-dot" aria-hidden="true"></span>
      </RouterLink>
    </nav>

    <div class="float-user glass">
      <span class="fu-clock">{{ clock }}<small>CST</small></span>
      <span class="fu-sep" aria-hidden="true"></span>
      <span class="fu-name"><b>{{ user?.display_name || '未登录' }}</b><small>{{ user?.role_label || '' }}</small></span>
      <button class="fu-out" @click="doLogout">退出</button>
    </div>

    <div class="float-status" aria-hidden="true">
      <span class="fs-dot"></span>
      <span class="fs-mod">{{ currentNav.code }} — {{ currentNav.zh }}</span>
      <span>HGH 杭州萧山枢纽 · NETWORK LIVE</span>
    </div>

    <main class="main">
      <RouterView v-slot="{ Component }">
        <Transition name="page" mode="out-in">
          <component :is="Component" :key="route.path" />
        </Transition>
      </RouterView>
    </main>

    <Directory :open="dirOpen" :user="user" @close="dirOpen = false" />
  </div>
</template>

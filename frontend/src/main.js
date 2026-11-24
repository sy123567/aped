import { createApp } from 'vue';
import { createRouter, createWebHashHistory } from 'vue-router';
import App from './App.vue';
import './styles.css';
import Login from './views/Login.vue';
/* v8 · M8：其余视图改为路由懒加载，登录页不再下载整包 */
const My = () => import('./views/My.vue');
const Dashboard = () => import('./views/Dashboard.vue');
const Orders = () => import('./views/Orders.vue');
const Dispatch = () => import('./views/Dispatch.vue');
const Pickup = () => import('./views/Pickup.vue');
const Transit = () => import('./views/Transit.vue');
const Delivery = () => import('./views/Delivery.vue');
const Tracking = () => import('./views/Tracking.vue');
const Exceptions = () => import('./views/Exceptions.vue');
const Customers = () => import('./views/Customers.vue');
const Billing = () => import('./views/Billing.vue');
const Performance = () => import('./views/Performance.vue');
const Reports = () => import('./views/Reports.vue');

const routes = [
  { path: '/login', component: Login, meta: { public: true } },
  { path: '/my', component: My, meta: { mod: 'my' } },
  { path: '/', component: Dashboard, meta: { mod: 'board' } },
  { path: '/orders', component: Orders, meta: { mod: 'orders' } },
  { path: '/dispatch', component: Dispatch, meta: { mod: 'dispatch' } },
  { path: '/pickup', component: Pickup, meta: { mod: 'pickup' } },
  { path: '/transit', component: Transit, meta: { mod: 'transit' } },
  { path: '/delivery', component: Delivery, meta: { mod: 'delivery' } },
  { path: '/tracking', component: Tracking, meta: { mod: 'tracking' } },
  { path: '/exceptions', component: Exceptions, meta: { mod: 'exceptions' } },
  { path: '/customers', component: Customers, meta: { mod: 'customers' } },
  { path: '/billing', component: Billing, meta: { mod: 'billing' } },
  { path: '/performance', component: Performance, meta: { mod: 'performance' } },
  { path: '/reports', component: Reports, meta: { mod: 'reports' } },
];

const router = createRouter({
  history: createWebHashHistory(),
  routes,
});

const readUser = () => {
  try { return JSON.parse(localStorage.getItem('aepds.user') || 'null'); } catch { return null; }
};

// 每个角色的落地页（第一个可访问的功能页），避免越权时被踢到一个也进不去的地址
const LANDING = {
  customer: '/my',
  courier: '/pickup',
  claims: '/exceptions',
  finance: '/billing',
  ops: '/performance',
  dispatcher: '/',
  admin: '/',
};
const homeFor = (user) => LANDING[user?.role] ?? '/';

// 未登录只能进登录页；已登录访问登录页回各自首页；越权访问按角色踢回
router.beforeEach((to) => {
  const token = localStorage.getItem('aepds.token');
  const u = readUser();
  if (!to.meta.public && !token) return '/login';
  if (to.meta.public && token) return homeFor(u);
  if (to.meta.mod) {
    const mods = u?.modules ?? [];
    if (!mods.includes('*') && !mods.includes(to.meta.mod)) return homeFor(u);
  }
  return true;
});

createApp(App).use(router).mount('#app');

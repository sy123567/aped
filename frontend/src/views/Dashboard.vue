<script setup>
import { ref, onMounted, onUnmounted } from 'vue';
import { api } from '../api.js';

const b = ref(null);
const clock = ref('');
let timer;
const fmtClock = () => {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  clock.value = `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
};
onMounted(async () => {
  b.value = await api.board();
  fmtClock(); timer = setInterval(fmtClock, 1000);
});
onUnmounted(() => clearInterval(timer));
</script>

<template>
  <section v-if="b" class="board" aria-label="今日运营信息板">
    <div class="title">
      <h2>出港信息板 · DEPARTURES</h2>
      <span class="clock">{{ clock }} CST</span>
    </div>
    <div class="stat"><b>{{ b.pendingOrders }}</b><span>待处理订单</span></div>
    <div class="stat"><b>{{ b.todayPickups }}</b><span>今日揽收</span></div>
    <div class="stat"><b>{{ b.todaySigns }}</b><span>今日签收</span></div>
    <div class="stat"><b>{{ b.inTransit }}</b><span>在途运单</span></div>
    <div class="stat" :class="{ alert: b.openExceptions }"><b>{{ b.openExceptions }}</b><span>未闭环异常</span></div>
    <div class="stat" :class="{ alert: b.unpaidBills }"><b>{{ b.unpaidBills }}</b><span>待结算账单</span></div>
    <div class="stat good"><b>¥{{ b.revenue.toFixed(0) }}</b><span>累计运费收入</span></div>
  </section>

  <div class="content" v-if="b">
    <div class="doc-head">
      <h1>今日工作台</h1>
      <span class="sub">hangzhou depot · {{ new Date().toISOString().slice(0, 10) }}</span>
    </div>

    <div class="grid3" style="margin-bottom: 22px;">
      <RouterLink to="/orders" class="tile">
        <div class="ic"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M4 4h16v4H4zM4 12h16v8H4z"/></svg></div>
        <div class="tx"><b>客户下单与派单</b><span>新订单等待调度</span></div>
        <div class="cnt">{{ b.pendingOrders }}</div>
      </RouterLink>
      <RouterLink to="/pickup" class="tile">
        <div class="ic"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2l3 7h7l-5.5 4.5L18 21l-6-4-6 4 1.5-7.5L2 9h7z"/></svg></div>
        <div class="tx"><b>揽收登记与计费</b><span>接单后上门揽收</span></div>
        <div class="cnt">{{ b.pendingOrders }}</div>
      </RouterLink>
      <RouterLink to="/delivery" class="tile">
        <div class="ic"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M2 7h11v9H2zM13 10h4l3 3v3h-7zM6 18a1.5 1.5 0 100-3 1.5 1.5 0 000 3zM17 18a1.5 1.5 0 100-3 1.5 1.5 0 000 3z"/></svg></div>
        <div class="tx"><b>派件任务与签收</b><span>到站快件派送</span></div>
        <div class="cnt">{{ b.inTransit }}</div>
      </RouterLink>
      <RouterLink to="/exceptions" class="tile">
        <div class="ic"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2L1 21h22L12 2zm1 14h-2v2h2v-2zm0-7h-2v5h2V9z"/></svg></div>
        <div class="tx"><b>异常件与理赔</b><span>拒收 / 破损 / 丢失</span></div>
        <div class="cnt" :class="{ alert: b.openExceptions }">{{ b.openExceptions }}</div>
      </RouterLink>
      <RouterLink to="/billing" class="tile">
        <div class="ic"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 3h16v18l-4-2-4 2-4-2-4 2V3zm2 4h12v2H6V7zm0 4h12v2H6v-2z"/></svg></div>
        <div class="tx"><b>月结账单</b><span>账期汇总与核销</span></div>
        <div class="cnt" :class="{ alert: b.unpaidBills }">{{ b.unpaidBills }}</div>
      </RouterLink>
      <RouterLink to="/performance" class="tile">
        <div class="ic"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 20h16v2H4zM6 16h3V8H6zm5 0h3V4h-3zm5 0h3v-6h-3z"/></svg></div>
        <div class="tx"><b>绩效与报表</b><span>排名与 CSV 导出</span></div>
        <div class="cnt">↗</div>
      </RouterLink>
    </div>

    <div class="grid2">
      <div>
        <div class="note">
          先派单，再揽收。调度员把「待派单」订单分配给快递员，快递员上门揽收时系统自动计费生成运单。
        </div>
        <table class="doc">
          <thead><tr><th>状态</th><th>含义</th></tr></thead>
          <tbody>
            <tr><td><span class="tag wait">待派单</span></td><td>客户已下单，等待调度员分配</td></tr>
            <tr><td><span class="tag wait">待揽收</span></td><td>任务已派给快递员，等待上门揽收</td></tr>
            <tr><td><span class="tag transit">转运中</span></td><td>分拣中转登记完成，在途运输</td></tr>
            <tr><td><span class="tag transit">派送中</span></td><td>派件任务单已生成，快递员派送中</td></tr>
            <tr><td><span class="tag done">已签收</span></td><td>签收登记完成，运单闭环</td></tr>
            <tr><td><span class="tag fail">派送异常</span></td><td>进入异常件处理流程</td></tr>
          </tbody>
        </table>
      </div>
      <div>
        <div class="note ok">
          运单全程轨迹已在「运单跟踪」开放查询，客户只需运单号。
        </div>
        <table class="doc">
          <thead><tr><th>口径</th><th>规则</th></tr></thead>
          <tbody>
            <tr><td>计费</td><td class="mono">首重1kg ¥12 + 续重 ¥4/kg；时效系数 1.2 / 1.0 / 0.85</td></tr>
            <tr><td>体积重量</td><td class="mono">体积(L) ÷ 6000，与实重取大计费</td></tr>
            <tr><td>保价</td><td class="mono">保价费 = 保价额 × 3‰；理赔上限 = 保价额</td></tr>
            <tr><td>绩效分</td><td class="mono">揽派总量 × 10 − 异常数 × 30</td></tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

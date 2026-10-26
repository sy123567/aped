<script setup>
import { ref, onMounted } from 'vue';

const period = ref(new Date().toISOString().slice(0, 7));
const rows = ref([]);

async function load() {
  const d = await fetch(`/api/performance?period=${period.value}`).then((r) => r.json());
  if (d.ok) rows.value = d.data.rows;
}
onMounted(load);

const exportCsv = (type) => {
  window.open(`/api/reports/export?type=${type}`, '_blank');
};
</script>

<template>
  <div class="content">
    <div class="doc-head">
      <h1>绩效统计与报表</h1>
      <span class="sub">performance &amp; export</span>
    </div>

    <div class="form-row">
      <div class="field"><label>统计周期</label>
        <input v-model="period" placeholder="2026-10" style="font-family: var(--mono)" @keyup.enter="load" />
      </div>
      <button class="btn" @click="load">重新统计</button>
      <button class="btn ghost" @click="exportCsv('performance')">导出绩效 CSV</button>
      <button class="btn ghost" @click="exportCsv('waybill')">导出发票台账 CSV</button>
    </div>

    <table class="doc">
      <thead>
        <tr><th class="num">排名</th><th>快递员</th><th class="num">揽收数</th><th class="num">签收数</th><th class="num">异常数</th><th class="num">准时率</th><th class="num">异常率</th><th class="num">绩效分</th></tr>
      </thead>
      <tbody>
        <tr v-for="r in rows" :key="r.courier">
          <td class="num" style="font-weight:700">{{ r.rank }}</td>
          <td>{{ r.courier }}</td>
          <td class="num">{{ r.pickups }}</td>
          <td class="num">{{ r.deliveries }}</td>
          <td class="num" :style="{ color: r.exceptions ? 'var(--signal)' : 'inherit' }">{{ r.exceptions }}</td>
          <td class="num">{{ r.onTime }}%</td>
          <td class="num">{{ r.exceptionRate }}%</td>
          <td class="num" style="font-weight:700">{{ r.score }}</td>
        </tr>
        <tr v-if="!rows.length"><td colspan="8" class="empty">暂无数据。</td></tr>
      </tbody>
    </table>

    <p style="font-size:12px; color: var(--slate); margin-top: 12px;">
      口径：绩效分 = 揽派总量 × 10 − 异常数 × 30；异常件不计入个人揽派量，需在异常登记中注明原因。CSV 为 UTF-8 BOM，Excel 可直接打开。
    </p>
  </div>
</template>

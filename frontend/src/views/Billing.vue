<script setup>
import { ref, onMounted } from 'vue';
import { api, fmt } from '../api.js';

const bills = ref([]);
const customers = [
  { id: 1, name: '张三（散客）' },
  { id: 2, name: '李四（月结）' },
  { id: 3, name: '王五（月结）' },
];
const form = ref({ customer_id: 2, period: new Date().toISOString().slice(0, 7) });
const msg = ref('');
const err = ref('');

const reload = async () => { bills.value = await api.bills(); };
onMounted(reload);

async function generate() {
  err.value = ''; msg.value = '';
  try {
    const d = await api.generateBill({ ...form.value });
    msg.value = `账单 ${d.bill_no} 已生成：${d.item_count} 单，合计 ${fmt(d.amount)}`;
    reload();
  } catch (e) { err.value = e.message; }
}
async function settle(b) {
  err.value = ''; msg.value = '';
  try {
    const d = await api.settleBill({ id: b.id, paid: b.amount });
    msg.value = `账单 ${b.bill_no} 已${d.status}`;
    reload();
  } catch (e) { err.value = e.message; }
}
</script>

<template>
  <div class="content">
    <div class="doc-head">
      <h1>月结账单</h1>
      <span class="sub">monthly billing</span>
    </div>

    <div class="note ok" v-if="msg">{{ msg }}</div>
    <div class="note err" v-if="err">{{ err }}</div>

    <table class="doc" style="margin-bottom: 24px;">
      <thead>
        <tr><th>账单号</th><th>客户</th><th>账期</th><th class="num">运单数</th><th class="num">金额</th><th>状态</th><th>操作</th></tr>
      </thead>
      <tbody>
        <tr v-for="b in bills" :key="b.id">
          <td class="mono">{{ b.bill_no }}</td>
          <td>{{ b.customer_name }}</td>
          <td class="mono">{{ b.period }}</td>
          <td class="num">{{ b.item_count }}</td>
          <td class="num">{{ fmt(b.amount) }}</td>
          <td><span class="tag" :class="b.status === '已结清' ? 'done' : b.status === '部分核销' ? 'transit' : 'wait'">{{ b.status }}</span></td>
          <td>
            <button v-if="b.status !== '已结清'" class="btn small" @click="settle(b)">全额回款核销</button>
            <span v-else style="color: var(--slate)">已结清</span>
          </td>
        </tr>
        <tr v-if="!bills.length"><td colspan="7" class="empty">暂无账单。</td></tr>
      </tbody>
    </table>

    <div class="doc-head" style="margin-top: 28px;"><h1 style="font-size:15px">生成月结账单</h1></div>
    <div class="form-row">
      <div class="field"><label>月结客户</label>
        <select v-model="form.customer_id">
          <option v-for="c in customers" :key="c.id" :value="c.id">{{ c.name }}</option>
        </select>
      </div>
      <div class="field"><label>账期</label><input v-model="form.period" placeholder="2026-10" style="font-family: var(--mono)" /></div>
      <button class="btn" @click="generate">生成账单</button>
    </div>
    <p style="font-size:12px; color:var(--slate)">仅汇总账期内状态为「已签收」的计费运单；账期为自然月，次月 10 日前出账。</p>
  </div>
</template>

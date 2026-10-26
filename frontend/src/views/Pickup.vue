<script setup>
import { ref, onMounted } from 'vue';
import { api, fmt } from '../api.js';

const tasks = ref([]);
const msg = ref('');
const err = ref('');
const form = ref({ task_id: null, weight: '', volumeL: '', insured: '', product: '隔日达' });

const reload = async () => { tasks.value = await api.pickupTasks(); };
onMounted(reload);

async function accept(t) {
  try { await api.acceptPickup(t.id); msg.value = `已接单：${t.order_no}`; reload(); }
  catch (e) { err.value = e.message; }
}
function start(t) {
  form.value = { task_id: t.id, weight: t.weight ?? '', volumeL: '', insured: '', product: '隔日达' };
  msg.value = ''; err.value = '';
  document.getElementById('pickup-form')?.scrollIntoView({ behavior: 'smooth', block: 'center' });
}
async function submit() {
  msg.value = ''; err.value = '';
  try {
    const d = await api.pickup({
      task_id: form.value.task_id,
      weight: Number(form.value.weight),
      volumeL: Number(form.value.volumeL || 0),
      insured: Number(form.value.insured || 0),
      product: form.value.product,
    });
    msg.value = `揽收成功：运单号 ${d.waybill_no}，运费 ${fmt(d.fee)}（${d.detail}）`;
    reload();
  } catch (e) { err.value = e.message; }
}
</script>

<template>
  <div class="content">
    <div class="doc-head">
      <h1>揽收登记</h1>
      <span class="sub">pickup &amp; billing</span>
    </div>

    <div class="note ok" v-if="msg">{{ msg }}</div>
    <div class="note err" v-if="err">{{ err }}</div>

    <table class="doc" style="margin-bottom: 24px;">
      <thead>
        <tr><th>订单号</th><th>快递员</th><th>寄件人</th><th>品类</th><th class="num">预估(kg)</th><th>取件时段</th><th>任务状态</th><th>操作</th></tr>
      </thead>
      <tbody>
        <tr v-for="t in tasks" :key="t.id">
          <td class="mono">{{ t.order_no }}</td>
          <td>{{ t.courier }}</td>
          <td>{{ t.sender }}</td>
          <td>{{ t.category }}</td>
          <td class="num">{{ t.weight ?? '—' }}</td>
          <td class="mono">{{ t.slot }}</td>
          <td><span class="tag" :class="t.status === '已揽收' ? 'done' : t.status === '已接单' ? 'transit' : 'wait'">{{ t.status }}</span></td>
          <td>
            <template v-if="t.status === '待接单'">
              <button class="btn small" @click="accept(t)">接单</button>
            </template>
            <template v-else-if="t.status === '已接单'">
              <button class="btn small" @click="start(t)">上门揽收</button>
            </template>
            <span v-else style="color: var(--slate)">已闭环</span>
          </td>
        </tr>
        <tr v-if="!tasks.length"><td colspan="8" class="empty">暂无揽收任务。先在「客户下单」页派单。</td></tr>
      </tbody>
    </table>

    <div class="doc-head" id="pickup-form" style="margin-top: 28px;">
      <h1>揽收登记与计费</h1>
      <span class="sub">weigh · volume · insure</span>
    </div>
    <div class="form-row">
      <div class="field"><label>关联任务</label>
        <select v-model="form.task_id">
          <option :value="null" disabled>选择已接单任务</option>
          <option v-for="t in tasks.filter(t => t.status === '已接单')" :key="t.id" :value="t.id">
            {{ t.order_no }} · {{ t.sender }}
          </option>
        </select>
      </div>
      <div class="field"><label>实重(kg)</label><input v-model="form.weight" type="number" min="0.01" max="1000" step="0.01" /></div>
      <div class="field"><label>体积(L)</label><input v-model="form.volumeL" type="number" min="0" step="0.1" /></div>
      <div class="field"><label>保价金额(元)</label><input v-model="form.insured" type="number" min="0" step="10" /></div>
      <div class="field"><label>产品时效</label>
        <select v-model="form.product">
          <option>次日达</option><option>隔日达</option><option>经济件</option>
        </select>
      </div>
      <button class="btn" :disabled="!form.task_id || !form.weight" @click="submit">提交揽收并生成运单</button>
    </div>
    <p style="color: var(--slate); font-size: 12px;">
      计费规则：首重 1kg ¥12，续重 ¥4/kg；体积重量 = 体积(L) ÷ 6000，取大计费；时效系数 次日达 ×1.2、隔日达 ×1.0、经济件 ×0.85；保价费 = 保价额 × 3‰。
    </p>
  </div>
</template>

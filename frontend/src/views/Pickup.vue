<script setup>
import { ref, onMounted } from 'vue';
import { api, fmt } from '../api.js';

const tasks = ref([]);
const msg = ref('');
const err = ref('');
const loading = ref(false);
const busy = ref(false);
const form = ref({ task_id: null, weight: '', length: '', width: '', height: '', insured: '', product: '隔日达' });

const reload = async () => {
  loading.value = true;
  try { tasks.value = await api.pickupTasks(); } catch (e) { err.value = e.message; }
  finally { loading.value = false; }
};
onMounted(reload);

// 体积重（前端即时提示，与后端同一口径）：长×宽×高(cm) ÷ 6000，与实重取大
const volWeight = () => {
  const { length, width, height } = form.value;
  return length > 0 && width > 0 && height > 0 ? (length * width * height / 6000) : 0;
};

async function accept(t) {
  try { await api.acceptPickup(t.id); msg.value = `已接单：${t.order_no}`; reload(); }
  catch (e) { err.value = e.message; }
}
function start(t) {
  form.value = { task_id: t.id, weight: t.weight ?? '', length: '', width: '', height: '', insured: '', product: '隔日达' };
  msg.value = ''; err.value = '';
  document.getElementById('pickup-form')?.scrollIntoView({ behavior: 'smooth', block: 'center' });
}
async function submit() {
  msg.value = ''; err.value = '';
  const w = Number(form.value.weight);
  if (!(w > 0) || w > 1000) { err.value = '实重须在 0.01~1000kg'; return; }
  const dims = [form.value.length, form.value.width, form.value.height].filter((v) => v !== '' && v != null);
  if (dims.length && dims.length !== 3) { err.value = '长、宽、高须同时填写（用于体积重计算），或全部留空'; return; }
  if (dims.some((v) => !(Number(v) > 0))) { err.value = '长宽高须为正数（cm）'; return; }
  busy.value = true;
  try {
    const d = await api.pickup({
      task_id: form.value.task_id,
      weight: w,
      length: Number(form.value.length || 0),
      width: Number(form.value.width || 0),
      height: Number(form.value.height || 0),
      insured: Number(form.value.insured || 0),
      product: form.value.product,
    });
    msg.value = `揽收成功：运单号 ${d.waybill_no}，运费 ${fmt(d.fee)}（计费重 ${d.chargeKg}kg${d.volWeight ? `，体积重 ${d.volWeight}kg` : ''}；${d.detail}）`;
    reload();
  } catch (e) { err.value = e.message; }
  finally { busy.value = false; }
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
      <div class="field"><label>长(cm)</label><input v-model="form.length" type="number" min="0" step="0.1" /></div>
      <div class="field"><label>宽(cm)</label><input v-model="form.width" type="number" min="0" step="0.1" /></div>
      <div class="field"><label>高(cm)</label><input v-model="form.height" type="number" min="0" step="0.1" /></div>
      <div class="field"><label>保价金额(元)</label><input v-model="form.insured" type="number" min="0" step="10" /></div>
      <div class="field"><label>产品时效</label>
        <select v-model="form.product">
          <option>次日达</option><option>隔日达</option><option>经济件</option>
        </select>
      </div>
      <button class="btn" :disabled="busy || !form.task_id || !form.weight" @click="submit">{{ busy ? '提交中…' : '提交揽收并生成运单' }}</button>
    </div>
    <p v-if="volWeight()" style="color: var(--slate); font-size: 12px;">
      体积重提示：{{ form.length }}×{{ form.width }}×{{ form.height }}cm ÷ 6000 = {{ volWeight().toFixed(2) }}kg，将与实重 {{ form.weight || '—' }}kg 取大计费。
    </p>
    <p style="color: var(--slate); font-size: 12px;">
      计费规则：基础运费 =（首重 + 阶梯续重）× 产品时效系数 × 区域系数，再叠加燃油/保价/包装/超重超长，月结客户享协议折扣且不低于最低一票；体积重量 = 长×宽×高(cm) ÷ 6000，与实重取大；保价费 = 保价额 × 3‰。完整明细随运单 fee_snapshot 落库，可在计费试算与账单中逐项对账。
    </p>
  </div>
</template>

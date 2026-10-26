<script setup>
import { ref, onMounted } from 'vue';
import { api, fmt } from '../api.js';

const orders = ref([]);
const customers = ref([]);
const quote = ref(null);
const msg = ref('');
const err = ref('');
const loading = ref(false);
const busy = ref(false);
const form = ref({
  customer_id: null, sender: '', recv_name: '', recv_phone: '', recv_addr: '', category: '',
  weight: '', length: '', width: '', height: '', insured: '', product: '隔日达', slot: '10:00-12:00',
});

const reload = async () => {
  loading.value = true;
  try {
    orders.value = await api.orders();
    if (!customers.value.length) {
      // 客户角色登录时后端只返回本人；调度/管理员返回全部客户
      customers.value = await api.customers();
      if (form.value.customer_id == null && customers.value.length) form.value.customer_id = customers.value[0].id;
      const self = customers.value[0];
      if (self && !form.value.sender) form.value.sender = self.name;
    }
  } catch (e) { err.value = e.message; }
  finally { loading.value = false; }
};
onMounted(reload);

async function doQuote() {
  err.value = ''; quote.value = null;
  if (!(Number(form.value.weight) > 0)) { err.value = '试算请先填写预估重量'; return; }
  try {
    quote.value = await api.quote({
      customer_id: form.value.customer_id, product: form.value.product,
      weight: Number(form.value.weight),
      length: Number(form.value.length || 0), width: Number(form.value.width || 0), height: Number(form.value.height || 0),
      insured: Number(form.value.insured || 0), dest_addr: form.value.recv_addr,
    });
  } catch (e) { err.value = e.message; }
}

async function submit() {
  err.value = ''; msg.value = '';
  if (!form.value.sender || !form.value.recv_name || !form.value.recv_addr) { err.value = '寄件人、收件人、收件地址必填'; return; }
  if (!form.value.slot) { err.value = '请选择取件时段'; return; }
  if (form.value.weight && (Number(form.value.weight) <= 0 || Number(form.value.weight) > 1000)) { err.value = '预估重量须在 0.01~1000kg'; return; }
  busy.value = true;
  try {
    const d = await api.createOrder({
      ...form.value,
      weight: form.value.weight ? Number(form.value.weight) : null,
      customer_id: form.value.customer_id,
    });
    msg.value = `下单成功：${d.order_no}，预估运费 ${fmt(d.est_fee)}（${d.zone_label}）${d.area_note ? '｜' + d.area_note : ''}`;
    form.value = {
      customer_id: form.value.customer_id, sender: form.value.sender, recv_name: '', recv_phone: '', recv_addr: '', category: '',
      weight: '', length: '', width: '', height: '', insured: '', product: '隔日达', slot: '10:00-12:00',
    };
    quote.value = null;
    reload();
  } catch (e) { err.value = e.message; }
  finally { busy.value = false; }
}

async function cancel(o) {
  err.value = ''; msg.value = '';
  try {
    await api.cancelOrder(o.id, '客户主动取消');
    msg.value = `订单 ${o.order_no} 已取消，待接单任务同步回滚`;
    reload();
  } catch (e) { err.value = e.message; }
}
</script>

<template>
  <div class="content">
    <div class="doc-head">
      <h1>客户下单与取件预约</h1>
      <span class="sub">order intake</span>
    </div>

    <div class="note ok" v-if="msg">{{ msg }}</div>
    <div class="note err" v-if="err">{{ err }}</div>

    <div class="doc-head" style="margin-bottom: 10px;">
      <h1 style="font-size:15px">新增寄件订单</h1>
      <span class="sub">寄件人 · 收件人 · 取件时段</span>
    </div>
    <div class="form-row" style="margin-bottom: 10px;">
      <div class="field"><label>寄件客户</label>
        <select v-model="form.customer_id">
          <option v-if="!customers.length" :value="null" disabled>加载客户中…</option>
          <option v-for="c in customers" :key="c.id" :value="c.id">{{ c.name }}{{ c.monthly ? '（月结）' : '（散客）' }}</option>
        </select>
      </div>
      <div class="field"><label>寄件人</label><input v-model="form.sender" /></div>
      <div class="field"><label>收件人</label><input v-model="form.recv_name" /></div>
      <div class="field"><label>收件电话</label><input v-model="form.recv_phone" /></div>
      <div class="field"><label>收件地址</label><input v-model="form.recv_addr" style="min-width:280px" placeholder="省/市/区/街道，如 北京市朝阳区建国路1号" /></div>
      <div class="field"><label>品类</label><input v-model="form.category" /></div>
    </div>
    <div class="form-row" style="margin-bottom: 10px;">
      <div class="field"><label>预估重量(kg)</label><input v-model="form.weight" type="number" min="0.01" max="1000" step="0.01" /></div>
      <div class="field"><label>长(cm)</label><input v-model="form.length" type="number" min="0" step="0.1" /></div>
      <div class="field"><label>宽(cm)</label><input v-model="form.width" type="number" min="0" step="0.1" /></div>
      <div class="field"><label>高(cm)</label><input v-model="form.height" type="number" min="0" step="0.1" /></div>
      <div class="field"><label>保价金额(元)</label><input v-model="form.insured" type="number" min="0" step="10" /></div>
      <div class="field"><label>产品时效</label>
        <select v-model="form.product"><option>次日达</option><option>隔日达</option><option>经济件</option></select>
      </div>
      <div class="field"><label>取件时段</label>
        <select v-model="form.slot">
          <option>10:00-12:00</option><option>14:00-16:00</option><option>16:00-18:00</option>
        </select>
      </div>
      <button class="btn ghost" @click="doQuote">运费试算</button>
      <button class="btn" :disabled="busy" @click="submit">{{ busy ? '提交中…' : '提交下单' }}</button>
      <span style="color:var(--slate);font-size:12px">提交后进入待派单池，由「派单调度」按策略指派</span>
    </div>
    <div v-if="quote" class="note" style="background:#f7f9fc;border:1px solid #dfe4ea;padding:12px 14px;margin-bottom:24px;">
      <div style="font-weight:600;margin-bottom:6px;">
        试算：{{ quote.product }}｜计费重 {{ quote.chargeKg }}kg<span v-if="quote.volWeight">（体积重 {{ quote.volWeight }}kg）</span>｜{{ quote.zone_label }}｜预计送达 {{ quote.promise_time }}
      </div>
      <div v-for="it in quote.items" :key="it.code" style="font-size:12px;color:var(--slate);display:flex;justify-content:space-between;max-width:560px">
        <span>{{ it.label }}<span style="color:#9aa4b2"> — {{ it.rule }}</span></span><span class="mono">{{ fmt(it.amount) }}</span>
      </div>
      <div style="font-size:13px;margin-top:6px">预估总价：<b>{{ fmt(quote.total) }}</b>（下单预估不含尺寸时按重量计，揽收称重后以实际计费为准）</div>
    </div>

    <div class="doc-head" style="margin-bottom: 10px;">
      <h1 style="font-size:15px">订单记录</h1>
      <span class="sub">{{ orders.length }} 条</span>
    </div>
    <table class="doc">
      <thead>
        <tr><th>订单号</th><th>寄件人</th><th>收件人</th><th>收件地址</th><th>品类</th><th class="num">预估(kg)</th><th class="num">预估运费</th><th>取件时段</th><th>状态</th><th></th></tr>
      </thead>
      <tbody>
        <tr v-for="o in orders" :key="o.id">
          <td class="mono">{{ o.order_no }}</td>
          <td>{{ o.sender }}</td>
          <td>{{ o.recv_name }}</td>
          <td>{{ o.recv_addr }}</td>
          <td>{{ o.category }}</td>
          <td class="num">{{ o.weight ?? '—' }}</td>
          <td class="num">{{ o.est_fee ? fmt(o.est_fee) : '—' }}</td>
          <td class="mono">{{ o.slot }}</td>
          <td><span class="tag" :class="o.status === '待派单' ? 'wait' : o.status === '已取消' ? 'fail' : 'transit'">{{ o.status }}</span></td>
          <td>
            <button v-if="['待派单','待揽收'].includes(o.status)" class="btn small" @click="cancel(o)">取消</button>
            <span v-else style="color:var(--slate)">—</span>
          </td>
        </tr>
        <tr v-if="!orders.length"><td colspan="10" class="empty">还没有订单。用上方表单建第一单。</td></tr>
      </tbody>
    </table>
    <p style="color:var(--slate);font-size:12px;margin-top:10px">
      本页只负责「接单」；派单在「派单调度」页，揽收在「揽收登记」页，职责分开。
    </p>
  </div>
</template>

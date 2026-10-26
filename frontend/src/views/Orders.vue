<script setup>
import { ref, onMounted } from 'vue';
import { api, fmt } from '../api.js';

const orders = ref([]);
const msg = ref('');
const err = ref('');
const form = ref({ sender: '', recv_name: '', recv_phone: '', recv_addr: '', category: '', weight: '', slot: '10:00-12:00' });

const reload = async () => { orders.value = await api.orders(); };
onMounted(reload);

async function submit() {
  err.value = ''; msg.value = '';
  try {
    const d = await api.createOrder({
      ...form.value,
      weight: form.value.weight ? Number(form.value.weight) : null,
      customer_id: 1,
    });
    msg.value = `下单成功：${d.order_no}，预估运费 ${fmt(d.est_fee)}（${d.zone_label}）${d.area_note ? '｜' + d.area_note : ''}`;
    form.value = { sender: '', recv_name: '', recv_phone: '', recv_addr: '', category: '', weight: '', slot: '10:00-12:00' };
    reload();
  } catch (e) { err.value = e.message; }
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
      <div class="field"><label>寄件人</label><input v-model="form.sender" /></div>
      <div class="field"><label>收件人</label><input v-model="form.recv_name" /></div>
      <div class="field"><label>收件电话</label><input v-model="form.recv_phone" /></div>
      <div class="field"><label>收件地址</label><input v-model="form.recv_addr" style="min-width:280px" placeholder="省/市/区/街道" /></div>
      <div class="field"><label>品类</label><input v-model="form.category" /></div>
    </div>
    <div class="form-row" style="margin-bottom: 24px;">
      <div class="field"><label>预估重量(kg)</label><input v-model="form.weight" type="number" min="0.01" max="1000" step="0.01" /></div>
      <div class="field"><label>取件时段</label>
        <select v-model="form.slot">
          <option>10:00-12:00</option><option>14:00-16:00</option><option>16:00-18:00</option>
        </select>
      </div>
      <button class="btn" @click="submit">提交下单</button>
      <span style="color:var(--slate);font-size:12px">提交后进入待派单池，由「派单调度」按策略指派</span>
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

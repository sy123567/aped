<script setup>
import { ref, onMounted } from 'vue';
import { api, session, fmt } from '../api.js';

const user = session.user ?? {};
const me = ref(null);
const bill = ref(null);
const msg = ref('');
const err = ref('');
const loading = ref(true);

async function load() {
  loading.value = true;
  try {
    const cid = user.customer_id;
    if (!cid) { err.value = '当前账号未绑定客户档案'; return; }
    me.value = await api.customer(cid);
  } catch (e) {
    err.value = e.message;
  } finally {
    loading.value = false;
  }
}
onMounted(load);

const stat = (k) => {
  const o = me.value?.orders ?? [];
  if (k === 'cnt') return o.filter((x) => x.status !== '已取消').length;
  return 0;
};

async function openBill(b) {
  err.value = '';
  try { bill.value = await api.bill(b.id); } catch (e) { err.value = e.message; }
}

async function cancel(o) {
  err.value = ''; msg.value = '';
  try {
    await api.cancelOrder(o.id, '客户主动取消');
    msg.value = `订单 ${o.order_no} 已取消`;
    await load();
  } catch (e) { err.value = e.message; }
}
</script>

<template>
  <div class="content">
    <div class="doc-head">
      <h1>我的账户</h1>
      <span class="sub">my account</span>
    </div>

    <div class="note ok" v-if="msg">{{ msg }}</div>
    <div class="note err" v-if="err">{{ err }}</div>
    <div v-if="loading" class="note">加载中…</div>

    <template v-if="me">
      <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:12px;margin-bottom:24px;">
        <div style="border:1px solid #dfe4ea;border-left:3px solid #2f6fed;padding:12px 14px;">
          <div style="font-size:11px;letter-spacing:.08em;color:#6b7684">客户</div>
          <div style="font-size:18px;font-weight:600">{{ me.customer.name }}</div>
          <div style="font-size:11px;color:#6b7684">{{ me.customer.monthly ? '月结客户' : '散客' }}</div>
        </div>
        <div style="border:1px solid #dfe4ea;border-left:3px solid #1f9d6b;padding:12px 14px;">
          <div style="font-size:11px;letter-spacing:.08em;color:#6b7684">订单数</div>
          <div style="font-size:24px;font-weight:600">{{ stat('cnt') }}</div>
        </div>
        <div style="border:1px solid #dfe4ea;border-left:3px solid #6b7684;padding:12px 14px;">
          <div style="font-size:11px;letter-spacing:.08em;color:#6b7684">账单</div>
          <div style="font-size:24px;font-weight:600">{{ me.bills.length }}</div>
        </div>
        <div style="border:1px solid #dfe4ea;border-left:3px solid #c2410c;padding:12px 14px;">
          <div style="font-size:11px;letter-spacing:.08em;color:#6b7684">联系电话</div>
          <div style="font-size:15px;font-weight:600;font-family:ui-monospace,monospace">{{ me.customer.phone }}</div>
        </div>
      </div>

      <div class="doc-head" style="margin-bottom:10px;">
        <h1 style="font-size:15px">我的订单</h1>
        <span class="sub">可自助取消未揽收的订单</span>
      </div>
      <table class="doc" style="margin-bottom:24px;">
        <thead>
          <tr><th>订单号</th><th>收件人</th><th>收件地址</th><th class="num">预估运费</th><th>取件时段</th><th>状态</th><th></th></tr>
        </thead>
        <tbody>
          <tr v-for="o in me.orders" :key="o.id">
            <td class="mono">{{ o.order_no }}</td>
            <td>{{ o.recv_name }}</td>
            <td>{{ o.recv_addr }}</td>
            <td class="num">{{ o.est_fee ? fmt(o.est_fee) : '—' }}</td>
            <td class="mono">{{ o.slot }}</td>
            <td><span class="tag" :class="o.status === '待派单' ? 'wait' : o.status === '已取消' ? 'fail' : 'transit'">{{ o.status }}</span></td>
            <td>
              <button v-if="['待派单','待揽收'].includes(o.status)" class="btn small" @click="cancel(o)">取消</button>
              <span v-else style="color:var(--slate)">—</span>
            </td>
          </tr>
          <tr v-if="!me.orders.length"><td colspan="7" class="empty">还没有订单，去「客户下单」建第一单。</td></tr>
        </tbody>
      </table>

      <div class="doc-head" style="margin-bottom:10px;">
        <h1 style="font-size:15px">我的账单</h1>
        <span class="sub">月结账期内可查运单级明细</span>
      </div>
      <table class="doc">
        <thead><tr><th>账单号</th><th>账期</th><th class="num">金额</th><th class="num">已收</th><th class="num">待收</th><th>状态</th><th></th></tr></thead>
        <tbody>
          <tr v-for="b in me.bills" :key="b.id">
            <td class="mono">{{ b.bill_no }}</td>
            <td class="mono">{{ b.period }}</td>
            <td class="num">{{ fmt(b.amount) }}</td>
            <td class="num">{{ fmt(b.paid_amount) }}</td>
            <td class="num">{{ fmt(b.amount - b.paid_amount) }}</td>
            <td><span class="tag" :class="b.status === '已结清' ? 'done' : b.status === '部分核销' ? 'transit' : 'wait'">{{ b.status }}</span></td>
            <td><button class="btn small" @click="openBill(b)">明细</button></td>
          </tr>
          <tr v-if="!me.bills.length"><td colspan="7" class="empty">暂无账单</td></tr>
        </tbody>
      </table>

      <template v-if="bill">
        <div class="doc-head" style="margin:24px 0 10px;">
          <h1 style="font-size:15px">账单 {{ bill.bill.bill_no }} 明细</h1>
          <span class="sub">合计 {{ fmt(bill.bill.amount) }}</span>
        </div>
        <table class="doc">
          <thead><tr><th>运单号</th><th>产品</th><th class="num">运费</th><th>签收时间</th><th>备注</th></tr></thead>
          <tbody>
            <tr v-for="it in bill.items" :key="it.id">
              <td class="mono">{{ it.waybill_no }}</td>
              <td>{{ it.product || '—' }}</td>
              <td class="num">{{ fmt(it.fee) }}</td>
              <td class="mono">{{ it.signed_at || '—' }}</td>
              <td><span v-if="it.frozen" class="tag fail">争议冻结</span><span v-else>—</span></td>
            </tr>
            <tr v-if="!bill.items.length"><td colspan="5" class="empty">该账单没有明细</td></tr>
          </tbody>
        </table>
      </template>
    </template>
  </div>
</template>

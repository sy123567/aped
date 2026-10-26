<script setup>
import { ref, onMounted } from 'vue';
import { api, fmt } from '../api.js';

const list = ref([]);
const detail = ref(null);
const bill = ref(null);
const msg = ref('');
const err = ref('');
const pay = ref({ amount: '', voucher: '', operator: '财务小赵' });

const reload = async () => { list.value = await api.customers(); };
onMounted(reload);

async function open(c) {
  msg.value = ''; err.value = ''; bill.value = null;
  try { detail.value = await api.customer(c.id); } catch (e) { err.value = e.message; }
}

async function openBill(b) {
  try { bill.value = await api.bill(b.id); } catch (e) { err.value = e.message; }
}

async function refreshBill() {
  if (bill.value) bill.value = await api.bill(bill.value.bill.id);
  if (detail.value) detail.value = await api.customer(detail.value.customer.id);
  reload();
}

async function generate() {
  msg.value = ''; err.value = '';
  const period = new Date().toISOString().slice(0, 7);
  try {
    const d = await api.generateBill({ customer_id: detail.value.customer.id, period });
    msg.value = `已出账 ${d.bill_no}：${d.item_count} 笔运单，合计 ${fmt(d.amount)}`;
    await refreshBill();
  } catch (e) { err.value = e.message; }
}

async function settle() {
  msg.value = ''; err.value = '';
  try {
    const d = await api.settleBill({
      id: bill.value.bill.id,
      amount: pay.value.amount === '' ? null : Number(pay.value.amount),
      voucher: pay.value.voucher,
      operator: pay.value.operator,
    });
    msg.value = `核销成功：状态「${d.status}」，已收 ${fmt(d.paid_amount)}，待收 ${fmt(d.remaining)}`;
    pay.value = { amount: '', voucher: '', operator: pay.value.operator };
    await refreshBill();
  } catch (e) { err.value = e.message; }
}

async function freeze(item) {
  msg.value = ''; err.value = '';
  try {
    await api.freezeItem(item.id, !item.frozen);
    msg.value = item.frozen ? `已解冻 ${item.waybill_no}` : `已冻结争议明细 ${item.waybill_no}，账单金额同步重算`;
    await refreshBill();
  } catch (e) { err.value = e.message; }
}

async function cancel(o) {
  msg.value = ''; err.value = '';
  try {
    await api.cancelOrder(o.id, '客户主动取消');
    msg.value = `订单 ${o.order_no} 已取消`;
    await refreshBill();
  } catch (e) { err.value = e.message; }
}
</script>

<template>
  <div class="content">
    <div class="doc-head">
      <h1>客户账户与月结账单</h1>
      <span class="sub">accounts &amp; monthly billing</span>
    </div>

    <div class="note ok" v-if="msg">{{ msg }}</div>
    <div class="note err" v-if="err">{{ err }}</div>

    <table class="doc" style="margin-bottom: 22px;">
      <thead>
        <tr><th>客户</th><th>电话</th><th>地址</th><th>账户类型</th><th class="num">订单数</th><th class="num">累计运费</th><th class="num">待收</th><th></th></tr>
      </thead>
      <tbody>
        <tr v-for="c in list" :key="c.id">
          <td>{{ c.name }}</td>
          <td class="mono">{{ c.phone }}</td>
          <td>{{ c.addr }}</td>
          <td><span class="tag" :class="c.monthly ? 'done' : 'wait'">{{ c.monthly ? '月结客户' : '散客' }}</span></td>
          <td class="num">{{ c.order_cnt }}</td>
          <td class="num">{{ fmt(c.total_fee) }}</td>
          <td class="num">{{ fmt(c.unpaid) }}</td>
          <td><button class="btn small" @click="open(c)">查看</button></td>
        </tr>
      </tbody>
    </table>

    <template v-if="detail">
      <div class="doc-head" style="margin-top: 26px;">
        <h1>{{ detail.customer.name }} · 账户明细</h1>
        <span class="sub">orders · bills · receipts</span>
      </div>

      <div class="form-row" style="margin-bottom: 14px;">
        <button class="btn" @click="generate">生成当月账单</button>
        <span style="color:var(--slate);font-size:12px">
          仅汇总「已签收」运单；同一账期重复出账会被拒绝。
        </span>
      </div>

      <table class="doc" style="margin-bottom: 22px;">
        <thead>
          <tr><th>订单号</th><th>收件地址</th><th>品类</th><th>状态</th><th class="num">预估运费</th><th>下单时间</th><th></th></tr>
        </thead>
        <tbody>
          <tr v-for="o in detail.orders" :key="o.id">
            <td class="mono">{{ o.order_no }}</td>
            <td>{{ o.recv_addr }}</td>
            <td>{{ o.category }}</td>
            <td><span class="tag" :class="o.status === '已取消' ? 'fail' : o.status === '待派单' ? 'wait' : 'transit'">{{ o.status }}</span></td>
            <td class="num">{{ o.est_fee ? fmt(o.est_fee) : '—' }}</td>
            <td class="mono">{{ o.created_at }}</td>
            <td>
              <button v-if="['待派单','待揽收'].includes(o.status)" class="btn small" @click="cancel(o)">取消</button>
              <span v-else style="color:var(--slate)">—</span>
            </td>
          </tr>
          <tr v-if="!detail.orders.length"><td colspan="7" class="empty">该客户暂无订单</td></tr>
        </tbody>
      </table>

      <table class="doc" style="margin-bottom: 22px;">
        <thead>
          <tr><th>账单号</th><th>账期</th><th class="num">金额</th><th class="num">已收</th><th class="num">待收</th><th>状态</th><th>出账时间</th><th></th></tr>
        </thead>
        <tbody>
          <tr v-for="b in detail.bills" :key="b.id">
            <td class="mono">{{ b.bill_no }}</td>
            <td class="mono">{{ b.period }}</td>
            <td class="num">{{ fmt(b.amount) }}</td>
            <td class="num">{{ fmt(b.paid_amount) }}</td>
            <td class="num">{{ fmt(b.amount - b.paid_amount) }}</td>
            <td><span class="tag" :class="b.status === '已结清' ? 'done' : b.status === '部分核销' ? 'transit' : 'wait'">{{ b.status }}</span></td>
            <td class="mono">{{ b.created_at }}</td>
            <td><button class="btn small" @click="openBill(b)">明细</button></td>
          </tr>
          <tr v-if="!detail.bills.length"><td colspan="8" class="empty">暂无账单</td></tr>
        </tbody>
      </table>
    </template>

    <template v-if="bill">
      <div class="doc-head" style="margin-top: 26px;">
        <h1>账单 {{ bill.bill.bill_no }} · 运单级明细</h1>
        <span class="sub">reconcile &amp; settle</span>
      </div>

      <table class="doc" style="margin-bottom: 18px;">
        <thead>
          <tr><th>运单号</th><th>产品</th><th class="num">运费</th><th>签收时间</th><th>状态</th><th></th></tr>
        </thead>
        <tbody>
          <tr v-for="it in bill.items" :key="it.id">
            <td class="mono">{{ it.waybill_no }}</td>
            <td>{{ it.product || '—' }}</td>
            <td class="num">{{ fmt(it.fee) }}</td>
            <td class="mono">{{ it.signed_at || '—' }}</td>
            <td><span class="tag" :class="it.frozen ? 'fail' : 'done'">{{ it.frozen ? '争议冻结' : '正常' }}</span></td>
            <td><button class="btn small" @click="freeze(it)">{{ it.frozen ? '解冻' : '标记争议' }}</button></td>
          </tr>
          <tr v-if="!bill.items.length"><td colspan="6" class="empty">该账单没有明细</td></tr>
        </tbody>
      </table>

      <p style="font-size:12px;color:var(--slate);margin:0 0 14px">
        账单金额 {{ fmt(bill.bill.amount) }}　已收 {{ fmt(bill.bill.paid_amount) }}　
        争议冻结合计 {{ fmt(bill.frozen_amount) }}　待收 {{ fmt(bill.bill.amount - bill.bill.paid_amount) }}
      </p>

      <div class="form-row" style="margin-bottom: 18px;">
        <div class="field"><label>本次核销金额（留空=全额）</label><input v-model="pay.amount" type="number" min="0" step="0.01" /></div>
        <div class="field"><label>回款凭证号</label><input v-model="pay.voucher" placeholder="如 银行流水号" /></div>
        <div class="field"><label>经办人</label><input v-model="pay.operator" /></div>
        <button class="btn" @click="settle">登记回款核销</button>
      </div>

      <table class="doc">
        <thead><tr><th>核销流水</th><th class="num">金额</th><th>回款时间</th><th>凭证</th><th>经办人</th></tr></thead>
        <tbody>
          <tr v-for="r in bill.receipts" :key="r.id">
            <td class="mono">#{{ r.id }}</td>
            <td class="num">{{ fmt(r.amount) }}</td>
            <td class="mono">{{ r.paid_at }}</td>
            <td class="mono">{{ r.voucher || '—' }}</td>
            <td>{{ r.operator || '—' }}</td>
          </tr>
          <tr v-if="!bill.receipts.length"><td colspan="5" class="empty">尚无回款</td></tr>
        </tbody>
      </table>
    </template>
  </div>
</template>

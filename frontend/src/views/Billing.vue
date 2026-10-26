<script setup>
import { ref, onMounted } from 'vue';
import { api, fmt } from '../api.js';

const bills = ref([]);
const customers = ref([]);
const form = ref({ customer_id: null, period: new Date().toISOString().slice(0, 7) });
const detail = ref(null);
const pay = ref({ amount: '', voucher: '', operator: '财务小赵' });
const msg = ref('');
const err = ref('');
const loading = ref(false);
const busy = ref(false);

async function reload() {
  loading.value = true;
  try {
    bills.value = await api.bills();
    if (!customers.value.length) {
      customers.value = (await api.customers()).filter((c) => c.monthly);
      if (form.value.customer_id == null && customers.value.length) form.value.customer_id = customers.value[0].id;
    }
  } catch (e) { err.value = e.message; }
  finally { loading.value = false; }
}
onMounted(reload);

async function generate() {
  err.value = ''; msg.value = '';
  if (!form.value.customer_id) { err.value = '请选择月结客户'; return; }
  if (!/^\d{4}-\d{2}$/.test(form.value.period)) { err.value = '账期格式须为 YYYY-MM'; return; }
  busy.value = true;
  try {
    const d = await api.generateBill({ ...form.value });
    msg.value = `账单 ${d.bill_no} 已生成：${d.item_count} 单，合计 ${fmt(d.amount)}`;
    await reload();
  } catch (e) { err.value = e.message; }
  finally { busy.value = false; }
}

async function openBill(b) {
  err.value = '';
  try { detail.value = await api.bill(b.id); } catch (e) { err.value = e.message; }
}

async function refreshDetail() {
  if (detail.value) detail.value = await api.bill(detail.value.bill.id);
  await reload();
}

async function settleAll(b) {
  err.value = ''; msg.value = '';
  const remain = +(b.amount - (b.paid_amount ?? 0)).toFixed(2);
  if (!(remain > 0)) { err.value = '该账单无待收余额'; return; }
  busy.value = true;
  try {
    const d = await api.settleBill({ id: b.id, amount: remain, voucher: '', operator: pay.value.operator });
    msg.value = `账单 ${b.bill_no} 已${d.status}（本次核销 ${fmt(remain)}）`;
    await refreshDetail();
  } catch (e) { err.value = e.message; }
  finally { busy.value = false; }
}

async function settlePart() {
  err.value = ''; msg.value = '';
  const amt = pay.value.amount === '' ? null : Number(pay.value.amount);
  if (amt != null && !(amt > 0)) { err.value = '核销金额须大于 0'; return; }
  busy.value = true;
  try {
    const d = await api.settleBill({
      id: detail.value.bill.id, amount: amt,
      voucher: pay.value.voucher, operator: pay.value.operator,
    });
    msg.value = `核销成功：状态「${d.status}」，已收 ${fmt(d.paid_amount)}，待收 ${fmt(d.remaining)}`;
    pay.value.amount = ''; pay.value.voucher = '';
    await refreshDetail();
  } catch (e) { err.value = e.message; }
  finally { busy.value = false; }
}

async function freeze(item) {
  err.value = ''; msg.value = '';
  try {
    await api.freezeItem(item.id, !item.frozen);
    msg.value = item.frozen ? `已解冻 ${item.waybill_no}` : `已冻结争议明细 ${item.waybill_no}，账单应收金额已按未冻结明细重算`;
    await refreshDetail();
  } catch (e) { err.value = e.message; }
}

const remainOf = (b) => +(b.amount - (b.paid_amount ?? 0)).toFixed(2);
</script>

<template>
  <div class="content">
    <div class="doc-head">
      <h1>月结账单</h1>
      <span class="sub">monthly billing · settle</span>
    </div>

    <div class="note ok" v-if="msg">{{ msg }}</div>
    <div class="note err" v-if="err">{{ err }}</div>
    <div class="note" v-if="loading">加载中…</div>

    <table class="doc" style="margin-bottom: 24px;">
      <thead>
        <tr><th>账单号</th><th>客户</th><th>账期</th><th class="num">运单数</th><th class="num">应收</th><th class="num">已收</th><th class="num">待收</th><th>状态</th><th>操作</th></tr>
      </thead>
      <tbody>
        <tr v-for="b in bills" :key="b.id">
          <td class="mono">{{ b.bill_no }}</td>
          <td>{{ b.customer_name }}</td>
          <td class="mono">{{ b.period }}</td>
          <td class="num">{{ b.item_count }}</td>
          <td class="num">{{ fmt(b.amount) }}</td>
          <td class="num">{{ fmt(b.paid_amount ?? 0) }}</td>
          <td class="num">{{ fmt(remainOf(b)) }}</td>
          <td><span class="tag" :class="b.status === '已结清' ? 'done' : b.status === '部分核销' ? 'transit' : 'wait'">{{ b.status }}</span></td>
          <td>
            <button class="btn small" @click="openBill(b)">明细/核销</button>
            <button v-if="b.status !== '已结清'" class="btn small ghost" :disabled="busy" @click="settleAll(b)">全额核销</button>
            <span v-else style="color: var(--slate)">已结清</span>
          </td>
        </tr>
        <tr v-if="!bills.length && !loading"><td colspan="9" class="empty">暂无账单。用下方表单按月结客户 + 账期生成第一张。</td></tr>
      </tbody>
    </table>

    <template v-if="detail">
      <div class="doc-head" style="margin: 26px 0 10px;">
        <h1 style="font-size:15px">账单 {{ detail.bill.bill_no }} · {{ detail.bill.customer_name }} · {{ detail.bill.period }}</h1>
        <span class="sub">应收 {{ fmt(detail.bill.amount) }}｜已收 {{ fmt(detail.bill.paid_amount ?? 0) }}｜待收 {{ fmt(remainOf(detail.bill)) }}<span v-if="detail.frozen_amount">｜争议冻结 {{ fmt(detail.frozen_amount) }}（不计入应收）</span></span>
      </div>

      <table class="doc" style="margin-bottom: 18px;">
        <thead><tr><th>运单号</th><th>产品</th><th class="num">运费</th><th>签收时间</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="it in detail.items" :key="it.id">
            <td class="mono">{{ it.waybill_no }}</td>
            <td>{{ it.product || '—' }}</td>
            <td class="num">{{ fmt(it.fee) }}</td>
            <td class="mono">{{ it.signed_at || '—' }}</td>
            <td><span v-if="it.frozen" class="tag fail">争议冻结</span><span v-else class="tag done">正常</span></td>
            <td>
              <button v-if="detail.bill.status !== '已结清'" class="btn small ghost" @click="freeze(it)">{{ it.frozen ? '解冻' : '冻结争议' }}</button>
              <span v-else style="color:var(--slate)">—</span>
            </td>
          </tr>
          <tr v-if="!detail.items.length"><td colspan="6" class="empty">该账单没有明细</td></tr>
        </tbody>
      </table>

      <div class="doc-head" style="margin-bottom: 8px;"><h1 style="font-size:14px">核销流水</h1></div>
      <table class="doc" style="margin-bottom: 18px;">
        <thead><tr><th class="num">金额</th><th>核销时间</th><th>凭证</th><th>经办人</th></tr></thead>
        <tbody>
          <tr v-for="r in detail.receipts" :key="r.id">
            <td class="num">{{ fmt(r.amount) }}</td>
            <td class="mono">{{ r.paid_at }}</td>
            <td>{{ r.voucher || '—' }}</td>
            <td>{{ r.operator || '—' }}</td>
          </tr>
          <tr v-if="!detail.receipts.length"><td colspan="4" class="empty">尚无回款记录</td></tr>
        </tbody>
      </table>

      <div v-if="detail.bill.status !== '已结清'" class="form-row">
        <div class="field"><label>本次核销金额（留空 = 全额）</label><input v-model="pay.amount" type="number" min="0.01" step="0.01" /></div>
        <div class="field"><label>回款凭证</label><input v-model="pay.voucher" placeholder="银行流水号/支票号" /></div>
        <div class="field"><label>经办人</label><input v-model="pay.operator" /></div>
        <button class="btn" :disabled="busy" @click="settlePart">提交核销</button>
      </div>
      <p v-else style="font-size:12px;color:var(--slate)">该账单已结清，明细冻结与核销均已锁定。</p>
    </template>

    <div class="doc-head" style="margin-top: 28px;"><h1 style="font-size:15px">生成月结账单</h1></div>
    <div class="form-row">
      <div class="field"><label>月结客户</label>
        <select v-model="form.customer_id">
          <option v-if="!customers.length" :value="null" disabled>暂无月结客户</option>
          <option v-for="c in customers" :key="c.id" :value="c.id">{{ c.name }}（月结）</option>
        </select>
      </div>
      <div class="field"><label>账期</label><input v-model="form.period" placeholder="2026-10" style="font-family: var(--mono)" /></div>
      <button class="btn" :disabled="busy || !customers.length" @click="generate">生成账单</button>
    </div>
    <p style="font-size:12px; color:var(--slate)">仅汇总账期内状态为「已签收」的计费运单（散客不生成月结账单，后端会拒绝）；同一客户同一账期重复出账会被拒绝。冻结争议明细后应收按未冻结明细重算。</p>
  </div>
</template>

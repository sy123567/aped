<script setup>
import { ref, onMounted } from 'vue';
import { api, tag } from '../api.js';

const list = ref([]);
const msg = ref('');
const err = ref('');
const form = ref({ waybill_no: '', type: '破损', desc: '', photos: '' });
const claim = ref({ exception_id: null, amount: '' });

const reload = async () => { list.value = await api.exceptions(); };
onMounted(reload);

async function submit() {
  err.value = ''; msg.value = '';
  try {
    await api.createException({ ...form.value });
    msg.value = '异常登记成功';
    form.value = { waybill_no: '', type: '破损', desc: '', photos: '' };
    reload();
  } catch (e) { err.value = e.message; }
}
async function submitClaim() {
  err.value = ''; msg.value = '';
  try {
    await api.createClaim({ exception_id: claim.value.exception_id, amount: Number(claim.value.amount) });
    msg.value = '理赔单已提交审批';
    claim.value = { exception_id: null, amount: '' };
    reload();
  } catch (e) { err.value = e.message; }
}
async function approve(c, approved) {
  try {
    await api.approveClaim({ id: c.id, approved, by: '理赔专员小陈' });
    msg.value = approved ? '理赔已赔付' : '理赔已驳回';
    reload();
  } catch (e) { err.value = e.message; }
}
async function closeEx(e) {
  err.value = ''; msg.value = '';
  try {
    const reason = e.type === '拒收' ? '收件人拒收，原件退回' : '无需理赔，直接关闭';
    await api.closeException(e.id, reason);
    msg.value = `异常单 #${e.id} 已关闭：${reason}`;
    reload();
  } catch (err2) { err.value = err2.message; }
}
</script>

<template>
  <div class="content">
    <div class="doc-head">
      <h1>异常件处理与理赔</h1>
      <span class="sub">exceptions &amp; claims</span>
    </div>

    <div class="note ok" v-if="msg">{{ msg }}</div>
    <div class="note err" v-if="err">{{ err }}</div>

    <table class="doc" style="margin-bottom: 24px;">
      <thead>
        <tr><th>运单号</th><th>类型</th><th>说明</th><th>异常状态</th><th>理赔金额</th><th>理赔状态</th><th>操作</th></tr>
      </thead>
      <tbody>
        <tr v-for="e in list" :key="e.id">
          <td class="mono">{{ e.waybill_no }}</td>
          <td><span class="tag fail">{{ e.type }}</span></td>
          <td>{{ e.desc_text || '—' }}</td>
          <td><span class="tag" :class="e.status === '已理赔' ? 'done' : e.status === '待审批' ? 'transit' : 'wait'">{{ e.status }}</span></td>
          <td class="num">{{ e.amount != null ? '¥' + Number(e.amount).toFixed(2) : '—' }}</td>
          <td><span v-if="e.claim_status" class="tag" :class="e.claim_status === '已赔付' ? 'done' : e.claim_status === '已驳回' ? 'fail' : 'wait'">{{ e.claim_status }}</span><span v-else>—</span></td>
          <td>
            <template v-if="e.status === '待处理'">
              <button class="btn small" @click="claim = { exception_id: e.id, amount: '' }">发起理赔</button>
              <button class="btn small" @click="closeEx(e)" title="拒收无损失时直接退回，不走理赔">直接退回关闭</button>
            </template>
            <template v-else-if="e.claim_status === '待审批'">
              <button class="btn small" @click="approve({ id: e.claim_id ?? e.id }, true)">审批通过并赔付</button>
            </template>
            <span v-else style="color: var(--slate)">已闭环</span>
          </td>
        </tr>
        <tr v-if="!list.length"><td colspan="7" class="empty">暂无异常登记。</td></tr>
      </tbody>
    </table>

    <div class="grid2">
      <div>
        <div class="doc-head"><h1 style="font-size:15px">登记异常件</h1></div>
        <div class="form-row">
          <div class="field"><label>运单号</label><input v-model="form.waybill_no" style="font-family:var(--mono)" /></div>
          <div class="field"><label>异常类型</label>
            <select v-model="form.type"><option>拒收</option><option>破损</option><option>丢失</option></select>
          </div>
        </div>
        <div class="form-row">
          <div class="field"><label>情况说明</label><input v-model="form.desc" style="min-width: 280px" /></div>
          <button class="btn" @click="submit">登记异常</button>
        </div>
      </div>
      <div v-if="claim.exception_id">
        <div class="doc-head"><h1 style="font-size:15px">发起理赔（异常单 #{{ claim.exception_id }}）</h1></div>
        <div class="form-row">
          <div class="field"><label>核定金额(元)</label><input v-model.number="claim.amount" type="number" min="0" step="10" /></div>
          <button class="btn" @click="submitClaim">提交审批</button>
        </div>
        <p style="font-size:12px; color:var(--slate)">理赔金额不能超过运单保价额；丢失按声明价值、破损按折损比例核定。</p>
      </div>
    </div>
  </div>
</template>

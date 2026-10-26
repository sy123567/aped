<script setup>
import { ref, onMounted } from 'vue';
import { api, tag } from '../api.js';

const list = ref([]);
const msg = ref('');
const err = ref('');
const loading = ref(false);
const form = ref({ waybill_no: '', type: '破损', desc: '', photos: '' });
const claim = ref({ exception_id: null, severity: '中度' });
const assess = ref(null);

const reload = async () => {
  loading.value = true;
  try { list.value = await api.exceptions(); } catch (e) { err.value = e.message; }
  finally { loading.value = false; }
};
onMounted(reload);

async function doAssess() {
  err.value = ''; assess.value = null;
  if (!claim.value.exception_id) return;
  try {
    assess.value = await api.assessException(claim.value.exception_id, claim.value.severity);
  } catch (e) { err.value = e.message; }
}

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
    // 金额由后端定责引擎核定（保价额 × 折损系数，封顶保价额），前端不再手填金额
    const d = await api.createClaim({ exception_id: claim.value.exception_id, severity: claim.value.severity });
    msg.value = `理赔单已提交审批：${d.liable_label}，核定 ${'¥' + Number(d.amount).toFixed(2)}（上限 ¥${Number(d.cap).toFixed(2)}）${d.need_second_approval ? '，金额较大需二级审批' : ''}${d.need_evidence ? '；注意：缺少证据材料' : ''}`;
    claim.value = { exception_id: null, severity: '中度' };
    assess.value = null;
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
              <button class="btn small" @click="claim = { exception_id: e.id, severity: e.severity ?? '中度' }; assess = null">发起理赔</button>
              <button class="btn small" @click="closeEx(e)" title="拒收无损失时直接退回，不走理赔">直接退回关闭</button>
            </template>
            <template v-else-if="e.claim_status === '待审批'">
              <button class="btn small" @click="approve({ id: e.claim_id ?? e.id }, true)">审批通过并赔付</button>
              <button class="btn small ghost" @click="approve({ id: e.claim_id ?? e.id }, false)">驳回</button>
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
          <div class="field"><label>证据（照片链接/编号）</label><input v-model="form.photos" placeholder="如 evidence://damage-1.jpg" style="min-width: 220px" /></div>
          <button class="btn" :disabled="!form.waybill_no" @click="submit">登记异常</button>
        </div>
        <p style="font-size:12px;color:var(--slate)">破损/丢失理赔需证据材料，缺证据时定责试算会提示补齐。</p>
      </div>
      <div v-if="claim.exception_id">
        <div class="doc-head"><h1 style="font-size:15px">发起理赔（异常单 #{{ claim.exception_id }}）</h1></div>
        <div class="form-row">
          <div class="field"><label>折损程度</label>
            <select v-model="claim.severity">
              <option>轻微</option><option>中度</option><option>严重</option><option>全损</option>
            </select>
          </div>
          <button class="btn ghost" @click="doAssess">定责试算</button>
          <button class="btn" @click="submitClaim">按试算核定提交审批</button>
        </div>
        <div v-if="assess" class="note" style="background:#f7f9fc;border:1px solid #dfe4ea;padding:12px 14px">
          <div style="font-weight:600;margin-bottom:6px">
            {{ assess.liable_label }}｜{{ assess.severity_label }}｜试算赔付 ¥{{ Number(assess.amount).toFixed(2) }}（上限 ¥{{ Number(assess.cap).toFixed(2) }}）
          </div>
          <div style="font-size:12px;color:var(--slate)">
            <span v-for="(r, i) in assess.rules" :key="i" style="display:block">· {{ r }}</span>
            <span v-if="assess.need_evidence" style="display:block;color:#c2410c">· 缺少照片/证明材料，补齐后才能赔付</span>
            <span v-if="assess.need_second_approval" style="display:block;color:#c2410c">· 金额超过二级审批门槛，需二级审批</span>
          </div>
        </div>
        <p style="font-size:12px; color:var(--slate)">金额由定责引擎核定：已保价 = 保价额 × 折损系数（轻微 0.2 / 中度 0.4 / 严重 0.7 / 全损 1.0）；未保价按运费 3 倍且 ¥300 封顶；拒收为非承运责任，赔付为 0，请走「直接退回关闭」。</p>
      </div>
    </div>
  </div>
</template>

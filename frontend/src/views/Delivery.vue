<script setup>
import { ref, onMounted } from 'vue';
import { api } from '../api.js';

const tasks = ref([]);
const waybills = ref([]);
const msg = ref('');
const err = ref('');
const courierId = ref(1);
const selected = ref([]);
const signing = ref(null);
const signForm = ref({ signer: '', sign_way: '本人签收' });
const failForm = ref({ id: null, type: '拒收', desc: '' });

const reload = async () => {
  tasks.value = await api.deliveryTasks();
  waybills.value = await api.waybills();
};
onMounted(reload);

async function assign() {
  err.value = ''; msg.value = '';
  if (!selected.value.length) { err.value = '先勾选「转运中」的运单'; return; }
  try {
    await api.assignDelivery({ waybill_nos: selected.value, courier_id: courierId.value });
    msg.value = `已生成派件任务单：${selected.value.join('、')}`;
    selected.value = []; reload();
  } catch (e) { err.value = e.message; }
}
function openSign(t) {
  signing.value = t;
  signForm.value = { signer: '', sign_way: '本人签收' };
}
async function confirmSign() {
  err.value = ''; msg.value = '';
  try {
    await api.sign({ id: signing.value.id, ...signForm.value });
    msg.value = `签收成功：${signing.value.waybill_no}`;
    signing.value = null; reload();
  } catch (e) { err.value = e.message; }
}
async function markFail() {
  err.value = ''; msg.value = '';
  try {
    await api.failDelivery({ id: failForm.value.id, type: failForm.value.type, desc: failForm.value.desc });
    msg.value = '已登记派送异常，转入异常件处理';
    failForm.value = { id: null, type: '拒收', desc: '' }; reload();
  } catch (e) { err.value = e.message; }
}
</script>

<template>
  <div class="content">
    <div class="doc-head">
      <h1>派件任务与签收</h1>
      <span class="sub">delivery &amp; sign-off</span>
    </div>

    <div class="note ok" v-if="msg">{{ msg }}</div>
    <div class="note err" v-if="err">{{ err }}</div>

    <div class="form-row">
      <div class="field"><label>分配给快递员</label>
        <select v-model="courierId">
          <option :value="1">K1001 快递员小李</option>
          <option :value="2">K1002 快递员小王</option>
        </select>
      </div>
      <button class="btn" @click="assign">生成派件任务单</button>
      <span style="color: var(--slate); font-size: 12px;">勾选下方「转运中」运单</span>
    </div>

    <table class="doc" style="margin-bottom: 24px;">
      <thead>
        <tr><th>运单号</th><th>快递员</th><th>收件人</th><th>派送地址</th><th>状态</th><th>操作</th></tr>
      </thead>
      <tbody>
        <tr v-for="t in tasks" :key="t.id">
          <td class="mono">{{ t.waybill_no }}</td>
          <td>{{ t.courier }}</td>
          <td>{{ t.receiver }}</td>
          <td>{{ t.addr }}</td>
          <td><span class="tag" :class="t.status === '已签收' ? 'done' : t.status === '派送异常' ? 'fail' : t.status === '派送中' ? 'transit' : 'wait'">{{ t.status }}</span></td>
          <td>
            <template v-if="t.status !== '已签收' && t.status !== '派送异常'">
              <button class="btn small" @click="openSign(t)">签收</button>
              <button class="btn small ghost" style="margin-left:6px" @click="failForm = { id: t.id, type: '拒收', desc: '' }">派送异常</button>
            </template>
            <span v-else style="color: var(--slate); font-size: 12px;" class="mono">
              {{ t.signer ? t.signer + ' · ' + t.signed_at : '—' }}
            </span>
          </td>
        </tr>
        <tr v-if="!tasks.length"><td colspan="6" class="empty">暂无派件任务。</td></tr>
      </tbody>
    </table>

    <div class="doc-head" style="margin-top: 28px;">
      <h1>可分派运单（转运中）</h1>
      <span class="sub">in transit</span>
    </div>
    <table class="doc">
      <thead><tr><th>勾选</th><th>运单号</th><th>产品</th><th class="num">运费</th><th>状态</th><th>收件地址</th></tr></thead>
      <tbody>
        <tr v-for="w in waybills.filter(w => w.status === '转运中')" :key="w.id">
          <td><input type="checkbox" :value="w.waybill_no" v-model="selected" style="width:16px;min-width:0" aria-label="选择运单" /></td>
          <td class="mono">{{ w.waybill_no }}</td>
          <td>{{ w.product }}</td>
          <td class="num">{{ w.fee.toFixed(2) }}</td>
          <td><span class="tag transit">{{ w.status }}</span></td>
          <td>{{ w.recv_addr }}</td>
        </tr>
        <tr v-if="!waybills.some(w => w.status === '转运中')">
          <td colspan="6" class="empty">暂无转运中运单。先在「揽收登记」完成揽收，并在跟踪页补充分拣中转登记。</td>
        </tr>
      </tbody>
    </table>

    <!-- 电子签收 -->
    <div v-if="signing" class="note" style="margin-top: 24px; border-color: var(--ink);">
      <div style="font-weight:600; margin-bottom: 10px;">电子签收 · 运单 {{ signing.waybill_no }}（收件人 {{ signing.receiver }}）</div>
      <div class="form-row">
        <div class="field"><label>签收人</label><input v-model="signForm.signer" /></div>
        <div class="field"><label>签收方式</label>
          <select v-model="signForm.sign_way">
            <option>本人签收</option><option>代收（已登记证件）</option><option>快递柜 / 驿站投递</option>
          </select>
        </div>
        <button class="btn" @click="confirmSign">确认签收</button>
        <button class="btn ghost" @click="signing = null">取消</button>
      </div>
      <p style="font-size:12px; color:var(--slate)">代收须登记代收人姓名及证件后四位；投递快递柜须征得收件人同意。</p>
    </div>

    <!-- 派送异常 -->
    <div v-if="failForm.id" class="note err" style="margin-top: 16px;">
      <div style="font-weight:600; margin-bottom: 10px;">登记派送异常</div>
      <div class="form-row">
        <div class="field"><label>异常类型</label>
          <select v-model="failForm.type">
            <option>拒收</option><option>破损</option><option>丢失</option><option>联系不上</option>
          </select>
        </div>
        <div class="field"><label>情况说明</label><input v-model="failForm.desc" style="min-width:240px" /></div>
        <button class="btn" @click="markFail">提交登记</button>
        <button class="btn ghost" @click="failForm = { id: null, type: '拒收', desc: '' }">取消</button>
      </div>
    </div>
  </div>
</template>

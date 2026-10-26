<script setup>
import { ref, onMounted } from 'vue';
import { api } from '../api.js';

const rows = ref([]);
const waybills = ref([]);
const flights = ref([]);
const sorters = ref([]);
const msg = ref('');
const err = ref('');
const form = ref({ waybill_no: '', node: '杭州分拣中心', next_hop: '', flight: '', operator_id: null, security: true });
const routeInfo = ref(null);

async function onWaybillChange() {
  routeInfo.value = null;
  if (!form.value.waybill_no) return;
  try {
    const r = await api.transitSuggest(form.value.waybill_no);
    routeInfo.value = r;
    if (!form.value.next_hop) form.value.next_hop = r.next_hop ?? '';
  } catch (e) { err.value = e.message; }
}

const reload = async () => {
  rows.value = await api.transitList();
  waybills.value = (await api.waybills()).filter((w) => ['已揽收', '转运中'].includes(w.status));
  flights.value = await api.flights();
  sorters.value = await api.employees('sorter');
  if (!form.value.operator_id && sorters.value.length) form.value.operator_id = sorters.value[0].id;
};
onMounted(reload);

async function submit() {
  msg.value = ''; err.value = '';
  try {
    await api.transit({ ...form.value, operator_id: form.value.operator_id ?? null });
    msg.value = `中转登记成功：${form.value.waybill_no} → 下一跳 ${form.value.next_hop || '—'}`;
    form.value.next_hop = '';
    form.value.waybill_no = '';
    reload();
  } catch (e) { err.value = e.message; }
}
</script>

<template>
  <div class="content">
    <div class="doc-head">
      <h1>分拣中转登记</h1>
      <span class="sub">sorting &amp; handover</span>
    </div>

    <div class="note ok" v-if="msg">{{ msg }}</div>
    <div class="note err" v-if="err">{{ err }}</div>

    <table class="doc" style="margin-bottom: 24px;">
      <thead>
        <tr><th>运单号</th><th>产品</th><th>运单状态</th><th>当前节点</th><th>下一跳</th><th>航班</th><th>操作员</th><th>登记时间</th></tr>
      </thead>
      <tbody>
        <tr v-for="r in rows" :key="r.id">
          <td class="mono">{{ r.waybill_no }}</td>
          <td>{{ r.product ?? '—' }}</td>
          <td><span class="tag" :class="r.waybill_status === '已揽收' ? 'wait' : 'transit'">{{ r.waybill_status }}</span></td>
          <td>{{ r.node }}</td>
          <td>{{ r.next_hop || '—' }}</td>
          <td class="mono">{{ r.flight || '—' }}</td>
          <td>{{ r.operator ?? '—' }}</td>
          <td class="mono">{{ r.created_at }}</td>
        </tr>
        <tr v-if="!rows.length"><td colspan="8" class="empty">暂无中转记录。先在「揽收登记」生成运单。</td></tr>
      </tbody>
    </table>

    <div class="doc-head" style="margin-top: 28px;">
      <h1>登记一次中转</h1>
      <span class="sub">scan · route · handover</span>
    </div>

    <div class="form-row">
      <div class="field"><label>运单号</label>
        <select v-model="form.waybill_no" @change="onWaybillChange">
          <option value="" disabled>选择已揽收/在途运单</option>
          <option v-for="w in waybills" :key="w.waybill_no" :value="w.waybill_no">
            {{ w.waybill_no }} · {{ w.product }} · {{ w.status }}
          </option>
        </select>
      </div>
      <div class="field"><label>当前节点</label><input v-model="form.node" style="min-width:180px" /></div>
      <div class="field"><label>下一跳</label><input v-model="form.next_hop" placeholder="如 上海虹桥货站" style="min-width:180px" /></div>
      <div class="field"><label>航班</label>
        <select v-model="form.flight">
          <option value="">（陆运，不填）</option>
          <option v-for="f in flights" :key="f.flight_no" :value="f.flight_no">
            {{ f.flight_no }} {{ f.dep }}→{{ f.arr }}
          </option>
        </select>
      </div>
      <div class="field"><label>分拣操作员</label>
        <select v-model="form.operator_id">
          <option :value="null">未指定</option>
          <option v-for="s in sorters" :key="s.id" :value="s.id">{{ s.name }}</option>
        </select>
      </div>
      <div class="field"><label>航空安检</label>
        <label style="display:flex;align-items:center;gap:6px;height:34px;font-weight:400">
          <input type="checkbox" v-model="form.security" style="width:16px;min-width:0" /> 已通过安检
        </label>
      </div>
      <button class="btn" :disabled="!form.waybill_no || !form.node" @click="submit">提交中转登记</button>
    </div>
    <p style="color: var(--slate); font-size: 12px;">
      航班号必须来自航班主数据（当前 {{ flights.length }} 条），否则视为无效航班；状态为「已揽收」的运单登记中转后自动转为「转运中」，其余非法状态会被状态机拒绝。
    </p>

    <div v-if="routeInfo" class="note" style="background:#f7f9fc;border:1px solid #dfe4ea;padding:12px 14px">
      <div style="font-weight:600;margin-bottom:6px">
        分拨路由建议：{{ routeInfo.mode_label }}　·　{{ routeInfo.zone_label }}　·　ETA 约 {{ routeInfo.eta_hours ?? '—' }} 小时
      </div>
      <div style="font-size:13px;margin-bottom:6px">下一跳：<b>{{ routeInfo.next_hop }}</b></div>
      <div style="font-size:12px;color:var(--slate)">
        判定依据：
        <span v-for="(r, i) in routeInfo.rules" :key="i" style="display:block">· {{ r }}</span>
      </div>
    </div>
  </div>
</template>

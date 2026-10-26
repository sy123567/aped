<script setup>
import { ref } from 'vue';
import { api, tag, session } from '../api.js';

const no = ref('');
const result = ref(null);
const err = ref('');
const transit = ref({ waybill_no: '', node: '', next_hop: '', flight: '' });
const msg = ref('');
// 中转登记接口只对调度/快递员开放（理赔、运营、客户、财务调用会被后端 403），按角色隐藏表单
const canTransit = ['admin', 'dispatcher', 'courier'].includes(session.user?.role);

async function query() {
  err.value = ''; result.value = null;
  try {
    result.value = await api.tracking(no.value);
    transit.value = { waybill_no: no.value, node: '', next_hop: '', flight: '' };
  } catch (e) { err.value = e.message; }
}
async function submitTransit() {
  err.value = ''; msg.value = '';
  try {
    await api.transit({ ...transit.value });
    msg.value = `分拣中转登记成功：${transit.value.waybill_no}`;
    query();
  } catch (e) { err.value = e.message; }
}
</script>

<template>
  <div class="content">
    <div class="doc-head">
      <h1>运单跟踪</h1>
      <span class="sub">tracking &amp; transit log</span>
    </div>

    <div class="form-row">
      <div class="field"><label>运单号</label>
        <input v-model="no" placeholder="8801234567890" style="min-width: 220px; font-family: var(--mono);" @keyup.enter="query" />
      </div>
      <button class="btn" @click="query">查询轨迹</button>
    </div>

    <div class="note err" v-if="err">{{ err }}</div>
    <div class="note ok" v-if="msg">{{ msg }}</div>

    <template v-if="result">
      <div class="grid2">
        <div>
          <div class="doc-head"><h1 style="font-size:15px">运单信息</h1></div>
          <table class="doc">
            <tbody>
              <tr><td style="width:110px; color:var(--slate)">运单号</td><td class="mono">{{ no }}</td></tr>
              <tr><td style="color:var(--slate)">当前状态</td><td v-html="tag(result.waybill?.status ?? '未揽收')"></td></tr>
              <tr><td style="color:var(--slate)">产品时效</td><td>{{ result.waybill?.product ?? '—' }}</td></tr>
              <tr><td style="color:var(--slate)">运费</td><td class="mono">{{ result.waybill ? '¥' + Number(result.waybill.fee ?? 0).toFixed(2) : '—' }}</td></tr>
              <tr><td style="color:var(--slate)">收件人</td><td>{{ result.order?.recv_name ?? '—' }}</td></tr>
              <tr><td style="color:var(--slate)">收件地址</td><td>{{ result.order?.recv_addr ?? '—' }}</td></tr>
              <tr v-if="result.waybill"><td style="color:var(--slate)">承诺送达</td><td class="mono">{{ result.waybill.promise_time ?? '—' }}<span v-if="result.on_time != null">（{{ result.on_time ? '准时' : '超时' }}）</span></td></tr>
            </tbody>
          </table>

          <template v-if="canTransit">
            <div class="doc-head" style="margin-top: 24px;"><h1 style="font-size:15px">分拣中转登记</h1></div>
            <div class="form-row">
              <div class="field"><label>当前节点</label><input v-model="transit.node" placeholder="杭州萧山货站" /></div>
              <div class="field"><label>下一跳</label><input v-model="transit.next_hop" placeholder="上海虹桥货站" /></div>
              <div class="field"><label>航班号</label><input v-model="transit.flight" placeholder="MU5101（须在航班主数据中）" /></div>
              <button class="btn" @click="submitTransit">登记中转</button>
            </div>
          </template>
          <p v-else style="font-size:12px;color:var(--slate);margin-top:16px">当前角色仅可查询轨迹；分拣中转登记请由调度/快递员在「分拣中转」页操作。</p>
        </div>

        <div>
          <div class="doc-head"><h1 style="font-size:15px">全程轨迹</h1><span class="sub">{{ result.events.length }} 条记录</span></div>
          <table class="doc">
            <thead><tr><th>时间</th><th>节点</th><th>事件</th></tr></thead>
            <tbody>
              <tr v-for="(e, i) in [...result.events].reverse()" :key="i">
                <td class="mono" style="white-space:nowrap">{{ e.created_at }}</td>
                <td>{{ e.node }}</td>
                <td>{{ e.desc_text }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>
  </div>
</template>

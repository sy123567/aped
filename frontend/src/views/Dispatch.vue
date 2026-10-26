<script setup>
import { ref, onMounted } from 'vue';
import { api } from '../api.js';

const pool = ref([]);
const cap = ref(null);
const rule = ref(null);
const selected = ref([]);
const result = ref(null);
const msg = ref('');
const err = ref('');
const busy = ref(false);

const reload = async () => {
  const all = await api.orders();
  pool.value = all.filter((o) => o.status === '待派单');
  cap.value = await api.capacity();
  selected.value = selected.value.filter((id) => pool.value.some((o) => o.id === id));
};

onMounted(async () => {
  await reload();
  try { rule.value = await api.dispatchRule(); } catch { rule.value = null; }
});

async function assign() {
  err.value = ''; msg.value = ''; result.value = null;
  if (!selected.value.length) { err.value = '先勾选要分配的订单'; return; }
  busy.value = true;
  try {
    const d = await api.assignPickup({ order_ids: selected.value });
    result.value = d;
    msg.value = `已分配 ${d.assigned} 单${d.unassigned?.length ? `，${d.unassigned.length} 单未分配` : ''}`;
    selected.value = [];
    await reload();
  } catch (e) {
    err.value = e.message;
  } finally {
    busy.value = false;
  }
}

const toggleAll = (e) => { selected.value = e.target.checked ? pool.value.map((o) => o.id) : []; };
</script>

<template>
  <div class="content">
    <div class="doc-head">
      <h1>派单调度</h1>
      <span class="sub">按片区与负载自动定人</span>
    </div>

    <div class="note ok" v-if="msg">{{ msg }}</div>
    <div class="note err" v-if="err">{{ err }}</div>

    <div v-if="cap" style="display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:12px;margin-bottom:22px;">
      <div style="border:1px solid #dfe4ea;border-left:3px solid #2f6fed;padding:12px 14px;">
        <div style="font-size:11px;letter-spacing:.08em;color:#6b7684">待派单</div>
        <div style="font-size:24px;font-weight:600">{{ cap.pending_orders }}</div>
      </div>
      <div style="border:1px solid #dfe4ea;border-left:3px solid #1f9d6b;padding:12px 14px;">
        <div style="font-size:11px;letter-spacing:.08em;color:#6b7684">运力占用</div>
        <div style="font-size:24px;font-weight:600">{{ cap.utilization }}%</div>
        <div style="font-size:11px;color:#6b7684">{{ cap.total_load }} / {{ cap.total_capacity }} 单</div>
      </div>
      <div style="border:1px solid #dfe4ea;border-left:3px solid #6b7684;padding:12px 14px;">
        <div style="font-size:11px;letter-spacing:.08em;color:#6b7684">今日已派</div>
        <div style="font-size:24px;font-weight:600">{{ cap.today_assigned }}</div>
      </div>
      <div style="border:1px solid #dfe4ea;border-left:3px solid #c2410c;padding:12px 14px;">
        <div style="font-size:11px;letter-spacing:.08em;color:#6b7684">满载人员</div>
        <div style="font-size:24px;font-weight:600">{{ cap.couriers.filter(c => c.full).length }}</div>
        <div style="font-size:11px;color:#6b7684">共 {{ cap.couriers.length }} 人</div>
      </div>
    </div>

    <div class="doc-head" style="margin-bottom: 10px;">
      <h1 style="font-size:15px">待派单池</h1>
      <span class="sub">{{ pool.length }} 单</span>
    </div>
    <table class="doc" style="margin-bottom: 14px;">
      <thead>
        <tr>
          <th style="width:36px"><input type="checkbox" style="width:15px;min-width:0" aria-label="全选" @change="toggleAll" /></th>
          <th>订单号</th><th>收件地址</th><th>品类</th><th class="num">预估(kg)</th><th>取件时段</th><th>下单时间</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="o in pool" :key="o.id">
          <td><input type="checkbox" :value="o.id" v-model="selected" style="width:15px;min-width:0" :aria-label="`选择 ${o.order_no}`" /></td>
          <td class="mono">{{ o.order_no }}</td>
          <td>{{ o.recv_addr }}</td>
          <td>{{ o.category }}</td>
          <td class="num">{{ o.weight ?? '—' }}</td>
          <td class="mono">{{ o.slot }}</td>
          <td class="mono">{{ o.created_at }}</td>
        </tr>
        <tr v-if="!pool.length"><td colspan="7" class="empty">没有待派单订单。先去「客户下单」建单。</td></tr>
      </tbody>
    </table>

    <div class="form-row" style="margin-bottom: 12px;">
      <button class="btn" :disabled="busy || !selected.length" @click="assign">
        {{ busy ? '分配中…' : `一键自动分配（${selected.length} 单）` }}
      </button>
      <span style="color:var(--slate);font-size:12px">不需要选人——规则在数据库里，代码只执行</span>
    </div>

    <div v-if="rule" style="border:1px solid #dfe4ea;background:#f9fafb;padding:12px 14px;margin-bottom:22px;">
      <div style="font-size:13px;font-weight:600;margin-bottom:4px;">分配规则：{{ rule.name }}</div>
      <div style="font-size:12px;color:#6b7684;margin-bottom:6px;">{{ rule.summary }}</div>
      <div style="font-size:12px;color:#6b7684;">
        <span v-for="(s, i) in rule.steps" :key="i" style="display:block">· {{ s }}</span>
      </div>
      <details style="margin-top:8px;">
        <summary style="font-size:12px;color:#2f6fed;cursor:pointer;">查看规则 SQL</summary>
        <pre style="font-size:11px;background:#fff;border:1px solid #e6eaf0;padding:10px;overflow:auto;white-space:pre-wrap;">{{ rule.sql }}</pre>
      </details>
    </div>

    <table class="doc" v-if="result && result.assignments && result.assignments.length" style="margin-bottom: 10px;">
      <thead>
        <tr><th>订单号</th><th>收件地址</th><th>指派给</th><th>片区</th><th class="num">派前在途</th><th>依据</th></tr>
      </thead>
      <tbody>
        <tr v-for="a in result.assignments" :key="a.order_no">
          <td class="mono">{{ a.order_no }}</td>
          <td>{{ a.recv_addr }}</td>
          <td>{{ a.courier }}</td>
          <td class="mono">{{ a.grid_code }}</td>
          <td class="num">{{ a.load_before }} / {{ a.capacity }}</td>
          <td style="font-size:12px;color:var(--slate)">{{ a.reason }}</td>
        </tr>
      </tbody>
    </table>
    <p v-if="result && result.unassigned && result.unassigned.length" style="color:#c2410c;font-size:12px">
      未分配：<span v-for="u in result.unassigned" :key="u.order_no ?? u.order_id" style="margin-right:12px">{{ u.order_no ?? u.order_id }}（{{ u.why }}）</span>
    </p>

    <div class="doc-head" style="margin: 26px 0 10px;">
      <h1 style="font-size:15px">运力明细</h1>
      <span class="sub">load / capacity</span>
    </div>
    <table class="doc">
      <thead><tr><th>工号</th><th>快递员</th><th>片区</th><th class="num">在途</th><th class="num">上限</th><th class="num">利用率</th><th>状态</th></tr></thead>
      <tbody>
        <tr v-for="c in (cap?.couriers ?? [])" :key="c.id">
          <td class="mono">{{ c.code }}</td>
          <td>{{ c.name }}</td>
          <td class="mono">{{ c.grid_code || '—' }}</td>
          <td class="num">{{ c.load }}</td>
          <td class="num">{{ c.capacity }}</td>
          <td class="num">{{ c.util }}%</td>
          <td><span class="tag" :class="c.full ? 'fail' : c.load > 0 ? 'transit' : 'wait'">{{ c.full ? '满载' : c.load > 0 ? '在途' : '空闲' }}</span></td>
        </tr>
        <tr v-if="!cap || !cap.couriers.length"><td colspan="7" class="empty">没有在册快递员</td></tr>
      </tbody>
    </table>
  </div>
</template>

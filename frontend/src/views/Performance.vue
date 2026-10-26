<script setup>
import { ref, onMounted } from 'vue';
import { api } from '../api.js';

const period = ref(new Date().toISOString().slice(0, 7));
const data = ref(null);
const rows = ref([]);
const weights = ref([]);
const method = ref([]);
const msg = ref('');
const err = ref('');
const loading = ref(false);
const expanded = ref(null);

async function load() {
  err.value = ''; msg.value = ''; loading.value = true;
  try {
    const d = await api.performance(period.value);
    data.value = d;
    rows.value = d.rows ?? [];
    weights.value = d.weights ?? [];
    method.value = d.method ?? [];
  } catch (e) { err.value = e.message; rows.value = []; }
  finally { loading.value = false; }
}
onMounted(load);

function download(type) {
  window.open(api.exportUrl(type, 'csv', period.value), '_blank');
}
const pct = (v) => (v == null ? '—' : v + '%');
</script>

<template>
  <div class="content">
    <div class="doc-head">
      <h1>绩效统计与排名</h1>
      <span class="sub">performance · KPI</span>
    </div>

    <div class="note ok" v-if="msg">{{ msg }}</div>
    <div class="note err" v-if="err">{{ err }}</div>

    <div class="form-row">
      <div class="field"><label>统计周期</label>
        <input v-model="period" placeholder="2026-10" style="font-family: var(--mono)" @keyup.enter="load" />
      </div>
      <button class="btn" :disabled="loading" @click="load">{{ loading ? '统计中…' : '重新统计' }}</button>
      <button class="btn ghost" @click="download('performance')">导出绩效 CSV</button>
      <button class="btn ghost" @click="download('waybills')">导出运单台账 CSV</button>
    </div>

    <!-- 权重说明：得分怎么来的必须说清楚，否则排名不可信 -->
    <div v-if="weights.length" style="border:1px solid #dfe4ea;background:#f9fafb;padding:12px 14px;margin-bottom:18px;">
      <div style="font-size:13px;font-weight:600;margin-bottom:6px;">评分权重（权重和 = 1）</div>
      <table class="doc" style="margin-bottom:8px;">
        <thead><tr><th>指标</th><th class="num">权重</th><th>方向</th></tr></thead>
        <tbody>
          <tr v-for="w in weights" :key="w.metric">
            <td>{{ w.label }}</td>
            <td class="num">{{ (w.weight * 100).toFixed(0) }}%</td>
            <td>{{ w.direction === -1 ? '逆向（越低越好）' : '正向（越高越好）' }}</td>
          </tr>
        </tbody>
      </table>
      <div style="font-size:12px;color:var(--slate)">
        <span v-for="(m, i) in method" :key="i" style="display:block">· {{ m }}</span>
      </div>
    </div>

    <table class="doc">
      <thead>
        <tr><th class="num">排名</th><th>快递员</th><th>片区</th><th class="num">揽收数</th><th class="num">签收数</th><th class="num">异常数</th><th class="num">准时率</th><th class="num">异常率</th><th class="num">绩效分</th><th>等级</th><th></th></tr>
      </thead>
      <tbody>
        <template v-for="r in rows" :key="r.code ?? r.courier">
          <tr>
            <td class="num" style="font-weight:700">{{ r.rank ?? '—' }}</td>
            <td>{{ r.courier }}<span class="mono" style="color:var(--slate);font-size:11px"> {{ r.code }}</span></td>
            <td class="mono">{{ r.grid_code || '—' }}</td>
            <td class="num">{{ r.pickups }}</td>
            <td class="num">{{ r.deliveries }}</td>
            <td class="num" :style="{ color: r.exceptions ? 'var(--signal)' : 'inherit' }">{{ r.exceptions }}</td>
            <td class="num">{{ pct(r.on_time_rate) }}</td>
            <td class="num">{{ pct(r.exception_rate) }}</td>
            <td class="num" style="font-weight:700">{{ r.score ?? '—' }}</td>
            <td>{{ r.grade ?? '—' }}</td>
            <td>
              <button class="btn small ghost" @click="expanded = expanded === r.code ? null : r.code">
                {{ expanded === r.code ? '收起拆解' : '得分拆解' }}
              </button>
            </td>
          </tr>
          <tr v-if="r.exclude_reason">
            <td></td>
            <td colspan="10" style="font-size:12px;color:#c2410c">未入排名：{{ r.exclude_reason }}</td>
          </tr>
          <tr v-if="expanded === r.code && r.breakdown">
            <td></td>
            <td colspan="10">
              <table class="doc">
                <thead><tr><th>指标</th><th class="num">原始值</th><th class="num">归一化得分</th><th class="num">权重</th><th class="num">贡献分</th></tr></thead>
                <tbody>
                  <tr v-for="b in r.breakdown" :key="b.metric">
                    <td>{{ b.label }}</td>
                    <td class="num">{{ b.raw ?? '—' }}</td>
                    <td class="num">{{ b.score ?? '—' }}</td>
                    <td class="num">{{ (b.weight * 100).toFixed(0) }}%</td>
                    <td class="num">{{ b.contrib ?? '—' }}</td>
                  </tr>
                </tbody>
              </table>
            </td>
          </tr>
        </template>
        <tr v-if="!rows.length && !loading"><td colspan="11" class="empty">暂无绩效数据（尚无快递员完成揽收/签收作业）。</td></tr>
        <tr v-if="loading"><td colspan="11" class="empty">加载中…</td></tr>
      </tbody>
    </table>

    <p style="font-size:12px; color: var(--slate); margin-top: 12px;">
      口径：各指标先 min-max 归一化到 0~100 再按权重加权（见上方权重说明）；作业样本低于 {{ data?.min_sample ?? 3 }} 单不参与排名并注明原因。CSV 为 UTF-8 BOM，Excel 可直接打开；导出经登录 token 鉴权。
    </p>
  </div>
</template>

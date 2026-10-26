<script setup>
import { ref, onMounted } from 'vue';
import { api, fmt } from '../api.js';

const period = ref(new Date().toISOString().slice(0, 7));
const sum = ref(null);
const perf = ref([]);
const types = [
  { k: 'performance', t: '绩效排名' },
  { k: 'waybills', t: '运单明细' },
  { k: 'bills', t: '月结账单' },
  { k: 'exceptions', t: '异常与理赔' },
  { k: 'transit', t: '分拣中转' },
];
const type = ref('performance');
const preview = ref(null);
const err = ref('');

async function load() {
  err.value = '';
  try {
    sum.value = await api.summary(period.value);
    perf.value = (await api.performance(period.value)).rows;
    preview.value = await api.preview(type.value, period.value);
  } catch (e) { err.value = e.message; }
}
onMounted(load);

async function switchType() {
  try { preview.value = await api.preview(type.value, period.value); } catch (e) { err.value = e.message; }
}

function download(format) {
  window.open(api.exportUrl(type.value, format, period.value), '_blank');
}

const pct = (v) => (v == null ? '—' : v + '%');
</script>

<template>
  <div class="content">
    <div class="doc-head">
      <h1>运营统计与报表导出</h1>
      <span class="sub">operations report · export</span>
    </div>

    <div class="note err" v-if="err">{{ err }}</div>

    <div class="form-row" style="margin-bottom: 20px;">
      <div class="field"><label>统计账期</label><input v-model="period" placeholder="YYYY-MM" class="mono" /></div>
      <button class="btn" @click="load">重新统计</button>
      <span style="color:var(--slate);font-size:12px">默认当月；账期格式 YYYY-MM</span>
    </div>

    <div v-if="sum" style="display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px;margin-bottom:24px;">
      <div style="border:1px solid #dfe4ea;border-left:3px solid #2f6fed;padding:12px 14px;">
        <div style="font-size:11px;letter-spacing:.08em;color:#6b7684">运单量</div>
        <div style="font-size:24px;font-weight:600">{{ sum.waybills }}</div>
      </div>
      <div style="border:1px solid #dfe4ea;border-left:3px solid #2f6fed;padding:12px 14px;">
        <div style="font-size:11px;letter-spacing:.08em;color:#6b7684">运费收入</div>
        <div style="font-size:24px;font-weight:600">{{ fmt(sum.revenue) }}</div>
      </div>
      <div style="border:1px solid #dfe4ea;border-left:3px solid #1f9d6b;padding:12px 14px;">
        <div style="font-size:11px;letter-spacing:.08em;color:#6b7684">准时率</div>
        <div style="font-size:24px;font-weight:600">{{ pct(sum.on_time_rate) }}</div>
        <div style="font-size:11px;color:#6b7684">准时 {{ sum.on_time }} / 签收 {{ sum.signed }}</div>
      </div>
      <div style="border:1px solid #dfe4ea;border-left:3px solid #c2410c;padding:12px 14px;">
        <div style="font-size:11px;letter-spacing:.08em;color:#6b7684">超时件</div>
        <div style="font-size:24px;font-weight:600">{{ sum.late }}</div>
      </div>
      <div style="border:1px solid #dfe4ea;border-left:3px solid #c2410c;padding:12px 14px;">
        <div style="font-size:11px;letter-spacing:.08em;color:#6b7684">异常件</div>
        <div style="font-size:24px;font-weight:600">{{ sum.exceptions }}</div>
      </div>
      <div style="border:1px solid #dfe4ea;border-left:3px solid #6b7684;padding:12px 14px;">
        <div style="font-size:11px;letter-spacing:.08em;color:#6b7684">已赔付</div>
        <div style="font-size:24px;font-weight:600">{{ fmt(sum.claims_amount) }}</div>
        <div style="font-size:11px;color:#6b7684">{{ sum.claims_paid }} 单</div>
      </div>
      <div style="border:1px solid #dfe4ea;border-left:3px solid #6b7684;padding:12px 14px;">
        <div style="font-size:11px;letter-spacing:.08em;color:#6b7684">账单已收 / 应收</div>
        <div style="font-size:20px;font-weight:600">{{ fmt(sum.bill_paid) }}</div>
        <div style="font-size:11px;color:#6b7684">{{ fmt(sum.bill_amount) }} · {{ sum.bills }} 张</div>
      </div>
    </div>

    <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:18px;margin-bottom:26px;" v-if="sum">
      <div>
        <table class="doc">
          <thead><tr><th>产品</th><th class="num">运单数</th><th class="num">运费</th></tr></thead>
          <tbody>
            <tr v-for="r in sum.by_product" :key="r.product">
              <td>{{ r.product || '—' }}</td><td class="num">{{ r.cnt }}</td><td class="num">{{ fmt(r.fee) }}</td>
            </tr>
            <tr v-if="!sum.by_product.length"><td colspan="3" class="empty">本期无运单</td></tr>
          </tbody>
        </table>
      </div>
      <div>
        <table class="doc">
          <thead><tr><th>运单状态</th><th class="num">数量</th></tr></thead>
          <tbody>
            <tr v-for="r in sum.by_status" :key="r.status"><td>{{ r.status }}</td><td class="num">{{ r.cnt }}</td></tr>
          </tbody>
        </table>
      </div>
      <div>
        <table class="doc">
          <thead><tr><th>异常类型</th><th class="num">数量</th></tr></thead>
          <tbody>
            <tr v-for="r in sum.by_exception" :key="r.type"><td>{{ r.type }}</td><td class="num">{{ r.cnt }}</td></tr>
            <tr v-if="!sum.by_exception.length"><td colspan="2" class="empty">本期无异常</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="doc-head" style="margin-top: 26px;">
      <h1>快递员揽派绩效 {{ period }}</h1>
      <span class="sub">ranking · on-time rate</span>
    </div>
    <table class="doc" style="margin-bottom: 26px;">
      <thead>
        <tr><th class="num">排名</th><th>工号</th><th>快递员</th><th>片区</th><th class="num">揽收</th><th class="num">签收</th><th class="num">异常</th><th class="num">准时率</th><th class="num">综合得分</th></tr>
      </thead>
      <tbody>
        <tr v-for="r in perf" :key="r.code">
          <td class="num">{{ r.rank }}</td>
          <td class="mono">{{ r.code }}</td>
          <td>{{ r.courier }}</td>
          <td class="mono">{{ r.grid_code || '—' }}</td>
          <td class="num">{{ r.pickups }}</td>
          <td class="num">{{ r.deliveries }}</td>
          <td class="num">{{ r.exceptions }}</td>
          <td class="num">{{ pct(r.on_time_rate) }}</td>
          <td class="num">{{ r.score }}</td>
        </tr>
        <tr v-if="!perf.length"><td colspan="9" class="empty">暂无快递员绩效数据</td></tr>
      </tbody>
    </table>

    <div class="doc-head" style="margin-top: 26px;">
      <h1>报表导出</h1>
      <span class="sub">csv · excel</span>
    </div>
    <div class="form-row" style="margin-bottom: 14px;">
      <div class="field"><label>报表类型</label>
        <select v-model="type" @change="switchType">
          <option v-for="t in types" :key="t.k" :value="t.k">{{ t.t }}</option>
        </select>
      </div>
      <button class="btn" @click="download('csv')">导出 CSV</button>
      <button class="btn" @click="download('xls')">导出 Excel</button>
    </div>

    <table class="doc" v-if="preview">
      <thead><tr><th v-for="c in preview.cols" :key="c">{{ c }}</th></tr></thead>
      <tbody>
        <tr v-for="(r, i) in preview.rows.slice(0, 50)" :key="i">
          <td v-for="(c, j) in r" :key="j">{{ c }}</td>
        </tr>
        <tr v-if="!preview.rows.length"><td :colspan="preview.cols.length" class="empty">该报表本期无数据</td></tr>
      </tbody>
    </table>
    <p style="color:var(--slate);font-size:12px" v-if="preview && preview.rows.length > 50">
      预览仅显示前 50 行，共 {{ preview.total }} 行；完整数据请导出。
    </p>
  </div>
</template>

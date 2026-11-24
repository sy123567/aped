<script setup>
/* v8 · M8：工作台大时钟独立成小组件——每秒 tick 只重渲染自己，
   不再连带整个 Dashboard（含 SVG 树）每秒 diff 一次。 */
import { ref, onMounted, onUnmounted } from 'vue';

const clock = ref('');
const date = ref('');
let timer;
const tick = () => {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  clock.value = `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
  date.value = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
};
onMounted(() => { tick(); timer = setInterval(tick, 1000); });
onUnmounted(() => clearInterval(timer));
</script>

<template>
  <div class="st-clock">
    <b>{{ clock }}</b>
    <span>{{ date }} · CST</span>
  </div>
</template>

<style scoped>
.st-clock { display: flex; align-items: baseline; gap: 14px; margin-top: 20px; }
.st-clock b { font-family: var(--mono); font-size: 31px; font-weight: 600; font-variant-numeric: tabular-nums; letter-spacing: .02em; text-shadow: 0 1px 14px rgba(7, 9, 13, .9); }
.st-clock span { font-family: var(--mono); font-size: 11.5px; letter-spacing: .16em; color: var(--tx-2); }
</style>

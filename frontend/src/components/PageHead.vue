<script setup>
/* ================= v10 · 内页统一页头（PageHead） =================
   语法：超大代号描边字作背景水印（右上，出血不压字）+ 代号 kicker + 中文大标题 + 右侧实时计数，
   底部一条发丝线收束。与工作台「实心+描边」标题语法、登录页描边行同源——
   从工作台点进任何一页，仍是同一个品牌世界。计数由各页传入自己的主列表长度，不在此取数。 */
defineProps({
  code: { type: String, required: true },
  title: { type: String, required: true },
  sub: { type: String, default: '' },
  count: { type: [Number, String], default: null },
  countLabel: { type: String, default: '' },
});
</script>

<template>
  <header class="page-head">
    <span class="ph-ghost" aria-hidden="true">{{ code }}</span>
    <div class="ph-main">
      <p class="ph-kicker"><i aria-hidden="true"></i>{{ code }}<span v-if="sub" class="ph-sub">{{ sub }}</span></p>
      <h1 class="ph-title">{{ title }}</h1>
    </div>
    <div class="ph-side">
      <div v-if="count !== null && count !== undefined" class="ph-count">
        <b>{{ count }}</b><small>{{ countLabel }}</small>
      </div>
      <slot />
    </div>
  </header>
</template>

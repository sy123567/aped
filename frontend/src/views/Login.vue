<script setup>
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { api, session } from '../api.js';

const router = useRouter();
const form = ref({ username: '', password: '' });
const err = ref('');
const loading = ref(false);

const ROLE_TEXT = {
  admin: '全部模块',
  customer: '我的账户 · 下单 · 运单跟踪',
  dispatcher: '下单 · 派单 · 揽收 · 中转 · 派送',
  courier: '揽收 · 派送 · 轨迹',
  claims: '异常 · 理赔',
  finance: '账单 · 客户 · 报表',
  ops: '绩效 · 报表',
};
const ACCOUNTS = [
  { u: 'zhangsan', r: 'customer' }, { u: 'lisi', r: 'customer' }, { u: 'wangwu', r: 'customer' },
  { u: 'admin', r: 'admin' }, { u: 'dispatch', r: 'dispatcher' }, { u: 'courier', r: 'courier' },
  { u: 'claims', r: 'claims' }, { u: 'finance', r: 'finance' }, { u: 'ops', r: 'ops' },
];

async function submit() {
  err.value = '';
  if (!form.value.username || !form.value.password) { err.value = '请输入账号和密码'; return; }
  loading.value = true;
  try {
    const d = await api.login(form.value.username.trim(), form.value.password);
    session.token = d.token;
    session.user = d.user;
    router.push('/');
  } catch (e) {
    err.value = e.message;
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div style="min-height:100vh;display:flex;align-items:center;justify-content:center;background:#f4f6f9;">
    <div style="width:380px;background:#fff;border:1px solid #dfe4ea;padding:32px 30px;">
      <div style="display:flex;align-items:center;gap:10px;margin-bottom:22px;">
        <div style="width:36px;height:36px;background:#1c2230;color:#fff;display:flex;align-items:center;justify-content:center;font-size:18px;">✈</div>
        <div>
          <div style="font-size:15px;font-weight:600;color:#1c2230;">航空快递揽收与派送</div>
          <div style="font-size:11px;letter-spacing:.08em;color:#9aa4b2;">AEPDS · SIGN IN</div>
        </div>
      </div>

      <div v-if="err" style="background:#fdecec;border:1px solid #f5c2c2;color:#a52020;padding:8px 10px;font-size:13px;margin-bottom:14px;">{{ err }}</div>

      <div style="margin-bottom:12px;">
        <label style="display:block;font-size:12px;color:#6b7684;margin-bottom:4px;">账号</label>
        <input v-model="form.username" placeholder="admin / dispatch / courier / claims / finance / ops"
               style="width:100%;box-sizing:border-box;border:1px solid #dfe4ea;padding:8px 10px;font-size:14px;" />
      </div>
      <div style="margin-bottom:18px;">
        <label style="display:block;font-size:12px;color:#6b7684;margin-bottom:4px;">密码</label>
        <input v-model="form.password" type="password" placeholder="初始密码 123456" @keyup.enter="submit"
               style="width:100%;box-sizing:border-box;border:1px solid #dfe4ea;padding:8px 10px;font-size:14px;" />
      </div>

      <button :disabled="loading" @click="submit"
              style="width:100%;background:#1c2230;color:#fff;border:0;padding:10px;font-size:14px;cursor:pointer;">
        {{ loading ? '登录中…' : '登录' }}
      </button>

      <div style="margin-top:20px;padding-top:14px;border-top:1px solid #eef1f5;">
        <div style="font-size:11px;letter-spacing:.08em;color:#9aa4b2;margin-bottom:8px;">账号与可见模块</div>
        <div v-for="a in ACCOUNTS" :key="a.u" style="display:flex;justify-content:space-between;font-size:12px;color:#6b7684;padding:2px 0;">
          <span style="font-family:ui-monospace,monospace">{{ a.u }}</span>
          <span>{{ ROLE_TEXT[a.r] }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

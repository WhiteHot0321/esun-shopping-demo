<template>
  <section class="card auth-card">
    <div v-if="!authenticated">
      <h2>{{ mode === 'login' ? '會員登入' : '會員註冊' }}</h2>
      <div class="form-row">
        <input v-model.trim="form.email" type="email" placeholder="Email" autocomplete="email" />
        <input v-model="form.password" type="password"
          :placeholder="mode === 'register' ? '密碼（至少 8 碼）' : '密碼'"
          :autocomplete="mode === 'register' ? 'new-password' : 'current-password'" />
        <button :disabled="busy" @click="$emit('submit')">{{ mode === 'login' ? '登入' : '註冊' }}</button>
        <button class="secondary" :disabled="busy" @click="$emit('toggle')">切換至{{ mode === 'login' ? '註冊' : '登入' }}</button>
      </div>
      <p v-if="mode === 'register'" class="hint">密碼長度至少需 8 碼</p>
    </div>
    <div v-else class="header-row">
      <p>目前登入：{{ email }}</p>
      <button class="secondary" @click="$emit('logout')">登出</button>
    </div>
  </section>
</template>

<script setup>
defineProps({
  authenticated: Boolean,
  email: { type: String, default: '' },
  mode: { type: String, default: 'login' },
  form: { type: Object, required: true },
  busy: Boolean
})
defineEmits(['submit', 'toggle', 'logout'])
</script>

<style scoped>
.form-row { display: flex; gap: 10px; flex-wrap: wrap; }
.header-row { display: flex; justify-content: space-between; align-items: center; }
input, button { padding: 8px 12px; font-size: 14px; }
button { cursor: pointer; }
.secondary { background: #f5f5f5; }
.auth-card p { margin: 0; }
.hint { margin-top: 6px; font-size: 12px; color: #666; }
</style>

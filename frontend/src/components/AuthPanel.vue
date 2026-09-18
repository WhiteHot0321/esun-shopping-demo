<template>
  <section class="panel auth-panel" aria-labelledby="auth-title">
    <div class="segmented" role="tablist" aria-label="登入或註冊">
      <button v-for="option in modes" :key="option.value" type="button" role="tab"
        :aria-selected="mode === option.value" :class="{ active: mode === option.value }"
        :disabled="busy" @click="switchMode(option.value)">{{ option.label }}</button>
    </div>

    <h2 id="auth-title" class="panel__title">{{ mode === 'login' ? '登入後即可結帳' : '建立新帳號' }}</h2>

    <form novalidate @submit.prevent="submit">
      <div class="field">
        <label for="auth-email">Email</label>
        <input id="auth-email" v-model.trim="form.email" type="email" autocomplete="email" inputmode="email"
          placeholder="you@example.com" :aria-invalid="Boolean(shownErrors.email)"
          :aria-describedby="shownErrors.email ? 'auth-email-error' : undefined" @blur="touched.email = true" />
        <p v-if="shownErrors.email" id="auth-email-error" class="field__error">{{ shownErrors.email }}</p>
      </div>

      <div class="field">
        <label for="auth-password">密碼</label>
        <div class="input-affix">
          <input id="auth-password" v-model="form.password" :type="showPassword ? 'text' : 'password'"
            :autocomplete="mode === 'register' ? 'new-password' : 'current-password'"
            :placeholder="mode === 'register' ? '至少 8 碼' : '請輸入密碼'" :aria-invalid="Boolean(shownErrors.password)"
            :aria-describedby="shownErrors.password ? 'auth-password-error' : undefined" @blur="touched.password = true" />
          <button type="button" class="affix-button" :aria-pressed="showPassword"
            @click="showPassword = !showPassword">{{ showPassword ? '隱藏' : '顯示' }}</button>
        </div>
        <p v-if="shownErrors.password" id="auth-password-error" class="field__error">{{ shownErrors.password }}</p>
        <p v-else-if="mode === 'register'" class="field__hint">密碼長度至少需 8 碼</p>
      </div>

      <button type="submit" class="btn btn--primary btn--block" :disabled="busy">
        <span v-if="busy" class="spinner" aria-hidden="true"></span>
        {{ busy ? '處理中…' : submitLabel }}
      </button>
    </form>
  </section>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import api from '../api'
import { useAuthStore } from '../stores/auth'
import { useToast } from '../composables/toast'
import { errorText } from '../format'

const auth = useAuthStore()
const toast = useToast()
const modes = [{ value: 'login', label: '登入' }, { value: 'register', label: '註冊' }]
const mode = ref('login')
const busy = ref(false)
const showPassword = ref(false)
const form = reactive({ email: '', password: '' })
const touched = reactive({ email: false, password: false })

const submitLabel = computed(() => (mode.value === 'login' ? '登入' : '註冊並登入'))

const errors = computed(() => ({
  email: !form.email ? '請輸入 Email' : !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email) ? 'Email 格式不正確' : '',
  password: !form.password ? '請輸入密碼'
    : mode.value === 'register' && form.password.length < 8 ? '密碼長度至少需 8 碼' : ''
}))
// Only complain about a field after the user has left it or tried to submit.
const shownErrors = computed(() => ({
  email: touched.email ? errors.value.email : '',
  password: touched.password ? errors.value.password : ''
}))

const switchMode = (next) => {
  if (busy.value || mode.value === next) return
  mode.value = next
  touched.password = false
}

const submit = async () => {
  if (busy.value) return
  touched.email = true
  touched.password = true
  if (errors.value.email || errors.value.password) {
    document.getElementById(errors.value.email ? 'auth-email' : 'auth-password')?.focus()
    return
  }
  busy.value = true
  const registering = mode.value === 'register'
  try {
    const { data: result } = await api.post(registering ? '/auth/register' : '/auth/login', { ...form })
    auth.setSession(result.data.token, result.data.email)
    toast.success(registering ? '註冊成功，已自動登入' : `歡迎回來，${result.data.email}`)
    Object.assign(form, { email: '', password: '' })
    Object.assign(touched, { email: false, password: false })
  } catch (error) {
    toast.error(errorText(error, registering ? '註冊失敗，請稍後再試' : '登入失敗，請稍後再試'))
    form.password = ''
    touched.password = false
    document.getElementById('auth-password')?.focus()
  } finally {
    busy.value = false
  }
}
</script>

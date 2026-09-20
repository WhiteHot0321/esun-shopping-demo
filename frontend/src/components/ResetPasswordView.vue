<template>
  <header class="topbar">
    <div class="topbar__inner">
      <a class="brand" href="/">
        <span class="brand__mark" aria-hidden="true">E</span>
        <span>ESUN Shop</span>
      </a>
    </div>
  </header>

  <main class="container reset-password">
    <section class="panel" aria-labelledby="reset-password-title">
      <h2 id="reset-password-title" class="panel__title">重設密碼</h2>

      <div v-if="!token" class="notice notice--error" role="alert">
        <p class="notice__title">重設連結無效</p>
        <p>這個頁面需要從忘記密碼信件中的連結開啟，才會帶有重設權杖。</p>
        <a class="btn btn--primary btn--block" href="/">回到登入頁面</a>
      </div>

      <div v-else-if="done" class="notice notice--success" role="status">
        <p class="notice__title">密碼已重設</p>
        <p>請使用新密碼重新登入。</p>
        <a class="btn btn--primary btn--block" href="/">前往登入</a>
      </div>

      <form v-else novalidate @submit.prevent="submit">
        <p v-if="submitError" class="notice notice--error" role="alert">{{ submitError }}</p>

        <div class="field">
          <label for="reset-new-password">新密碼</label>
          <input id="reset-new-password" v-model="form.newPassword" type="password" autocomplete="new-password"
            placeholder="至少 8 碼" :aria-invalid="Boolean(shownErrors.newPassword)"
            :aria-describedby="shownErrors.newPassword ? 'reset-new-password-error' : undefined"
            @blur="touched.newPassword = true" />
          <p v-if="shownErrors.newPassword" id="reset-new-password-error" class="field__error">{{ shownErrors.newPassword }}</p>
          <p v-else class="field__hint">密碼長度至少需 8 碼</p>
        </div>

        <div class="field">
          <label for="reset-confirm-password">確認新密碼</label>
          <input id="reset-confirm-password" v-model="form.confirmPassword" type="password" autocomplete="new-password"
            placeholder="再輸入一次新密碼" :aria-invalid="Boolean(shownErrors.confirmPassword)"
            :aria-describedby="shownErrors.confirmPassword ? 'reset-confirm-password-error' : undefined"
            @blur="touched.confirmPassword = true" />
          <p v-if="shownErrors.confirmPassword" id="reset-confirm-password-error" class="field__error">{{ shownErrors.confirmPassword }}</p>
        </div>

        <button type="submit" class="btn btn--primary btn--block" :disabled="busy">
          <span v-if="busy" class="spinner" aria-hidden="true"></span>
          {{ busy ? '處理中…' : '重設密碼' }}
        </button>
      </form>
    </section>
  </main>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import api from '../api'
import { errorText } from '../format'

const route = useRoute()
const token = computed(() => (typeof route.query.token === 'string' ? route.query.token : ''))

const busy = ref(false)
const done = ref(false)
const submitError = ref('')
const form = reactive({ newPassword: '', confirmPassword: '' })
const touched = reactive({ newPassword: false, confirmPassword: false })

const errors = computed(() => ({
  newPassword: !form.newPassword ? '請輸入新密碼' : form.newPassword.length < 8 ? '密碼長度至少需 8 碼' : '',
  confirmPassword: !form.confirmPassword ? '請再輸入一次新密碼'
    : form.confirmPassword !== form.newPassword ? '兩次輸入的新密碼不一致' : ''
}))
const shownErrors = computed(() => ({
  newPassword: touched.newPassword ? errors.value.newPassword : '',
  confirmPassword: touched.confirmPassword ? errors.value.confirmPassword : ''
}))

const submit = async () => {
  if (busy.value) return
  touched.newPassword = true
  touched.confirmPassword = true
  submitError.value = ''
  if (errors.value.newPassword || errors.value.confirmPassword) {
    document.getElementById(errors.value.newPassword ? 'reset-new-password' : 'reset-confirm-password')?.focus()
    return
  }
  busy.value = true
  try {
    await api.post('/auth/reset-password', { token: token.value, newPassword: form.newPassword })
    done.value = true
  } catch (error) {
    submitError.value = errorText(error, '重設密碼失敗，請稍後再試')
  } finally {
    busy.value = false
  }
}
</script>

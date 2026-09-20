<template>
  <section class="panel change-password-panel" aria-labelledby="change-password-title">
    <div class="panel__head">
      <h2 id="change-password-title" class="panel__title">修改密碼</h2>
      <button type="button" class="link-button" :disabled="busy" @click="$emit('close')">關閉</button>
    </div>

    <form novalidate @submit.prevent="submit">
      <div class="field">
        <label for="current-password">目前密碼</label>
        <input id="current-password" v-model="form.currentPassword" type="password" autocomplete="current-password"
          placeholder="請輸入目前密碼" :aria-invalid="Boolean(shownErrors.currentPassword)"
          :aria-describedby="shownErrors.currentPassword ? 'current-password-error' : undefined"
          @blur="touched.currentPassword = true" />
        <p v-if="shownErrors.currentPassword" id="current-password-error" class="field__error">{{ shownErrors.currentPassword }}</p>
      </div>

      <div class="field">
        <label for="new-password">新密碼</label>
        <input id="new-password" v-model="form.newPassword" type="password" autocomplete="new-password"
          placeholder="至少 8 碼" :aria-invalid="Boolean(shownErrors.newPassword)"
          :aria-describedby="shownErrors.newPassword ? 'new-password-error' : undefined"
          @blur="touched.newPassword = true" />
        <p v-if="shownErrors.newPassword" id="new-password-error" class="field__error">{{ shownErrors.newPassword }}</p>
        <p v-else class="field__hint">密碼長度至少需 8 碼</p>
      </div>

      <div class="field">
        <label for="confirm-password">確認新密碼</label>
        <input id="confirm-password" v-model="form.confirmPassword" type="password" autocomplete="new-password"
          placeholder="再輸入一次新密碼" :aria-invalid="Boolean(shownErrors.confirmPassword)"
          :aria-describedby="shownErrors.confirmPassword ? 'confirm-password-error' : undefined"
          @blur="touched.confirmPassword = true" />
        <p v-if="shownErrors.confirmPassword" id="confirm-password-error" class="field__error">{{ shownErrors.confirmPassword }}</p>
      </div>

      <button type="submit" class="btn btn--primary btn--block" :disabled="busy">
        <span v-if="busy" class="spinner" aria-hidden="true"></span>
        {{ busy ? '處理中…' : '更新密碼' }}
      </button>
    </form>
  </section>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import api from '../api'
import { useToast } from '../composables/toast'
import { errorText } from '../format'

const emit = defineEmits(['close'])
const toast = useToast()
const busy = ref(false)
const form = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' })
const touched = reactive({ currentPassword: false, newPassword: false, confirmPassword: false })

const errors = computed(() => ({
  currentPassword: !form.currentPassword ? '請輸入目前密碼' : '',
  newPassword: !form.newPassword ? '請輸入新密碼' : form.newPassword.length < 8 ? '密碼長度至少需 8 碼' : '',
  confirmPassword: !form.confirmPassword ? '請再輸入一次新密碼'
    : form.confirmPassword !== form.newPassword ? '兩次輸入的新密碼不一致' : ''
}))
const shownErrors = computed(() => ({
  currentPassword: touched.currentPassword ? errors.value.currentPassword : '',
  newPassword: touched.newPassword ? errors.value.newPassword : '',
  confirmPassword: touched.confirmPassword ? errors.value.confirmPassword : ''
}))

const submit = async () => {
  if (busy.value) return
  touched.currentPassword = true
  touched.newPassword = true
  touched.confirmPassword = true
  const firstInvalid = ['currentPassword', 'newPassword', 'confirmPassword'].find((field) => errors.value[field])
  if (firstInvalid) {
    document.getElementById(firstInvalid === 'currentPassword' ? 'current-password'
      : firstInvalid === 'newPassword' ? 'new-password' : 'confirm-password')?.focus()
    return
  }
  busy.value = true
  try {
    await api.post('/auth/change-password', { currentPassword: form.currentPassword, newPassword: form.newPassword })
    toast.success('密碼已更新')
    emit('close')
  } catch (error) {
    toast.error(errorText(error, '修改密碼失敗，請稍後再試'))
    form.currentPassword = ''
    touched.currentPassword = false
    document.getElementById('current-password')?.focus()
  } finally {
    busy.value = false
  }
}
</script>

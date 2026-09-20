<template>
  <section class="panel profile-panel" aria-labelledby="profile-title">
    <div class="panel__head">
      <h2 id="profile-title" class="panel__title">個人資料</h2>
      <button type="button" class="link-button" :disabled="busy" @click="$emit('close')">關閉</button>
    </div>

    <p v-if="loading" role="status">載入個人資料中…</p>
    <form v-else novalidate @submit.prevent="submit">
      <div class="field">
        <label for="profile-email">Email</label>
        <input id="profile-email" :value="email" type="email" autocomplete="email" disabled />
        <p class="field__hint">登入 Email 不可在此變更</p>
      </div>

      <div class="field">
        <label for="profile-display-name">顯示名稱</label>
        <input id="profile-display-name" v-model="form.displayName" maxlength="100"
          autocomplete="name" placeholder="例如：王小明" />
      </div>

      <div class="field">
        <label for="profile-phone">電話</label>
        <input id="profile-phone" v-model="form.phone" maxlength="30" inputmode="tel"
          autocomplete="tel" placeholder="例如：0912-345-678" :aria-invalid="Boolean(phoneError)"
          :aria-describedby="phoneError ? 'profile-phone-error' : undefined" />
        <p v-if="phoneError" id="profile-phone-error" class="field__error">{{ phoneError }}</p>
      </div>

      <button type="submit" class="btn btn--primary btn--block" :disabled="busy || Boolean(phoneError)">
        <span v-if="busy" class="spinner" aria-hidden="true"></span>
        {{ busy ? '儲存中…' : '儲存個人資料' }}
      </button>
    </form>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import api from '../api'
import { useToast } from '../composables/toast'
import { errorText } from '../format'

defineEmits(['close'])
const toast = useToast()
const loading = ref(true)
const busy = ref(false)
const email = ref('')
const form = reactive({ displayName: '', phone: '' })
const phoneError = computed(() => form.phone && !/^[0-9+()\-\s]*$/.test(form.phone)
  ? '電話只能包含數字、空白及 + ( ) -' : '')

onMounted(async () => {
  try {
    const response = await api.get('/member/profile')
    const profile = response.data.data
    email.value = profile.email
    form.displayName = profile.displayName || ''
    form.phone = profile.phone || ''
  } catch (error) {
    toast.error(errorText(error, '載入個人資料失敗，請稍後再試'))
  } finally {
    loading.value = false
  }
})

const submit = async () => {
  if (busy.value || phoneError.value) return
  busy.value = true
  try {
    const response = await api.put('/member/profile', {
      displayName: form.displayName.trim() || null,
      phone: form.phone.trim() || null
    })
    const profile = response.data.data
    form.displayName = profile.displayName || ''
    form.phone = profile.phone || ''
    toast.success('個人資料已更新')
  } catch (error) {
    toast.error(errorText(error, '更新個人資料失敗，請稍後再試'))
  } finally {
    busy.value = false
  }
}
</script>

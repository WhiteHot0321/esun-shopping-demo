<template>
  <div class="container">
    <h1>電商購物中心系統</h1>

    <AuthPanel :authenticated="auth.isAuthenticated" :email="auth.email" :mode="authMode"
      :form="authForm" :busy="isAuthenticating" @submit="submitAuth" @toggle="toggleAuthMode" @logout="logout" />

    <ShopWorkspace :authenticated="auth.isAuthenticated" @message="message = $event" />

    <section class="card" v-if="message">
      <h2>訊息</h2>
      <p>{{ message }}</p>
    </section>

    <SupportChat />
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import api from './api'
import AuthPanel from './components/AuthPanel.vue'
import ShopWorkspace from './components/ShopWorkspace.vue'
import { useAuthStore } from './stores/auth'
import SupportChat from './components/SupportChat.vue'

const auth = useAuthStore()
const authMode = ref('login')
const isAuthenticating = ref(false)
const authForm = reactive({ email: '', password: '' })
const message = ref('')

const submitAuth = async () => {
  if (isAuthenticating.value) return
  if (!authForm.email || !authForm.password) {
    message.value = '請輸入 Email 與密碼'
    return
  }
  isAuthenticating.value = true
  try {
    const endpoint = authMode.value === 'login' ? '/auth/login' : '/auth/register'
    const { data: result } = await api.post(endpoint, authForm)
    auth.setSession(result.data.token, result.data.email)
    authForm.password = ''
    message.value = authMode.value === 'login' ? '登入成功' : '註冊成功，已自動登入'
  } catch (error) {
    message.value = error.response?.data?.message || (authMode.value === 'login' ? '登入失敗' : '註冊失敗')
  } finally {
    isAuthenticating.value = false
  }
}

const toggleAuthMode = () => {
  if (isAuthenticating.value) return
  authMode.value = authMode.value === 'login' ? 'register' : 'login'
  message.value = ''
}

const logout = () => {
  auth.logout()
  message.value = '已登出'
}

const handleAuthExpired = () => {
  auth.logout()
  message.value = '登入已失效，請重新登入'
}

onMounted(() => {
  window.addEventListener('auth-expired', handleAuthExpired)
})

onUnmounted(() => window.removeEventListener('auth-expired', handleAuthExpired))
</script>

<style scoped>
.container {
  max-width: 1100px;
  margin: 30px auto;
  font-family: Arial, sans-serif;
  color: #222;
}

h1, h2, h3 {
  margin-bottom: 12px;
}

.card {
  border: 1px solid #ddd;
  border-radius: 10px;
  padding: 20px;
  margin-bottom: 20px;
  background: #fff;
}

</style>

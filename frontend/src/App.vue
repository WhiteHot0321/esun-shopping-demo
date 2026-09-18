<template>
  <div class="container">
    <h1>電商購物中心系統</h1>

    <section class="card message-banner" v-if="message">
      <h2>訊息</h2>
      <p>{{ message }}</p>
    </section>

    <AuthPanel :authenticated="auth.isAuthenticated" :email="auth.email" :mode="authMode"
      :form="authForm" :busy="isAuthenticating" @submit="submitAuth" @toggle="toggleAuthMode" @logout="logout" />

    <ProductManagement :authenticated="auth.isAuthenticated" @message="message = $event" @changed="catalogRefreshKey += 1" />
    <ShopWorkspace :authenticated="auth.isAuthenticated" :refresh-key="catalogRefreshKey" @message="message = $event" />
    <OrderHistory :authenticated="auth.isAuthenticated" />

    <SupportChat />
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import api from './api'
import AuthPanel from './components/AuthPanel.vue'
import ShopWorkspace from './components/ShopWorkspace.vue'
import ProductManagement from './components/ProductManagement.vue'
import OrderHistory from './components/OrderHistory.vue'
import { useAuthStore } from './stores/auth'
import SupportChat from './components/SupportChat.vue'

const auth = useAuthStore()
const authMode = ref('login')
const isAuthenticating = ref(false)
const authForm = reactive({ email: '', password: '' })
const message = ref('')
const catalogRefreshKey = ref(0)

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
  const paymentParams = new URLSearchParams(window.location.search)
  if (paymentParams.has('payment') || paymentParams.has('RtnCode') || paymentParams.has('MerchantTradeNo')) {
    // This is deliberately only a bounded browser notice. The server callback is authoritative.
    message.value = '已從付款頁返回；付款結果仍以系統收到並驗證的通知為準。'
  }
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

.message-banner {
  position: sticky;
  top: 10px;
  z-index: 10;
  border-color: #4a90d9;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
}

</style>

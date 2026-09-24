<template>
  <a class="skip-link" href="#main">跳到主要內容</a>

  <header class="topbar">
    <div class="topbar__inner">
      <a class="brand" href="/">
        <span class="brand__mark" aria-hidden="true">E</span>
        <span>ESUN Shop</span>
      </a>
      <div class="topbar__user">
        <template v-if="auth.isAuthenticated">
          <span class="topbar__email" :title="auth.email">{{ auth.email }}</span>
          <button type="button" class="btn btn--ghost btn--sm" @click="togglePanel('orders')">我的訂單</button>
          <button v-if="isSeller" type="button" class="btn btn--ghost btn--sm" @click="togglePanel('sellerOrders')">訂單管理</button>
          <button v-if="isAdmin" type="button" class="btn btn--ghost btn--sm" @click="togglePanel('coupons')">優惠券管理</button>
          <button v-if="isAdmin" type="button" class="btn btn--ghost btn--sm" @click="togglePanel('audit')">稽核日誌</button>
          <button type="button" class="btn btn--ghost btn--sm" @click="toggleProfile">個人資料</button>
          <button type="button" class="btn btn--ghost btn--sm" @click="toggleChangePassword">修改密碼</button>
          <button type="button" class="btn btn--ghost btn--sm" @click="logout">登出</button>
        </template>
        <button v-else type="button" class="btn btn--primary btn--sm" @click="focusLogin">登入 / 註冊</button>
      </div>
    </div>
  </header>

  <main id="main" class="container">
    <OrdersPanel v-if="activePanel === 'orders'" mode="buyer" @close="activePanel = ''" />
    <OrdersPanel v-if="activePanel === 'sellerOrders' && isSeller" mode="seller" @close="activePanel = ''" />
    <CouponPanel v-if="activePanel === 'coupons' && isAdmin" @close="activePanel = ''" />
    <AuditLogPanel v-if="activePanel === 'audit' && isAdmin" @close="activePanel = ''" />
    <ProfilePanel v-if="showProfile" @close="showProfile = false" />
    <ChangePasswordPanel v-if="showChangePassword" @close="showChangePassword = false" />
    <ShopWorkspace />
  </main>

  <SupportChat />
  <ToastStack />
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, provide, ref, watch } from 'vue'
import AuditLogPanel from './components/AuditLogPanel.vue'
import ChangePasswordPanel from './components/ChangePasswordPanel.vue'
import CouponPanel from './components/CouponPanel.vue'
import OrdersPanel from './components/OrdersPanel.vue'
import ProfilePanel from './components/ProfilePanel.vue'
import ShopWorkspace from './components/ShopWorkspace.vue'
import SupportChat from './components/SupportChat.vue'
import ToastStack from './components/ToastStack.vue'
import { createToasts, TOAST_KEY } from './composables/toast'
import { useAuthStore } from './stores/auth'

const auth = useAuthStore()
const toast = createToasts()
provide(TOAST_KEY, toast)

const showChangePassword = ref(false)
const showProfile = ref(false)
// 'orders' (buyer history), 'sellerOrders' (fulfilment), 'coupons' or 'audit' (both ADMIN); only one is open at a time.
const activePanel = ref('')
const isSeller = computed(() => ['SELLER', 'ADMIN'].includes(auth.role))
const isAdmin = computed(() => auth.role === 'ADMIN')
// Logging out (or a 401 auth-expiry) while the panel is open would otherwise leave it open
// on top of a logged-out header.
watch(() => auth.isAuthenticated, (authenticated) => {
  if (!authenticated) {
    showChangePassword.value = false
    showProfile.value = false
    activePanel.value = ''
  }
})

const togglePanel = (name) => {
  activePanel.value = activePanel.value === name ? '' : name
  showProfile.value = false
  showChangePassword.value = false
}

const toggleProfile = () => {
  showProfile.value = !showProfile.value
  showChangePassword.value = false
  activePanel.value = ''
}

const toggleChangePassword = () => {
  showChangePassword.value = !showChangePassword.value
  showProfile.value = false
  activePanel.value = ''
}

const focusLogin = async () => {
  await nextTick()
  const email = document.getElementById('auth-email')
  email?.scrollIntoView?.({ behavior: 'smooth', block: 'center' })
  email?.focus()
}

const logout = () => {
  auth.logout()
  toast.info('已登出')
}

// api.js fires this on any 401. A failed login is also a 401, so only announce an expiry
// when there actually was a session to expire.
const handleAuthExpired = () => {
  const hadSession = auth.isAuthenticated
  auth.logout()
  if (hadSession) {
    toast.error('登入已失效，請重新登入')
    focusLogin()
  }
}

onMounted(() => window.addEventListener('auth-expired', handleAuthExpired))
onUnmounted(() => {
  window.removeEventListener('auth-expired', handleAuthExpired)
  toast.clear()
})
</script>

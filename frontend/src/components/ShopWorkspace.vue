<template>
  <div class="workspace">
    <div class="workspace__main">
      <ProductCatalog :products="products" :quantities="quantities" :status="loadStatus" @reload="loadProducts"
        @set-quantity="setQuantity" />
      <ProductForm v-if="auth.isAuthenticated" @created="loadProducts" />
    </div>

    <aside class="workspace__side">
      <AuthPanel v-if="!auth.isAuthenticated" />
      <CartPanel :items="selected" :total="total" :form="form" :authenticated="auth.isAuthenticated" :busy="busy"
        :pending-attempt="attempt" @set-quantity="setQuantity" @clear="clearCart" @checkout="submitOrder"
        @retry="retry" />
    </aside>

    <a v-if="selected.length" href="#cart" class="mobile-cart-bar">
      <span>購物車 {{ selected.length }} 項</span>
      <strong>{{ formatPrice(total) }}</strong>
      <span aria-hidden="true">前往結帳 →</span>
    </a>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import api from '../api'
import { createCheckoutLifecycle } from '../checkout'
import { useAuthStore } from '../stores/auth'
import { useToast } from '../composables/toast'
import { clampQuantity, errorText, formatPrice } from '../format'
import AuthPanel from './AuthPanel.vue'
import CartPanel from './CartPanel.vue'
import ProductCatalog from './ProductCatalog.vue'
import ProductForm from './ProductForm.vue'

const auth = useAuthStore()
const toast = useToast()

const products = ref([])
const loadStatus = ref('loading')
const quantities = reactive({})
const form = reactive({ memberId: auth.email, payStatus: 'PENDING' })
const lifecycle = createCheckoutLifecycle()
const attempt = ref(null)
const busy = ref(false)

// Member ID defaults to the signed-in email but stays editable; only overwrite it while the
// user has not typed something of their own.
watch(() => auth.email, (next, previous) => {
  if (!form.memberId || form.memberId === previous) form.memberId = next
})

const selected = computed(() => products.value
  .filter((p) => quantities[p.productId] > 0)
  .map((p) => ({
    productId: p.productId,
    productName: p.productName,
    price: Number(p.price),
    stock: p.quantity,
    quantity: quantities[p.productId],
    itemPrice: Number(p.price) * quantities[p.productId]
  })))
const total = computed(() => selected.value.reduce((sum, item) => sum + item.itemPrice, 0))

const setQuantity = (productId, value) => {
  const product = products.value.find((p) => p.productId === productId)
  if (!product) return
  const next = clampQuantity(value, product.quantity)
  if (Number(value) > product.quantity) toast.info(`「${product.productName}」庫存只剩 ${product.quantity} 件，已調整數量`)
  quantities[productId] = next
}

const clearCart = () => {
  Object.keys(quantities).forEach((key) => { quantities[key] = 0 })
}

const loadProducts = async () => {
  loadStatus.value = 'loading'
  try {
    const { data } = await api.get('/products/available')
    products.value = data.data || []
    // Keep the cart consistent with fresh stock: drop vanished products, shrink over-stock lines.
    const stock = new Map(products.value.map((p) => [p.productId, p]))
    const adjusted = []
    Object.keys(quantities).forEach((id) => {
      const product = stock.get(id)
      const next = product ? clampQuantity(quantities[id], product.quantity) : 0
      if (quantities[id] > 0 && next !== quantities[id]) adjusted.push(product?.productName || id)
      quantities[id] = next
    })
    products.value.forEach((p) => { if (quantities[p.productId] == null) quantities[p.productId] = 0 })
    if (adjusted.length) toast.info(`庫存已變動，購物車中的 ${adjusted.join('、')} 已自動調整`)
    loadStatus.value = 'ready'
  } catch (error) {
    loadStatus.value = 'error'
    toast.error(errorText(error, '商品載入失敗'))
  }
}

const finish = async (promise) => {
  busy.value = true
  // lifecycle.submit() creates its attempt before the request even leaves; only surface it as
  // "unconfirmed" once the request has actually come back ambiguous.
  const result = await promise
  busy.value = false
  attempt.value = lifecycle.attempt
  if (result.status === 'success') {
    toast.success(`訂單建立成功，訂單編號：${result.result.data.data.orderId}`)
    clearCart()
    await loadProducts()
  } else if (result.status === 'retry-required') {
    toast.info('上一筆訂單結果尚未確認，請先按「重試未確認訂單」')
  } else if (result.status === 'failure') {
    // 401 is announced once by App's auth-expired handler; don't stack a second message.
    if (result.error?.response?.status === 401) return
    toast.error(attempt.value
      ? '訂單結果尚未確認，請按「重試未確認訂單」再確認一次'
      : errorText(result.error, '訂單建立失敗，請稍後再試'))
    // A conflict usually means stock moved under us; show the real numbers right away.
    if (result.error?.response?.status === 409) await loadProducts()
  }
}

const submitOrder = () => {
  if (busy.value) return
  if (!auth.isAuthenticated) return toast.info('請先登入會員才能建立訂單')
  const items = selected.value.map((i) => ({ productId: i.productId, quantity: i.quantity }))
  if (!items.length && !lifecycle.attempt) return toast.info('請至少選擇一項商品')
  if (!form.memberId) {
    document.getElementById('member-id')?.focus()
    return toast.info('請輸入會員編號')
  }
  return finish(lifecycle.submit({ ...form, items }, (request) => api.post('/orders', request)))
}

const retry = () => {
  if (busy.value) return
  return finish(lifecycle.retry((request) => api.post('/orders', request)))
}

onMounted(loadProducts)
</script>

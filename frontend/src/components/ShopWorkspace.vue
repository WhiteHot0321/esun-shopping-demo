<template>
  <div class="workspace">
    <div class="workspace__main">
      <ProductManagement v-if="auth.isAuthenticated && ['SELLER', 'ADMIN'].includes(auth.role)"
        @message="toast.info($event)" @changed="loadProducts" />
      <ProductCatalog :products="products" :quantities="quantities" :status="loadStatus" @reload="loadProducts"
        @set-quantity="setQuantity" @view-reviews="reviewProduct = $event" />
      <ProductReviews v-if="reviewProduct" :product="reviewProduct" :authenticated="auth.isAuthenticated"
        :role="auth.role" @close="reviewProduct = null" @changed="loadProducts" />
    </div>

    <aside class="workspace__side">
      <AuthPanel v-if="!auth.isAuthenticated" />
      <CartPanel :items="selected" :total="total" :form="form" :authenticated="auth.isAuthenticated" :busy="busy"
        :pending-attempt="attempt" :preview-coupon="previewCoupon" @set-quantity="setQuantity" @clear="clearCart" @checkout="submitOrder"
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
import ProductManagement from './ProductManagement.vue'
import ProductReviews from './ProductReviews.vue'

const auth = useAuthStore()
const toast = useToast()

const products = ref([])
const reviewProduct = ref(null)
const loadStatus = ref('loading')
const quantities = reactive({})
const form = reactive({ memberId: auth.email, couponCode: '' })
const lifecycle = createCheckoutLifecycle()
const attempt = ref(null)
const busy = ref(false)
const cartReady = ref(false)
const serverCartReady = ref(false)
const cartClearing = ref(false)
const cartItemIds = reactive({})
const cartSyncs = new Map()
let cartMutationVersion = 0

const cartStorageKey = () => `esunShop.cart.v1:${encodeURIComponent((auth.email || 'guest').trim().toLowerCase())}`

const readStoredCart = () => {
  try {
    const stored = JSON.parse(localStorage.getItem(cartStorageKey()) || '{}')
    return stored && typeof stored === 'object' && !Array.isArray(stored) ? stored : {}
  } catch {
    localStorage.removeItem(cartStorageKey())
    return {}
  }
}

const restoreCart = () => {
  cartReady.value = false
  const stored = readStoredCart()
  products.value.forEach((product) => {
    quantities[product.productId] = clampQuantity(stored[product.productId] || 0, product.quantity)
  })
  Object.keys(quantities).forEach((id) => {
    if (!products.value.some((product) => product.productId === id)) delete quantities[id]
  })
  cartReady.value = true
}

// Member ID defaults to the signed-in email but stays editable; only overwrite it while the
// user has not typed something of their own.
watch(() => auth.email, (next, previous) => {
  if (!form.memberId || form.memberId === previous) form.memberId = next
  serverCartReady.value = false
  if (products.value.length) {
    restoreCart()
    if (auth.isAuthenticated) loadServerCart()
  }
})

watch(quantities, (next) => {
  if (!cartReady.value) return
  const stored = Object.fromEntries(Object.entries(next)
    .filter(([, quantity]) => Number.isInteger(quantity) && quantity > 0))
  localStorage.setItem(cartStorageKey(), JSON.stringify(stored))
}, { deep: true })

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

const syncCartItem = (productId, quantity) => {
  if (!auth.isAuthenticated || !serverCartReady.value) return
  const previous = cartSyncs.get(productId) || Promise.resolve()
  const task = previous.then(async () => {
    const itemId = cartItemIds[productId]
    if (quantity === 0) {
      if (itemId) await api.delete(`/cart/items/${itemId}`)
      delete cartItemIds[productId]
    } else if (itemId) {
      const { data } = await api.put(`/cart/items/${itemId}`, { quantity })
      cartItemIds[productId] = data.data.id
    } else {
      const { data } = await api.post('/cart/add', { productId, quantity })
      cartItemIds[productId] = data.data.id
    }
  })
  const tracked = task.catch((error) => {
    toast.error(errorText(error, '購物車同步失敗，已重新載入伺服器資料'))
    serverCartReady.value = false
    return { reload: true }
  })
  cartSyncs.set(productId, tracked)
  tracked.then((result) => {
    if (cartSyncs.get(productId) === tracked) cartSyncs.delete(productId)
    if (result?.reload) loadServerCart()
  })
}

const setQuantity = (productId, value) => {
  if (busy.value || cartClearing.value) return
  const product = products.value.find((p) => p.productId === productId)
  if (!product) return
  const next = clampQuantity(value, product.quantity)
  if (Number(value) > product.quantity) toast.info(`「${product.productName}」庫存只剩 ${product.quantity} 件，已調整數量`)
  if (quantities[productId] === next) return
  cartMutationVersion++
  quantities[productId] = next
  syncCartItem(productId, next)
}

const clearCart = async (persist = true) => {
  if (busy.value || cartClearing.value) return
  cartClearing.value = true
  try {
    cartMutationVersion++
    await waitForCartSyncs()
    if (persist && auth.isAuthenticated && serverCartReady.value) {
      await api.delete('/cart')
    }
    Object.keys(quantities).forEach((key) => { quantities[key] = 0 })
    Object.keys(cartItemIds).forEach((key) => { delete cartItemIds[key] })
  } catch (error) {
    toast.error(errorText(error, '購物車清空失敗'))
  } finally {
    cartClearing.value = false
  }
}

const waitForCartSyncs = async () => {
  while (cartSyncs.size) await Promise.allSettled([...cartSyncs.values()])
}

const loadServerCart = async () => {
  try {
    const pending = [...cartSyncs.values()]
    if (pending.length) await Promise.allSettled(pending)
    const requestedVersion = cartMutationVersion
    const { data } = await api.get('/cart')
    if (requestedVersion !== cartMutationVersion || cartSyncs.size) return loadServerCart()
    const items = Array.isArray(data.data) ? data.data : []
    const byProduct = new Map(items.map(item => [item.productId, item]))
    const adjusted = []
    products.value.forEach((product) => {
      const item = byProduct.get(product.productId)
      const next = item ? clampQuantity(item.quantity, product.quantity) : 0
      if ((quantities[product.productId] || 0) > next) adjusted.push(product.productName)
      quantities[product.productId] = next
      if (item) cartItemIds[product.productId] = item.id
      else delete cartItemIds[product.productId]
    })
    if (adjusted.length) toast.info(`庫存已變動，購物車中的 ${adjusted.join('、')} 已自動調整`)
    serverCartReady.value = true
  } catch (error) {
    serverCartReady.value = false
    toast.error(errorText(error, '購物車載入失敗'))
  }
}

const loadProducts = async () => {
  loadStatus.value = 'loading'
  try {
    const { data } = await api.get('/products/available')
    products.value = data.data || []
    if (!cartReady.value) restoreCart()
    if (auth.isAuthenticated) await loadServerCart()
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
    toast.success(`訂單建立成功，訂單編號：${result.result.data.data.orderId}，請至「我的訂單」完成付款`)
    await clearCart(false)
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
  if (busy.value || cartClearing.value) return
  if (!auth.isAuthenticated) return toast.info('請先登入會員才能建立訂單')
  const items = selected.value.map((i) => ({ productId: i.productId, quantity: i.quantity }))
  if (!items.length && !lifecycle.attempt) return toast.info('請至少選擇一項商品')
  if (!form.memberId) {
    document.getElementById('member-id')?.focus()
    return toast.info('請輸入會員編號')
  }
  return finish(lifecycle.submit({ ...form, items }, async (request) => {
    await waitForCartSyncs()
    return api.post('/cart/checkout', {
      requestId: request.requestId,
      shippingAddressId: request.shippingAddressId,
      couponCode: request.couponCode || undefined
    })
  }))
}

// Advisory pricing of the server-side cart; wait for pending cart writes so it sees what the buyer sees.
const previewCoupon = async (code) => {
  await waitForCartSyncs()
  const { data } = await api.post('/cart/coupon-preview', { code })
  return data.data
}

const retry = () => {
  if (busy.value || cartClearing.value) return
  return finish(lifecycle.retry(async (request) => {
    await waitForCartSyncs()
    return api.post('/cart/checkout', {
      requestId: request.requestId,
      shippingAddressId: request.shippingAddressId,
      couponCode: request.couponCode || undefined
    })
  }))
}

onMounted(loadProducts)
</script>

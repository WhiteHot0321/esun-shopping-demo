<template>
  <div class="container">
    <h1>電商購物中心系統</h1>

    <section class="card auth-card">
      <div v-if="!isAuthenticated">
        <h2>{{ authMode === 'login' ? '會員登入' : '會員註冊' }}</h2>
        <div class="form-row">
          <input v-model.trim="authForm.email" type="email" placeholder="Email" autocomplete="email" />
          <input v-model="authForm.password" type="password" placeholder="密碼" autocomplete="current-password" />
          <button :disabled="isAuthenticating" @click="submitAuth">
            {{ authMode === 'login' ? '登入' : '註冊' }}
          </button>
          <button class="secondary" @click="toggleAuthMode">
            切換至{{ authMode === 'login' ? '註冊' : '登入' }}
          </button>
        </div>
      </div>
      <div v-else class="header-row">
        <p>目前登入：{{ authenticatedEmail }}</p>
        <button class="secondary" @click="logout">登出</button>
      </div>
    </section>

    <section class="card" v-if="isAuthenticated">
      <h2>新增商品</h2>
      <div class="form-row">
        <input v-model="newProduct.productId" placeholder="商品編號" />
        <input v-model="newProduct.productName" placeholder="商品名稱" />
        <input v-model.number="newProduct.price" type="number" placeholder="價格" />
        <input v-model.number="newProduct.quantity" type="number" placeholder="庫存" />
        <button @click="createProduct">新增商品</button>
      </div>
    </section>

    <section class="card">
      <div class="header-row">
        <h2>建立訂單</h2>
        <button @click="fetchProducts">重新載入商品</button>
      </div>

      <table>
        <thead>
          <tr>
            <th>商品編號</th>
            <th>商品名稱</th>
            <th>售價</th>
            <th>庫存</th>
            <th>購買數量</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in products" :key="item.productId">
            <td>{{ item.productId }}</td>
            <td>{{ item.productName }}</td>
            <td>{{ item.price }}</td>
            <td>{{ item.quantity }}</td>
            <td>
              <input
                type="number"
                min="0"
                :max="item.quantity"
                v-model.number="orderQuantities[item.productId]"
              />
            </td>
          </tr>
        </tbody>
      </table>

      <div class="order-form">
        <label>會員編號</label>
        <input v-model="orderForm.memberId" placeholder="請輸入會員編號" />

        <label>付款狀態</label>
        <select v-model="orderForm.payStatus">
          <option value="PENDING">未付款</option>
          <option value="PAID">已付款</option>
        </select>
      </div>

      <div class="summary">
        <h3>訂單預覽</h3>
        <ul v-if="selectedItems.length > 0">
          <li v-for="item in selectedItems" :key="item.productId">
            {{ item.productName }} × {{ item.quantity }} = {{ item.itemPrice }}
          </li>
        </ul>
        <p v-else>尚未選擇商品</p>
        <p><strong>總金額：{{ totalPrice }}</strong></p>
        <button :disabled="isSubmittingOrder" @click="createOrder">建立訂單</button>
        <button v-if="checkoutAttempt" :disabled="isSubmittingOrder" @click="retryUnresolvedOrder">重試未確認訂單</button>
      </div>
    </section>

    <section class="card" v-if="message">
      <h2>訊息</h2>
      <p>{{ message }}</p>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import api from './api'
import { createCheckoutLifecycle } from './checkout'

const products = ref([])
const message = ref('')
const accessToken = ref(localStorage.getItem('accessToken') || '')
const authenticatedEmail = ref(localStorage.getItem('authenticatedEmail') || '')
const isAuthenticated = computed(() => Boolean(accessToken.value))
const authMode = ref('login')
const isAuthenticating = ref(false)
const authForm = reactive({ email: '', password: '' })

const newProduct = reactive({
  productId: '',
  productName: '',
  price: null,
  quantity: null
})

const orderForm = reactive({
  memberId: '',
  payStatus: 'PENDING'
})

const orderQuantities = reactive({})
const checkoutLifecycle = createCheckoutLifecycle()
const checkoutAttempt = ref(null)
const isSubmittingOrder = ref(false)

const errorMessage = (error, fallback) =>
  error.response?.data?.message || fallback

const submitAuth = async () => {
  if (!authForm.email || !authForm.password) {
    message.value = '請輸入 Email 與密碼'
    return
  }
  isAuthenticating.value = true
  try {
    const endpoint = authMode.value === 'login' ? '/auth/login' : '/auth/register'
    const { data: result } = await api.post(endpoint, authForm)
    localStorage.setItem('accessToken', result.data.token)
    accessToken.value = result.data.token
    localStorage.setItem('authenticatedEmail', result.data.email)
    authenticatedEmail.value = result.data.email
    authForm.password = ''
    message.value = authMode.value === 'login' ? '登入成功' : '註冊成功，已自動登入'
  } catch (error) {
    message.value = errorMessage(error, authMode.value === 'login' ? '登入失敗' : '註冊失敗')
  } finally {
    isAuthenticating.value = false
  }
}

const toggleAuthMode = () => {
  authMode.value = authMode.value === 'login' ? 'register' : 'login'
  message.value = ''
}

const logout = () => {
  localStorage.removeItem('accessToken')
  localStorage.removeItem('authenticatedEmail')
  accessToken.value = ''
  authenticatedEmail.value = ''
  message.value = '已登出'
}

const handleAuthExpired = () => {
  accessToken.value = ''
  authenticatedEmail.value = ''
  message.value = '登入已失效，請重新登入'
}

const fetchProducts = async (showSuccessMessage = true) => {
  try {
    const { data: result } = await api.get('/products/available')

    products.value = result.data || []

    products.value.forEach((item) => {
      if (orderQuantities[item.productId] == null) {
        orderQuantities[item.productId] = 0
      }
    })

    if (showSuccessMessage) {
      message.value = '商品載入成功'
    }
  } catch (error) {
    console.error(error)
    if (showSuccessMessage) {
      message.value = errorMessage(error, '載入商品失敗')
    }
  }
}

const createProduct = async () => {
  try {
    await api.post('/products', newProduct)

    message.value = '商品新增成功'

    newProduct.productId = ''
    newProduct.productName = ''
    newProduct.price = null
    newProduct.quantity = null

    await fetchProducts()
  } catch (error) {
    console.error(error)
    message.value = errorMessage(error, '商品新增失敗')
  }
}

const selectedItems = computed(() => {
  return products.value
    .filter((item) => Number(orderQuantities[item.productId] || 0) > 0)
    .map((item) => {
      const quantity = Number(orderQuantities[item.productId])
      return {
        productId: item.productId,
        productName: item.productName,
        quantity,
        itemPrice: Number(item.price) * quantity
      }
    })
})

const totalPrice = computed(() => {
  return selectedItems.value.reduce((sum, item) => sum + item.itemPrice, 0)
})

const syncCheckoutState = () => {
  checkoutAttempt.value = checkoutLifecycle.attempt
  isSubmittingOrder.value = checkoutLifecycle.isSubmitting
}

const postOrder = (request) => api.post('/orders', request)

const handleOrderResult = async (pendingResult) => {
  syncCheckoutState()
  const outcome = await pendingResult
  syncCheckoutState()

  if (outcome.status === 'success') {
    message.value = `訂單建立成功，訂單編號：${outcome.result.data.data.orderId}`
    Object.keys(orderQuantities).forEach((key) => {
      orderQuantities[key] = 0
    })
    await fetchProducts(false)
  } else if (outcome.status === 'retry-required') {
    message.value = '上一筆訂單結果尚未確認，請使用重試未確認訂單'
  } else if (outcome.status === 'failure') {
    message.value = errorMessage(outcome.error, '建立訂單失敗')
  }
}

const createOrder = async () => {
  if (checkoutLifecycle.isSubmitting) {
    return
  }

  const items = selectedItems.value.map((item) => ({
    productId: item.productId,
    quantity: item.quantity
  }))

  if (items.length === 0 && !checkoutLifecycle.attempt) {
    message.value = '請至少選擇一項商品'
    return
  }

  const payload = {
    memberId: orderForm.memberId,
    payStatus: orderForm.payStatus,
    items
  }

  await handleOrderResult(checkoutLifecycle.submit(payload, postOrder))
}

const retryUnresolvedOrder = async () => {
  await handleOrderResult(checkoutLifecycle.retry(postOrder))
}

onMounted(() => {
  window.addEventListener('auth-expired', handleAuthExpired)
  fetchProducts()
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

.form-row {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.order-form {
  margin-top: 20px;
  display: grid;
  gap: 10px;
  max-width: 300px;
}

input, select, button {
  padding: 8px 12px;
  font-size: 14px;
}

button {
  cursor: pointer;
}

.secondary {
  background: #f5f5f5;
}

.auth-card p {
  margin: 0;
}

table {
  width: 100%;
  border-collapse: collapse;
  margin-top: 10px;
}

th, td {
  border: 1px solid #ddd;
  padding: 10px;
  text-align: left;
}

.summary {
  margin-top: 20px;
}
</style>

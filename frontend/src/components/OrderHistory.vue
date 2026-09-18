<template>
  <section v-if="authenticated" class="card">
    <div class="header-row">
      <h2>我的訂單</h2>
      <div class="actions">
        <label>付款狀態
          <select v-model="payStatus" @change="reload">
            <option value="">全部</option>
            <option value="0">待付款</option>
            <option value="1">付款成功</option>
            <option value="2">付款失敗</option>
          </select>
        </label>
        <button :disabled="loading" @click="reload">重新載入</button>
      </div>
    </div>

    <p v-if="loading">訂單載入中…</p>
    <p v-else-if="error" class="error" role="alert">{{ error }}</p>
    <p v-else-if="!orders.length">目前沒有訂單</p>
    <template v-else>
      <table>
        <thead><tr><th>訂單編號</th><th>總金額</th><th>付款狀態</th><th>建立時間</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="order in orders" :key="order.orderId">
            <td>{{ order.orderId }}</td><td>{{ order.price }}</td><td>{{ payStatusLabel(order.payStatus) }}</td><td>{{ formatDate(order.createdAt) }}</td>
            <td><button @click="loadDetail(order.orderId)">查看明細</button></td>
          </tr>
        </tbody>
      </table>
      <div class="pagination">
        <button :disabled="page === 0 || loading" @click="goToPage(page - 1)">上一頁</button>
        <span>第 {{ page + 1 }} / {{ Math.max(totalPages, 1) }} 頁</span>
        <button :disabled="page + 1 >= totalPages || loading" @click="goToPage(page + 1)">下一頁</button>
      </div>
    </template>

    <section v-if="selectedOrderId" class="detail" aria-live="polite">
      <h3>訂單明細</h3>
      <p v-if="detailLoading">訂單明細載入中…</p>
      <p v-else-if="detailError" class="error" role="alert">{{ detailError }}</p>
      <template v-else-if="detail">
        <p>訂單編號：{{ detail.orderId }}；總金額：{{ detail.price }}；付款狀態：{{ payStatusLabel(detail.payStatus) }}</p>
        <table>
          <thead><tr><th>商品編號</th><th>數量</th><th>單價</th><th>小計</th></tr></thead>
          <tbody><tr v-for="item in detail.items" :key="item.productId"><td>{{ item.productId }}</td><td>{{ item.quantity }}</td><td>{{ item.unitPrice }}</td><td>{{ item.itemPrice }}</td></tr></tbody>
        </table>
      </template>
    </section>
  </section>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import api from '../api'

const props = defineProps({ authenticated: Boolean })
const orders = ref([])
const page = ref(0)
const totalPages = ref(0)
const payStatus = ref('')
const loading = ref(false)
const error = ref('')
const selectedOrderId = ref('')
const detail = ref(null)
const detailLoading = ref(false)
const detailError = ref('')
const size = 10

const errorText = (error) => error.response?.data?.message || '訂單資料載入失敗，請稍後再試'
const payStatusLabel = (value) => ({ 0: '待付款', 1: '付款成功', 2: '付款失敗' })[value] || '未知'
const formatDate = (value) => value ? String(value).replace('T', ' ') : '-'

const loadOrders = async () => {
  if (!props.authenticated) return
  loading.value = true
  error.value = ''
  const filter = payStatus.value === '' ? '' : `&payStatus=${encodeURIComponent(payStatus.value)}`
  try {
    const { data } = await api.get(`/orders?page=${page.value}&size=${size}${filter}`)
    const result = data.data || {}
    orders.value = result.content || []
    totalPages.value = result.totalPages || 0
  } catch (requestError) {
    orders.value = []
    totalPages.value = 0
    error.value = errorText(requestError)
  } finally {
    loading.value = false
  }
}

const reload = () => { page.value = 0; selectedOrderId.value = ''; detail.value = null; loadOrders() }
const goToPage = (nextPage) => { page.value = nextPage; loadOrders() }
const loadDetail = async (orderId) => {
  selectedOrderId.value = orderId
  detail.value = null
  detailError.value = ''
  detailLoading.value = true
  try {
    const { data } = await api.get(`/orders/${encodeURIComponent(orderId)}`)
    detail.value = data.data
  } catch (requestError) {
    detailError.value = errorText(requestError)
  } finally {
    detailLoading.value = false
  }
}

onMounted(loadOrders)
</script>

<style scoped>
.header-row, .actions, .pagination { display: flex; gap: 10px; align-items: center; }
.header-row { justify-content: space-between; }
.actions { flex-wrap: wrap; }
.pagination, .detail { margin-top: 16px; }
.error { color: #b42318; }
select, button { padding: 8px 12px; font-size: 14px; }
</style>

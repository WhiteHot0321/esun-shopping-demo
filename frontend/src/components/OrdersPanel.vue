<template>
  <section class="panel orders-panel" :aria-labelledby="titleId">
    <div class="panel__head">
      <h2 :id="titleId" class="panel__title">{{ isSeller ? '訂單管理' : '我的訂單' }}</h2>
      <button type="button" class="link-button" @click="$emit('close')">關閉</button>
    </div>

    <div class="orders-filter" role="group" aria-label="依狀態篩選">
      <button v-for="option in filters" :key="option.value" type="button"
        class="btn btn--sm" :class="filter === option.value ? 'btn--primary' : 'btn--ghost'"
        :aria-pressed="filter === option.value" @click="setFilter(option.value)">{{ option.label }}</button>
    </div>

    <p v-if="loading" role="status">載入訂單中…</p>
    <p v-else-if="!orders.length" class="field__hint">目前沒有{{ filter === 'all' ? '' : '此狀態的' }}訂單</p>
    <ul v-else class="orders-list">
      <li v-for="order in orders" :key="order.orderId" class="order-card">
        <div class="order-card__head">
          <strong>{{ order.orderId }}</strong>
          <span class="badge" :class="badgeClass(order.status)">{{ statusLabel(order.status) }}</span>
          <span class="order-card__total">{{ formatPrice(order.price) }}</span>
        </div>
        <p class="field__hint">{{ formatTime(order.createdAt) }}</p>
        <ul class="order-card__items">
          <li v-for="item in order.items" :key="item.productId">
            {{ item.productName }} × {{ item.quantity }}（{{ formatPrice(item.itemPrice) }}）
          </li>
        </ul>
        <p v-if="order.receiverName" class="field__hint">
          收件：{{ order.receiverName }} {{ order.receiverPhone }} {{ order.shippingAddress }}
        </p>
        <ol class="timeline" aria-label="訂單狀態時間軸">
          <li v-for="(entry, index) in order.timeline" :key="index">
            <span class="timeline__status">{{ statusLabel(entry.toStatus) }}</span>
            <span class="field__hint">{{ formatTime(entry.createdAt) }}</span>
          </li>
        </ol>
        <div v-if="order.allowedActions?.length" class="order-card__actions">
          <button v-for="action in order.allowedActions" :key="action" type="button"
            class="btn btn--sm" :class="action === 'CANCELLED' ? 'btn--ghost' : 'btn--primary'"
            :disabled="busyId === order.orderId" @click="act(order, action)">{{ actionLabel(action) }}</button>
        </div>
      </li>
    </ul>

    <div v-if="totalPages > 1" class="orders-pager">
      <button type="button" class="btn btn--ghost btn--sm" :disabled="page === 0 || loading" @click="goPage(page - 1)">上一頁</button>
      <span>第 {{ page + 1 }} / {{ totalPages }} 頁</span>
      <button type="button" class="btn btn--ghost btn--sm" :disabled="page + 1 >= totalPages || loading" @click="goPage(page + 1)">下一頁</button>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import api from '../api'
import { useToast } from '../composables/toast'
import { errorText, formatPrice } from '../format'

// mode "buyer": the caller's own orders (cancel only). mode "seller": orders containing the caller's
// products (SELLER) or every order (ADMIN), with fulfilment transitions. The backend decides which
// actions are legal (allowedActions); this component never derives permissions on its own.
const props = defineProps({ mode: { type: String, default: 'buyer' } })
defineEmits(['close'])

const toast = useToast()
const isSeller = computed(() => props.mode === 'seller')
const titleId = computed(() => `orders-title-${props.mode}`)
const basePath = computed(() => (isSeller.value ? '/seller/orders' : '/orders'))

const STATUS_LABELS = {
  CREATED: '待確認', CONFIRMED: '已確認', SHIPPED: '已出貨', DELIVERED: '已送達', CANCELLED: '已取消'
}
const ACTION_LABELS = {
  CONFIRMED: '確認訂單', SHIPPED: '標記出貨', DELIVERED: '標記送達', CANCELLED: '取消訂單'
}
const filters = [{ value: 'all', label: '全部' }, ...Object.entries(STATUS_LABELS).map(([value, label]) => ({ value, label }))]
const size = 10

const orders = ref([])
const total = ref(0)
const page = ref(0)
const filter = ref('all')
const loading = ref(true)
const busyId = ref('')
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size)))

const statusLabel = (status) => STATUS_LABELS[status] || status
const actionLabel = (action) => ACTION_LABELS[action] || action
const badgeClass = (status) => ({
  DELIVERED: 'badge--ok', SHIPPED: 'badge--ok', CANCELLED: 'badge--muted'
}[status] || 'badge--warn')
const formatTime = (value) => (value ? String(value).replace('T', ' ').slice(0, 16) : '')

// Only the newest request may update the list, so rapid filter/page clicks cannot show stale data.
let latestRequest = 0
const load = async () => {
  const request = ++latestRequest
  loading.value = true
  try {
    const { data } = await api.get(basePath.value, { params: { status: filter.value, page: page.value, size } })
    if (request !== latestRequest) return
    orders.value = data.data?.orders || []
    total.value = data.data?.total || 0
    // The last order on a page can leave it (e.g. cancelled under a status filter): step back.
    if (!orders.value.length && page.value > 0) {
      page.value -= 1
      await load()
      return
    }
  } catch (error) {
    if (request === latestRequest) toast.error(errorText(error, '載入訂單失敗，請稍後再試'))
  } finally {
    if (request === latestRequest) loading.value = false
  }
}

const setFilter = (value) => {
  filter.value = value
  page.value = 0
  load()
}
const goPage = (value) => {
  page.value = value
  load()
}

const act = async (order, action) => {
  if (busyId.value) return
  if (action === 'CANCELLED' && !window.confirm(`確定要取消訂單 ${order.orderId} 嗎？庫存會退回。`)) return
  busyId.value = order.orderId
  try {
    if (isSeller.value) {
      await api.post(`/seller/orders/${order.orderId}/status`, { status: action })
    } else {
      await api.post(`/orders/${order.orderId}/cancel`)
    }
    toast.success(`訂單已更新為「${statusLabel(action)}」`)
  } catch (error) {
    // 409 means someone else already moved the order; reload so the shown actions are current.
    toast.error(errorText(error, '更新訂單失敗，請稍後再試'))
  } finally {
    busyId.value = ''
    await load()
  }
}

onMounted(load)
</script>

<style scoped>
.orders-filter { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 12px; }
.orders-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 12px; }
.order-card { border: 1px solid var(--border, #e5e7eb); border-radius: 10px; padding: 12px; }
.order-card__head { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.order-card__total { margin-left: auto; font-weight: 700; }
.order-card__items { margin: 8px 0; padding-left: 18px; }
.order-card__actions { display: flex; gap: 8px; margin-top: 8px; }
.timeline { list-style: none; margin: 8px 0 0; padding: 0 0 0 12px; border-left: 2px solid var(--border, #e5e7eb); display: grid; gap: 4px; }
.timeline li { display: flex; gap: 8px; align-items: baseline; }
.timeline__status { font-weight: 600; }
.orders-pager { display: flex; justify-content: center; align-items: center; gap: 12px; margin-top: 12px; }
</style>

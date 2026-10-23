<template>
  <section class="panel ops-panel" aria-labelledby="ops-title">
    <div class="panel__head">
      <h2 id="ops-title" class="panel__title">營運儀表板</h2>
      <div class="ops-head__actions">
        <button type="button" class="link-button" :disabled="loading" data-testid="ops-refresh" @click="load">重新整理</button>
        <button type="button" class="link-button" @click="$emit('close')">關閉</button>
      </div>
    </div>

    <p v-if="loading && !dashboard" role="status">載入儀表板中…</p>

    <div v-else-if="error && !dashboard" class="ops-error" role="alert" data-testid="ops-error">
      <p>{{ error }}</p>
      <button type="button" class="btn btn--primary btn--sm" @click="load">重試</button>
    </div>

    <template v-else-if="dashboard">
      <p v-if="error" class="field__hint" role="alert" data-testid="ops-stale">{{ error }}（顯示的是上一次載入的資料）</p>
      <p class="field__hint" data-testid="ops-asof">
        資料時間 {{ asOfText }}（台北時間）。數字可能落後數秒；「今日」為 {{ dayText }}。
      </p>

      <dl class="ops-stats">
        <div class="ops-stat" data-testid="ops-orders">
          <dt>今日訂單數</dt>
          <dd>{{ dashboard.today.orderCount }}</dd>
          <small class="field__hint">不含已取消</small>
        </div>
        <div class="ops-stat" data-testid="ops-revenue">
          <dt>今日營收</dt>
          <dd>{{ formatPrice(dashboard.today.revenue) }}</dd>
          <small class="field__hint">已付款、不含已取消</small>
        </div>
        <div class="ops-stat" data-testid="ops-pending">
          <dt>待付款訂單</dt>
          <dd>{{ dashboard.pendingPaymentCount }}</dd>
          <small class="field__hint">目前累計，不限今日</small>
        </div>
        <div class="ops-stat" data-testid="ops-failures">
          <dt>今日付款失敗</dt>
          <dd :class="{ 'ops-stat--warn': dashboard.today.paymentFailureCount > 0 }">{{ dashboard.today.paymentFailureCount }}</dd>
          <small class="field__hint">不含系統關閉的嘗試</small>
        </div>
        <div class="ops-stat" data-testid="ops-refund">
          <dt>待退款</dt>
          <dd :class="{ 'ops-stat--warn': dashboard.refundRequiredCount > 0 }">{{ dashboard.refundRequiredCount }}</dd>
          <small class="field__hint">已收款但訂單無法成立，需人工退款</small>
        </div>
      </dl>

      <h3 class="ops-section">庫存快取（Redis）</h3>
      <p data-testid="ops-redis">
        <span :class="['badge', redis.badge]">{{ redis.label }}</span>
        <span class="field__hint">只反映這台伺服器的狀態</span>
      </p>

      <h3 class="ops-section">低庫存商品（庫存 ≤ {{ dashboard.lowStock.threshold }}）</h3>
      <p v-if="!dashboard.lowStock.items.length" class="field__hint" data-testid="ops-lowstock-empty">沒有低庫存商品</p>
      <template v-else>
        <ul class="ops-lowstock">
          <li v-for="item in dashboard.lowStock.items" :key="item.productId" data-testid="ops-lowstock-item">
            <strong>{{ item.productName }}</strong>
            <span class="field__hint">{{ item.productId }}</span>
            <span :class="['badge', item.quantity === 0 ? 'badge--warn' : 'badge--muted']">庫存 {{ item.quantity }}</span>
          </li>
        </ul>
        <p v-if="dashboard.lowStock.count > dashboard.lowStock.items.length" class="field__hint" data-testid="ops-lowstock-more">
          僅顯示前 {{ dashboard.lowStock.items.length }} 項，共 {{ dashboard.lowStock.count }} 項
        </p>
      </template>
    </template>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import api from '../api'
import { errorText, formatPrice } from '../format'

// ADMIN-only, read-only. The backend authorises every request; this panel only presents the aggregates.
defineEmits(['close'])

const REDIS_STATES = {
  ENABLED: { badge: 'badge--ok', label: '運作中' },
  DISABLED: { badge: 'badge--muted', label: '未啟用' },
  LATCHED_DB_ONLY: { badge: 'badge--warn', label: '已降級為僅資料庫，需維護後重啟' }
}

const dashboard = ref(null)
const loading = ref(true)
const error = ref('')

// asOf / today.from are ISO strings already in Asia/Taipei (+08:00), so they are sliced rather than re-parsed
// through the browser's own zone.
const asOfText = computed(() => (dashboard.value ? dashboard.value.asOf.slice(0, 19).replace('T', ' ') : ''))
const dayText = computed(() => (dashboard.value ? dashboard.value.today.from.slice(0, 10) : ''))
const redis = computed(() => REDIS_STATES[dashboard.value?.redis?.mode] || { badge: 'badge--muted', label: dashboard.value?.redis?.mode || '未知' })

let latestRequest = 0
async function load () {
  const request = ++latestRequest
  loading.value = true
  error.value = ''
  try {
    const { data } = await api.get('/admin/dashboard')
    if (request !== latestRequest) return
    dashboard.value = data.data
  } catch (e) {
    if (request === latestRequest) error.value = errorText(e, '載入儀表板失敗，請稍後再試')
  } finally {
    if (request === latestRequest) loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.ops-head__actions { display: flex; gap: 8px; }
.ops-stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 10px; margin: 12px 0; }
.ops-stat { border: 1px solid var(--border, #e5e7eb); border-radius: 10px; padding: 10px 12px; margin: 0; }
.ops-stat dt { font-size: 0.8125rem; color: var(--muted, #6b7280); }
.ops-stat dd { margin: 2px 0; font-size: 1.5rem; font-weight: 700; }
.ops-stat--warn { color: var(--warn, #b45309); }
.ops-section { font-size: 0.9375rem; font-weight: 700; margin: 16px 0 6px; }
.ops-lowstock { list-style: none; margin: 0; padding: 0; display: grid; gap: 6px; }
.ops-lowstock li { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.ops-error { display: grid; gap: 8px; justify-items: start; }
</style>

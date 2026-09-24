<template>
  <section class="panel audit-panel" aria-labelledby="audit-title">
    <div class="panel__head">
      <h2 id="audit-title" class="panel__title">操作稽核日誌</h2>
      <button type="button" class="link-button" @click="$emit('close')">關閉</button>
    </div>

    <form class="audit-filter" @submit.prevent="applyFilters">
      <div class="field">
        <label for="audit-action">動作</label>
        <select id="audit-action" v-model="form.action">
          <option value="">全部</option>
          <option v-for="(label, value) in ACTION_LABELS" :key="value" :value="value">{{ label }}</option>
        </select>
      </div>
      <div class="field">
        <label for="audit-actor">操作者 Email</label>
        <input id="audit-actor" v-model.trim="form.actor" type="text" placeholder="完整 Email" />
      </div>
      <div class="field">
        <label for="audit-target">目標編號</label>
        <input id="audit-target" v-model.trim="form.targetId" type="text" placeholder="商品 / 訂單編號 / 評論 ID / 優惠券 ID" />
      </div>
      <div class="field">
        <label for="audit-from">起</label>
        <input id="audit-from" v-model="form.from" type="datetime-local" />
      </div>
      <div class="field">
        <label for="audit-to">迄</label>
        <input id="audit-to" v-model="form.to" type="datetime-local" />
      </div>
      <div class="audit-filter__actions field">
        <button type="submit" class="btn btn--primary btn--sm">查詢</button>
        <button type="button" class="btn btn--ghost btn--sm" @click="resetFilters">清除</button>
      </div>
    </form>

    <p v-if="loading" role="status">載入稽核日誌中…</p>
    <p v-else-if="!entries.length" class="field__hint">沒有符合條件的稽核紀錄</p>
    <ul v-else class="audit-list">
      <li v-for="entry in entries" :key="entry.id" class="audit-entry">
        <div class="audit-entry__head">
          <span class="badge badge--warn">{{ actionLabel(entry.action) }}</span>
          <strong>{{ entry.targetType }} · {{ entry.targetId }}</strong>
          <span class="field__hint audit-entry__time">{{ formatTime(entry.createdAt) }}</span>
        </div>
        <p class="field__hint">{{ entry.actor }}（{{ entry.actorRole }}）</p>
        <ul class="audit-diff" :aria-label="`稽核紀錄 ${entry.id} 的變更內容`">
          <li v-for="row in diffRows(entry)" :key="row.key">
            <span class="audit-diff__key">{{ row.key }}</span>
            <span v-if="row.before !== undefined" class="audit-diff__before">{{ row.before }}</span>
            <span v-if="row.before !== undefined" aria-hidden="true">→</span>
            <span class="audit-diff__after">{{ row.after }}</span>
          </li>
        </ul>
      </li>
    </ul>

    <div v-if="totalPages > 1" class="audit-pager">
      <button type="button" class="btn btn--ghost btn--sm" :disabled="page === 0 || loading" @click="goPage(page - 1)">上一頁</button>
      <span>第 {{ page + 1 }} / {{ totalPages }} 頁（共 {{ total }} 筆）</span>
      <button type="button" class="btn btn--ghost btn--sm" :disabled="page + 1 >= totalPages || loading" @click="goPage(page + 1)">下一頁</button>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import api from '../api'
import { useToast } from '../composables/toast'
import { errorText } from '../format'

// Read-only view for maintainers (ADMIN). The backend enforces the role; this panel only presents the trail.
defineEmits(['close'])

const toast = useToast()

const ACTION_LABELS = {
  PRODUCT_CREATE: '建立商品',
  PRODUCT_UPDATE: '修改商品',
  PRODUCT_DELETE: '刪除商品',
  PRODUCT_RESTOCK: '商品補貨',
  PRODUCT_IMAGE_UPLOAD: '上傳商品圖片',
  ORDER_STATUS_CHANGE: '訂單狀態變更',
  REVIEW_VISIBILITY_CHANGE: '評論顯示切換',
  COUPON_CREATE: '建立優惠券',
  COUPON_UPDATE: '修改優惠券'
}
const size = 20

const entries = ref([])
const total = ref(0)
const page = ref(0)
const loading = ref(true)
const form = reactive({ action: '', actor: '', targetId: '', from: '', to: '' })
// Filters are applied from a snapshot so typing in the form does not silently change paging requests.
const applied = ref({})
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size)))

const actionLabel = (action) => ACTION_LABELS[action] || action
const formatTime = (value) => (value ? String(value).replace('T', ' ').slice(0, 19) : '')
// datetime-local yields "YYYY-MM-DDTHH:mm"; the backend expects a full ISO local date-time.
const toIso = (value) => (value && value.length === 16 ? `${value}:00` : value)

const stringify = (value) => (value !== null && typeof value === 'object' ? JSON.stringify(value) : String(value))

// Show only what changed. Creations (no before) list every recorded field.
const diffRows = (entry) => {
  const before = entry.before || null
  const after = entry.after || {}
  const keys = [...new Set([...Object.keys(before || {}), ...Object.keys(after)])]
  return keys
    .filter((key) => !before || stringify(before[key]) !== stringify(after[key]))
    .map((key) => ({
      key,
      before: before && key in before ? stringify(before[key]) : undefined,
      after: key in after ? stringify(after[key]) : '—'
    }))
}

// Only the newest request may update the list, so rapid filter/page clicks cannot show stale data.
let latestRequest = 0
const load = async () => {
  const request = ++latestRequest
  loading.value = true
  try {
    const params = { page: page.value, size }
    Object.entries(applied.value).forEach(([key, value]) => {
      if (value) params[key] = value
    })
    const { data } = await api.get('/admin/audit-logs', { params })
    if (request !== latestRequest) return
    entries.value = data.data?.entries || []
    total.value = data.data?.total || 0
  } catch (error) {
    if (request === latestRequest) toast.error(errorText(error, '載入稽核日誌失敗，請稍後再試'))
  } finally {
    if (request === latestRequest) loading.value = false
  }
}

const applyFilters = () => {
  applied.value = {
    action: form.action, actor: form.actor, targetId: form.targetId, from: toIso(form.from), to: toIso(form.to)
  }
  page.value = 0
  load()
}
const resetFilters = () => {
  Object.assign(form, { action: '', actor: '', targetId: '', from: '', to: '' })
  applyFilters()
}
const goPage = (value) => {
  page.value = value
  load()
}

onMounted(load)
</script>

<style scoped>
.audit-filter { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 10px; align-items: end; margin-bottom: 12px; }
.audit-filter__actions { display: flex; gap: 8px; }
.audit-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 10px; }
.audit-entry { border: 1px solid var(--border, #e5e7eb); border-radius: 10px; padding: 10px 12px; }
.audit-entry__head { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.audit-entry__time { margin-left: auto; }
.audit-diff { list-style: none; margin: 6px 0 0; padding: 0; display: grid; gap: 2px; font-size: 0.875rem; }
.audit-diff li { display: flex; flex-wrap: wrap; gap: 6px; align-items: baseline; }
.audit-diff__key { font-weight: 600; }
.audit-diff__before { text-decoration: line-through; color: var(--muted, #6b7280); }
.audit-pager { display: flex; justify-content: center; align-items: center; gap: 12px; margin-top: 12px; }
</style>

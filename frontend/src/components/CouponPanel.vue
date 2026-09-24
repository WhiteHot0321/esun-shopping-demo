<template>
  <section class="panel coupon-panel" aria-labelledby="coupon-title">
    <div class="panel__head">
      <h2 id="coupon-title" class="panel__title">優惠券管理</h2>
      <button type="button" class="link-button" @click="$emit('close')">關閉</button>
    </div>

    <form class="coupon-form" novalidate @submit.prevent="submit">
      <div class="field">
        <label for="cp-code">優惠碼</label>
        <input id="cp-code" v-model.trim="form.code" maxlength="32" autocomplete="off" placeholder="英數、底線、連字號（3-32）" />
      </div>
      <div class="field">
        <label for="cp-type">折扣類型</label>
        <select id="cp-type" v-model="form.discountType">
          <option value="PERCENT">百分比折扣</option>
          <option value="FIXED">固定金額折扣</option>
        </select>
      </div>
      <div class="field">
        <label for="cp-value">{{ form.discountType === 'PERCENT' ? '折扣 %（1-99）' : '折抵金額（元）' }}</label>
        <input id="cp-value" v-model="form.discountValue" inputmode="numeric" />
      </div>
      <div v-if="form.discountType === 'PERCENT'" class="field">
        <label for="cp-max">折扣上限（元，選填）</label>
        <input id="cp-max" v-model="form.maxDiscount" inputmode="numeric" />
      </div>
      <div class="field">
        <label for="cp-min">最低消費（元，選填）</label>
        <input id="cp-min" v-model="form.minOrderAmount" inputmode="decimal" />
      </div>
      <div class="field">
        <label for="cp-quota">總發行量（選填，空白為不限）</label>
        <input id="cp-quota" v-model="form.totalQuota" inputmode="numeric" />
      </div>
      <div class="field">
        <label for="cp-member">每人使用上限</label>
        <input id="cp-member" v-model="form.perMemberLimit" inputmode="numeric" />
      </div>
      <div class="field">
        <label for="cp-start">開始時間</label>
        <input id="cp-start" v-model="form.startsAt" type="datetime-local" />
      </div>
      <div class="field">
        <label for="cp-end">到期時間</label>
        <input id="cp-end" v-model="form.expiresAt" type="datetime-local" />
      </div>
      <p v-if="formError" class="field__error coupon-form__error" role="alert">{{ formError }}</p>
      <div class="coupon-form__actions">
        <button type="submit" class="btn btn--primary btn--sm" :disabled="saving">{{ saving ? '建立中…' : '建立優惠券' }}</button>
      </div>
    </form>

    <p v-if="loading" role="status">載入優惠券中…</p>
    <p v-else-if="!coupons.length" class="field__hint">尚未建立任何優惠券</p>
    <ul v-else class="coupon-list">
      <li v-for="coupon in coupons" :key="coupon.id" class="coupon-entry" data-testid="coupon-entry">
        <div class="coupon-entry__head">
          <strong>{{ coupon.code }}</strong>
          <span class="badge" :class="statusClass(coupon.status)">{{ statusLabel(coupon.status) }}</span>
          <button type="button" class="btn btn--ghost btn--sm coupon-entry__toggle" :disabled="busyId === coupon.id"
            @click="toggle(coupon)">{{ coupon.active ? '停用' : '啟用' }}</button>
        </div>
        <p class="field__hint">{{ ruleText(coupon) }}</p>
        <p class="field__hint">
          已使用 {{ coupon.usedCount }}{{ coupon.totalQuota ? ` / ${coupon.totalQuota}` : '（不限量）' }}，每人 {{ coupon.perMemberLimit }} 次 ·
          {{ formatTime(coupon.startsAt) }} ~ {{ formatTime(coupon.expiresAt) }}
        </p>
      </li>
    </ul>

    <div v-if="totalPages > 1" class="coupon-pager">
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
import { errorText, formatPrice } from '../format'

// ADMIN-only management view. The backend enforces the role; rule fields are immutable after creation, so the only
// edit offered here is enabling/disabling.
defineEmits(['close'])

const toast = useToast()
const size = 20
const STATUS = {
  ACTIVE: ['使用中', 'badge--ok'],
  SCHEDULED: ['尚未開始', 'badge--warn'],
  EXPIRED: ['已過期', 'badge--muted'],
  EXHAUSTED: ['已領完', 'badge--muted'],
  DISABLED: ['已停用', 'badge--muted']
}

const coupons = ref([])
const total = ref(0)
const page = ref(0)
const loading = ref(true)
const saving = ref(false)
const busyId = ref(null)
const formError = ref('')
const emptyForm = () => ({
  code: '', discountType: 'PERCENT', discountValue: '', maxDiscount: '', minOrderAmount: '',
  totalQuota: '', perMemberLimit: '1', startsAt: '', expiresAt: ''
})
const form = reactive(emptyForm())
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size)))

const statusLabel = (status) => (STATUS[status] || [status])[0]
const statusClass = (status) => (STATUS[status] || [])[1] || ''
const formatTime = (value) => (value ? String(value).replace('T', ' ').slice(0, 16) : '')
const toIso = (value) => (value && value.length === 16 ? `${value}:00` : value)
const ruleText = (coupon) => {
  const base = coupon.discountType === 'PERCENT'
    ? `${coupon.discountValue}% off${coupon.maxDiscount ? `（最高折抵 ${formatPrice(coupon.maxDiscount)}）` : ''}`
    : `折抵 ${formatPrice(coupon.discountValue)}`
  return Number(coupon.minOrderAmount) > 0 ? `${base}，滿 ${formatPrice(coupon.minOrderAmount)} 可用` : base
}

const wholeNumber = (value) => /^\d+$/.test(String(value).trim())

// Blank optional fields are omitted so the server applies its own defaults.
const buildPayload = () => {
  if (!form.code) return { error: '請輸入優惠碼' }
  if (!wholeNumber(form.discountValue)) return { error: '折扣值必須為正整數' }
  const value = Number(form.discountValue)
  if (form.discountType === 'PERCENT' && (value < 1 || value > 99)) return { error: '百分比折扣必須介於 1 到 99' }
  if (form.discountType === 'FIXED' && value < 1) return { error: '折抵金額至少為 1' }
  if (form.discountType === 'PERCENT' && form.maxDiscount !== '' && !wholeNumber(form.maxDiscount)) {
    return { error: '折扣上限必須為正整數' }
  }
  if (form.totalQuota !== '' && !wholeNumber(form.totalQuota)) return { error: '總發行量必須為正整數' }
  if (!wholeNumber(form.perMemberLimit) || Number(form.perMemberLimit) < 1) return { error: '每人使用上限必須為正整數' }
  if (!form.startsAt || !form.expiresAt) return { error: '請選擇開始與到期時間' }
  if (form.expiresAt <= form.startsAt) return { error: '到期時間必須晚於開始時間' }
  const payload = {
    code: form.code,
    discountType: form.discountType,
    discountValue: value,
    perMemberLimit: Number(form.perMemberLimit),
    startsAt: toIso(form.startsAt),
    expiresAt: toIso(form.expiresAt)
  }
  if (form.discountType === 'PERCENT' && form.maxDiscount !== '') payload.maxDiscount = Number(form.maxDiscount)
  if (form.minOrderAmount !== '') payload.minOrderAmount = Number(form.minOrderAmount)
  if (form.totalQuota !== '') payload.totalQuota = Number(form.totalQuota)
  return { payload }
}

// Only the newest request may update the list, so rapid paging clicks cannot show stale data.
let latestRequest = 0
const load = async () => {
  const request = ++latestRequest
  loading.value = true
  try {
    const { data } = await api.get('/admin/coupons', { params: { page: page.value, size } })
    if (request !== latestRequest) return
    coupons.value = data.data?.items || []
    total.value = data.data?.total || 0
  } catch (error) {
    if (request === latestRequest) toast.error(errorText(error, '載入優惠券失敗，請稍後再試'))
  } finally {
    if (request === latestRequest) loading.value = false
  }
}

const submit = async () => {
  if (saving.value) return
  const { payload, error } = buildPayload()
  formError.value = error || ''
  if (error) return
  saving.value = true
  try {
    await api.post('/admin/coupons', payload)
    toast.success(`優惠券 ${payload.code.toUpperCase()} 已建立`)
    Object.assign(form, emptyForm())
    page.value = 0
    await load()
  } catch (err) {
    formError.value = errorText(err, '優惠券建立失敗，請稍後再試')
  } finally {
    saving.value = false
  }
}

const toggle = async (coupon) => {
  busyId.value = coupon.id
  try {
    // Dedicated endpoint: it changes only `active`, so a stale list row can never overwrite someone else's edit.
    await api.post(`/admin/coupons/${coupon.id}/active`, { active: !coupon.active })
    await load()
  } catch (error) {
    toast.error(errorText(error, '更新優惠券失敗，請稍後再試'))
  } finally {
    busyId.value = null
  }
}

const goPage = (value) => {
  page.value = value
  load()
}

onMounted(load)
</script>

<style scoped>
.coupon-form { display: grid; grid-template-columns: repeat(auto-fit, minmax(190px, 1fr)); gap: 10px; align-items: end; margin-bottom: 16px; }
.coupon-form__error, .coupon-form__actions { grid-column: 1 / -1; }
.coupon-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 10px; }
.coupon-entry { border: 1px solid var(--border, #e5e7eb); border-radius: 10px; padding: 10px 12px; }
.coupon-entry__head { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.coupon-entry__toggle { margin-left: auto; }
.coupon-pager { display: flex; justify-content: center; align-items: center; gap: 12px; margin-top: 12px; }
</style>

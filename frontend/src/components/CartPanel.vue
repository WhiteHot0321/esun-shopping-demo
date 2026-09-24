<template>
  <section id="cart" class="panel cart" aria-labelledby="cart-title">
    <div class="panel__head">
      <h2 id="cart-title" class="panel__title">購物車 <span v-if="count" class="count-pill">{{ count }}</span></h2>
      <button v-if="items.length" type="button" class="link-button" :disabled="busy" @click="$emit('clear')">清空</button>
    </div>

    <div v-if="pendingAttempt" class="notice notice--warn" role="alert">
      <p class="notice__title">上一筆訂單結果尚未確認</p>
      <p>送出後連線中斷或伺服器異常，無法確定訂單是否已成立。請按下方按鈕重新確認：
        系統會用同一個訂單識別碼重送，<strong>不會重複建立訂單</strong>。</p>
      <p class="muted">待確認內容：{{ pendingSummary }}</p>
      <button type="button" class="btn btn--warn btn--block" :disabled="busy" @click="$emit('retry')">
        <span v-if="busy" class="spinner" aria-hidden="true"></span>{{ busy ? '確認中…' : '重試未確認訂單' }}
      </button>
    </div>

    <p v-if="!items.length" class="cart__empty muted">尚未選擇商品，請在左側商品卡片調整購買數量。</p>

    <ul v-else class="cart__list">
      <li v-for="item in items" :key="item.productId" class="cart__line">
        <div class="cart__info">
          <p class="cart__name">{{ item.productName }}</p>
          <p class="muted">{{ formatPrice(item.price) }} × {{ item.quantity }}</p>
        </div>
        <div class="cart__controls">
          <QuantityStepper compact :model-value="item.quantity" :max="item.stock" :label="item.productName" :disabled="busy"
            @update:model-value="$emit('set-quantity', item.productId, $event)" />
          <p class="cart__subtotal">{{ formatPrice(item.itemPrice) }}</p>
        </div>
      </li>
    </ul>

    <div class="cart__total">
      <span>總金額</span>
      <strong data-testid="cart-total">{{ formatPrice(total) }}</strong>
    </div>
    <template v-if="applied">
      <div class="cart__total">
        <span>優惠折抵（{{ applied.code }}）</span>
        <strong data-testid="cart-discount">−{{ formatPrice(applied.discountAmount) }}</strong>
      </div>
      <div class="cart__total">
        <span>應付金額</span>
        <strong data-testid="cart-payable">{{ formatPrice(applied.total) }}</strong>
      </div>
    </template>

    <form class="checkout" novalidate @submit.prevent="submitCheckout">
      <template v-if="authenticated">
        <div class="field">
          <label for="member-id">會員編號</label>
          <input id="member-id" v-model.trim="form.memberId" autocomplete="off" :disabled="busy" />
          <p class="field__hint">預設為登入 Email，可視需要修改</p>
        </div>

        <div class="field address-book">
          <label for="shipping-address">收件地址</label>
          <select id="shipping-address" v-model="form.shippingAddressId" :disabled="busy || addressBusy">
            <option :value="null" disabled>{{ addressBusy ? '地址載入中…' : '請選擇收件地址' }}</option>
            <option v-for="entry in addresses" :key="entry.id" :value="entry.id">
              {{ entry.isDefault ? '★ ' : '' }}{{ entry.label }} — {{ entry.receiverName }}，{{ entry.address }}
            </option>
          </select>
          <button type="button" class="link-button" :disabled="busy" @click="showAddressForm = !showAddressForm">
            {{ showAddressForm ? '取消新增' : '新增收件地址' }}
          </button>
          <p v-if="addressError" class="field__error" role="alert">{{ addressError }}</p>
        </div>

        <div v-if="showAddressForm" class="notice" data-testid="new-address-form">
          <div class="field"><label for="address-label">標籤</label><input id="address-label" v-model.trim="newAddress.label" maxlength="50" placeholder="住家 / 公司" /></div>
          <div class="field"><label for="receiver-name">收件人</label><input id="receiver-name" v-model.trim="newAddress.receiverName" maxlength="100" autocomplete="name" /></div>
          <div class="field"><label for="address-phone">電話</label><input id="address-phone" v-model.trim="newAddress.phone" maxlength="30" autocomplete="tel" /></div>
          <div class="field"><label for="postal-code">郵遞區號</label><input id="postal-code" v-model.trim="newAddress.postalCode" maxlength="10" autocomplete="postal-code" /></div>
          <div class="field"><label for="street-address">地址</label><input id="street-address" v-model.trim="newAddress.address" maxlength="255" autocomplete="street-address" /></div>
          <label><input v-model="newAddress.isDefault" type="checkbox" /> 設為預設地址</label>
          <button type="button" class="btn btn--secondary btn--block" :disabled="addressBusy" @click="createAddress">
            {{ addressBusy ? '儲存中…' : '儲存地址' }}
          </button>
        </div>

        <div v-if="items.length" class="field coupon">
          <label for="coupon-code">優惠碼</label>
          <div class="coupon__row">
            <input id="coupon-code" v-model.trim="couponInput" maxlength="32" autocomplete="off" placeholder="輸入優惠碼"
              :disabled="couponLocked || Boolean(applied)" @keydown.enter.prevent="applyCoupon" />
            <button v-if="!applied" type="button" class="btn btn--secondary btn--sm"
              :disabled="couponLocked || !couponInput" @click="applyCoupon">
              {{ couponBusy ? '確認中…' : '套用' }}
            </button>
            <button v-else type="button" class="link-button" :disabled="couponLocked" @click="removeCoupon">移除</button>
          </div>
          <p v-if="couponError" class="field__error" role="alert">{{ couponError }}</p>
          <p v-else-if="couponNotice" class="field__hint">{{ couponNotice }}</p>
          <p v-else-if="applied" class="field__hint" data-testid="coupon-applied">
            已套用，實際折扣以建立訂單時為準
          </p>
        </div>

        <p class="field__hint">建立訂單後，請至「我的訂單」完成付款。</p>
      </template>

      <button type="submit" class="btn btn--primary btn--block btn--lg"
        :disabled="busy || !authenticated || Boolean(pendingAttempt) || !items.length || !form.shippingAddressId">
        <span v-if="busy && !pendingAttempt" class="spinner" aria-hidden="true"></span>
        {{ busy && !pendingAttempt ? '訂單送出中…' : '建立訂單' }}
      </button>
      <p v-if="!authenticated" class="field__hint center">請先登入會員才能建立訂單</p>
      <p v-else-if="!items.length && !pendingAttempt" class="field__hint center">選擇商品後即可建立訂單</p>
      <p v-else-if="!form.shippingAddressId" class="field__hint center">請先選擇或新增收件地址</p>
    </form>
  </section>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import api from '../api'
import QuantityStepper from './QuantityStepper.vue'
import { errorText, formatPrice } from '../format'

const props = defineProps({
  items: { type: Array, required: true },
  total: { type: Number, default: 0 },
  form: { type: Object, required: true },
  authenticated: Boolean,
  busy: Boolean,
  pendingAttempt: { type: Object, default: null },
  // async (code) => { code, subtotal, discountAmount, total }; prices the caller's server-side cart (advisory only).
  previewCoupon: { type: Function, default: null }
})
const emit = defineEmits(['set-quantity', 'clear', 'checkout', 'retry'])

const count = computed(() => props.items.reduce((sum, item) => sum + item.quantity, 0))
const addresses = ref([])
const addressBusy = ref(false)
const addressError = ref('')
const showAddressForm = ref(false)
const newAddress = reactive({ label: '', receiverName: '', phone: '', postalCode: '', address: '', isDefault: false })

const loadAddresses = async () => {
  if (!props.authenticated) {
    addresses.value = []
    props.form.shippingAddressId = null
    return
  }
  addressBusy.value = true
  addressError.value = ''
  try {
    const { data } = await api.get('/member/addresses')
    addresses.value = Array.isArray(data.data) ? data.data : []
    const selectedStillExists = addresses.value.some(entry => entry.id === props.form.shippingAddressId)
    if (!selectedStillExists) {
      props.form.shippingAddressId = addresses.value.find(entry => entry.isDefault)?.id || addresses.value[0]?.id || null
    }
  } catch {
    addressError.value = '收件地址載入失敗，請稍後再試'
  } finally {
    addressBusy.value = false
  }
}

const createAddress = async () => {
  addressError.value = ''
  if (!newAddress.label || !newAddress.receiverName || !newAddress.phone || !newAddress.address) {
    addressError.value = '請填寫標籤、收件人、電話與地址'
    return
  }
  addressBusy.value = true
  try {
    const payload = { ...newAddress }
    const { data } = await api.post('/member/addresses', payload)
    addresses.value = [...addresses.value.map(entry => ({ ...entry, isDefault: data.data.isDefault ? false : entry.isDefault })), data.data]
      .sort((a, b) => Number(b.isDefault) - Number(a.isDefault) || a.id - b.id)
    props.form.shippingAddressId = data.data.id
    Object.assign(newAddress, { label: '', receiverName: '', phone: '', postalCode: '', address: '', isDefault: false })
    showAddressForm.value = false
  } catch (error) {
    addressError.value = error.response?.data?.message || '地址儲存失敗，請稍後再試'
  } finally {
    addressBusy.value = false
  }
}

// The coupon is only ever *named* by the client; the server prices it again under lock at checkout. `form.couponCode`
// is set only after a successful preview so a code that was merely typed is never submitted.
const couponInput = ref('')
const applied = ref(null)
const couponBusy = ref(false)
const couponError = ref('')
const couponNotice = ref('')
const couponLocked = computed(() => props.busy || couponBusy.value || Boolean(props.pendingAttempt))

const resetCoupon = (notice = '') => {
  applied.value = null
  props.form.couponCode = ''
  couponError.value = ''
  couponNotice.value = notice
}

const applyCoupon = async () => {
  if (!couponInput.value || couponLocked.value || !props.previewCoupon) return
  couponBusy.value = true
  couponError.value = ''
  couponNotice.value = ''
  const totalAtRequest = props.total
  try {
    const preview = await props.previewCoupon(couponInput.value)
    // The cart moved while the preview was in flight: that amount describes an older cart, so show nothing.
    if (props.total !== totalAtRequest) {
      couponNotice.value = '購物車內容已變更，請重新套用優惠碼'
      return
    }
    applied.value = preview
    props.form.couponCode = preview.code
    couponInput.value = preview.code
  } catch (error) {
    resetCoupon()
    couponError.value = errorText(error, '優惠碼驗證失敗，請稍後再試')
  } finally {
    couponBusy.value = false
  }
}

const removeCoupon = () => {
  resetCoupon()
  couponInput.value = ''
}

// Any cart change invalidates the previewed amount, so drop it instead of showing a stale discount.
watch(() => props.total, () => {
  if (!applied.value) return
  resetCoupon(props.items.length ? '購物車內容已變更，請重新套用優惠碼' : '')
  if (!props.items.length) couponInput.value = ''
})
watch(() => props.authenticated, (value) => {
  if (!value) removeCoupon()
})

const submitCheckout = () => {
  if (!props.form.shippingAddressId) {
    addressError.value = '請先選擇或新增收件地址'
    return
  }
  emit('checkout')
}

watch(() => props.authenticated, loadAddresses, { immediate: true })
const pendingSummary = computed(() => {
  const lines = props.pendingAttempt?.payload?.items || []
  const units = lines.reduce((sum, line) => sum + line.quantity, 0)
  return `${lines.length} 項商品，共 ${units} 件`
})
</script>

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

    <form class="checkout" novalidate @submit.prevent="$emit('checkout')">
      <template v-if="authenticated">
        <div class="field">
          <label for="member-id">會員編號</label>
          <input id="member-id" v-model.trim="form.memberId" autocomplete="off" :disabled="busy" />
          <p class="field__hint">預設為登入 Email，可視需要修改</p>
        </div>

        <fieldset class="field">
          <legend>付款狀態</legend>
          <div class="segmented segmented--full">
            <label v-for="option in payOptions" :key="option.value" :class="{ active: form.payStatus === option.value }">
              <input v-model="form.payStatus" class="visually-hidden" type="radio" name="pay-status" :value="option.value"
                :disabled="busy" />{{ option.label }}
            </label>
          </div>
        </fieldset>
      </template>

      <button type="submit" class="btn btn--primary btn--block btn--lg"
        :disabled="busy || !authenticated || Boolean(pendingAttempt) || !items.length">
        <span v-if="busy && !pendingAttempt" class="spinner" aria-hidden="true"></span>
        {{ busy && !pendingAttempt ? '訂單送出中…' : '建立訂單' }}
      </button>
      <p v-if="!authenticated" class="field__hint center">請先登入會員才能建立訂單</p>
      <p v-else-if="!items.length && !pendingAttempt" class="field__hint center">選擇商品後即可建立訂單</p>
    </form>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import QuantityStepper from './QuantityStepper.vue'
import { formatPrice } from '../format'

const props = defineProps({
  items: { type: Array, required: true },
  total: { type: Number, default: 0 },
  form: { type: Object, required: true },
  authenticated: Boolean,
  busy: Boolean,
  pendingAttempt: { type: Object, default: null }
})
defineEmits(['set-quantity', 'clear', 'checkout', 'retry'])

const payOptions = [{ value: 'PENDING', label: '未付款' }, { value: 'PAID', label: '已付款' }]
const count = computed(() => props.items.reduce((sum, item) => sum + item.quantity, 0))
const pendingSummary = computed(() => {
  const lines = props.pendingAttempt?.payload?.items || []
  const units = lines.reduce((sum, line) => sum + line.quantity, 0)
  return `${lines.length} 項商品，共 ${units} 件`
})
</script>

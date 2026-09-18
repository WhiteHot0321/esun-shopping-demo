<template>
  <details class="panel product-form" :open="open" @toggle="open = $event.target.open">
    <summary class="panel__title">上架新商品</summary>

    <form class="product-form__grid" novalidate @submit.prevent="submit">
      <div v-for="field in fields" :key="field.key" class="field">
        <label :for="`product-${field.key}`">{{ field.label }}</label>
        <input :id="`product-${field.key}`" v-model="product[field.key]" :type="field.type || 'text'"
          :inputmode="field.inputmode" :maxlength="field.maxlength" :min="field.min" :step="field.step"
          :placeholder="field.placeholder" :disabled="busy" :aria-invalid="Boolean(shownErrors[field.key])"
          @blur="touched[field.key] = true" />
        <p v-if="shownErrors[field.key]" class="field__error">{{ shownErrors[field.key] }}</p>
      </div>
      <div class="product-form__actions">
        <button type="button" class="btn btn--ghost" :disabled="busy" @click="reset">清除</button>
        <button type="submit" class="btn btn--primary" :disabled="busy">
          <span v-if="busy" class="spinner" aria-hidden="true"></span>{{ busy ? '新增中…' : '新增商品' }}
        </button>
      </div>
    </form>
  </details>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import api from '../api'
import { useToast } from '../composables/toast'
import { errorText } from '../format'

const emit = defineEmits(['created'])
const toast = useToast()
const open = ref(false)
const busy = ref(false)

// Limits mirror CreateProductRequest's bean-validation constraints on the backend.
const fields = [
  { key: 'productId', label: '商品編號', placeholder: '例如 P010（最多 20 字）', maxlength: 20 },
  { key: 'productName', label: '商品名稱', placeholder: '最多 100 字', maxlength: 100 },
  { key: 'price', label: '價格（NT$）', type: 'number', inputmode: 'decimal', min: 0, step: '0.01', placeholder: '0' },
  { key: 'quantity', label: '庫存數量', type: 'number', inputmode: 'numeric', min: 0, step: 1, placeholder: '0' }
]
const blank = () => ({ productId: '', productName: '', price: '', quantity: '' })
const product = reactive(blank())
const touched = reactive({})

const isBlank = (value) => value === '' || value === null || value === undefined
const errors = computed(() => ({
  productId: !String(product.productId).trim() ? '請輸入商品編號' : '',
  productName: !String(product.productName).trim() ? '請輸入商品名稱' : '',
  price: isBlank(product.price) ? '請輸入價格' : Number(product.price) < 0 ? '價格不可為負數' : '',
  quantity: isBlank(product.quantity) ? '請輸入庫存數量'
    : !Number.isInteger(Number(product.quantity)) || Number(product.quantity) < 0 ? '庫存需為 0 以上的整數' : ''
}))
const shownErrors = computed(() => Object.fromEntries(
  Object.entries(errors.value).map(([key, message]) => [key, touched[key] ? message : ''])
))

const reset = () => {
  Object.assign(product, blank())
  fields.forEach(({ key }) => { touched[key] = false })
}

const submit = async () => {
  if (busy.value) return
  fields.forEach(({ key }) => { touched[key] = true })
  const firstInvalid = fields.find(({ key }) => errors.value[key])
  if (firstInvalid) {
    document.getElementById(`product-${firstInvalid.key}`)?.focus()
    return
  }
  busy.value = true
  try {
    await api.post('/products', {
      productId: product.productId.trim(),
      productName: product.productName.trim(),
      price: Number(product.price),
      quantity: Number(product.quantity)
    })
    toast.success(`商品「${product.productName.trim()}」新增成功`)
    reset()
    emit('created')
  } catch (error) {
    toast.error(errorText(error, '商品新增失敗，請稍後再試'))
  } finally {
    busy.value = false
  }
}
</script>

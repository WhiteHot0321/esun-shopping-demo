<template>
    <section class="card">
      <div class="header-row"><h2>建立訂單</h2><button @click="loadProducts">重新載入商品</button></div>
      <table><thead><tr><th>商品編號</th><th>商品名稱</th><th>售價</th><th>庫存</th><th>購買數量</th></tr></thead>
        <tbody><tr v-for="item in products" :key="item.productId"><td>{{ item.productId }}</td><td>{{ item.productName }}</td><td>{{ item.price }}</td><td>{{ item.quantity }}</td>
          <td><input type="number" min="0" :max="item.quantity" v-model.number="quantities[item.productId]" /></td></tr></tbody>
      </table>
      <div class="order-form"><span class="badge">訂單建立後可前往付款</span></div>
      <div class="summary"><h3>訂單預覽</h3><ul v-if="selected.length"><li v-for="item in selected" :key="item.productId">{{ item.productName }} × {{ item.quantity }} = {{ item.itemPrice }}</li></ul><p v-else>尚未選擇商品</p>
        <p><strong>總金額：{{ total }}</strong></p><button :disabled="busy" @click="submitOrder">建立訂單</button><button v-if="attempt" :disabled="busy" @click="retry">重試未確認訂單</button>
        <button v-if="paymentOrderId" :disabled="paymentBusy" @click="startPayment">前往付款</button></div>
    </section>
</template>
<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import api from '../api'
import { createCheckoutLifecycle } from '../checkout'
const props = defineProps({ authenticated: Boolean, refreshKey: Number })
const emit = defineEmits(['message'])
const products = ref([]), quantities = reactive({})
const lifecycle = createCheckoutLifecycle(), attempt = ref(null), busy = ref(false), paymentOrderId = ref(''), paymentBusy = ref(false)
const selected = computed(() => products.value.filter(p => Number(quantities[p.productId] || 0) > 0).map(p => ({ ...p, quantity: Number(quantities[p.productId]), itemPrice: Number(p.price) * Number(quantities[p.productId]) })))
const total = computed(() => selected.value.reduce((sum, item) => sum + item.itemPrice, 0))
const errorText = e => e.response?.data?.message || '操作失敗'
const loadProducts = async () => { try { const { data } = await api.get('/products/available'); products.value = data.data || []; products.value.forEach(p => { if (quantities[p.productId] == null) quantities[p.productId] = 0 }) } catch (e) { emit('message', errorText(e)) } }
const finish = async promise => { busy.value = true; attempt.value = lifecycle.attempt; const result = await promise; busy.value = false; attempt.value = lifecycle.attempt; if (result.status === 'success') { const orderId = result.result.data.data.orderId; paymentOrderId.value = orderId; emit('message', `訂單建立成功，訂單編號：${orderId}`); Object.keys(quantities).forEach(k => { quantities[k] = 0 }); await loadProducts() } else if (result.status === 'retry-required') emit('message', '上一筆訂單結果尚未確認，請使用重試未確認訂單'); else if (result.status === 'failure') emit('message', errorText(result.error)) }
const submitOrder = () => { const items = selected.value.map(i => ({ productId: i.productId, quantity: i.quantity })); if (!items.length && !lifecycle.attempt) return emit('message', '請至少選擇一項商品'); return finish(lifecycle.submit({ items }, request => api.post('/orders', request))) }
const retry = () => finish(lifecycle.retry(request => api.post('/orders', request)))
const startPayment = async () => {
  paymentBusy.value = true
  try {
    const { data } = await api.post(`/orders/${paymentOrderId.value}/payment-form`)
    const paymentForm = data.data
    if (!paymentForm?.actionUrl || !paymentForm?.fields || typeof paymentForm.fields !== 'object') throw new Error('invalid payment form')
    const form = document.createElement('form')
    form.method = 'POST'
    form.action = paymentForm.actionUrl
    form.style.display = 'none'
    Object.entries(paymentForm.fields).forEach(([name, value]) => {
      const input = document.createElement('input')
      input.type = 'hidden'; input.name = name; input.value = String(value)
      form.appendChild(input)
    })
    document.body.appendChild(form)
    form.submit()
  } catch (e) { emit('message', errorText(e)) } finally { paymentBusy.value = false }
}
onMounted(loadProducts)
watch(() => props.refreshKey, loadProducts)
</script>

<style scoped>
h2, h3 { margin-bottom: 12px; }
.form-row { display: flex; gap: 10px; flex-wrap: wrap; }
.header-row { display: flex; justify-content: space-between; align-items: center; }
.order-form { margin-top: 20px; display: grid; gap: 10px; max-width: 300px; }
input, select, button { padding: 8px 12px; font-size: 14px; }
table { margin-top: 10px; }
</style>

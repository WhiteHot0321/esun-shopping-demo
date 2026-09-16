<template>
    <section class="card" v-if="authenticated">
      <h2>新增商品</h2><div class="form-row">
        <input v-model="product.productId" placeholder="商品編號" /><input v-model="product.productName" placeholder="商品名稱" />
        <input v-model.number="product.price" type="number" placeholder="價格" /><input v-model.number="product.quantity" type="number" placeholder="庫存" />
        <button @click="createProduct">新增商品</button>
      </div>
    </section>
    <section class="card">
      <div class="header-row"><h2>建立訂單</h2><button @click="loadProducts">重新載入商品</button></div>
      <table><thead><tr><th>商品編號</th><th>商品名稱</th><th>售價</th><th>庫存</th><th>購買數量</th></tr></thead>
        <tbody><tr v-for="item in products" :key="item.productId"><td>{{ item.productId }}</td><td>{{ item.productName }}</td><td>{{ item.price }}</td><td>{{ item.quantity }}</td>
          <td><input type="number" min="0" :max="item.quantity" v-model.number="quantities[item.productId]" /></td></tr></tbody>
      </table>
      <div class="order-form"><label>會員編號</label><input v-model="form.memberId" placeholder="請輸入會員編號" /><label>付款狀態</label>
        <select v-model="form.payStatus"><option value="PENDING">未付款</option><option value="PAID">已付款</option></select></div>
      <div class="summary"><h3>訂單預覽</h3><ul v-if="selected.length"><li v-for="item in selected" :key="item.productId">{{ item.productName }} × {{ item.quantity }} = {{ item.itemPrice }}</li></ul><p v-else>尚未選擇商品</p>
        <p><strong>總金額：{{ total }}</strong></p><button :disabled="busy" @click="submitOrder">建立訂單</button><button v-if="attempt" :disabled="busy" @click="retry">重試未確認訂單</button></div>
    </section>
</template>
<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import api from '../api'
import { createCheckoutLifecycle } from '../checkout'
defineProps({ authenticated: Boolean })
const emit = defineEmits(['message'])
const products = ref([]), quantities = reactive({}), product = reactive({ productId: '', productName: '', price: null, quantity: null })
const form = reactive({ memberId: '', payStatus: 'PENDING' }), lifecycle = createCheckoutLifecycle(), attempt = ref(null), busy = ref(false)
const selected = computed(() => products.value.filter(p => Number(quantities[p.productId] || 0) > 0).map(p => ({ ...p, quantity: Number(quantities[p.productId]), itemPrice: Number(p.price) * Number(quantities[p.productId]) })))
const total = computed(() => selected.value.reduce((sum, item) => sum + item.itemPrice, 0))
const errorText = e => e.response?.data?.message || '操作失敗'
const loadProducts = async () => { try { const { data } = await api.get('/products/available'); products.value = data.data || []; products.value.forEach(p => { if (quantities[p.productId] == null) quantities[p.productId] = 0 }) } catch (e) { emit('message', errorText(e)) } }
const createProduct = async () => { try { await api.post('/products', product); emit('message', '商品新增成功'); Object.assign(product, { productId: '', productName: '', price: null, quantity: null }); await loadProducts() } catch (e) { emit('message', errorText(e)) } }
const finish = async promise => { busy.value = true; attempt.value = lifecycle.attempt; const result = await promise; busy.value = false; attempt.value = lifecycle.attempt; if (result.status === 'success') { emit('message', `訂單建立成功，訂單編號：${result.result.data.data.orderId}`); Object.keys(quantities).forEach(k => { quantities[k] = 0 }); await loadProducts() } else if (result.status === 'retry-required') emit('message', '上一筆訂單結果尚未確認，請使用重試未確認訂單'); else if (result.status === 'failure') emit('message', errorText(result.error)) }
const submitOrder = () => { const items = selected.value.map(i => ({ productId: i.productId, quantity: i.quantity })); if (!items.length && !lifecycle.attempt) return emit('message', '請至少選擇一項商品'); return finish(lifecycle.submit({ ...form, items }, request => api.post('/orders', request))) }
const retry = () => finish(lifecycle.retry(request => api.post('/orders', request)))
onMounted(loadProducts)
</script>

<style scoped>
h2, h3 { margin-bottom: 12px; }
.form-row { display: flex; gap: 10px; flex-wrap: wrap; }
.header-row { display: flex; justify-content: space-between; align-items: center; }
.order-form { margin-top: 20px; display: grid; gap: 10px; max-width: 300px; }
input, select, button { padding: 8px 12px; font-size: 14px; }
table { margin-top: 10px; }
</style>

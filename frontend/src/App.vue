<template>
  <div class="container">
    <h1>電商購物中心系統</h1>

    <section class="card">
      <h2>新增商品</h2>
      <div class="form-row">
        <input v-model="newProduct.productId" placeholder="商品編號" />
        <input v-model="newProduct.productName" placeholder="商品名稱" />
        <input v-model.number="newProduct.price" type="number" placeholder="價格" />
        <input v-model.number="newProduct.quantity" type="number" placeholder="庫存" />
        <button @click="createProduct">新增商品</button>
      </div>
    </section>

    <section class="card">
      <div class="header-row">
        <h2>建立訂單</h2>
        <button @click="fetchProducts">重新載入商品</button>
      </div>

      <table>
        <thead>
          <tr>
            <th>商品編號</th>
            <th>商品名稱</th>
            <th>售價</th>
            <th>庫存</th>
            <th>購買數量</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in products" :key="item.productId">
            <td>{{ item.productId }}</td>
            <td>{{ item.productName }}</td>
            <td>{{ item.price }}</td>
            <td>{{ item.quantity }}</td>
            <td>
              <input
                type="number"
                min="0"
                :max="item.quantity"
                v-model.number="orderQuantities[item.productId]"
              />
            </td>
          </tr>
        </tbody>
      </table>

      <div class="order-form">
        <label>會員編號</label>
        <input v-model="orderForm.memberId" placeholder="請輸入會員編號" />

        <label>付款狀態</label>
        <select v-model="orderForm.payStatus">
          <option value="PENDING">未付款</option>
          <option value="PAID">已付款</option>
        </select>
      </div>

      <div class="summary">
        <h3>訂單預覽</h3>
        <ul v-if="selectedItems.length > 0">
          <li v-for="item in selectedItems" :key="item.productId">
            {{ item.productName }} × {{ item.quantity }} = {{ item.itemPrice }}
          </li>
        </ul>
        <p v-else>尚未選擇商品</p>
        <p><strong>總金額：{{ totalPrice }}</strong></p>
        <button @click="createOrder">建立訂單</button>
      </div>
    </section>

    <section class="card" v-if="message">
      <h2>訊息</h2>
      <p>{{ message }}</p>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'

const API_BASE = 'http://localhost:8080/api'

const products = ref([])
const message = ref('')

const newProduct = reactive({
  productId: '',
  productName: '',
  price: null,
  quantity: null
})

const orderForm = reactive({
  memberId: '',
  payStatus: 'PENDING'
})

const orderQuantities = reactive({})

const fetchProducts = async () => {
  try {
    const response = await fetch(`${API_BASE}/products/available`)
    const result = await response.json()

    if (!result.success) {
      message.value = result.message
      return
    }

    products.value = result.data || []

    products.value.forEach((item) => {
      if (orderQuantities[item.productId] == null) {
        orderQuantities[item.productId] = 0
      }
    })

    message.value = '商品載入成功'
  } catch (error) {
    console.error(error)
    message.value = '載入商品失敗'
  }
}

const createProduct = async () => {
  try {
    const response = await fetch(`${API_BASE}/products`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(newProduct)
    })

    const result = await response.json()

    if (!result.success) {
      message.value = result.message
      return
    }

    message.value = '商品新增成功'

    newProduct.productId = ''
    newProduct.productName = ''
    newProduct.price = null
    newProduct.quantity = null

    await fetchProducts()
  } catch (error) {
    console.error(error)
    message.value = '商品新增失敗'
  }
}

const selectedItems = computed(() => {
  return products.value
    .filter((item) => Number(orderQuantities[item.productId] || 0) > 0)
    .map((item) => {
      const quantity = Number(orderQuantities[item.productId])
      return {
        productId: item.productId,
        productName: item.productName,
        quantity,
        itemPrice: Number(item.price) * quantity
      }
    })
})

const totalPrice = computed(() => {
  return selectedItems.value.reduce((sum, item) => sum + item.itemPrice, 0)
})

const createOrder = async () => {
  try {
    const items = selectedItems.value.map((item) => ({
      productId: item.productId,
      quantity: item.quantity
    }))

    if (items.length === 0) {
      message.value = '請至少選擇一項商品'
      return
    }

    const payload = {
      memberId: orderForm.memberId,
      payStatus: orderForm.payStatus,
      items
    }

    const response = await fetch(`${API_BASE}/orders`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    })

    const result = await response.json()

    if (!result.success) {
      message.value = result.message
      return
    }

    message.value = `訂單建立成功，訂單編號：${result.data.orderId}`

    Object.keys(orderQuantities).forEach((key) => {
      orderQuantities[key] = 0
    })

    await fetchProducts()
  } catch (error) {
    console.error(error)
    message.value = '建立訂單失敗'
  }
}

onMounted(() => {
  fetchProducts()
})
</script>

<style scoped>
.container {
  max-width: 1100px;
  margin: 30px auto;
  font-family: Arial, sans-serif;
  color: #222;
}

h1, h2, h3 {
  margin-bottom: 12px;
}

.card {
  border: 1px solid #ddd;
  border-radius: 10px;
  padding: 20px;
  margin-bottom: 20px;
  background: #fff;
}

.form-row {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.order-form {
  margin-top: 20px;
  display: grid;
  gap: 10px;
  max-width: 300px;
}

input, select, button {
  padding: 8px 12px;
  font-size: 14px;
}

button {
  cursor: pointer;
}

table {
  width: 100%;
  border-collapse: collapse;
  margin-top: 10px;
}

th, td {
  border: 1px solid #ddd;
  padding: 10px;
  text-align: left;
}

.summary {
  margin-top: 20px;
}
</style>
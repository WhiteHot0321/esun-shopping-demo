<template>
  <section class="card product-management">
    <div class="header-row"><h2>我的商品管理</h2><button type="button" @click="loadProducts">重新載入</button></div>
    <div class="form-row filters"><button :class="{ selected: filter === 'active' }" @click="changeFilter('active')">上架中</button><button :class="{ selected: filter === 'deleted' }" @click="changeFilter('deleted')">已下架</button><input v-model.trim="keyword" placeholder="搜尋編號或名稱" @keyup.enter="search" /><button type="button" @click="search">搜尋</button></div>
    <div class="form-row">
      <input v-model.trim="createForm.productId" placeholder="商品編號" maxlength="20" /><input v-model.trim="createForm.productName" placeholder="商品名稱" maxlength="100" />
      <input v-model="createForm.price" type="number" min="0.01" step="0.01" placeholder="價格" /><input v-model="createForm.quantity" type="number" min="0" step="1" placeholder="庫存" />
      <button type="button" :disabled="Boolean(createError) || creating" @click="createProduct">新增商品</button><p v-if="createError" class="validation">{{ createError }}</p>
    </div>
    <div v-if="filter === 'active'" class="form-row bulk"><button :disabled="!selectedIds.length" @click="bulkDelete">批量下架</button><input v-model="bulkAmount" type="number" min="1" step="1" placeholder="批量補貨量" /><button :disabled="!selectedIds.length || !validPositiveInteger(bulkAmount)" @click="bulkRestock">批量補貨</button></div>
    <p v-if="loading">載入中…</p><p v-else-if="!products.length">沒有{{ filter === 'active' ? '上架中' : '已下架' }}的商品</p>
    <table v-else><thead><tr><th v-if="filter === 'active'">選取</th><th>編號</th><th>名稱</th><th>價格</th><th>庫存</th><th>圖片</th><th>操作</th></tr></thead><tbody>
      <tr v-for="item in products" :key="item.productId">
        <td v-if="filter === 'active'"><input v-model="selectedIds" type="checkbox" :value="item.productId" :aria-label="`選取 ${item.productName}`" /></td>
        <template v-if="editingId === item.productId"><td>{{ item.productId }}</td><td><input v-model.trim="editForm.productName" placeholder="商品名稱" /></td><td><input v-model="editForm.price" type="number" min="0.01" step="0.01" placeholder="價格" /><p v-if="editError" class="validation">{{ editError }}</p></td><td>{{ item.quantity }}</td><td><ProductImages :urls="item.imageUrls" /></td><td><button :disabled="Boolean(editError) || saving" @click="saveEdit(item.productId)">儲存</button><button :disabled="saving" @click="cancelEdit">取消</button></td></template>
        <template v-else><td>{{ item.productId }}</td><td>{{ item.productName }}</td><td>{{ item.price }}</td><td>{{ item.quantity }}</td><td><ProductImages :urls="item.imageUrls" /><input v-if="filter === 'active'" type="file" accept="image/jpeg,image/png,image/webp" multiple :aria-label="`上傳 ${item.productName} 圖片`" @change="uploadImages(item.productId, $event)" /></td><td><button v-if="filter === 'active'" @click="beginEdit(item)">編輯</button><button v-if="filter === 'active'" @click="deleteProduct(item)">下架</button><input v-if="filter === 'active'" v-model="restockAmounts[item.productId]" type="number" min="1" step="1" placeholder="補貨量" /><button v-if="filter === 'active'" :disabled="Boolean(restockError(item.productId))" @click="restock(item.productId)">補貨</button><p v-if="filter === 'active' && restockError(item.productId)" class="validation">{{ restockError(item.productId) }}</p></td></template>
      </tr>
    </tbody></table>
    <div v-if="totalPages > 1" class="form-row pagination"><button :disabled="page === 0" @click="goPage(page - 1)">上一頁</button><span>第 {{ page + 1 }} / {{ totalPages }} 頁</span><button :disabled="page + 1 >= totalPages" @click="goPage(page + 1)">下一頁</button></div>
  </section>
</template>

<script setup>
import { computed, defineComponent, h, onMounted, reactive, ref } from 'vue'
import api from '../api'
import { assetUrl } from '../format'

const emit = defineEmits(['message', 'changed'])
const products = ref([]), filter = ref('active'), keyword = ref(''), page = ref(0), size = 10, total = ref(0), loading = ref(false), creating = ref(false), saving = ref(false), editingId = ref('')
const selectedIds = ref([]), bulkAmount = ref('')
const createForm = reactive({ productId: '', productName: '', price: '', quantity: '' })
const editForm = reactive({ productName: '', price: '' })
const restockAmounts = reactive({})
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size)))
const createError = computed(() => validateCreate(createForm))
const editError = computed(() => validateEdit(editForm))
const errorText = error => {
  const status = error.response?.status
  if (status === 403) return '你沒有管理此商品的權限'
  if (status === 409) return error.response?.data?.message || '商品狀態已變更，請重新載入'
  if (status === 400) return error.response?.data?.message || '請檢查輸入欄位'
  return error.response?.data?.message || '網路或伺服器發生錯誤，請稍後再試'
}
function validPrice (value) { return value !== '' && Number.isFinite(Number(value)) && Number(value) >= 0.01 }
function validNonNegativeInteger (value) { return value !== '' && Number.isInteger(Number(value)) && Number(value) >= 0 }
function validPositiveInteger (value) { return value !== '' && Number.isInteger(Number(value)) && Number(value) > 0 }
function validateCreate (form) { if (!form.productId) return '請輸入商品編號'; if (!form.productName) return '請輸入商品名稱'; if (!validPrice(form.price)) return '價格至少為 0.01'; if (!validNonNegativeInteger(form.quantity)) return '庫存必須為 0 以上整數'; return '' }
function validateEdit (form) { if (!form.productName) return '請輸入商品名稱'; if (!validPrice(form.price)) return '價格至少為 0.01'; return '' }
function restockError (productId) { const value = restockAmounts[productId]; return Number.isInteger(Number(value)) && Number(value) > 0 ? '' : '補貨量必須大於 0' }
async function loadProducts () { loading.value = true; try { const { data } = await api.get('/admin/products/search', { params: { keyword: keyword.value, status: filter.value, page: page.value, size } }); products.value = data.data?.products || []; total.value = data.data?.total || 0; selectedIds.value = [] } catch (error) { emit('message', errorText(error)) } finally { loading.value = false } }
function changeFilter (value) { filter.value = value; page.value = 0; loadProducts() }
function search () { page.value = 0; loadProducts() }
function goPage (value) { page.value = value; loadProducts() }
async function createProduct () { if (createError.value) return; creating.value = true; try { await api.post('/admin/products', { productId: createForm.productId, productName: createForm.productName, price: Number(createForm.price), quantity: Number(createForm.quantity) }); Object.assign(createForm, { productId: '', productName: '', price: '', quantity: '' }); emit('message', '商品新增成功'); await refresh() } catch (error) { emit('message', errorText(error)) } finally { creating.value = false } }
function beginEdit (item) { editingId.value = item.productId; Object.assign(editForm, { productName: item.productName, price: String(item.price) }) }
function cancelEdit () { editingId.value = '' }
async function saveEdit (productId) { if (editError.value) return; saving.value = true; try { await api.put(`/admin/products/${productId}`, { productName: editForm.productName, price: Number(editForm.price) }); editingId.value = ''; emit('message', '商品更新成功'); await refresh() } catch (error) { emit('message', errorText(error)) } finally { saving.value = false } }
async function deleteProduct (item) { if (!window.confirm(`確定要下架「${item.productName}」嗎？`)) return; try { await api.delete(`/admin/products/${item.productId}`); emit('message', '商品已下架'); await refresh() } catch (error) { emit('message', errorText(error)) } }
async function restock (productId) { if (restockError(productId)) return; try { await api.post(`/admin/products/${productId}/restock?amount=${Number(restockAmounts[productId])}`); restockAmounts[productId] = ''; emit('message', '補貨成功'); await refresh() } catch (error) { emit('message', errorText(error)) } }
// api.js 預設 JSON；上傳需明確指定 multipart，否則 axios 會把 FormData 序列化成 JSON 而丟掉檔案。
async function uploadImages (productId, event) { const files = [...event.target.files]; if (!files.length) return; const form = new FormData(); files.forEach(file => form.append('images', file)); try { await api.post(`/admin/products/${productId}/images`, form, { headers: { 'Content-Type': 'multipart/form-data' } }); emit('message', '圖片上傳成功'); await refresh() } catch (error) { emit('message', errorText(error)) } finally { event.target.value = '' } }
async function bulkDelete () { if (!window.confirm(`確定要下架 ${selectedIds.value.length} 項商品嗎？`)) return; await bulkRequest('DELETE') }
async function bulkRestock () { if (!validPositiveInteger(bulkAmount.value)) return; await bulkRequest('RESTOCK', Number(bulkAmount.value)); bulkAmount.value = '' }
async function bulkRequest (action, amount) { try { await api.post('/admin/products/bulk', { productIds: selectedIds.value, action, ...(amount ? { amount } : {}) }); emit('message', action === 'DELETE' ? '批量下架成功' : '批量補貨成功'); await refresh() } catch (error) { emit('message', errorText(error)) } }
async function refresh () { await loadProducts(); emit('changed') }
const ProductImages = defineComponent({ props: { urls: { type: Array, default: () => [] } }, setup: props => () => h('div', { class: 'images' }, props.urls.map(url => h('img', { src: assetUrl(url), alt: '商品圖片' }))) })
onMounted(loadProducts)
</script>

<style scoped>
.header-row,.form-row { display:flex; gap:10px; flex-wrap:wrap; align-items:center; }.filters,.bulk,.pagination { margin:12px 0; }.selected { font-weight:bold; }.validation { color:#b42318; margin:0; font-size:13px; } input,button { padding:8px 12px; font-size:14px; } table { margin-top:10px; }.images { display:flex; gap:4px; flex-wrap:wrap; }.images img { width:56px; height:56px; object-fit:cover; border-radius:4px; }
</style>

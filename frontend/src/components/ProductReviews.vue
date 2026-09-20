<template>
  <section class="panel" aria-labelledby="reviews-title">
    <div class="section-head">
      <div>
        <h2 id="reviews-title">{{ product.productName }} 評論</h2>
        <p class="muted">★ {{ Number(summary.averageRating || 0).toFixed(1) }}（{{ summary.reviewCount || 0 }} 則）</p>
      </div>
      <button type="button" class="btn btn--ghost btn--sm" @click="$emit('close')">關閉</button>
    </div>

    <label for="review-sort">排序</label>
    <select id="review-sort" v-model="sort" @change="load">
      <option value="newest">最新</option>
      <option value="rating-high">星等高至低</option>
      <option value="rating-low">星等低至高</option>
    </select>

    <form v-if="authenticated && role === 'BUYER'" class="product-form" @submit.prevent="save">
      <h3>{{ mine ? '編輯我的評論' : '留下已購評論' }}</h3>
      <label for="review-rating">星等</label>
      <select id="review-rating" v-model.number="form.rating" required>
        <option v-for="rating in [5,4,3,2,1]" :key="rating" :value="rating">{{ rating }} 星</option>
      </select>
      <label for="review-content">評論</label>
      <textarea id="review-content" v-model.trim="form.content" maxlength="1000" required></textarea>
      <div class="section-head__actions">
        <button type="submit" class="btn btn--primary btn--sm" :disabled="busy">{{ mine ? '更新評論' : '送出評論' }}</button>
        <button v-if="mine" type="button" class="btn btn--ghost btn--sm" :disabled="busy" @click="remove">刪除</button>
      </div>
    </form>
    <p v-else-if="!authenticated" class="muted">登入且購買過此商品後即可評論。</p>

    <div v-if="loading" class="muted">評論載入中…</div>
    <div v-else-if="!reviews.length" class="empty-state">目前尚無評論</div>
    <article v-for="review in reviews" :key="review.id" class="panel">
      <div class="section-head">
        <strong>{{ review.reviewerName }} · {{ '★'.repeat(review.rating) }}</strong>
        <span v-if="review.visibility === 'HIDDEN'" class="badge badge--warn">已隱藏</span>
      </div>
      <p>{{ review.content }}</p>
      <button v-if="['SELLER','ADMIN'].includes(role)" type="button" class="btn btn--ghost btn--sm"
        @click="moderate(review)">{{ review.visibility === 'HIDDEN' ? '恢復' : '隱藏' }}</button>
    </article>
  </section>
</template>

<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import api from '../api'
import { useToast } from '../composables/toast'
import { errorText } from '../format'

const props = defineProps({
  product: { type: Object, required: true },
  authenticated: { type: Boolean, default: false },
  role: { type: String, default: 'BUYER' }
})
const emit = defineEmits(['close', 'changed'])
const toast = useToast()
const reviews = ref([])
const summary = reactive({ averageRating: 0, reviewCount: 0 })
const mine = ref(null)
const sort = ref('newest')
const loading = ref(false)
const busy = ref(false)
const form = reactive({ rating: 5, content: '' })

const load = async () => {
  loading.value = true
  try {
    const { data } = await api.get(`/products/${props.product.productId}/reviews`, { params: { page: 0, size: 20, sort: sort.value } })
    Object.assign(summary, data.data)
    reviews.value = data.data.reviews || []
    if (props.authenticated && props.role === 'BUYER') {
      const mineResponse = await api.get(`/reviews/mine/${props.product.productId}`)
      mine.value = mineResponse.data.data
      Object.assign(form, mine.value ? { rating: mine.value.rating, content: mine.value.content } : { rating: 5, content: '' })
    } else if (['SELLER', 'ADMIN'].includes(props.role)) {
      const sellerResponse = await api.get('/seller/reviews', { params: { productId: props.product.productId, page: 0, size: 20 } })
      reviews.value = sellerResponse.data.data || []
    }
  } catch (error) {
    toast.error(errorText(error, '評論載入失敗'))
  } finally {
    loading.value = false
  }
}

const save = async () => {
  if (!form.content || busy.value) return
  busy.value = true
  try {
    if (mine.value) await api.put(`/reviews/${mine.value.id}`, { ...form })
    else await api.post(`/products/${props.product.productId}/reviews`, { ...form })
    toast.success(mine.value ? '評論已更新' : '評論已送出')
    await load()
    emit('changed')
  } catch (error) {
    toast.error(errorText(error, '評論儲存失敗'))
  } finally { busy.value = false }
}

const remove = async () => {
  if (!mine.value || busy.value) return
  busy.value = true
  try {
    await api.delete(`/reviews/${mine.value.id}`)
    toast.success('評論已刪除')
    mine.value = null
    Object.assign(form, { rating: 5, content: '' })
    await load()
    emit('changed')
  } catch (error) {
    toast.error(errorText(error, '評論刪除失敗'))
  } finally { busy.value = false }
}

const moderate = async (review) => {
  const action = review.visibility === 'HIDDEN' ? 'restore' : 'hide'
  try {
    await api.post(`/seller/reviews/${review.id}/${action}`)
    toast.success(action === 'hide' ? '評論已隱藏' : '評論已恢復')
    await load()
    emit('changed')
  } catch (error) {
    toast.error(errorText(error, '評論審核失敗'))
  }
}

watch(() => props.product.productId, load)
onMounted(load)
</script>

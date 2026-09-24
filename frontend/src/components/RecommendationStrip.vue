<template>
  <section v-if="status !== 'idle' && (status !== 'ready' || items.length)" class="panel recommendations"
    :aria-label="title" data-testid="recommendations">
    <div class="section-head">
      <h2>{{ title }}</h2>
    </div>
    <p v-if="status === 'loading' && !items.length" class="muted" aria-busy="true">推薦載入中…</p>
    <p v-else-if="status === 'error'" class="muted" data-testid="recommendation-error">推薦暫時無法載入。</p>
    <ul v-else class="product-grid">
      <li v-for="item in items" :key="item.product.productId" class="product-card" data-testid="recommendation-item">
        <div class="product-card__top">
          <span class="product-card__id">{{ item.product.productId }}</span>
          <span class="badge" :class="REASONS[item.reason]?.tone || 'badge--muted'">
            {{ REASONS[item.reason]?.text || '推薦' }}
          </span>
        </div>
        <h3 class="product-card__name">{{ item.product.productName }}</h3>
        <p class="product-card__price">{{ formatPrice(item.product.price) }}</p>
        <p class="muted">
          ★ {{ Number(item.product.averageRating || 0).toFixed(1) }}（{{ item.product.reviewCount || 0 }} 則）
        </p>
        <button type="button" class="btn btn--ghost btn--sm" @click="$emit('add', item.product.productId)">
          加入購物車
        </button>
      </li>
    </ul>
  </section>
</template>

<script setup>
import { ref, watch } from 'vue'
import api from '../api'
import { formatPrice } from '../format'

const props = defineProps({
  // With a productId: "customers who bought this also bought" (public). Without: the signed-in member's own list.
  productId: { type: String, default: null },
  title: { type: String, required: true },
  limit: { type: Number, default: 6 },
  // Bumped by the parent after something that changes the signals (e.g. a checkout) to reload the list.
  refreshKey: { type: [Number, String], default: 0 }
})
defineEmits(['add'])

const REASONS = {
  CO_PURCHASE: { text: '常被一起購買', tone: 'badge--ok' },
  POPULAR: { text: '熱銷商品', tone: 'badge--warn' },
  NEW_ARRIVAL: { text: '新上架', tone: 'badge--muted' }
}

const items = ref([])
const status = ref('loading')
// Only the newest request may write: switching products quickly must not show a slower, older answer.
let requestId = 0

const load = async () => {
  const current = ++requestId
  status.value = 'loading'
  try {
    const url = props.productId
      ? `/products/${encodeURIComponent(props.productId)}/recommendations`
      : '/recommendations'
    const { data } = await api.get(url, { params: { limit: props.limit } })
    if (current !== requestId) return
    // Defensive: only well-formed entries render; anything else (wrong shape, missing product) is dropped.
    items.value = Array.isArray(data.data) ? data.data.filter((entry) => entry?.product?.productId) : []
    status.value = 'ready'
  } catch {
    if (current !== requestId) return
    items.value = []
    status.value = 'error'
  }
}

watch(() => [props.productId, props.limit, props.refreshKey], load, { immediate: true })
</script>

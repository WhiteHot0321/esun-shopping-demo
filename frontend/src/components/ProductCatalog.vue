<template>
  <section class="catalog" aria-labelledby="catalog-title">
    <div class="section-head">
      <div>
        <h2 id="catalog-title">商品列表</h2>
        <p class="muted">{{ status === 'ready' ? `共 ${products.length} 項商品有庫存` : ' ' }}</p>
      </div>
      <div class="section-head__actions">
        <label class="visually-hidden" for="catalog-search">搜尋商品</label>
        <input id="catalog-search" v-model.trim="keyword" type="search" class="search" placeholder="搜尋商品名稱或編號" />
        <button type="button" class="btn btn--ghost" :disabled="status === 'loading'" @click="$emit('reload')">
          <span v-if="status === 'loading'" class="spinner" aria-hidden="true"></span>重新整理
        </button>
      </div>
    </div>

    <div v-if="status === 'loading' && !products.length" class="product-grid" aria-busy="true">
      <div v-for="n in 6" :key="n" class="product-card skeleton" aria-hidden="true"></div>
    </div>

    <div v-else-if="status === 'error' && !products.length" class="empty-state">
      <p class="empty-state__title">商品載入失敗</p>
      <p class="muted">請確認後端服務是否啟動，再重新整理一次。</p>
      <button type="button" class="btn btn--primary" @click="$emit('reload')">再試一次</button>
    </div>

    <div v-else-if="!products.length" class="empty-state">
      <p class="empty-state__title">目前沒有可購買的商品</p>
      <p class="muted">所有商品都已售完，或尚未上架。</p>
    </div>

    <div v-else-if="!filtered.length" class="empty-state">
      <p class="empty-state__title">找不到符合「{{ keyword }}」的商品</p>
      <button type="button" class="btn btn--ghost" @click="keyword = ''">清除搜尋</button>
    </div>

    <ul v-else class="product-grid">
      <li v-for="item in filtered" :key="item.productId" class="product-card"
        :class="{ 'product-card--selected': quantities[item.productId] > 0 }">
        <div class="product-card__top">
          <span class="product-card__id">{{ item.productId }}</span>
          <span class="badge" :class="stockBadge(item).tone">{{ stockBadge(item).text }}</span>
        </div>
        <h3 class="product-card__name">{{ item.productName }}</h3>
        <p class="product-card__price">{{ formatPrice(item.price) }}</p>
        <p class="muted" :aria-label="`${item.productName} 評分`">
          ★ {{ Number(item.averageRating || 0).toFixed(1) }}（{{ item.reviewCount || 0 }} 則）
        </p>
        <button type="button" class="btn btn--ghost btn--sm" @click="$emit('view-reviews', item)">查看評論</button>
        <QuantityStepper :model-value="quantities[item.productId] || 0" :max="item.quantity" :label="item.productName"
          @update:model-value="$emit('set-quantity', item.productId, $event)" />
      </li>
    </ul>
  </section>
</template>

<script setup>
import { computed, ref } from 'vue'
import QuantityStepper from './QuantityStepper.vue'
import { formatPrice } from '../format'

const props = defineProps({
  products: { type: Array, required: true },
  quantities: { type: Object, required: true },
  status: { type: String, default: 'ready' }
})
defineEmits(['reload', 'set-quantity', 'view-reviews'])

const keyword = ref('')
const filtered = computed(() => {
  const term = keyword.value.toLowerCase()
  if (!term) return props.products
  return props.products.filter((p) => `${p.productId} ${p.productName}`.toLowerCase().includes(term))
})

const LOW_STOCK = 5
const stockBadge = (item) => (item.quantity <= LOW_STOCK
  ? { text: `僅剩 ${item.quantity} 件`, tone: 'badge--warn' }
  : { text: `庫存 ${item.quantity}`, tone: 'badge--ok' })
</script>

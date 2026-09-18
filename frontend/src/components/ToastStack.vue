<template>
  <div class="toast-stack" role="status" aria-live="polite">
    <TransitionGroup name="toast">
      <div v-for="item in toasts.items" :key="item.id" class="toast" :class="`toast--${item.type}`"
        :role="item.type === 'error' ? 'alert' : undefined">
        <span class="toast__icon" aria-hidden="true">{{ icons[item.type] }}</span>
        <p class="toast__text">{{ item.text }}</p>
        <button type="button" class="icon-button" aria-label="關閉訊息" @click="toasts.dismiss(item.id)">×</button>
      </div>
    </TransitionGroup>
  </div>
</template>

<script setup>
import { useToast } from '../composables/toast'

const toasts = useToast()
const icons = { success: '✓', error: '!', info: 'i' }
</script>

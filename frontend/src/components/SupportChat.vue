<template>
  <div class="support">
    <button v-if="!open" type="button" class="support__launcher" aria-controls="support-panel" :aria-expanded="open"
      @click="toggle(true)">
      <span aria-hidden="true">💬</span> AI 客服
    </button>

    <section v-else id="support-panel" class="support__panel" role="dialog" aria-labelledby="support-title"
      @keydown.esc="toggle(false)">
      <header class="support__head">
        <div>
          <h2 id="support-title">商品 AI 客服</h2>
          <p class="muted">回答僅根據商品資料與 FAQ</p>
        </div>
        <button type="button" class="icon-button" aria-label="關閉客服視窗" @click="toggle(false)">×</button>
      </header>

      <div ref="log" class="support__log" aria-live="polite">
        <div v-if="!messages.length" class="support__intro">
          <p>您好！可以問我商品或購物相關問題，例如：</p>
          <div class="chips">
            <button v-for="sample in samples" :key="sample" type="button" class="chip" :disabled="loading"
              @click="ask(sample)">{{ sample }}</button>
          </div>
        </div>

        <div v-for="message in messages" :key="message.id" class="bubble" :class="`bubble--${message.role}`">
          <p>{{ message.text }}</p>
          <ul v-if="message.sources?.length" class="bubble__sources">
            <li v-for="(source, index) in message.sources" :key="index">
              <span class="badge badge--muted">{{ source.sourceType === 'product' ? '商品' : 'FAQ' }}</span>
              {{ source.title }}
            </li>
          </ul>
          <button v-if="message.retry" type="button" class="link-button" :disabled="loading"
            @click="ask(message.retry)">重新詢問</button>
        </div>

        <div v-if="loading" class="bubble bubble--assistant bubble--typing" aria-label="客服回覆中">
          <span></span><span></span><span></span>
        </div>
      </div>

      <form class="support__form" @submit.prevent="ask(question)">
        <label class="visually-hidden" for="support-question">輸入問題</label>
        <input id="support-question" ref="input" v-model="question" placeholder="請輸入您的問題" maxlength="500"
          autocomplete="off" :disabled="loading" />
        <button type="submit" class="btn btn--primary" :disabled="loading || !question.trim()">送出</button>
      </form>
    </section>
  </div>
</template>

<script setup>
import { nextTick, ref } from 'vue'
import { askSupport } from '../supportApi'

// Samples map to seeded FAQ entries (04_faq.sql) so first-time users get a grounded answer.
const samples = ['怎麼申請退換貨？', '有哪些付款方式？', '出貨後多久會到？']
const open = ref(false)
const question = ref('')
const messages = ref([])
const loading = ref(false)
const log = ref(null)
const input = ref(null)
let nextId = 1

const scrollToEnd = async () => {
  await nextTick()
  if (log.value) log.value.scrollTop = log.value.scrollHeight
}

const toggle = async (next) => {
  open.value = next
  if (next) {
    await nextTick()
    input.value?.focus()
  }
}

const push = (message) => {
  messages.value.push({ id: nextId++, ...message })
  scrollToEnd()
}

const ask = async (text) => {
  const trimmed = (text || '').trim()
  if (!trimmed || loading.value) return
  push({ role: 'user', text: trimmed })
  question.value = ''
  loading.value = true
  scrollToEnd()
  try {
    const result = await askSupport(trimmed)
    push({ role: 'assistant', text: result.answer, sources: result.sources || [] })
  } catch (err) {
    push({
      role: 'error',
      text: err.response?.data?.message || '客服服務暫時無法回應，請稍後再試',
      retry: trimmed
    })
  } finally {
    loading.value = false
    await nextTick()
    input.value?.focus()
  }
}
</script>

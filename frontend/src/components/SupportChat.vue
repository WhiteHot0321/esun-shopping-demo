<template>
  <section class="card support-chat">
    <h2>商品 AI 客服</h2>
    <p class="hint">用一般文字提問，例如「有沒有防水的商品？」或「怎麼申請退貨？」</p>

    <div class="form-row">
      <input
        v-model="question"
        placeholder="請輸入您的問題"
        :disabled="loading"
        @keyup.enter="ask"
      />
      <button :disabled="loading || !question.trim()" @click="ask">
        {{ loading ? '詢問中…' : '送出' }}
      </button>
    </div>

    <p v-if="error" class="error">{{ error }}</p>

    <div v-if="answer" class="answer-box">
      <h3>回答</h3>
      <p>{{ answer }}</p>

      <div v-if="sources.length > 0" class="sources">
        <h4>參考來源</h4>
        <ul>
          <li v-for="(source, index) in sources" :key="index">
            [{{ source.sourceType === 'product' ? '商品' : 'FAQ' }}] {{ source.title }}
            <span class="similarity">(相似度 {{ source.similarity.toFixed(2) }})</span>
          </li>
        </ul>
      </div>
    </div>
  </section>
</template>

<script setup>
import { ref } from 'vue'
import { askSupport } from '../supportApi'

const question = ref('')
const answer = ref('')
const sources = ref([])
const error = ref('')
const loading = ref(false)

const ask = async () => {
  const trimmed = question.value.trim()
  if (!trimmed || loading.value) {
    return
  }

  loading.value = true
  error.value = ''

  try {
    const result = await askSupport(trimmed)
    answer.value = result.answer
    sources.value = result.sources || []
  } catch (err) {
    console.error(err)
    error.value = err.response?.data?.message || '客服服務暫時無法回應，請稍後再試'
    answer.value = ''
    sources.value = []
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.support-chat .form-row {
  display: flex;
  gap: 10px;
}

.support-chat .form-row input {
  flex: 1;
}

.hint {
  color: #666;
  margin-bottom: 12px;
}

.error {
  color: #c0392b;
}

.answer-box {
  margin-top: 16px;
  padding: 14px;
  background: #f7f7f7;
  border-radius: 8px;
}

.sources ul {
  padding-left: 20px;
  margin: 8px 0 0;
}

.similarity {
  color: #888;
  font-size: 12px;
}
</style>

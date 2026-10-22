<template>
  <section class="panel member-panel" aria-labelledby="member-title">
    <div class="panel__head">
      <h2 id="member-title" class="panel__title">會員管理</h2>
      <button type="button" class="link-button" @click="$emit('close')">關閉</button>
    </div>

    <form class="member-filter" @submit.prevent="applyFilters">
      <div class="field">
        <label for="mem-q">Email 開頭</label>
        <input id="mem-q" v-model.trim="form.q" type="text" placeholder="例如 alice" />
      </div>
      <div class="field">
        <label for="mem-role">角色</label>
        <select id="mem-role" v-model="form.role">
          <option value="">全部</option>
          <option v-for="r in ROLES" :key="r" :value="r">{{ ROLE_LABELS[r] }}</option>
        </select>
      </div>
      <div class="field">
        <button type="submit" class="btn btn--primary btn--sm">查詢</button>
      </div>
    </form>

    <p class="field__hint">變更角色或停用後，該會員目前的登入立即失效，需重新登入。</p>

    <p v-if="loading" role="status">載入會員中…</p>
    <p v-else-if="!members.length" class="field__hint">沒有符合條件的會員</p>
    <ul v-else class="member-list">
      <li v-for="m in members" :key="m.id" class="member-entry" data-testid="member-entry">
        <div class="member-entry__head">
          <strong>{{ m.email }}</strong>
          <span class="badge badge--muted">{{ ROLE_LABELS[m.role] || m.role }}</span>
          <span :class="['badge', m.status === 'ACTIVE' ? 'badge--ok' : 'badge--warn']">{{ m.status === 'ACTIVE' ? '啟用中' : '已停用' }}</span>
        </div>
        <div class="member-entry__actions">
          <label :for="`mem-role-${m.id}`" class="field__hint">角色</label>
          <select :id="`mem-role-${m.id}`" :value="m.role" :disabled="busyId === m.id || m.email === selfEmail"
                  @change="changeRole(m, $event.target.value, $event)">
            <option v-for="r in ROLES" :key="r" :value="r">{{ ROLE_LABELS[r] }}</option>
          </select>
          <button v-if="m.status === 'ACTIVE'" type="button" class="btn btn--ghost btn--sm" data-testid="member-disable"
                  :disabled="busyId === m.id || m.email === selfEmail" @click="toggleStatus(m)">停用</button>
          <button v-else type="button" class="btn btn--ghost btn--sm" data-testid="member-enable"
                  :disabled="busyId === m.id" @click="toggleStatus(m)">啟用</button>
        </div>
      </li>
    </ul>

    <div v-if="totalPages > 1" class="member-pager">
      <button type="button" class="btn btn--ghost btn--sm" :disabled="page === 0 || loading" @click="goPage(page - 1)">上一頁</button>
      <span class="field__hint">第 {{ page + 1 }} / {{ totalPages }} 頁（共 {{ total }} 位）</span>
      <button type="button" class="btn btn--ghost btn--sm" :disabled="page + 1 >= totalPages || loading" @click="goPage(page + 1)">下一頁</button>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import api from '../api'
import { useToast } from '../composables/toast'
import { errorText } from '../format'
import { useAuthStore } from '../stores/auth'

// ADMIN-only. The backend enforces role, self-protection and the last-admin rule; this panel presents them
// (own row is locked) and surfaces the server's 409 messages.
defineEmits(['close'])

const toast = useToast()
const auth = useAuthStore()
const selfEmail = computed(() => auth.email)

const ROLES = ['BUYER', 'SELLER', 'ADMIN']
const ROLE_LABELS = { BUYER: '買家', SELLER: '賣家', ADMIN: '管理員' }
const size = 20

const members = ref([])
const total = ref(0)
const page = ref(0)
const loading = ref(true)
const busyId = ref(null)
const form = reactive({ q: '', role: '' })
const applied = ref({})
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size)))

let latestRequest = 0
async function load () {
  const request = ++latestRequest
  loading.value = true
  try {
    const params = { page: page.value, size }
    if (applied.value.q) params.q = applied.value.q
    if (applied.value.role) params.role = applied.value.role
    const { data } = await api.get('/admin/members', { params })
    if (request !== latestRequest) return
    members.value = data.data.items
    total.value = data.data.total
  } catch (error) {
    if (request === latestRequest) toast.error(errorText(error, '載入會員失敗，請稍後再試'))
  } finally {
    if (request === latestRequest) loading.value = false
  }
}

function applyFilters () {
  applied.value = { q: form.q, role: form.role }
  page.value = 0
  load()
}

function goPage (next) {
  page.value = next
  load()
}

async function mutate (member, run, successText, failText) {
  busyId.value = member.id
  try {
    await run()
    toast.success(successText)
  } catch (error) {
    toast.error(errorText(error, failText))
  } finally {
    busyId.value = null
    await load()
  }
}

function changeRole (member, role, event) {
  if (role === member.role) return
  if (!window.confirm(`確定要把 ${member.email} 改為「${ROLE_LABELS[role]}」嗎？對方目前的登入會立即失效。`)) {
    event.target.value = member.role
    return
  }
  mutate(member, () => api.patch(`/admin/members/${member.id}/role`, { role }),
    `${member.email} 已改為${ROLE_LABELS[role]}`, '變更角色失敗，請稍後再試')
}

function toggleStatus (member) {
  const disabling = member.status === 'ACTIVE'
  if (disabling && !window.confirm(`確定要停用 ${member.email} 嗎？對方會被立即登出且無法登入。`)) return
  mutate(member, () => api.post(`/admin/members/${member.id}/${disabling ? 'disable' : 'enable'}`),
    `${member.email} 已${disabling ? '停用' : '啟用'}`, `${disabling ? '停用' : '啟用'}失敗，請稍後再試`)
}

onMounted(load)
</script>

import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('accessToken') || '')
  const email = ref(localStorage.getItem('authenticatedEmail') || '')
  const role = ref(localStorage.getItem('authenticatedRole') || 'BUYER')
  const isAuthenticated = computed(() => Boolean(token.value))

  const setSession = (nextToken, nextEmail, nextRole = 'BUYER') => {
    token.value = nextToken
    email.value = nextEmail
    role.value = nextRole
    localStorage.setItem('accessToken', nextToken)
    localStorage.setItem('authenticatedEmail', nextEmail)
    localStorage.setItem('authenticatedRole', nextRole)
  }

  const logout = () => {
    token.value = ''
    email.value = ''
    role.value = 'BUYER'
    localStorage.removeItem('accessToken')
    localStorage.removeItem('authenticatedEmail')
    localStorage.removeItem('authenticatedRole')
  }

  return { token, email, role, isAuthenticated, setSession, logout }
})

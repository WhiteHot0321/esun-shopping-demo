import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('accessToken') || '')
  const email = ref(localStorage.getItem('authenticatedEmail') || '')
  const isAuthenticated = computed(() => Boolean(token.value))

  const setSession = (nextToken, nextEmail) => {
    token.value = nextToken
    email.value = nextEmail
    localStorage.setItem('accessToken', nextToken)
    localStorage.setItem('authenticatedEmail', nextEmail)
  }

  const logout = () => {
    token.value = ''
    email.value = ''
    localStorage.removeItem('accessToken')
    localStorage.removeItem('authenticatedEmail')
  }

  return { token, email, isAuthenticated, setSession, logout }
})

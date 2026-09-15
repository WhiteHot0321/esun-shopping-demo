import axios from 'axios'

// Base URL comes from Vite env (frontend/.env, git-ignored) with a
// localhost fallback so the app still runs with no .env present.
const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api',
  headers: {
    'Content-Type': 'application/json'
  }
})

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('accessToken')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('accessToken')
      localStorage.removeItem('authenticatedEmail')
      window.dispatchEvent(new Event('auth-expired'))
    }
    return Promise.reject(error)
  }
)

export default api

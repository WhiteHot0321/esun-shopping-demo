import axios from 'axios'

// Base URL comes from Vite env (frontend/.env, git-ignored) with a
// localhost fallback so the app still runs with no .env present.
const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api',
  headers: {
    'Content-Type': 'application/json'
  }
})

export default api

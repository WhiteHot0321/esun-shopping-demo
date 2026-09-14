import api from './api'

export async function askSupport(question) {
  const response = await api.post('/support/ask', { question })
  return response.data.data // { answer, sources }
}

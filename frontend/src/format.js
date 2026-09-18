// zh-TW's currency style renders TWD as a bare "$", which reads as USD; prefix explicitly.
const whole = new Intl.NumberFormat('zh-TW', { maximumFractionDigits: 0 })
const cents = new Intl.NumberFormat('zh-TW', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

export const formatPrice = (value) => {
  const number = Number(value) || 0
  return `NT$${(Number.isInteger(number) ? whole : cents).format(number)}`
}

// Backend messages are already user-facing Chinese; only fall back when there is none.
export const errorText = (error, fallback = '操作失敗，請稍後再試') => {
  if (error?.response?.data?.message) return error.response.data.message
  if (error && !error.response) return '無法連線到伺服器，請確認網路後再試一次'
  return fallback
}

// Coerces any user input into a whole number within [0, max].
export const clampQuantity = (value, max) => {
  const number = Math.floor(Number(value))
  if (!Number.isFinite(number) || number < 0) return 0
  return Math.min(number, Math.max(0, Number(max) || 0))
}

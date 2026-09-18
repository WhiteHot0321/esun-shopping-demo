import { inject, reactive } from 'vue'

export const TOAST_KEY = Symbol('toast')

const DURATION = { success: 3500, info: 4000, error: 7000 }

// One toast queue per App instance (provided, not module-global) so component tests
// that mount App repeatedly never see a previous test's messages or timers.
export const createToasts = () => {
  const items = reactive([])
  const timers = new Map()
  let nextId = 1

  const dismiss = (id) => {
    const index = items.findIndex((item) => item.id === id)
    if (index !== -1) items.splice(index, 1)
    clearTimeout(timers.get(id))
    timers.delete(id)
  }

  const show = (text, type = 'info') => {
    // Identical text already on screen: restart its timer instead of stacking duplicates.
    const existing = items.find((item) => item.text === text && item.type === type)
    if (existing) dismiss(existing.id)
    const id = nextId++
    items.push({ id, text, type })
    timers.set(id, setTimeout(() => dismiss(id), DURATION[type] ?? DURATION.info))
    return id
  }

  const clear = () => {
    timers.forEach((timer) => clearTimeout(timer))
    timers.clear()
    items.splice(0)
  }

  return {
    items,
    dismiss,
    clear,
    success: (text) => show(text, 'success'),
    error: (text) => show(text, 'error'),
    info: (text) => show(text, 'info')
  }
}

export const useToast = () => inject(TOAST_KEY)

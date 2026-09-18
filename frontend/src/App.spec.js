import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import App from './App.vue'
import api from './api'

vi.mock('./api', () => ({ default: { get: vi.fn(), post: vi.fn() } }))
vi.mock('./supportApi', () => ({ askSupport: vi.fn() }))

let wrapper
const button = text => wrapper.findAll('button').find(b => b.text() === text)
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const products = [
  { productId: 'P001', productName: 'Tea', price: 12.5, quantity: 5 },
  { productId: 'P002', productName: 'Coffee Beans', price: 1200, quantity: 20 }
]
const mountApp = async ({ signedIn = false } = {}) => {
  localStorage.clear()
  if (signedIn) {
    localStorage.setItem('accessToken', 'jwt')
    localStorage.setItem('authenticatedEmail', 'member@example.com')
  }
  wrapper = mount(App, { attachTo: document.body, global: { plugins: [createPinia()] } })
  await flushPromises()
}
const fillAuth = async (email = 'member@example.com', password = 'password123') => {
  await wrapper.get('#auth-email').setValue(email)
  await wrapper.get('#auth-password').setValue(password)
}
const submitAuth = async () => {
  await wrapper.get('.auth-panel form').trigger('submit')
  await flushPromises()
}
const qtyInput = name => wrapper.get(`.catalog input[aria-label="${name} 購買數量"]`)
const setQty = async (name, value) => {
  const input = qtyInput(name)
  await input.setValue(value)
  await input.trigger('change')
  await flushPromises()
}
const checkout = async () => { await wrapper.get('form.checkout').trigger('submit'); }

beforeEach(() => {
  vi.resetAllMocks()
  api.get.mockResolvedValue({ data: { data: structuredClone(products) } })
})
afterEach(() => {
  wrapper.unmount()
  document.body.innerHTML = ''
})

describe('authentication UI', () => {
  beforeEach(() => mountApp())

  it('shows backend login failure, keeps the user signed out and permits another attempt', async () => {
    api.post.mockRejectedValue({ response: { status: 401, data: { message: '帳號或密碼錯誤' } } })
    await fillAuth()
    await submitAuth()
    // api.js fires auth-expired on every 401; a failed login must not claim a session expired.
    window.dispatchEvent(new Event('auth-expired'))
    await flushPromises()
    expect(wrapper.text()).toContain('帳號或密碼錯誤')
    expect(wrapper.text()).not.toContain('登入已失效')
    expect(button('登入').attributes('disabled')).toBeUndefined()
    expect(wrapper.get('#auth-password').element.value).toBe('')
    expect(localStorage.getItem('accessToken')).toBeNull()
  })

  it('shows a connection-specific message for network failure', async () => {
    api.post.mockRejectedValue(new Error('offline'))
    await fillAuth()
    await submitAuth()
    expect(wrapper.text()).toContain('無法連線到伺服器')
  })

  it('validates fields inline before sending', async () => {
    await submitAuth()
    expect(api.post).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('請輸入 Email')
    expect(wrapper.text()).toContain('請輸入密碼')
    await fillAuth('not-an-email', 'x')
    await submitAuth()
    expect(wrapper.text()).toContain('Email 格式不正確')
    expect(api.post).not.toHaveBeenCalled()
  })

  it('enforces the register password length before calling the backend', async () => {
    await wrapper.findAll('[role="tab"]').find(t => t.text() === '註冊').trigger('click')
    await fillAuth('member@example.com', 'short')
    await submitAuth()
    expect(api.post).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('密碼長度至少需 8 碼')
  })

  it('registers, persists session, prefills member id and logs out from the header', async () => {
    await wrapper.findAll('[role="tab"]').find(t => t.text() === '註冊').trigger('click')
    await fillAuth()
    api.post.mockResolvedValue({ data: { data: { token: 'jwt', email: 'member@example.com' } } })
    await submitAuth()
    expect(api.post).toHaveBeenCalledWith('/auth/register', { email: 'member@example.com', password: 'password123' })
    expect(wrapper.text()).toContain('註冊成功，已自動登入')
    expect(localStorage.getItem('accessToken')).toBe('jwt')
    expect(wrapper.find('.auth-panel').exists()).toBe(false)
    expect(wrapper.get('#member-id').element.value).toBe('member@example.com')
    await button('登出').trigger('click')
    expect(localStorage.getItem('accessToken')).toBeNull()
    expect(wrapper.get('#auth-password').element.value).toBe('')
  })

  it('holds busy state and mode until login completes, then handles expiry', async () => {
    const pending = deferred()
    api.post.mockReturnValue(pending.promise)
    await fillAuth()
    await wrapper.get('.auth-panel form').trigger('submit')
    expect(button('處理中…').attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('[role="tab"]').every(t => t.attributes('disabled') !== undefined)).toBe(true)
    await wrapper.get('.auth-panel form').trigger('submit')
    expect(api.post).toHaveBeenCalledTimes(1)
    pending.resolve({ data: { data: { token: 'jwt', email: 'member@example.com' } } })
    await flushPromises()
    expect(wrapper.text()).toContain('歡迎回來，member@example.com')
    window.dispatchEvent(new Event('auth-expired'))
    await flushPromises()
    expect(wrapper.text()).toContain('登入已失效，請重新登入')
    expect(localStorage.getItem('accessToken')).toBeNull()
  })

  it('blocks checkout while signed out instead of sending an unauthenticated order', async () => {
    await setQty('Tea', 1)
    expect(wrapper.get('form.checkout button[type="submit"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('請先登入會員才能建立訂單')
    await checkout()
    expect(api.post).not.toHaveBeenCalled()
  })
})

describe('catalog and cart', () => {
  beforeEach(() => mountApp({ signedIn: true }))

  it('formats prices and filters by keyword with an empty state', async () => {
    expect(wrapper.text()).toContain('NT$1,200')
    await wrapper.get('#catalog-search').setValue('coffee')
    expect(wrapper.text()).not.toContain('Tea')
    await wrapper.get('#catalog-search').setValue('nothing')
    expect(wrapper.text()).toContain('找不到符合「nothing」的商品')
  })

  it('clamps quantities to stock and whole numbers, with stepper buttons', async () => {
    await setQty('Tea', 99)
    expect(qtyInput('Tea').element.value).toBe('5')
    expect(wrapper.text()).toContain('庫存只剩 5 件')
    await setQty('Tea', -3)
    expect(qtyInput('Tea').element.value).toBe('0')
    await setQty('Tea', 2.7)
    expect(qtyInput('Tea').element.value).toBe('2')
    await wrapper.get('.catalog button[aria-label="增加 Tea 數量"]').trigger('click')
    expect(qtyInput('Tea').element.value).toBe('3')
    await setQty('Tea', 5)
    expect(wrapper.get('.catalog button[aria-label="增加 Tea 數量"]').attributes('disabled')).toBeDefined()
  })

  it('shows an error state with retry when products fail to load', async () => {
    wrapper.unmount()
    api.get.mockRejectedValueOnce(new Error('down'))
    await mountApp({ signedIn: true })
    expect(wrapper.text()).toContain('商品載入失敗')
    await button('再試一次').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Tea')
  })
})

describe('checkout', () => {
  beforeEach(() => mountApp({ signedIn: true }))

  it('calculates the cart, submits once and clears cart on success', async () => {
    await setQty('Tea', 2)
    expect(wrapper.get('[data-testid="cart-total"]').text()).toBe('NT$25')
    const pending = deferred()
    api.post.mockReturnValue(pending.promise)
    await checkout()
    await checkout()
    expect(api.post).toHaveBeenCalledTimes(1)
    expect(button('訂單送出中…').attributes('disabled')).toBeDefined()
    expect(api.post).toHaveBeenCalledWith('/orders', expect.objectContaining({
      memberId: 'member@example.com', payStatus: 'PENDING', requestId: expect.any(String),
      items: [{ productId: 'P001', quantity: 2 }]
    }))
    pending.resolve({ data: { data: { orderId: 'ORDER-1' } } })
    await flushPromises()
    expect(wrapper.text()).toContain('訂單建立成功，訂單編號：ORDER-1')
    expect(qtyInput('Tea').element.value).toBe('0')
    expect(api.get).toHaveBeenCalledTimes(2)
  })

  it('retries a lost response with the original request after cart and products change', async () => {
    await setQty('Tea', 2)
    api.post.mockRejectedValueOnce(new Error('lost response'))
    await checkout()
    await flushPromises()
    const original = structuredClone(api.post.mock.calls[0][1])
    expect(wrapper.text()).toContain('上一筆訂單結果尚未確認')
    expect(wrapper.text()).toContain('1 項商品，共 2 件')
    expect(wrapper.get('form.checkout button[type="submit"]').attributes('disabled')).toBeDefined()
    api.get.mockResolvedValue({ data: { data: [] } })
    await button('重新整理').trigger('click')
    await flushPromises()
    api.post.mockResolvedValue({ data: { data: { orderId: 'ORIGINAL' } } })
    await button('重試未確認訂單').trigger('click')
    await flushPromises()
    expect(api.post.mock.calls[1][1]).toEqual(original)
    expect(wrapper.text()).toContain('ORIGINAL')
    expect(button('重試未確認訂單')).toBeUndefined()
  })

  it('shows definitive checkout error, refreshes stock and keeps no retry', async () => {
    await setQty('Tea', 1)
    api.post.mockRejectedValue({ response: { status: 409, data: { message: '商品庫存不足' } } })
    await checkout()
    await flushPromises()
    expect(wrapper.text()).toContain('商品庫存不足')
    expect(button('重試未確認訂單')).toBeUndefined()
    expect(api.get).toHaveBeenCalledTimes(2)
  })

  it('shrinks cart lines when a reload reports lower stock', async () => {
    await setQty('Coffee Beans', 10)
    api.get.mockResolvedValue({ data: { data: [{ ...products[1], quantity: 3 }] } })
    await button('重新整理').trigger('click')
    await flushPromises()
    expect(qtyInput('Coffee Beans').element.value).toBe('3')
    expect(wrapper.text()).toContain('Coffee Beans 已自動調整')
  })

  it('requires a member id before sending', async () => {
    await setQty('Tea', 1)
    await wrapper.get('#member-id').setValue('')
    await checkout()
    expect(api.post).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('請輸入會員編號')
  })
})

describe('product form', () => {
  beforeEach(() => mountApp({ signedIn: true }))

  it('validates, creates a product and refreshes listing', async () => {
    await wrapper.get('.product-form form').trigger('submit')
    expect(api.post).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('請輸入商品編號')
    await wrapper.get('#product-productId').setValue('P003')
    await wrapper.get('#product-productName').setValue('Matcha')
    await wrapper.get('#product-price').setValue(20)
    await wrapper.get('#product-quantity').setValue(10)
    api.post.mockResolvedValue({ data: { data: null } })
    await wrapper.get('.product-form form').trigger('submit')
    await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/products', { productId: 'P003', productName: 'Matcha', price: 20, quantity: 10 })
    expect(wrapper.text()).toContain('商品「Matcha」新增成功')
    expect(wrapper.get('#product-productId').element.value).toBe('')
    expect(api.get).toHaveBeenCalledTimes(2)
  })
})

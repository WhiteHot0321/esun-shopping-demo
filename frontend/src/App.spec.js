import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import App from './App.vue'
import api from './api'

vi.mock('./api', () => ({ default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() } }))
vi.mock('./supportApi', () => ({ askSupport: vi.fn() }))

let wrapper
const button = text => wrapper.findAll('button').find(b => b.text() === text)
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const products = [
  { productId: 'P001', productName: 'Tea', price: 12.5, quantity: 5 },
  { productId: 'P002', productName: 'Coffee Beans', price: 1200, quantity: 20 }
]
const addresses = [{ id: 7, label: '住家', receiverName: '王小明', phone: '0912-345-678', postalCode: '100', address: '台北市測試路 1 號', isDefault: true }]
let serverCart
const mountApp = async ({ signedIn = false, storedCart = null, role = 'BUYER' } = {}) => {
  localStorage.clear()
  if (signedIn) {
    localStorage.setItem('accessToken', 'jwt')
    localStorage.setItem('authenticatedEmail', 'member@example.com')
    localStorage.setItem('authenticatedRole', role)
    if (storedCart) localStorage.setItem('esunShop.cart.v1:member%40example.com', JSON.stringify(storedCart))
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
  serverCart = []
  api.get.mockImplementation(url => Promise.resolve({
    data: { data: structuredClone(url === '/member/addresses' ? addresses : url === '/cart' ? serverCart : products) }
  }))
  api.post.mockImplementation((url, payload) => {
    if (url === '/cart/add') return Promise.resolve({ data: { data: { id: 99, ...payload } } })
    return Promise.resolve({ data: { data: null } })
  })
  api.put.mockResolvedValue({ data: { data: { id: 99 } } })
  api.delete.mockResolvedValue({ data: { data: null } })
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

describe('member profile UI', () => {
  beforeEach(() => mountApp({ signedIn: true }))

  it('loads and updates the authenticated member profile without permitting email edits', async () => {
    api.get.mockResolvedValueOnce({ data: { data: {
      email: 'member@example.com', displayName: 'Old Name', phone: '0900-000-000'
    } } })
    await button('個人資料').trigger('click')
    await flushPromises()

    expect(wrapper.get('#profile-email').element.value).toBe('member@example.com')
    expect(wrapper.get('#profile-email').attributes('disabled')).toBeDefined()
    await wrapper.get('#profile-display-name').setValue('  New Name  ')
    await wrapper.get('#profile-phone').setValue('  0912-345-678  ')
    api.put.mockResolvedValue({ data: { data: {
      email: 'member@example.com', displayName: 'New Name', phone: '0912-345-678'
    } } })
    await wrapper.get('.profile-panel form').trigger('submit')
    await flushPromises()

    expect(api.put).toHaveBeenCalledWith('/member/profile', {
      displayName: 'New Name', phone: '0912-345-678'
    })
    expect(wrapper.text()).toContain('個人資料已更新')
  })

  it('blocks an invalid phone before sending it', async () => {
    api.get.mockResolvedValueOnce({ data: { data: {
      email: 'member@example.com', displayName: null, phone: null
    } } })
    await button('個人資料').trigger('click')
    await flushPromises()
    await wrapper.get('#profile-phone').setValue('call-me')
    await wrapper.get('.profile-panel form').trigger('submit')
    expect(api.put).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('電話只能包含數字')
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
    let productRequestFails = true
    api.get.mockImplementation(url => {
      if (url === '/products/available' && productRequestFails) return Promise.reject(new Error('down'))
      return Promise.resolve({ data: { data: structuredClone(url === '/member/addresses' ? addresses : products) } })
    })
    await mountApp({ signedIn: true })
    expect(wrapper.text()).toContain('商品載入失敗')
    productRequestFails = false
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
    api.post.mockClear()
    api.post.mockReturnValue(pending.promise)
    await checkout()
    await checkout()
    expect(api.post).toHaveBeenCalledTimes(1)
    expect(button('訂單送出中…').attributes('disabled')).toBeDefined()
    expect(api.post).toHaveBeenCalledWith('/cart/checkout', {
      shippingAddressId: 7, requestId: expect.any(String)
    })
    pending.resolve({ data: { data: { orderId: 'ORDER-1' } } })
    await flushPromises()
    expect(wrapper.text()).toContain('訂單建立成功，訂單編號：ORDER-1')
    expect(qtyInput('Tea').element.value).toBe('0')
    expect(api.get).toHaveBeenCalledTimes(5)
  })

  it('gives the buyer no say in payment status and points to paying after the order exists', async () => {
    expect(wrapper.find('input[name="pay-status"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('付款狀態')
    expect(wrapper.text()).toContain('請至「我的訂單」完成付款')
  })

  it('retries a lost response with the original request after cart and products change', async () => {
    await setQty('Tea', 2)
    api.post.mockClear()
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
    api.post.mockClear()
    api.post.mockRejectedValue({ response: { status: 409, data: { message: '商品庫存不足' } } })
    await checkout()
    await flushPromises()
    expect(wrapper.text()).toContain('商品庫存不足')
    expect(button('重試未確認訂單')).toBeUndefined()
    expect(api.get).toHaveBeenCalledTimes(5)
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
    expect(api.post.mock.calls.filter(([url]) => url === '/cart/checkout')).toHaveLength(0)
    expect(wrapper.text()).toContain('請輸入會員編號')
  })
})

describe('product form', () => {
  beforeEach(() => mountApp({ signedIn: true, role: 'SELLER' }))

  it('validates, creates a product and refreshes listing', async () => {
    expect(api.post).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('請輸入商品編號')
    const management = wrapper.get('.product-management')
    await management.get('input[placeholder="商品編號"]').setValue('P003')
    await management.get('input[placeholder="商品名稱"]').setValue('Matcha')
    await management.get('input[placeholder="價格"]').setValue(20)
    await management.get('input[placeholder="庫存"]').setValue(10)
    api.post.mockResolvedValue({ data: { data: null } })
    await management.findAll('button').find(item => item.text() === '新增商品').trigger('click')
    await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/admin/products', { productId: 'P003', productName: 'Matcha', price: 20, quantity: 10 })
    expect(wrapper.text()).toContain('商品新增成功')
    expect(management.get('input[placeholder="商品編號"]').element.value).toBe('')
    expect(api.get.mock.calls.filter(([url]) => url === '/products/available').length).toBeGreaterThanOrEqual(2)
  })

  it('restores the signed-in cart, clamps it to current stock and persists later changes', async () => {
    wrapper.unmount()
    serverCart = [
      { id: 11, productId: 'P001', quantity: 2 },
      { id: 12, productId: 'P002', quantity: 99 }
    ]
    await mountApp({ signedIn: true, storedCart: { P001: 2, P002: 99, missing: 4 } })

    expect(qtyInput('Tea').element.value).toBe('2')
    expect(qtyInput('Coffee Beans').element.value).toBe('20')
    api.put.mockResolvedValue({ data: { data: { id: 11 } } })
    await setQty('Tea', 3)

    expect(JSON.parse(localStorage.getItem('esunShop.cart.v1:member%40example.com'))).toEqual({ P001: 3, P002: 20 })
    expect(api.put).toHaveBeenCalledWith('/cart/items/11', { quantity: 3 })
  })

  it('waits for an in-flight cart write before applying a reload', async () => {
    wrapper.unmount()
    serverCart = [{ id: 11, productId: 'P001', quantity: 2 }]
    await mountApp({ signedIn: true })
    api.get.mockClear()
    const pendingPut = deferred()
    api.put.mockReturnValue(pendingPut.promise)

    const change = setQty('Tea', 3)
    await button('重新整理').trigger('click')
    await flushPromises()
    expect(api.get.mock.calls.filter(([url]) => url === '/cart')).toHaveLength(0)

    serverCart = [{ id: 11, productId: 'P001', quantity: 3 }]
    pendingPut.resolve({ data: { data: { id: 11 } } })
    await change
    await flushPromises()

    expect(api.get.mock.calls.filter(([url]) => url === '/cart')).toHaveLength(1)
    expect(qtyInput('Tea').element.value).toBe('3')
    expect(JSON.parse(localStorage.getItem('esunShop.cart.v1:member%40example.com'))).toEqual({ P001: 3 })
  })

  it('waits for an in-flight add before clearing the server cart', async () => {
    wrapper.unmount()
    await mountApp({ signedIn: true })
    const pendingAdd = deferred()
    api.post.mockImplementation(url => url === '/cart/add'
      ? pendingAdd.promise
      : Promise.resolve({ data: { data: null } }))

    const change = setQty('Tea', 1)
    await flushPromises()
    const clearing = button('清空').trigger('click')
    await flushPromises()
    expect(api.delete.mock.calls.filter(([url]) => url === '/cart')).toHaveLength(0)

    pendingAdd.resolve({ data: { data: { id: 23, productId: 'P001', quantity: 1 } } })
    await change
    await clearing
    await flushPromises()

    expect(api.delete.mock.calls.filter(([url]) => url === '/cart')).toHaveLength(1)
    expect(qtyInput('Tea').element.value).toBe('0')
    expect(JSON.parse(localStorage.getItem('esunShop.cart.v1:member%40example.com'))).toEqual({})
  })

  it('keeps clear and checkout mutually exclusive', async () => {
    wrapper.unmount()
    await mountApp({ signedIn: true })
    const pendingAdd = deferred()
    api.post.mockImplementation(url => url === '/cart/add'
      ? pendingAdd.promise
      : Promise.resolve({ data: { data: { orderId: 'UNEXPECTED' } } }))
    const change = setQty('Tea', 1)
    await flushPromises()
    const clearing = button('清空').trigger('click')
    await checkout()
    expect(api.post.mock.calls.filter(([url]) => url === '/cart/checkout')).toHaveLength(0)
    pendingAdd.resolve({ data: { data: { id: 24, productId: 'P001', quantity: 1 } } })
    await change
    await clearing
    await flushPromises()

    await setQty('Tea', 1)
    const pendingCheckout = deferred()
    api.post.mockImplementation(url => url === '/cart/checkout'
      ? pendingCheckout.promise
      : Promise.resolve({ data: { data: { id: 25, productId: 'P001', quantity: 1 } } }))
    api.delete.mockClear()
    await checkout()
    await button('清空').trigger('click')
    expect(api.delete.mock.calls.filter(([url]) => url === '/cart')).toHaveLength(0)
    pendingCheckout.resolve({ data: { data: { orderId: 'ORDER-MUTEX' } } })
    await flushPromises()
    expect(wrapper.text()).toContain('ORDER-MUTEX')
  })

  it('blocks an ambiguous-checkout retry while clear is waiting for a cart write', async () => {
    wrapper.unmount()
    await mountApp({ signedIn: true })
    await setQty('Tea', 1)
    api.post.mockClear()
    api.post.mockRejectedValueOnce(new Error('lost response'))
    await checkout()
    await flushPromises()
    expect(button('重試未確認訂單')).toBeDefined()

    const pendingPut = deferred()
    api.put.mockReturnValue(pendingPut.promise)
    const change = setQty('Tea', 2)
    await flushPromises()
    const clearing = button('清空').trigger('click')
    await button('重試未確認訂單').trigger('click')
    expect(api.post.mock.calls.filter(([url]) => url === '/cart/checkout')).toHaveLength(1)

    pendingPut.resolve({ data: { data: { id: 99 } } })
    await change
    await clearing
    await flushPromises()
    expect(api.delete.mock.calls.filter(([url]) => url === '/cart')).toHaveLength(1)
  })
})

describe('product reviews', () => {
  it('shows aggregate ratings and lets a verified buyer submit a review', async () => {
    api.get.mockImplementation((url) => {
      if (url === '/products/available') return Promise.resolve({ data: { data: [{ ...products[0], averageRating: 4.5, reviewCount: 2 }] } })
      if (url.startsWith('/products/P001/reviews')) return Promise.resolve({ data: { data: {
        reviews: [{ id: 1, reviewerName: '已購買買家', rating: 5, content: '很好', visibility: 'VISIBLE' }],
        averageRating: 5, reviewCount: 1
      } } })
      if (url === '/reviews/mine/P001') return Promise.resolve({ data: { data: null } })
      if (url === '/member/addresses') return Promise.resolve({ data: { data: addresses } })
      if (url === '/cart') return Promise.resolve({ data: { data: [] } })
      return Promise.resolve({ data: { data: [] } })
    })
    await mountApp({ signedIn: true })
    expect(wrapper.text()).toContain('★ 4.5（2 則）')
    await wrapper.findAll('button').find(b => b.text() === '查看評論').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('很好')
    await wrapper.get('#review-content').setValue('我的評論')
    await wrapper.get('section[aria-labelledby="reviews-title"] form').trigger('submit')
    await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/products/P001/reviews', { rating: 5, content: '我的評論' })
  })

  it('lets a seller hide a review but does not show the buyer authoring form', async () => {
    const review = { id: 7, reviewerName: '已購買買家', rating: 2, content: '待審核', visibility: 'VISIBLE' }
    api.get.mockImplementation((url) => {
      if (url === '/products/available') return Promise.resolve({ data: { data: [products[0]] } })
      if (url.startsWith('/products/P001/reviews')) return Promise.resolve({ data: { data: { reviews: [review], averageRating: 2, reviewCount: 1 } } })
      if (url === '/seller/reviews') return Promise.resolve({ data: { data: [review] } })
      if (url === '/member/addresses') return Promise.resolve({ data: { data: addresses } })
      if (url === '/cart') return Promise.resolve({ data: { data: [] } })
      return Promise.resolve({ data: { data: [] } })
    })
    await mountApp({ signedIn: true, role: 'SELLER' })
    await wrapper.findAll('button').find(b => b.text() === '查看評論').trigger('click')
    await flushPromises()
    expect(wrapper.find('#review-content').exists()).toBe(false)
    await wrapper.findAll('button').find(b => b.text() === '隱藏').trigger('click')
    await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/seller/reviews/7/hide')
  })
})

describe('shipping address book UI', () => {
  beforeEach(() => mountApp({ signedIn: true }))

  it('creates a saved address and selects it for checkout', async () => {
    await button('新增收件地址').trigger('click')
    await wrapper.get('#address-label').setValue('公司')
    await wrapper.get('#receiver-name').setValue('林小華')
    await wrapper.get('#address-phone').setValue('02-1234-5678')
    await wrapper.get('#postal-code').setValue('110')
    await wrapper.get('#street-address').setValue('台北市信義區測試路 2 號')
    api.post.mockResolvedValueOnce({ data: { data: {
      id: 8, label: '公司', receiverName: '林小華', phone: '02-1234-5678',
      postalCode: '110', address: '台北市信義區測試路 2 號', isDefault: false
    } } })
    await button('儲存地址').trigger('click')
    await flushPromises()

    expect(api.post).toHaveBeenCalledWith('/member/addresses', {
      label: '公司', receiverName: '林小華', phone: '02-1234-5678', postalCode: '110',
      address: '台北市信義區測試路 2 號', isDefault: false
    })
    expect(wrapper.get('#shipping-address').element.value).toBe('8')
  })
})

describe('audit log entry point', () => {
  it('is offered to ADMIN only and opens the audit panel', async () => {
    api.get.mockImplementation((url) => {
      if (url === '/admin/audit-logs') return Promise.resolve({ data: { data: { entries: [], total: 0, page: 0, size: 20 } } })
      if (url === '/member/addresses') return Promise.resolve({ data: { data: addresses } })
      if (url === '/cart') return Promise.resolve({ data: { data: [] } })
      return Promise.resolve({ data: { data: products } })
    })
    await mountApp({ signedIn: true, role: 'SELLER' })
    expect(button('稽核日誌')).toBeUndefined()
    wrapper.unmount()

    await mountApp({ signedIn: true, role: 'ADMIN' })
    await button('稽核日誌').trigger('click')
    await flushPromises()
    expect(api.get).toHaveBeenCalledWith('/admin/audit-logs', { params: { page: 0, size: 20 } })
    expect(wrapper.text()).toContain('操作稽核日誌')
    await wrapper.get('.audit-panel .link-button').trigger('click')
    expect(wrapper.find('.audit-panel').exists()).toBe(false)
  })
})

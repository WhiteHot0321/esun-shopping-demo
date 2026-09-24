import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import OrdersPanel from './OrdersPanel.vue'
import api from '../api'
import { createToasts, TOAST_KEY } from '../composables/toast'

vi.mock('../api', () => ({ default: { get: vi.fn(), post: vi.fn() } }))

const order = (overrides = {}) => ({
  orderId: 'Ms1', status: 'CREATED', price: 200, createdAt: '2026-09-24T10:00:00',
  receiverName: '王小明', receiverPhone: '0912', shippingAddress: '100 台北市',
  items: [{ productId: 'P1', productName: 'Tea', quantity: 2, unitPrice: 100, itemPrice: 200 }],
  timeline: [{ fromStatus: null, toStatus: 'CREATED', actorRole: 'BUYER', createdAt: '2026-09-24T10:00:00' }],
  allowedActions: ['CANCELLED'], ...overrides
})
const pageOf = (orders) => ({ data: { data: { orders, total: orders.length, page: 0, size: 10 } } })

let toast
const mountPanel = async (mode) => {
  toast = createToasts()
  const wrapper = mount(OrdersPanel, { props: { mode }, global: { provide: { [TOAST_KEY]: toast } } })
  await flushPromises()
  return wrapper
}
const button = (wrapper, text) => wrapper.findAll('button').find((item) => item.text() === text)

beforeEach(() => {
  vi.resetAllMocks()
  vi.spyOn(window, 'confirm').mockReturnValue(true)
})

describe('OrdersPanel', () => {
  it('buyer mode loads own orders, shows the timeline and only offers the backend-allowed cancel', async () => {
    api.get.mockResolvedValue(pageOf([order()]))
    const wrapper = await mountPanel('buyer')
    expect(api.get).toHaveBeenCalledWith('/orders', { params: { status: 'all', page: 0, size: 10 } })
    expect(wrapper.text()).toContain('我的訂單')
    expect(wrapper.text()).toContain('待確認')
    expect(wrapper.find('.timeline').text()).toContain('待確認')
    expect(button(wrapper, '取消訂單')).toBeDefined()
    expect(button(wrapper, '標記出貨')).toBeUndefined()
  })

  it('buyer cancel calls the cancel endpoint, then reloads the list', async () => {
    api.get.mockResolvedValueOnce(pageOf([order()])).mockResolvedValueOnce(pageOf([order({ status: 'CANCELLED', allowedActions: [] })]))
    api.post.mockResolvedValue({ data: { success: true } })
    const wrapper = await mountPanel('buyer')
    await button(wrapper, '取消訂單').trigger('click')
    await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/orders/Ms1/cancel')
    expect(api.get).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('已取消')
    expect(button(wrapper, '取消訂單')).toBeUndefined()
  })

  it('does nothing when the cancel confirmation is declined', async () => {
    window.confirm.mockReturnValue(false)
    api.get.mockResolvedValue(pageOf([order()]))
    const wrapper = await mountPanel('buyer')
    await button(wrapper, '取消訂單').trigger('click')
    await flushPromises()
    expect(api.post).not.toHaveBeenCalled()
  })

  it('seller mode uses the seller endpoint, filters by status and posts the chosen transition', async () => {
    api.get.mockResolvedValue(pageOf([order({ status: 'CONFIRMED', allowedActions: ['SHIPPED', 'CANCELLED'] })]))
    api.post.mockResolvedValue({ data: { success: true } })
    const wrapper = await mountPanel('seller')
    expect(api.get).toHaveBeenLastCalledWith('/seller/orders', { params: { status: 'all', page: 0, size: 10 } })
    expect(wrapper.text()).toContain('訂單管理')

    await button(wrapper, '已出貨').trigger('click')
    await flushPromises()
    expect(api.get).toHaveBeenLastCalledWith('/seller/orders', { params: { status: 'SHIPPED', page: 0, size: 10 } })

    await button(wrapper, '標記出貨').trigger('click')
    await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/seller/orders/Ms1/status', { status: 'SHIPPED' })
  })

  it('shows the server message and reloads when a transition conflicts', async () => {
    api.get.mockResolvedValue(pageOf([order({ status: 'CONFIRMED', allowedActions: ['SHIPPED'] })]))
    api.post.mockRejectedValue({ response: { status: 409, data: { message: '訂單狀態已被其他人變更，請重新整理' } } })
    const wrapper = await mountPanel('seller')
    await button(wrapper, '標記出貨').trigger('click')
    await flushPromises()
    expect(toast.items.map((item) => item.text)).toContain('訂單狀態已被其他人變更，請重新整理')
    expect(api.get).toHaveBeenCalledTimes(2)
  })

  it('shows an empty state and hides actions for read-only orders', async () => {
    api.get.mockResolvedValue(pageOf([]))
    const wrapper = await mountPanel('buyer')
    expect(wrapper.text()).toContain('目前沒有訂單')
    api.get.mockResolvedValue(pageOf([order({ status: 'DELIVERED', allowedActions: [] })]))
    await button(wrapper, '全部').trigger('click')
    await flushPromises()
    expect(wrapper.find('.order-card__actions').exists()).toBe(false)
  })

  it('ignores a slow stale response after the filter changed', async () => {
    let releaseFirst
    api.get.mockImplementationOnce(() => new Promise((resolve) => { releaseFirst = resolve }))
    const wrapper = mount(OrdersPanel, { props: { mode: 'buyer' }, global: { provide: { [TOAST_KEY]: createToasts() } } })
    api.get.mockResolvedValueOnce(pageOf([order({ orderId: 'MsNEW', status: 'SHIPPED', allowedActions: [] })]))
    await button(wrapper, '已出貨').trigger('click')
    await flushPromises()
    releaseFirst(pageOf([order({ orderId: 'MsSTALE' })]))
    await flushPromises()
    expect(wrapper.text()).toContain('MsNEW')
    expect(wrapper.text()).not.toContain('MsSTALE')
  })

  describe('payment', () => {
    const unpaid = (overrides = {}) => order({ payStatus: 'PENDING', paymentStatus: null, payable: true, ...overrides })
    const payment = { paymentId: 1, orderId: 'Ms1', merchantTradeNo: 'PAYabc', amount: 200, status: 'INITIATED',
      provider: 'sandbox', simulatable: true }

    it('labels each payment state from the server fields and offers to pay only payable buyer orders', async () => {
      api.get.mockResolvedValue(pageOf([
        unpaid({ orderId: 'MsA' }),
        unpaid({ orderId: 'MsB', paymentStatus: 'FAILED' }),
        order({ orderId: 'MsC', payStatus: 'PAID', paymentStatus: 'SUCCEEDED', payable: false, allowedActions: [] }),
        order({ orderId: 'MsD', status: 'CANCELLED', payStatus: 'PAID', paymentStatus: 'REFUND_REQUIRED', payable: false, allowedActions: [] }),
        order({ orderId: 'MsE', status: 'CANCELLED', payStatus: 'PENDING', paymentStatus: 'FAILED', payable: false, allowedActions: [] })
      ]))
      const wrapper = await mountPanel('buyer')
      const badges = wrapper.findAll('[data-testid="pay-badge"]').map((item) => item.text())
      expect(badges).toEqual(['未付款', '付款失敗', '已付款', '待退款'])
      expect(wrapper.findAll('button').filter((item) => item.text() === '前往付款')).toHaveLength(1)
      expect(wrapper.findAll('button').filter((item) => item.text() === '重新付款')).toHaveLength(1)
    })

    it('never offers payment in seller mode', async () => {
      api.get.mockResolvedValue(pageOf([unpaid({ allowedActions: [] })]))
      const wrapper = await mountPanel('seller')
      expect(button(wrapper, '前往付款')).toBeUndefined()
      expect(wrapper.find('[data-testid="pay-badge"]').text()).toBe('未付款')
    })

    it('starts a payment, reports the sandbox result and reloads the list', async () => {
      api.get.mockResolvedValueOnce(pageOf([unpaid()]))
        .mockResolvedValueOnce(pageOf([order({ payStatus: 'PAID', paymentStatus: 'SUCCEEDED', payable: false })]))
      api.post.mockResolvedValueOnce({ data: { data: payment } }).mockResolvedValueOnce({ data: { data: { ...payment, status: 'SUCCEEDED' } } })
      const wrapper = await mountPanel('buyer')

      await button(wrapper, '前往付款').trigger('click')
      await flushPromises()
      expect(api.post).toHaveBeenNthCalledWith(1, '/orders/Ms1/payment')
      expect(wrapper.find('[data-testid="pay-box"]').text()).toContain('PAYabc')

      await button(wrapper, '模擬付款成功').trigger('click')
      await flushPromises()
      expect(api.post).toHaveBeenNthCalledWith(2, '/payments/PAYabc/sandbox-result', { result: 'SUCCESS' })
      expect(api.get).toHaveBeenCalledTimes(2)
      expect(wrapper.find('[data-testid="pay-box"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="pay-badge"]').text()).toBe('已付款')
      expect(button(wrapper, '前往付款')).toBeUndefined()
    })

    it('a simulated failure closes the box and keeps the order payable', async () => {
      api.get.mockResolvedValueOnce(pageOf([unpaid()])).mockResolvedValueOnce(pageOf([unpaid({ paymentStatus: 'FAILED' })]))
      api.post.mockResolvedValueOnce({ data: { data: payment } }).mockResolvedValueOnce({ data: { data: { ...payment, status: 'FAILED' } } })
      const wrapper = await mountPanel('buyer')
      await button(wrapper, '前往付款').trigger('click')
      await flushPromises()
      await button(wrapper, '模擬付款失敗').trigger('click')
      await flushPromises()
      expect(api.post).toHaveBeenLastCalledWith('/payments/PAYabc/sandbox-result', { result: 'FAILED' })
      expect(wrapper.find('[data-testid="pay-box"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="pay-badge"]').text()).toBe('付款失敗')
      expect(button(wrapper, '重新付款')).toBeDefined()
    })

    it('shows the server message and no pay box when payment cannot be started', async () => {
      api.get.mockResolvedValue(pageOf([unpaid()]))
      api.post.mockRejectedValue({ response: { status: 503, data: { message: '付款服務未啟用' } } })
      const wrapper = await mountPanel('buyer')
      await button(wrapper, '前往付款').trigger('click')
      await flushPromises()
      expect(toast.items.map((item) => item.text)).toContain('付款服務未啟用')
      expect(wrapper.find('[data-testid="pay-box"]').exists()).toBe(false)
    })
  })

  it('steps back a page when the current page becomes empty', async () => {
    const many = { data: { data: { orders: [order()], total: 11, page: 0, size: 10 } } }
    api.get.mockResolvedValueOnce(many)
      .mockResolvedValueOnce({ data: { data: { orders: [], total: 10, page: 1, size: 10 } } })
      .mockResolvedValueOnce(pageOf([order({ orderId: 'MsBACK' })]))
    const wrapper = await mountPanel('buyer')
    await button(wrapper, '下一頁').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('MsBACK')
    expect(api.get.mock.calls.at(-1)[1].params.page).toBe(0)
  })
})


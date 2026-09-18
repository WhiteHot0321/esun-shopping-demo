import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import OrderHistory from './OrderHistory.vue'
import api from '../api'

vi.mock('../api', () => ({ default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() } }))

const order = { orderId: 'O-1', price: 100, payStatus: 0, createdAt: '2026-09-17T10:00:00' }
const page = (content) => ({ data: { data: { content, page: 0, size: 10, totalElements: content.length, totalPages: content.length ? 1 : 0 } } })
let wrapper

const mountHistory = async () => {
  wrapper = mount(OrderHistory, { props: { authenticated: true } })
  await flushPromises()
}

beforeEach(() => {
  vi.resetAllMocks()
})

describe('OrderHistory', () => {
  it('does not load orders when not authenticated', async () => {
    wrapper = mount(OrderHistory, { props: { authenticated: false } })
    await flushPromises()
    expect(api.get).not.toHaveBeenCalled()
    expect(wrapper.find('.card').exists()).toBe(false)
  })

  it('lists only the authenticated member\'s orders and loads detail on click', async () => {
    api.get.mockResolvedValueOnce(page([order]))
    await mountHistory()
    expect(api.get).toHaveBeenCalledWith('/orders?page=0&size=10')
    expect(wrapper.text()).toContain('O-1')

    api.get.mockResolvedValueOnce({ data: { data: { orderId: 'O-1', price: 100, payStatus: 0, items: [{ productId: 'P001', quantity: 2, unitPrice: 10, itemPrice: 20 }] } } })
    await wrapper.findAll('button').find(b => b.text() === '查看明細').trigger('click')
    await flushPromises()
    expect(api.get).toHaveBeenLastCalledWith('/orders/O-1')
    expect(wrapper.text()).toContain('P001')
  })

  it('shows an empty state when there are no orders', async () => {
    api.get.mockResolvedValueOnce(page([]))
    await mountHistory()
    expect(wrapper.text()).toContain('目前沒有訂單')
  })

  it('shows a readable error when the list request fails, without leaking raw error detail', async () => {
    api.get.mockRejectedValueOnce(new Error('network down'))
    await mountHistory()
    expect(wrapper.find('[role="alert"]').text()).toBe('訂單資料載入失敗，請稍後再試')
  })

  it('shows a readable error when another member\'s order detail is forbidden', async () => {
    api.get.mockResolvedValueOnce(page([order]))
    await mountHistory()
    api.get.mockRejectedValueOnce({ response: { status: 403, data: { message: '無權存取此訂單' } } })
    await wrapper.findAll('button').find(b => b.text() === '查看明細').trigger('click')
    await flushPromises()
    expect(wrapper.get('.detail [role="alert"]').text()).toBe('無權存取此訂單')
  })

  it('reloads with the selected payStatus filter and resets to page 0', async () => {
    api.get.mockResolvedValueOnce(page([order]))
    await mountHistory()
    api.get.mockResolvedValueOnce(page([order]))
    await wrapper.get('select').setValue('1')
    await flushPromises()
    expect(api.get).toHaveBeenLastCalledWith('/orders?page=0&size=10&payStatus=1')
  })
})

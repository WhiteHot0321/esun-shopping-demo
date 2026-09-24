import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import AuditLogPanel from './AuditLogPanel.vue'
import api from '../api'
import { createToasts, TOAST_KEY } from '../composables/toast'

vi.mock('../api', () => ({ default: { get: vi.fn() } }))

const entry = (overrides = {}) => ({
  id: 1, actor: 'seller@example.com', actorRole: 'SELLER', action: 'PRODUCT_RESTOCK',
  targetType: 'PRODUCT', targetId: 'P1', createdAt: '2026-09-24T10:00:00.123',
  before: { productName: 'Tea', quantity: 1 }, after: { productName: 'Tea', quantity: 6, restockAmount: 5 }, ...overrides
})
const pageOf = (entries, total = entries.length) => ({ data: { data: { entries, total, page: 0, size: 20 } } })

let toast
const mountPanel = async () => {
  toast = createToasts()
  const wrapper = mount(AuditLogPanel, { global: { provide: { [TOAST_KEY]: toast } } })
  await flushPromises()
  return wrapper
}
const button = (wrapper, text) => wrapper.findAll('button').find((item) => item.text() === text)

beforeEach(() => {
  vi.resetAllMocks()
})

describe('AuditLogPanel', () => {
  it('loads the newest page and shows actor, role, action label and only the changed fields', async () => {
    api.get.mockResolvedValue(pageOf([entry()]))
    const wrapper = await mountPanel()
    expect(api.get).toHaveBeenCalledWith('/admin/audit-logs', { params: { page: 0, size: 20 } })
    expect(wrapper.text()).toContain('商品補貨')
    expect(wrapper.text()).toContain('seller@example.com（SELLER）')
    expect(wrapper.text()).toContain('PRODUCT · P1')
    const diff = wrapper.get('.audit-diff').text()
    expect(diff).toContain('quantity')
    expect(diff).toContain('restockAmount')
    // Unchanged fields are not repeated.
    expect(diff).not.toContain('productName')
  })

  it('lists every recorded field for a creation (no before snapshot)', async () => {
    api.get.mockResolvedValue(pageOf([entry({ action: 'PRODUCT_CREATE', before: null, after: { productName: 'Tea', price: 10 } })]))
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain('建立商品')
    expect(wrapper.get('.audit-diff').text()).toContain('productName')
    expect(wrapper.get('.audit-diff').text()).toContain('price')
    expect(wrapper.find('.audit-diff__before').exists()).toBe(false)
  })

  it('sends only the filled filters, converts datetime-local to ISO seconds and resets to page 0', async () => {
    api.get.mockResolvedValue(pageOf([entry()]))
    const wrapper = await mountPanel()
    await wrapper.get('#audit-action').setValue('PRODUCT_DELETE')
    await wrapper.get('#audit-actor').setValue('seller@example.com')
    await wrapper.get('#audit-from').setValue('2026-09-24T08:30')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.get).toHaveBeenLastCalledWith('/admin/audit-logs', {
      params: { page: 0, size: 20, action: 'PRODUCT_DELETE', actor: 'seller@example.com', from: '2026-09-24T08:30:00' }
    })

    await button(wrapper, '清除').trigger('click')
    await flushPromises()
    expect(api.get).toHaveBeenLastCalledWith('/admin/audit-logs', { params: { page: 0, size: 20 } })
  })

  it('pages through results keeping the applied filters', async () => {
    api.get.mockResolvedValue(pageOf([entry()], 45))
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain('第 1 / 3 頁（共 45 筆）')
    await button(wrapper, '下一頁').trigger('click')
    await flushPromises()
    expect(api.get).toHaveBeenLastCalledWith('/admin/audit-logs', { params: { page: 1, size: 20 } })
  })

  it('shows an empty state and surfaces the server message on failure', async () => {
    api.get.mockResolvedValueOnce(pageOf([]))
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain('沒有符合條件的稽核紀錄')

    api.get.mockRejectedValueOnce({ response: { status: 403, data: { message: '權限不足' } } })
    await button(wrapper, '查詢').trigger('click')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(toast.items.map((item) => item.text)).toContain('權限不足')
  })

  it('ignores a slow stale response after a newer query', async () => {
    let releaseFirst
    api.get.mockImplementationOnce(() => new Promise((resolve) => { releaseFirst = resolve }))
    const wrapper = mount(AuditLogPanel, { global: { provide: { [TOAST_KEY]: createToasts() } } })
    api.get.mockResolvedValueOnce(pageOf([entry({ id: 2, targetId: 'NEW' })]))
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    releaseFirst(pageOf([entry({ id: 3, targetId: 'STALE' })]))
    await flushPromises()
    expect(wrapper.text()).toContain('NEW')
    expect(wrapper.text()).not.toContain('STALE')
  })
})

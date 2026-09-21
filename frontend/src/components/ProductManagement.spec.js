import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ProductManagement from './ProductManagement.vue'
import api from '../api'

vi.mock('../api', () => ({ default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() } }))

const active = { productId: 'OWN-1', productName: 'Tea', price: 12.5, quantity: 3, deletedAt: null, imageUrls: ['/uploads/products/a.png'] }
const active2 = { productId: 'OWN-2', productName: 'Coffee', price: 20, quantity: 4, deletedAt: null, imageUrls: [] }
const deleted = { productId: 'OWN-9', productName: 'Cake', price: 30, quantity: 1, deletedAt: '2026-09-17T10:00:00', imageUrls: [] }
const page = (products, total = products.length) => ({ data: { data: { products, total, page: 0, size: 10 } } })
let wrapper
const button = text => wrapper.findAll('button').find(item => item.text() === text)
const lastGetParams = () => api.get.mock.calls.at(-1)[1].params

beforeEach(async () => {
  vi.resetAllMocks()
  api.get.mockResolvedValue(page([active, active2]))
  wrapper = mount(ProductManagement)
  await flushPromises()
})

describe('ProductManagement', () => {
  it('loads the active page from the search endpoint and shows images from the API origin', () => {
    expect(api.get).toHaveBeenCalledWith('/admin/products/search', { params: { keyword: '', status: 'active', page: 0, size: 10 } })
    expect(wrapper.text()).toContain('Tea')
    expect(wrapper.find('.images img').attributes('src')).toBe('http://localhost:8080/uploads/products/a.png')
  })

  it('switches to deleted products without management actions, and searches from the first page', async () => {
    api.get.mockResolvedValue(page([deleted]))
    await button('已下架').trigger('click'); await flushPromises()
    expect(lastGetParams()).toMatchObject({ status: 'deleted', page: 0 })
    expect(wrapper.text()).toContain('Cake')
    expect(button('編輯')).toBeUndefined()
    expect(button('批量下架')).toBeUndefined()

    await wrapper.find('input[placeholder="搜尋編號或名稱"]').setValue('cak')
    await button('搜尋').trigger('click'); await flushPromises()
    expect(lastGetParams()).toMatchObject({ keyword: 'cak', status: 'deleted', page: 0 })
  })

  it('creates without any ownership field', async () => {
    await wrapper.find('input[placeholder="商品編號"]').setValue('OWN-3')
    await wrapper.find('input[placeholder="商品名稱"]').setValue('Juice')
    await wrapper.find('input[placeholder="價格"]').setValue('20')
    await wrapper.find('input[placeholder="庫存"]').setValue('4')
    api.post.mockResolvedValue({ data: { success: true } })
    await button('新增商品').trigger('click'); await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/admin/products', { productId: 'OWN-3', productName: 'Juice', price: 20, quantity: 4 })
    expect(api.post.mock.calls[0][1]).not.toHaveProperty('creatorId')
  })

  it('edits, confirms soft delete, and restocks', async () => {
    api.put.mockResolvedValue({ data: { success: true } }); api.delete.mockResolvedValue({ data: { success: true } }); api.post.mockResolvedValue({ data: { success: true } })
    await button('編輯').trigger('click')
    await wrapper.find('tbody input[placeholder="商品名稱"]').setValue('Better tea')
    await wrapper.find('tbody input[placeholder="價格"]').setValue('15')
    await button('儲存').trigger('click'); await flushPromises()
    expect(api.put).toHaveBeenCalledWith('/admin/products/OWN-1', { productName: 'Better tea', price: 15 })

    await wrapper.find('tbody input[placeholder="補貨量"]').setValue('2')
    await button('補貨').trigger('click'); await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/admin/products/OWN-1/restock?amount=2')

    vi.spyOn(window, 'confirm').mockReturnValue(true)
    await button('下架').trigger('click'); await flushPromises()
    expect(api.delete).toHaveBeenCalledWith('/admin/products/OWN-1')
  })

  it('pages through results using the server total', async () => {
    api.get.mockResolvedValue(page([active], 25))
    await button('重新載入').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('第 1 / 3 頁')
    expect(button('上一頁').attributes('disabled')).toBeDefined()

    await button('下一頁').trigger('click'); await flushPromises()
    expect(lastGetParams()).toMatchObject({ page: 1, size: 10 })
  })

  it('bulk actions are disabled until rows are selected and send only ids, action and amount', async () => {
    api.post.mockResolvedValue({ data: { success: true } })
    expect(button('批量下架').attributes('disabled')).toBeDefined()
    expect(button('批量補貨').attributes('disabled')).toBeDefined()

    const boxes = wrapper.findAll('input[type="checkbox"]')
    await boxes[0].setValue(true); await boxes[1].setValue(true)
    expect(button('批量下架').attributes('disabled')).toBeUndefined()
    expect(button('批量補貨').attributes('disabled')).toBeDefined()

    await wrapper.find('input[placeholder="批量補貨量"]').setValue('5')
    await button('批量補貨').trigger('click'); await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/admin/products/bulk', { productIds: ['OWN-1', 'OWN-2'], action: 'RESTOCK', amount: 5 })

    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const again = wrapper.findAll('input[type="checkbox"]')
    await again[0].setValue(true)
    await button('批量下架').trigger('click'); await flushPromises()
    expect(confirm).toHaveBeenCalled()
    expect(api.post).toHaveBeenLastCalledWith('/admin/products/bulk', { productIds: ['OWN-1'], action: 'DELETE' })
  })

  it('does not bulk delete when the confirmation is declined', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    await wrapper.findAll('input[type="checkbox"]')[0].setValue(true)
    await button('批量下架').trigger('click'); await flushPromises()
    expect(api.post).not.toHaveBeenCalled()
  })

  it('uploads multiple images as multipart "images" parts and refreshes', async () => {
    api.post.mockResolvedValue({ data: { data: ['/uploads/products/x.png'] } })
    const input = wrapper.find('input[type="file"]')
    const files = [new File(['a'], 'a.png', { type: 'image/png' }), new File(['b'], 'b.png', { type: 'image/png' })]
    Object.defineProperty(input.element, 'files', { value: files, configurable: true })
    await input.trigger('change'); await flushPromises()

    const [url, form, config] = api.post.mock.calls[0]
    expect(url).toBe('/admin/products/OWN-1/images')
    expect(config.headers['Content-Type']).toBe('multipart/form-data')
    expect(form).toBeInstanceOf(FormData)
    expect(form.getAll('images')).toHaveLength(2)
    expect(api.get).toHaveBeenCalledTimes(2)
  })

  it('surfaces server validation and permission errors from bulk and upload calls', async () => {
    api.post.mockRejectedValueOnce({ response: { status: 403 } }).mockRejectedValueOnce({ response: { status: 400, data: { message: '僅支援 JPEG、PNG、WEBP 圖片' } } })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    await wrapper.findAll('input[type="checkbox"]')[0].setValue(true)
    await button('批量下架').trigger('click'); await flushPromises()
    expect(wrapper.emitted('message').at(-1)).toEqual(['你沒有管理此商品的權限'])

    const input = wrapper.find('input[type="file"]')
    Object.defineProperty(input.element, 'files', { value: [new File(['x'], 'x.gif', { type: 'image/gif' })], configurable: true })
    await input.trigger('change'); await flushPromises()
    expect(wrapper.emitted('message').at(-1)).toEqual(['僅支援 JPEG、PNG、WEBP 圖片'])
  })
})

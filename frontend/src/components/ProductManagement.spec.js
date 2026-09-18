import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ProductManagement from './ProductManagement.vue'
import api from '../api'

vi.mock('../api', () => ({ default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() } }))

const active = { productId: 'OWN-1', productName: 'Tea', price: 12.5, quantity: 3, deletedAt: null }
const deleted = { productId: 'OWN-2', productName: 'Cake', price: 30, quantity: 1, deletedAt: '2026-09-17T10:00:00' }
let wrapper
const button = text => wrapper.findAll('button').find(item => item.text() === text)

beforeEach(async () => {
  vi.resetAllMocks()
  api.get.mockResolvedValue({ data: { data: [active, deleted] } })
  wrapper = mount(ProductManagement, { props: { authenticated: true } })
  await flushPromises()
})

describe('ProductManagement', () => {
  it('filters own active and deleted products without sending ownership fields', async () => {
    expect(wrapper.text()).toContain('Tea')
    expect(wrapper.text()).not.toContain('Cake')
    await button('已下架').trigger('click')
    expect(wrapper.text()).toContain('Cake')
    expect(wrapper.text()).not.toContain('Tea')

    await button('上架中').trigger('click')
    const inputs = wrapper.findAll('.product-management input').slice(0, 4)
    await inputs[0].setValue('OWN-3'); await inputs[1].setValue('Coffee'); await inputs[2].setValue('20'); await inputs[3].setValue('4')
    api.post.mockResolvedValue({ data: { success: true } })
    await button('新增商品').trigger('click')
    await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/admin/products', { productId: 'OWN-3', productName: 'Coffee', price: 20, quantity: 4 })
    expect(api.post.mock.calls[0][1]).not.toHaveProperty('creatorId')
  })

  it('shows immediate validation and readable authorization errors', async () => {
    expect(button('新增商品').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('請輸入商品編號')
    const inputs = wrapper.findAll('.product-management input').slice(0, 4)
    await inputs[0].setValue('OWN-3'); await inputs[1].setValue('Coffee'); await inputs[2].setValue('0'); await inputs[3].setValue('-1')
    expect(wrapper.text()).toContain('價格至少為 0.01')
    api.post.mockRejectedValue({ response: { status: 403, data: {} } })
    await inputs[2].setValue('10'); await inputs[3].setValue('1')
    await button('新增商品').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('message').at(-1)[0]).toBe('你沒有管理此商品的權限')
  })

  it('edits, confirms soft delete, and restocks an active product', async () => {
    api.put.mockResolvedValue({ data: { success: true } }); api.delete.mockResolvedValue({ data: { success: true } }); api.post.mockResolvedValue({ data: { success: true } })
    await button('編輯').trigger('click')
    const editInputs = wrapper.findAll('tbody input')
    await editInputs[0].setValue('Better tea'); await editInputs[1].setValue('15')
    await button('儲存').trigger('click'); await flushPromises()
    expect(api.put).toHaveBeenCalledWith('/admin/products/OWN-1', { productName: 'Better tea', price: 15 })

    const restock = wrapper.find('tbody input[placeholder="補貨量"]')
    await restock.setValue('2'); await button('補貨').trigger('click'); await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/admin/products/OWN-1/restock?amount=2')

    vi.spyOn(window, 'confirm').mockReturnValue(true)
    await button('下架').trigger('click'); await flushPromises()
    expect(api.delete).toHaveBeenCalledWith('/admin/products/OWN-1')
  })
})

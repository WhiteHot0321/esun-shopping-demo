import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import CouponPanel from './CouponPanel.vue'
import api from '../api'
import { createToasts, TOAST_KEY } from '../composables/toast'

vi.mock('../api', () => ({ default: { get: vi.fn(), post: vi.fn(), put: vi.fn() } }))

const coupon = (overrides = {}) => ({
  id: 1, code: 'SAVE10', discountType: 'PERCENT', discountValue: 10, maxDiscount: 100, minOrderAmount: 200,
  totalQuota: 50, usedCount: 3, perMemberLimit: 1, startsAt: '2026-09-01T00:00:00', expiresAt: '2026-12-01T00:00:00',
  active: true, status: 'ACTIVE', ...overrides
})
const pageOf = (items, total = items.length) => ({ data: { data: { items, total, page: 0, size: 20 } } })

let toast
const mountPanel = async () => {
  toast = createToasts()
  const wrapper = mount(CouponPanel, { global: { provide: { [TOAST_KEY]: toast } } })
  await flushPromises()
  return wrapper
}

const fillValid = async (wrapper, code, value) => {
  await wrapper.get('#cp-code').setValue(code)
  await wrapper.get('#cp-value').setValue(value)
  await wrapper.get('#cp-start').setValue('2026-10-01T00:00')
  await wrapper.get('#cp-end').setValue('2026-11-01T00:00')
}

beforeEach(() => {
  vi.resetAllMocks()
  api.get.mockResolvedValue(pageOf([coupon()]))
})

describe('CouponPanel', () => {
  it('lists coupons with rule, usage and status', async () => {
    const wrapper = await mountPanel()
    expect(api.get).toHaveBeenCalledWith('/admin/coupons', { params: { page: 0, size: 20 } })
    const entry = wrapper.get('[data-testid="coupon-entry"]').text()
    expect(entry).toContain('SAVE10')
    expect(entry).toContain('使用中')
    expect(entry).toContain('10% off')
    expect(entry).toContain('已使用 3 / 50')
  })

  it('validates locally before calling the API', async () => {
    const wrapper = await mountPanel()
    await wrapper.get('form.coupon-form').trigger('submit')
    expect(wrapper.get('.coupon-form__error').text()).toContain('請輸入優惠碼')
    await wrapper.get('#cp-code').setValue('NEW20')
    await wrapper.get('#cp-value').setValue('120')
    await wrapper.get('form.coupon-form').trigger('submit')
    expect(wrapper.get('.coupon-form__error').text()).toContain('1 到 99')
    expect(api.post).not.toHaveBeenCalled()
  })

  it('creates a coupon, omitting blank optional fields, then reloads', async () => {
    api.post.mockResolvedValue({ data: { data: coupon({ id: 2, code: 'NEW20' }) } })
    const wrapper = await mountPanel()
    await fillValid(wrapper, 'new20', '20')
    await wrapper.get('form.coupon-form').trigger('submit')
    await flushPromises()

    expect(api.post).toHaveBeenCalledWith('/admin/coupons', {
      code: 'new20', discountType: 'PERCENT', discountValue: 20, perMemberLimit: 1,
      startsAt: '2026-10-01T00:00:00', expiresAt: '2026-11-01T00:00:00'
    })
    expect(api.get).toHaveBeenCalledTimes(2)
    expect(wrapper.get('#cp-code').element.value).toBe('')
  })

  it('shows the server error (for example a duplicate code) inside the form', async () => {
    api.post.mockRejectedValue({ response: { status: 409, data: { message: '優惠碼已存在' } } })
    const wrapper = await mountPanel()
    await fillValid(wrapper, 'SAVE10', '5')
    await wrapper.get('form.coupon-form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('.coupon-form__error').text()).toContain('優惠碼已存在')
  })

  it('disables a coupon through the active-only endpoint, never resending cached expiry or quota', async () => {
    api.post.mockResolvedValue({ data: { data: coupon({ active: false }) } })
    const wrapper = await mountPanel()
    await wrapper.get('.coupon-entry__toggle').trigger('click')
    await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/admin/coupons/1/active', { active: false })
    expect(api.put).not.toHaveBeenCalled()
  })
})

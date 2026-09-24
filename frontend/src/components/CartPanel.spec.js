import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { reactive } from 'vue'
import CartPanel from './CartPanel.vue'
import api from '../api'

vi.mock('../api', () => ({ default: { get: vi.fn(), post: vi.fn() } }))

const item = { productId: 'P1', productName: 'Tea', price: 100, quantity: 3, stock: 9, itemPrice: 300 }
const preview = { code: 'SAVE10', subtotal: 300, discountAmount: 30, total: 270 }

const mountCart = async (props = {}) => {
  const form = reactive({ memberId: 'b@example.com', shippingAddressId: null, couponCode: '' })
  const previewCoupon = props.previewCoupon || vi.fn().mockResolvedValue(preview)
  const wrapper = mount(CartPanel, {
    props: { items: [item], total: 300, form, authenticated: true, busy: false, previewCoupon, ...props }
  })
  await flushPromises()
  return { wrapper, form, previewCoupon }
}

const applyButton = (wrapper) => wrapper.findAll('button').find((b) => b.text() === '套用')

beforeEach(() => {
  vi.resetAllMocks()
  api.get.mockResolvedValue({ data: { data: [{ id: 7, label: '住家', receiverName: 'A', address: 'X', isDefault: true }] } })
})

describe('CartPanel coupon', () => {
  it('applies a coupon, shows the priced result and records the code for checkout', async () => {
    const { wrapper, form, previewCoupon } = await mountCart()
    await wrapper.get('#coupon-code').setValue('save10')
    await applyButton(wrapper).trigger('click')
    await flushPromises()

    expect(previewCoupon).toHaveBeenCalledWith('save10')
    expect(wrapper.get('[data-testid="cart-discount"]').text()).toContain('30')
    expect(wrapper.get('[data-testid="cart-payable"]').text()).toContain('270')
    expect(form.couponCode).toBe('SAVE10')
    expect(wrapper.get('#coupon-code').attributes('disabled')).toBeDefined()
  })

  it('shows the server message and submits no code when the coupon is refused', async () => {
    const previewCoupon = vi.fn().mockRejectedValue({ response: { status: 409, data: { message: '優惠券已被領完' } } })
    const { wrapper, form } = await mountCart({ previewCoupon })
    await wrapper.get('#coupon-code').setValue('GONE')
    await applyButton(wrapper).trigger('click')
    await flushPromises()

    expect(wrapper.get('.coupon .field__error').text()).toContain('優惠券已被領完')
    expect(wrapper.find('[data-testid="cart-discount"]').exists()).toBe(false)
    expect(form.couponCode).toBe('')
  })

  it('never submits a code that was typed but not applied', async () => {
    const { wrapper, form } = await mountCart()
    await wrapper.get('#coupon-code').setValue('TYPED')
    expect(form.couponCode).toBe('')
  })

  it('drops the discount and asks to re-apply when the cart total changes', async () => {
    const { wrapper, form } = await mountCart()
    await wrapper.get('#coupon-code').setValue('SAVE10')
    await applyButton(wrapper).trigger('click')
    await flushPromises()
    expect(form.couponCode).toBe('SAVE10')

    await wrapper.setProps({ total: 400, items: [{ ...item, quantity: 4, itemPrice: 400 }] })
    expect(wrapper.find('[data-testid="cart-discount"]').exists()).toBe(false)
    expect(form.couponCode).toBe('')
    expect(wrapper.text()).toContain('請重新套用優惠碼')
  })

  it('ignores a preview that comes back after the cart changed', async () => {
    let resolvePreview
    const previewCoupon = vi.fn(() => new Promise((resolve) => { resolvePreview = resolve }))
    const { wrapper, form } = await mountCart({ previewCoupon })
    await wrapper.get('#coupon-code').setValue('SAVE10')
    await applyButton(wrapper).trigger('click')

    await wrapper.setProps({ total: 400, items: [{ ...item, quantity: 4, itemPrice: 400 }] })
    resolvePreview(preview)
    await flushPromises()

    expect(wrapper.find('[data-testid="cart-discount"]').exists()).toBe(false)
    expect(form.couponCode).toBe('')
    expect(wrapper.text()).toContain('請重新套用優惠碼')
  })

  it('removes an applied coupon on request and locks the field while an unconfirmed order is pending', async () => {
    const { wrapper, form } = await mountCart()
    await wrapper.get('#coupon-code').setValue('SAVE10')
    await applyButton(wrapper).trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find((b) => b.text() === '移除').trigger('click')
    expect(form.couponCode).toBe('')
    expect(wrapper.get('#coupon-code').element.value).toBe('')

    await wrapper.setProps({ pendingAttempt: { requestId: 'r', payload: { items: [] } } })
    expect(wrapper.get('#coupon-code').attributes('disabled')).toBeDefined()
  })

  it('offers no coupon field for an empty cart', async () => {
    const { wrapper } = await mountCart({ items: [], total: 0 })
    expect(wrapper.find('#coupon-code').exists()).toBe(false)
  })
})

import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import RecommendationStrip from './RecommendationStrip.vue'
import api from '../api'

vi.mock('../api', () => ({ default: { get: vi.fn() } }))

const item = (id, reason = 'CO_PURCHASE', score = 2) => ({
  product: { productId: id, productName: `商品 ${id}`, price: 120, quantity: 5, averageRating: 4.5, reviewCount: 3 },
  reason,
  score
})
const respond = (items) => ({ data: { data: items } })
const deferred = () => {
  let resolve
  const promise = new Promise((r) => { resolve = r })
  return { promise, resolve }
}

const mountStrip = async (props = {}) => {
  const wrapper = mount(RecommendationStrip, { props: { title: '為你推薦', ...props } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.resetAllMocks()
})

describe('RecommendationStrip', () => {
  it('loads the signed-in member list when no product is given and labels each reason', async () => {
    api.get.mockResolvedValue(respond([item('A'), item('B', 'POPULAR'), item('C', 'NEW_ARRIVAL', 0)]))
    const wrapper = await mountStrip()

    expect(api.get).toHaveBeenCalledWith('/recommendations', { params: { limit: 6 } })
    const cards = wrapper.findAll('[data-testid="recommendation-item"]')
    expect(cards).toHaveLength(3)
    expect(cards[0].text()).toContain('常被一起購買')
    expect(cards[1].text()).toContain('熱銷商品')
    expect(cards[2].text()).toContain('新上架')
    expect(cards[0].text()).toContain('商品 A')
    expect(wrapper.get('h2').text()).toBe('為你推薦')
  })

  it('uses the per-product endpoint and escapes the id', async () => {
    api.get.mockResolvedValue(respond([item('B')]))
    await mountStrip({ productId: 'P 1/2', title: '也買了' })
    expect(api.get).toHaveBeenCalledWith('/products/P%201%2F2/recommendations', { params: { limit: 6 } })
  })

  it('emits add with the product id', async () => {
    api.get.mockResolvedValue(respond([item('A'), item('B')]))
    const wrapper = await mountStrip()
    await wrapper.findAll('button')[1].trigger('click')
    expect(wrapper.emitted('add')).toEqual([['B']])
  })

  it('renders nothing when there is nothing to recommend', async () => {
    api.get.mockResolvedValue(respond([]))
    const wrapper = await mountStrip()
    expect(wrapper.find('[data-testid="recommendations"]').exists()).toBe(false)
  })

  it('drops malformed entries instead of crashing the page', async () => {
    api.get.mockResolvedValue(respond([{ reason: 'POPULAR' }, null, item('A')]))
    const wrapper = await mountStrip()
    expect(wrapper.findAll('[data-testid="recommendation-item"]')).toHaveLength(1)
    api.get.mockResolvedValue({ data: { data: { not: 'a list' } } })
    await wrapper.setProps({ refreshKey: 1 })
    await flushPromises()
    expect(wrapper.find('[data-testid="recommendations"]').exists()).toBe(false)
  })

  it('shows a quiet message instead of throwing when the request fails', async () => {
    api.get.mockRejectedValue(new Error('boom'))
    const wrapper = await mountStrip()
    expect(wrapper.get('[data-testid="recommendation-error"]').text()).toContain('暫時無法載入')
    expect(wrapper.findAll('[data-testid="recommendation-item"]')).toHaveLength(0)
  })

  it('rejects incomplete, mistyped and out-of-range card fields without exposing add buttons', async () => {
    const invalidFields = {
      productId: [undefined, null, '', '  ', 1, {}],
      productName: [undefined, null, '', '  ', 1, {}],
      price: [undefined, null, '', '120', true, {}, NaN, Infinity, -Infinity, -1, 0, 0.001, 0.009],
      quantity: [undefined, null, '5', true, NaN, Infinity, -1, 0, 1.5],
      averageRating: ['4.5', {}, NaN, Infinity, -1, 5.1],
      reviewCount: ['3', {}, NaN, Infinity, -1, 1.5]
    }
    const invalid = [{ product: { productId: 'A' } }, { product: [] }, ...Object.entries(invalidFields)
      .flatMap(([field, values]) => values.map((value) => ({
        ...item('BAD'), product: { ...item('BAD').product, [field]: value }
      })))]
    api.get.mockResolvedValue(respond(invalid))
    const wrapper = await mountStrip()
    expect(wrapper.find('[data-testid="recommendations"]').exists()).toBe(false)
    expect(wrapper.findAll('button')).toHaveLength(0)
    expect(wrapper.emitted('add')).toBeUndefined()

    api.get.mockResolvedValue(respond([invalid[0], item('VALID'), ...invalid.slice(1)]))
    await wrapper.setProps({ refreshKey: 1 })
    await flushPromises()
    expect(wrapper.findAll('[data-testid="recommendation-item"]')).toHaveLength(1)
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('add')).toEqual([['VALID']])
  })

  it('preserves valid boundary values, optional rating defaults and unknown reason fallback', async () => {
    const entries = [item('LOW', 'FUTURE_REASON', 0), item('HIGH'), item('DEFAULT')]
    Object.assign(entries[0].product, { price: 0.01, quantity: 1, averageRating: 0, reviewCount: 0 })
    entries[1].product.averageRating = 5
    delete entries[2].product.averageRating
    entries[2].product.reviewCount = null
    api.get.mockResolvedValue(respond(entries))
    const wrapper = await mountStrip()
    const cards = wrapper.findAll('[data-testid="recommendation-item"]')
    expect(cards).toHaveLength(3)
    expect(cards[0].text()).toContain('NT$0.01')
    expect(cards[0].text()).toContain('推薦')
    expect(cards[1].text()).toContain('★ 5.0')
    expect(cards[2].text()).toContain('★ 0.0（0 則）')
    await cards[0].get('button').trigger('click')
    expect(wrapper.emitted('add')).toEqual([['LOW']])
  })

  it('treats a null response body as an empty recommendation list', async () => {
    api.get.mockResolvedValue({ data: null })
    const wrapper = await mountStrip()
    expect(wrapper.find('[data-testid="recommendations"]').exists()).toBe(false)
  })

  it('reloads when the product or refresh key changes and ignores a slower stale answer', async () => {
    const slow = deferred()
    api.get.mockReturnValueOnce(slow.promise).mockResolvedValueOnce(respond([item('NEW')]))
    const wrapper = mount(RecommendationStrip, { props: { title: 't', productId: 'OLD' } })
    await wrapper.setProps({ productId: 'NEWER' })
    await flushPromises()
    expect(wrapper.findAll('[data-testid="recommendation-item"]')).toHaveLength(1)
    expect(wrapper.text()).toContain('商品 NEW')

    slow.resolve(respond([item('STALE')]))
    await flushPromises()
    expect(wrapper.text()).not.toContain('商品 STALE')
    expect(wrapper.text()).toContain('商品 NEW')

    api.get.mockResolvedValueOnce(respond([item('AFTER')]))
    await wrapper.setProps({ refreshKey: 1 })
    await flushPromises()
    expect(api.get).toHaveBeenCalledTimes(3)
    expect(wrapper.text()).toContain('商品 AFTER')
  })
})

import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import OpsDashboardPanel from './OpsDashboardPanel.vue'
import api from '../api'

vi.mock('../api', () => ({ default: { get: vi.fn() } }))

const dashboard = (overrides = {}) => ({
  asOf: '2026-10-23T09:30:15.123+08:00',
  timezone: 'Asia/Taipei',
  today: {
    from: '2026-10-23T00:00:00+08:00', to: '2026-10-24T00:00:00+08:00',
    orderCount: 12, revenue: 3456.5, paymentFailureCount: 0
  },
  pendingPaymentCount: 4,
  refundRequiredCount: 0,
  lowStock: { threshold: 5, count: 2, items: [
    { productId: 'P001', productName: '無線滑鼠', quantity: 0 },
    { productId: 'P002', productName: '鍵盤', quantity: 3 }
  ] },
  redis: { mode: 'ENABLED', scope: 'THIS_INSTANCE' },
  ...overrides
})
const respond = (data) => ({ data: { data } })

const mountPanel = async () => {
  const wrapper = mount(OpsDashboardPanel)
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.resetAllMocks()
  api.get.mockResolvedValue(respond(dashboard()))
})

describe('OpsDashboardPanel', () => {
  it('loads the dashboard and shows today\'s figures', async () => {
    const wrapper = await mountPanel()
    expect(api.get).toHaveBeenCalledWith('/admin/dashboard')
    expect(wrapper.get('[data-testid="ops-orders"]').text()).toContain('12')
    expect(wrapper.get('[data-testid="ops-revenue"]').text()).toContain('NT$3,456.50')
    expect(wrapper.get('[data-testid="ops-pending"]').text()).toContain('4')
    expect(wrapper.get('[data-testid="ops-failures"]').text()).toContain('0')
    expect(wrapper.get('[data-testid="ops-refund"]').text()).toContain('0')
  })

  it('shows the Taipei timestamp and day exactly as sent, not through the browser zone', async () => {
    const wrapper = await mountPanel()
    const text = wrapper.get('[data-testid="ops-asof"]').text()
    expect(text).toContain('2026-10-23 09:30:15')
    expect(text).toContain('2026-10-23')
  })

  it('lists low-stock products and flags sold-out ones', async () => {
    const wrapper = await mountPanel()
    const items = wrapper.findAll('[data-testid="ops-lowstock-item"]')
    expect(items).toHaveLength(2)
    expect(items[0].text()).toContain('無線滑鼠')
    expect(items[0].text()).toContain('庫存 0')
    expect(items[0].find('.badge--warn').exists()).toBe(true)
    expect(items[1].find('.badge--warn').exists()).toBe(false)
    expect(wrapper.text()).toContain('庫存 ≤ 5')
    expect(wrapper.find('[data-testid="ops-lowstock-more"]').exists()).toBe(false)
  })

  it('says when the list is truncated', async () => {
    api.get.mockResolvedValue(respond(dashboard({ lowStock: { threshold: 5, count: 37, items: [
      { productId: 'P001', productName: '無線滑鼠', quantity: 0 }
    ] } })))
    const wrapper = await mountPanel()
    expect(wrapper.get('[data-testid="ops-lowstock-more"]').text()).toContain('共 37 項')
  })

  it('renders the empty state when nothing is low on stock', async () => {
    api.get.mockResolvedValue(respond(dashboard({ lowStock: { threshold: 5, count: 0, items: [] } })))
    const wrapper = await mountPanel()
    expect(wrapper.find('[data-testid="ops-lowstock-empty"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-testid="ops-lowstock-item"]')).toHaveLength(0)
  })

  it('highlights payment failures and pending refunds only when they exist', async () => {
    api.get.mockResolvedValue(respond(dashboard({
      refundRequiredCount: 2, today: { ...dashboard().today, paymentFailureCount: 3 }
    })))
    const wrapper = await mountPanel()
    expect(wrapper.get('[data-testid="ops-failures"] dd').classes()).toContain('ops-stat--warn')
    expect(wrapper.get('[data-testid="ops-refund"] dd').classes()).toContain('ops-stat--warn')
    expect(wrapper.get('[data-testid="ops-orders"] dd').classes()).not.toContain('ops-stat--warn')
  })

  it.each([
    ['ENABLED', '運作中', 'badge--ok'],
    ['DISABLED', '未啟用', 'badge--muted'],
    ['LATCHED_DB_ONLY', '已降級為僅資料庫', 'badge--warn']
  ])('shows the Redis state %s', async (mode, label, badge) => {
    api.get.mockResolvedValue(respond(dashboard({ redis: { mode, scope: 'THIS_INSTANCE' } })))
    const wrapper = await mountPanel()
    const redis = wrapper.get('[data-testid="ops-redis"]')
    expect(redis.text()).toContain(label)
    expect(redis.find(`.${badge}`).exists()).toBe(true)
    expect(redis.text()).toContain('這台伺服器')
  })

  it('shows an error with a retry that recovers', async () => {
    api.get.mockRejectedValueOnce({ response: { data: { message: '權限不足' } } })
    const wrapper = await mountPanel()
    expect(wrapper.get('[data-testid="ops-error"]').text()).toContain('權限不足')

    await wrapper.get('[data-testid="ops-error"] button').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="ops-error"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="ops-orders"]').text()).toContain('12')
  })

  it('falls back to a generic message when the server error carries none', async () => {
    api.get.mockRejectedValueOnce({ response: { status: 500, data: {} } })
    const wrapper = await mountPanel()
    expect(wrapper.get('[data-testid="ops-error"]').text()).toContain('載入儀表板失敗')
  })

  it('reports an unreachable server', async () => {
    api.get.mockRejectedValueOnce(new Error('network'))
    const wrapper = await mountPanel()
    expect(wrapper.get('[data-testid="ops-error"]').text()).toContain('無法連線到伺服器')
  })

  it('keeps the previous figures visible when a refresh fails', async () => {
    const wrapper = await mountPanel()
    api.get.mockRejectedValueOnce({ response: { data: { message: '伺服器忙碌' } } })

    await wrapper.get('[data-testid="ops-refresh"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="ops-stale"]').text()).toContain('伺服器忙碌')
    expect(wrapper.get('[data-testid="ops-orders"]').text()).toContain('12')
  })

  it('refreshes on demand', async () => {
    const wrapper = await mountPanel()
    api.get.mockResolvedValue(respond(dashboard({ today: { ...dashboard().today, orderCount: 99 } })))

    await wrapper.get('[data-testid="ops-refresh"]').trigger('click')
    await flushPromises()

    expect(api.get).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[data-testid="ops-orders"]').text()).toContain('99')
  })

  it('emits close', async () => {
    const wrapper = await mountPanel()
    await wrapper.findAll('button').find((b) => b.text() === '關閉').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
  })
})

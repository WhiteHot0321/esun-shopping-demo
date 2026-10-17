import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import MemberAdminPanel from './MemberAdminPanel.vue'
import api from '../api'
import { useAuthStore } from '../stores/auth'
import { createToasts, TOAST_KEY } from '../composables/toast'

vi.mock('../api', () => ({ default: { get: vi.fn(), post: vi.fn(), patch: vi.fn() } }))

const member = (overrides = {}) => ({
  id: 2, email: 'bob@example.com', role: 'BUYER', status: 'ACTIVE', createdAt: '2026-10-01T00:00:00', ...overrides
})
const pageOf = (items, total = items.length) => ({ data: { data: { items, total, page: 0, size: 20 } } })

let toast
const mountPanel = async () => {
  toast = createToasts()
  const wrapper = mount(MemberAdminPanel, { global: { provide: { [TOAST_KEY]: toast } } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.resetAllMocks()
  setActivePinia(createPinia())
  useAuthStore().email = 'admin@example.com'
  api.get.mockResolvedValue(pageOf([member(), member({ id: 1, email: 'admin@example.com', role: 'ADMIN' })]))
  vi.spyOn(window, 'confirm').mockReturnValue(true)
})

describe('MemberAdminPanel', () => {
  it('lists members with role and status, and locks the admin\'s own row', async () => {
    const wrapper = await mountPanel()
    expect(api.get).toHaveBeenCalledWith('/admin/members', { params: { page: 0, size: 20 } })
    const rows = wrapper.findAll('[data-testid="member-entry"]')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('bob@example.com')
    expect(rows[0].text()).toContain('買家')
    expect(rows[0].text()).toContain('啟用中')
    expect(rows[1].get('select').attributes('disabled')).toBeDefined()
    expect(rows[1].get('[data-testid="member-disable"]').attributes('disabled')).toBeDefined()
  })

  it('sends filters on search', async () => {
    const wrapper = await mountPanel()
    await wrapper.get('#mem-q').setValue('bo')
    await wrapper.get('#mem-role').setValue('BUYER')
    await wrapper.get('form.member-filter').trigger('submit')
    await flushPromises()
    expect(api.get).toHaveBeenLastCalledWith('/admin/members', { params: { page: 0, size: 20, q: 'bo', role: 'BUYER' } })
  })

  it('changes a role after confirmation and reloads', async () => {
    api.patch.mockResolvedValue({ data: { data: member({ role: 'SELLER' }) } })
    const wrapper = await mountPanel()
    await wrapper.get('#mem-role-2').setValue('SELLER')
    await flushPromises()
    expect(api.patch).toHaveBeenCalledWith('/admin/members/2/role', { role: 'SELLER' })
    expect(api.get).toHaveBeenCalledTimes(2)
    expect(toast.items.some((t) => t.type === 'success')).toBe(true)
  })

  it('does nothing when the confirmation is declined', async () => {
    window.confirm.mockReturnValue(false)
    const wrapper = await mountPanel()
    await wrapper.get('#mem-role-2').setValue('SELLER')
    await flushPromises()
    expect(api.patch).not.toHaveBeenCalled()
  })

  it('disables an account and surfaces a server conflict', async () => {
    api.post.mockRejectedValue({ response: { status: 409, data: { message: '不可停用最後一位管理員' } } })
    const wrapper = await mountPanel()
    await wrapper.get('[data-testid="member-disable"]').trigger('click')
    await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/admin/members/2/disable')
    expect(toast.items.some((t) => t.type === 'error')).toBe(true)
  })

  it('enables a disabled account', async () => {
    api.get.mockResolvedValue(pageOf([member({ status: 'DISABLED' })]))
    api.post.mockResolvedValue({ data: { data: member() } })
    const wrapper = await mountPanel()
    expect(wrapper.get('[data-testid="member-entry"]').text()).toContain('已停用')
    await wrapper.get('[data-testid="member-enable"]').trigger('click')
    await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/admin/members/2/enable')
  })

  it('shows empty and error states', async () => {
    api.get.mockResolvedValueOnce(pageOf([]))
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain('沒有符合條件的會員')
    api.get.mockRejectedValueOnce(new Error('boom'))
    await wrapper.get('form.member-filter').trigger('submit')
    await flushPromises()
    expect(toast.items.some((t) => t.type === 'error')).toBe(true)
  })
})

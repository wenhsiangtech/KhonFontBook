import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import HomeView from './HomeView.vue'
import { fontsApi } from '../api'

vi.mock('../api', () => ({ fontsApi: { getFontCatalog: vi.fn() } }))
vi.mock('@halo-dev/ui-shared', () => ({ utils: { permission: { has: () => true } } }))
vi.mock('./SchemePanel.vue', () => ({ default: { template: '<div />' } }))
vi.mock('./RulePanel.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@halo-dev/components', () => {
  const container = { template: '<div><slot /><slot name="header" /></div>' }
  return {
    Dialog: {}, Toast: {},
    VPageHeader: { template: '<header><slot name="actions" /></header>' },
    VButton: { props: ['disabled'], template: '<button :disabled="disabled"><slot /></button>' },
    VAlert: { props: ['title', 'description'], template: '<div role="alert">{{ title }} {{ description }}</div>' },
    VCard: container, VEmpty: container, VEntity: container, VEntityContainer: container,
    VEntityField: container, VLoading: container, VModal: container, VPagination: container,
  }
})

describe('font storage readiness', () => {
  it('blocks import while storage failed and restores it after a successful refresh', async () => {
    const data = { families: [], assets: [], faces: [], attachments: [], total: 0,
      runtime: { available: true }, policyName: '', storageMessage: '存储准备失败，请重试' }
    vi.mocked(fontsApi.getFontCatalog).mockResolvedValueOnce({ data } as never)
      .mockResolvedValueOnce({ data: { ...data, policyName: 'khon-font-book-local', storageMessage: '策略就绪' } } as never)
    const wrapper = mount(HomeView, { global: { stubs: { FormKit: true } } })
    try {
      await flushPromises()
      const importButton = () => wrapper.findAll('button').find(button => button.text() === '导入字体')!
      expect(importButton().element.disabled).toBe(true)
      expect(wrapper.find('[role="alert"]').text()).toContain(data.storageMessage)
      await wrapper.findAll('button').find(button => button.text() === '刷新')!.trigger('click')
      await flushPromises()
      expect(importButton().element.disabled).toBe(false)
      expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    } finally { wrapper.unmount() }
  })

  it('blocks import during refresh and after failure until a fresh status is available', async () => {
    const data = { families: [], assets: [], faces: [], attachments: [], total: 0,
      runtime: { available: true }, policyName: 'khon-font-book-local', storageMessage: '策略就绪' }
    let rejectRefresh!: (reason: Error) => void
    vi.mocked(fontsApi.getFontCatalog).mockResolvedValueOnce({ data } as never)
      .mockImplementationOnce(() => new Promise((_resolve, reject) => { rejectRefresh = reject }))
      .mockResolvedValueOnce({ data } as never)
    const wrapper = mount(HomeView, { global: { stubs: { FormKit: true } } })
    try {
      await flushPromises()
      const importButton = () => wrapper.findAll('button').find(button => button.text() === '导入字体')!
      const refresh = () => wrapper.findAll('button').find(button => button.text() === '刷新')!
      expect(importButton().element.disabled).toBe(false)
      await refresh().trigger('click')
      expect(importButton().element.disabled).toBe(true)
      rejectRefresh(new Error('权限不足，无法读取当前状态'))
      await flushPromises()
      expect(wrapper.find('[role="alert"]').text()).toContain('权限不足')
      expect(importButton().element.disabled).toBe(true)
      await refresh().trigger('click')
      await flushPromises()
      expect(importButton().element.disabled).toBe(false)
    } finally { wrapper.unmount() }
  })
})

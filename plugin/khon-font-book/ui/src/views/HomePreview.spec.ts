import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import HomeView from './HomeView.vue'
import { fontsApi } from '../api'

vi.mock('../api', () => ({ fontsApi: { getFontCatalog: vi.fn(), getFontPreview: vi.fn() } }))
vi.mock('@halo-dev/ui-shared', () => ({ utils: { permission: { has: () => true } } }))
vi.mock('./SchemePanel.vue', () => ({ default: { template: '<div />' } }))
vi.mock('./RulePanel.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@halo-dev/components', () => {
  const container = { template: '<div><slot /><slot name="actions" /><slot name="start" /><slot name="end" /><slot name="footer" /></div>' }
  return {
    Dialog: {}, Toast: {}, VPageHeader: container,
    VButton: { props: ['disabled'], template: '<button :disabled="disabled"><slot /></button>' },
    VAlert: container, VCard: container, VEmpty: container, VEntity: container, VEntityContainer: container,
    VEntityField: container, VLoading: container, VModal: container, VPagination: container,
  }
})

const fontSet = { add: vi.fn(), delete: vi.fn() }
const createFont = vi.fn(function (this: { family: string; load: () => Promise<unknown> }, family: string) {
  this.family = family
  this.load = async () => this
})
const fontsDescriptor = Object.getOwnPropertyDescriptor(document, 'fonts')
const data = {
  families: [], assets: [], attachments: [], total: 1, runtime: { available: true }, policyName: 'font-policy',
  faces: [{ face: { metadata: { name: 'face-test' }, spec: { assetId: 'asset-test', familyId: 'family-test', enabled: true,
    metadata: { family: 'Test', subfamily: 'Regular', weight: 400, style: 'normal', characterCount: 1 } } }, availability: 'READY' }],
}
beforeEach(() => {
  vi.mocked(fontsApi.getFontCatalog).mockResolvedValue({ data } as never)
  vi.mocked(fontsApi.getFontPreview).mockResolvedValue({ data: new Blob(['test-font']) } as never)
  Object.defineProperty(document, 'fonts', { configurable: true, value: fontSet })
  vi.stubGlobal('FontFace', createFont)
  vi.stubGlobal('URL', { createObjectURL: vi.fn(() => 'blob:test-font'), revokeObjectURL: vi.fn() })
})
afterEach(() => {
  vi.clearAllMocks()
  vi.unstubAllGlobals()
  if (fontsDescriptor) Object.defineProperty(document, 'fonts', fontsDescriptor)
  else Reflect.deleteProperty(document, 'fonts')
})

describe('library preview lifecycle', () => {
  for (const action of ['refresh', 'close'] as const) {
    it(`ignores font bytes returned after ${action}`, async () => {
      let resolve!: (value: never) => void
      vi.mocked(fontsApi.getFontPreview).mockImplementationOnce(() => new Promise<never>(yes => { resolve = yes }))
      const wrapper = mount(HomeView, { global: { stubs: { FormKit: true } } })
      try {
        await flushPromises()
        const click = async (text: string) => wrapper.findAll('button').find(b => b.text() === text)!.trigger('click')
        await click('详情与预览')
        await click('加载真实字体预览')
        await click(action === 'refresh' ? '刷新' : '关闭')
        await flushPromises()
        resolve({ data: new Blob(['stale-font']) } as never)
        await flushPromises()
        expect(createFont).not.toHaveBeenCalled()
        expect(fontSet.add).not.toHaveBeenCalled()
      } finally { wrapper.unmount() }
    })
  }

  it('removes a loaded preview when catalog is refreshed', async () => {
    const wrapper = mount(HomeView, { global: { stubs: { FormKit: true } } })
    try {
      await flushPromises()
      const click = async (text: string) => wrapper.findAll('button').find(b => b.text() === text)!.trigger('click')
      await click('详情与预览')
      await click('加载真实字体预览')
      await flushPromises()
      expect(fontSet.add).toHaveBeenCalledTimes(1)
      await click('刷新')
      await flushPromises()
      expect(fontSet.delete).toHaveBeenCalledWith(fontSet.add.mock.calls[0]![0])
      expect(wrapper.text()).toContain('尚未加载预览')
    } finally { wrapper.unmount() }
  })
})

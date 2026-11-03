import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import HomeView from './HomeView.vue'
import SchemePanel from './SchemePanel.vue'
import RulePanel from './RulePanel.vue'
import { fontsApi } from '../api'

vi.mock('../api', () => ({ fontsApi: {
  getFontCatalog: vi.fn(), listFontSchemes: vi.fn(), listFontRules: vi.fn(), getFontRuleOptions: vi.fn(),
} }))
vi.mock('@halo-dev/ui-shared', () => ({ utils: { permission: { has: () => true } } }))
vi.mock('./RuleContentSelect.vue', () => ({ default: { template: '<div />' } }))
vi.mock('./SelectorProbe.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@halo-dev/components', () => {
  const container = { template: '<div><slot /><slot name="header" /><slot name="actions" /><slot name="start" /><slot name="end" /><slot name="footer" /></div>' }
  return {
    Dialog: {}, Toast: {},
    VPageHeader: container,
    VButton: { props: ['disabled', 'loading'], template: '<button :disabled="disabled" :data-loading="!!loading"><slot /></button>' },
    VAlert: { props: ['title', 'description'], template: '<div role="alert">{{ title }} {{ description }}</div>' },
    VEntityField: { props: ['title'], template: '<div>{{ title }}<slot /><slot name="description" /></div>' },
    VCard: container, VEmpty: container, VEntity: container, VEntityContainer: container,
    VLoading: container, VModal: container, VPagination: container,
  }
})

function deferred() {
  let resolve!: (value: never) => void
  let reject!: (reason: Error) => void
  const promise = new Promise<never>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve: (data: unknown) => resolve({ data } as never), reject }
}
const cases = [
  { name: 'library', component: HomeView, api: fontsApi.getFontCatalog, refresh: '刷新', data: (name: string) => ({
    families: [{ metadata: { name: 'family-test' }, spec: { displayName: name, enabled: true } }],
    assets: [], faces: [], attachments: [], total: 0, runtime: { available: true }, policyName: 'font-policy',
  }) },
  { name: 'schemes', component: SchemePanel, api: fontsApi.listFontSchemes, refresh: '刷新方案', data: (name: string) => ({
    items: [{ scheme: { metadata: { name: 'scheme-test' }, spec: { displayName: name, enabled: true, weight: 400, style: 'normal' } }, usable: true }], families: [], total: 1,
  }) },
  { name: 'rules', component: RulePanel, api: fontsApi.listFontRules, refresh: '刷新规则', data: (name: string) => ({
    items: [{ rule: { metadata: { name: 'rule-test' }, spec: { displayName: name, enabled: true, scope: 'THEME_ALL', selector: '.title' } } }], total: 1,
  }) },
]

beforeEach(() => {
  vi.mocked(fontsApi.getFontCatalog).mockResolvedValue({ data: cases[0]!.data('initial') } as never)
  vi.mocked(fontsApi.listFontSchemes).mockResolvedValue({ data: cases[1]!.data('initial') } as never)
  vi.mocked(fontsApi.listFontRules).mockResolvedValue({ data: cases[2]!.data('initial') } as never)
  vi.mocked(fontsApi.getFontRuleOptions).mockResolvedValue({ data: { themes: [], targets: [], contents: [] } } as never)
})
afterEach(() => vi.clearAllMocks())

describe.each(cases)('$name request ordering', ({ component, api, refresh, data }) => {
  for (const outcome of ['success', 'failure'] as const) {
    it(`ignores an obsolete ${outcome} after a newer refresh`, async () => {
      const wrapper = mount(component, { props: component === HomeView ? {} : { resourceRevision: 0 }, global: { stubs: { FormKit: true } } })
      try {
        await flushPromises()
        const old = deferred(), latest = deferred()
        vi.mocked(api).mockImplementationOnce(() => old.promise).mockImplementationOnce(() => latest.promise)
        const button = () => wrapper.findAll('button').find(b => b.text() === refresh)!
        await button().trigger('click')
        await button().trigger('click')
        latest.resolve(data('latest-result'))
        await flushPromises()
        if (outcome === 'success') old.resolve(data('obsolete-result'))
        else old.reject(new Error('obsolete-error'))
        await flushPromises()
        expect(wrapper.text()).toContain('latest-result')
        expect(wrapper.text()).not.toContain('obsolete-result')
        expect(wrapper.text()).not.toContain('obsolete-error')
      } finally { wrapper.unmount() }
    })
  }

  it('keeps loading until the newest request finishes', async () => {
    const wrapper = mount(component, { props: component === HomeView ? {} : { resourceRevision: 0 }, global: { stubs: { FormKit: true } } })
    try {
      await flushPromises()
      const old = deferred(), latest = deferred()
      vi.mocked(api).mockImplementationOnce(() => old.promise).mockImplementationOnce(() => latest.promise)
      const button = () => wrapper.findAll('button').find(b => b.text() === refresh)!
      await button().trigger('click')
      await button().trigger('click')
      old.resolve(data('obsolete-result'))
      await flushPromises()
      expect(button().attributes('data-loading')).toBe('true')
      latest.resolve(data('latest-result'))
      await flushPromises()
      expect(button().attributes('data-loading')).toBe('false')
    } finally { wrapper.unmount() }
  })
})

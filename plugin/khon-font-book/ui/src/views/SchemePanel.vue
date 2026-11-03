<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { utils } from '@halo-dev/ui-shared'
import { Dialog, Toast, VAlert, VButton, VCard, VEmpty, VEntity, VEntityContainer, VEntityField, VModal, VPagination } from '@halo-dev/components'
import { isAxiosError } from 'axios'
import { fontsApi } from '../api'
import type { FontScheme, SchemeCatalog, SchemeUpdate, Variant } from '../api/generated'

const props = defineProps<{ resourceRevision: number }>()

const catalog = ref<SchemeCatalog>()
const selected = ref<FontScheme>()
const visible = ref(false)
const busy = ref(false)
const loading = ref(false)
const error = ref('')
const formError = ref('')
const page = ref(1)
const size = ref(10)
const keyword = ref('')
const sample = ref('中文，。、《》与 English Aa 012345, . “引号” —\n缺字回退：かな 한글 😀')
const status = ref('预览基于已保存方案；更改后请先保存。')
const diagnostics = ref<string[]>([])
const loaded = ref<string[]>([])
const previewFamily = ref('sans-serif')
const previewWeight = ref(400)
const previewStyle = ref('normal')
let generation = 0
let loadGeneration = 0
let previewFonts: FontFace[] = []
const canManage = computed(() => utils.permission.has(['khon-font-book:manage']))
const genericOptions = ['sans-serif', 'serif', 'monospace', 'system-ui'].map(value => ({ value, label: value }))
const fallbackFamily = (values: string[]) => values.map((f, i, a) => i === a.length - 1 ? f : JSON.stringify(f)).join(', ')
type Editor = Pick<SchemeUpdate, 'displayName' | 'enabled' | 'latinFamilyId' | 'cjkFamilyId'> & { variant: string; fallbackText: string; generic: string }
const form = ref<Editor>({ displayName: '', enabled: true, latinFamilyId: '', cjkFamilyId: '', variant: '400:normal', fallbackText: 'Arial\nMicrosoft YaHei', generic: 'sans-serif' })
const familyOptions = computed(() => {
  const options = (catalog.value?.families || []).map(f => ({ value: f.family?.metadata.name || '', label: `${f.family?.spec.displayName}${f.family?.spec.enabled ? '' : '（停用）'}` }))
  for (const id of [form.value.latinFamilyId, form.value.cjkFamilyId]) {
    if (id && !options.some(o => o.value === id)) options.push({ value: id, label: `缺失字体族：${id}` })
  }
  return options
})
const variantKey = (v: Variant) => `${v.weight}:${v.style}`
const variants = computed(() => {
  const latin = catalog.value?.families?.find(f => f.family?.metadata.name === form.value.latinFamilyId)?.latinVariants || []
  const cjk = catalog.value?.families?.find(f => f.family?.metadata.name === form.value.cjkFamilyId)?.cjkVariants || []
  return latin.filter(l => cjk.some(c => variantKey(c) === variantKey(l)))
})
const variantOptions = computed(() => {
  const options = variants.value.map(v => ({ value: variantKey(v), label: `${v.weight} / ${v.style}（两侧真实提供）` }))
  if (!options.some(o => o.value === form.value.variant)) options.push({ value: form.value.variant, label: `${form.value.variant}（当前不可用，仅可停用保存）` })
  return options
})
watch(variants, values => {
  const s = selected.value?.spec
  const changed = !s || s.latinFamilyId !== form.value.latinFamilyId || s.cjkFamilyId !== form.value.cjkFamilyId
  if (changed && values.length && !values.some(v => variantKey(v) === form.value.variant)) form.value.variant = variantKey(values[0]!)
})

async function reason(ex: unknown) {
  if (isAxiosError(ex)) {
    let data = ex.response?.data
    if (data instanceof Blob) { try { data = JSON.parse(await data.text()) } catch { data = undefined } }
    return data?.detail || data?.title || ex.message
  }
  return ex instanceof Error ? ex.message : '操作失败，请刷新后重试'
}
function clearPreview() {
  generation++
  previewFonts.forEach(f => document.fonts.delete(f))
  previewFonts = []
  previewFamily.value = 'sans-serif'
  diagnostics.value = []
  loaded.value = []
  status.value = '预览基于已保存方案；更改后请先保存。'
}
async function load() {
  const run = ++loadGeneration
  clearPreview()
  loading.value = true
  error.value = ''
  try {
    const { data } = await fontsApi.listFontSchemes({ page: page.value, size: size.value, keyword: keyword.value })
    if (run === loadGeneration) catalog.value = data
  }
  catch (ex) { const message = await reason(ex); if (run === loadGeneration) error.value = message }
  finally { if (run === loadGeneration) loading.value = false }
}
function open(value?: FontScheme) {
  clearPreview()
  selected.value = value
  const s = value?.spec
  form.value = { displayName: s?.displayName || '', enabled: s?.enabled ?? true, latinFamilyId: s?.latinFamilyId || '', cjkFamilyId: s?.cjkFamilyId || '', variant: `${s?.weight || 400}:${s?.style || 'normal'}`, fallbackText: s?.fallbacks?.slice(0, -1).join('\n') ?? 'Arial\nMicrosoft YaHei', generic: s?.fallbacks?.at(-1) || 'sans-serif' }
  formError.value = ''
  visible.value = true
}
async function save() {
  busy.value = true
  formError.value = ''
  clearPreview()
  try {
    const [weight, style] = form.value.variant.split(':')
    const request: SchemeUpdate = { version: selected.value?.metadata.version ?? undefined, displayName: form.value.displayName, enabled: !!form.value.enabled, latinFamilyId: form.value.latinFamilyId, cjkFamilyId: form.value.cjkFamilyId, rangePolicy: 'latin-cjk-v1', weight: Number(weight), style, fallbacks: [...form.value.fallbackText.split(/\r?\n/).map(s => s.trim()).filter(Boolean), form.value.generic] }
    selected.value = (selected.value
      ? await fontsApi.updateFontScheme({ id: selected.value.metadata.name, schemeUpdate: request })
      : await fontsApi.createFontScheme({ schemeUpdate: request })).data
    Toast.success('字体方案已保存')
    await load()
  } catch (ex) { formError.value = await reason(ex) }
  finally { busy.value = false }
}
async function preview() {
  if (!selected.value) return
  clearPreview()
  const run = generation
  status.value = '正在读取已保存方案和实际字体…'
  try {
    const plan = (await fontsApi.getFontSchemePreview({ id: selected.value.metadata.name })).data
    if (run !== generation) return
    diagnostics.value = plan.diagnostics || []
    const fallbacks = fallbackFamily(plan.fallbacks || ['sans-serif'])
    previewFamily.value = fallbacks
    previewWeight.value = plan.weight || 400
    previewStyle.value = plan.style || 'normal'
    const points = Array.from(sample.value, c => c.codePointAt(0)!)
    const faces = (plan.faces || []).filter(f => points.some(p => f.unicodeRanges?.some(r => p >= (r.start ?? -1) && p <= (r.end ?? -1))))
    const family = `kfb-scheme-preview-${plan.schemeId}-${run}`
    const results = await Promise.all(faces.map(async binding => {
      try {
        const { data } = await fontsApi.getFontSchemePreviewFont({ id: binding.faceId!, schemeId: plan.schemeId!, revision: plan.revision! }, { responseType: 'blob' })
        const url = URL.createObjectURL(data)
        try {
          const unicodeRange = binding.unicodeRanges!.map(r => `U+${r.start!.toString(16)}-${r.end!.toString(16)}`).join(',')
          const font = new FontFace(family, `url(${url})`, { weight: String(binding.weight), style: binding.style, unicodeRange, display: 'swap' })
          await font.load()
          const assigned = [...new Set(points.filter(p => binding.unicodeRanges!.some(r => p >= r.start! && p <= r.end!)))].map(p => String.fromCodePoint(p)).join('')
          return { font, message: `${binding.role} 已加载；${binding.weight}/${binding.style}；样文分配：${assigned}` }
        } finally { URL.revokeObjectURL(url) }
      } catch (ex) { return { font: undefined, message: `${binding.role} 加载失败，使用回退链：${await reason(ex)}` } }
    }))
    if (run !== generation) return
    const fresh = (await fontsApi.getFontSchemePreview({ id: plan.schemeId! })).data
    if (run !== generation) return
    if (fresh.revision !== plan.revision) throw new Error('方案或字体已变化，请重新加载预览')
    results.forEach(result => { if (result.font) { document.fonts.add(result.font); previewFonts.push(result.font) } })
    loaded.value = results.map(result => result.message)
    diagnostics.value.push(...results.filter(result => !result.font).map(result => result.message))
    previewFamily.value = previewFonts.length ? `${family}, ${fallbacks}` : fallbacks
    status.value = !plan.enabled ? '方案已停用，全部使用回退链。' : `预览 ${plan.weight}/${plan.style}；已加载 ${previewFonts.length}/${faces.length} 个样文所需字形；未覆盖或加载失败的字符使用回退链。`
  } catch (ex) { const message = await reason(ex); if (run === generation) { clearPreview(); previewFamily.value = fallbackFamily(selected.value?.spec.fallbacks || ['sans-serif']); status.value = `预览未完成，使用已保存回退链：${message}` } }
}
async function remove(value: FontScheme) {
  try {
    const refs = (await fontsApi.getFontReferences({ kind: 'scheme', id: value.metadata.name })).data
    if (refs.items?.length) { Dialog.info({ title: '方案仍被引用', description: refs.items.map(r => r.displayName || r.id).join('；') }); return }
    Dialog.warning({ title: '删除字体方案？', description: '只删除方案并解除其内部引用，字体与 Halo 附件保留。', confirmText: '删除方案', onConfirm: async () => {
      busy.value = true
      try { await fontsApi.deleteFontScheme({ id: value.metadata.name, version: value.metadata.version ?? 0 }); visible.value = false; await load(); Toast.success('方案已删除，引用已解除') }
      catch (ex) { formError.value = await reason(ex) }
      finally { busy.value = false }
    } })
  } catch (ex) { formError.value = await reason(ex) }
}
watch(visible, value => { if (!value) clearPreview() })
watch(sample, clearPreview)
watch(() => props.resourceRevision, load)
onMounted(load)
onBeforeUnmount(() => { loadGeneration++; clearPreview() })
</script>

<template>
  <VCard title="字体方案">
    <template #actions><div class="kfb-scheme-toolbar"><VButton size="sm" :loading="loading" @click="load">刷新方案</VButton><VButton v-if="canManage" size="sm" type="secondary" @click="open()">创建方案</VButton></div></template>
    <VAlert v-if="error" type="error" title="方案加载失败" :description="error" />
    <div class="kfb-scheme-intro"><span class="kfb-scheme-mark" aria-hidden="true">Aa / 字</span><div><strong>让中英文字体各司其职</strong><p class="kfb-scheme-hint">选择两侧共有的真实字重与样式，按字符覆盖分配中英与标点。</p></div></div>
    <form class="kfb-scheme-search" @submit.prevent="page = 1; load()"><FormKit v-model="keyword" type="search" label="搜索方案" placeholder="输入方案名称" /><VButton @click="page = 1; load()">搜索方案</VButton></form>
    <VEmpty v-if="!catalog?.items?.length" title="尚无匹配的字体方案" message="先导入中英字体，再选择两侧共有的真实字重与样式。" />
    <div v-else class="kfb-scheme-list" tabindex="0" aria-label="方案列表，可横向滚动"><VEntityContainer><VEntity v-for="item in catalog.items" :key="item.scheme!.metadata.name">
      <template #start><VEntityField :title="item.scheme!.spec.displayName" :description="`${item.scheme!.spec.weight} / ${item.scheme!.spec.style} · ${item.scheme!.spec.enabled ? item.usable ? '已启用' : '资源不完整，预览会回退' : '已停用'}`" /></template>
      <template #end><VButton size="sm" @click="open(item.scheme)">方案详情与预览</VButton></template>
    </VEntity></VEntityContainer></div>
    <template v-if="catalog?.total" #footer><VPagination v-model:page="page" v-model:size="size" :size-options="[10, 20, 50]" :total="catalog.total" @change="load" /></template>
  </VCard>
  <VModal v-model:visible="visible" :title="selected ? '字体方案详情与预览' : '创建字体方案'" :width="800" :layer-closable="!busy">
    <div class="kfb-scheme-dialog">
      <VAlert v-if="formError" type="error" title="方案操作未完成" :description="formError" />
      <p v-if="selected" class="kfb-scheme-hint">稳定标识：{{ selected.metadata.name }}</p>
      <p v-if="selected" class="kfb-scheme-hint">已保存：{{ selected.spec.weight }}/{{ selected.spec.style }}；回退链：{{ selected.spec.fallbacks?.join(' → ') }}</p>
      <details class="kfb-scheme-guide"><summary>字符分配与主题应用说明</summary><p class="kfb-scheme-hint">latin-cjk-v1：英文、数字、半角标点、弯引号和破折号归 Latin；汉字、全角标点、顿号与书名号归 CJK；其他及缺失字符回退。方案停用仍保留引用。此处只在预览区域加载；主题应用请配置规则并在插件设置中开启注入。</p></details>
      <details v-if="canManage" :open="!selected"><summary>编辑方案</summary>
      <FormKit v-if="visible && canManage" v-model="form" type="form" :actions="false" :disabled="busy" @submit="save">
        <FormKit type="text" name="displayName" label="方案名称" validation="required|length:1,160" />
        <FormKit type="select" name="latinFamilyId" label="Latin 字体族" placeholder="请选择" :options="familyOptions" validation="required" />
        <FormKit type="select" name="cjkFamilyId" label="CJK 字体族" placeholder="请选择" :options="familyOptions" validation="required" />
        <FormKit type="select" name="variant" label="真实字重与样式" :options="variantOptions" validation="required" />
        <p class="kfb-scheme-hint">共有可用组合：{{ variants.map(v => `${v.weight}/${v.style}`).join('、') || '暂无；启用保存会被拒绝，可停用保存待配置' }}。不合成缺失字重或斜体。</p>
        <FormKit type="textarea" name="fallbackText" label="回退字体名称（每行一个，可留空）" help="仅字体名称，不接受 CSS；本机未安装时依次尝试下一项。" />
        <FormKit type="select" name="generic" label="最后的通用回退" :options="genericOptions" />
        <FormKit type="checkbox" name="enabled" label="启用方案" />
        <FormKit type="submit" label="保存方案" :disabled="busy" />
      </FormKit>
      </details>
      <div v-if="selected">
        <div class="kfb-scheme-toolbar"><VButton :disabled="busy" @click="preview">加载已保存方案预览</VButton><VButton v-if="canManage" :disabled="busy" @click="remove(selected)">删除方案</VButton></div>
        <FormKit v-model="sample" type="textarea" label="中英混排样文" />
        <p class="kfb-scheme-sample" :style="{ fontFamily: previewFamily, fontWeight: previewWeight, fontStyle: previewStyle }">{{ sample }}</p>
        <p role="status" class="kfb-scheme-hint">{{ status }}</p>
        <p v-for="line in diagnostics" :key="line" class="kfb-scheme-hint">{{ line }}</p>
        <details v-if="loaded.length"><summary>查看字形加载与样文分配</summary><p v-for="line in loaded" :key="line" class="kfb-scheme-hint">{{ line }}</p></details>
        <p class="kfb-scheme-hint">只加载样文需要的字形；更换样文后重新加载。字体或方案在其他窗口变化后请刷新方案。字符覆盖不保证所有语言的排版效果。</p>
      </div>
    </div>
  </VModal>
</template>

<style scoped>
.kfb-scheme-toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: .75rem; justify-content: space-between; }
.kfb-scheme-list { max-width: 100%; overflow-x: auto; }
.kfb-scheme-list:focus-visible { outline: 2px solid #4f46e5; outline-offset: 3px; }
.kfb-scheme-intro { display: flex; align-items: center; gap: 1rem; padding: 1rem; background: #f8fafc; border-radius: .5rem; margin-bottom: 1rem; }
.kfb-scheme-mark { flex: 0 0 auto; font: 1.5rem Georgia, serif; color: #36594d; }
.kfb-scheme-intro strong { color: #334155; font-size: .9rem; }
.kfb-scheme-search { display: flex; gap: .5rem; align-items: flex-end; flex-wrap: wrap; margin-bottom: 1rem; }
.kfb-scheme-search :deep(.formkit-outer) { margin: 0; padding: 0; min-width: 0; flex: 1 1 14rem; max-width: 24rem; }
.kfb-scheme-dialog { display: grid; grid-template-columns: minmax(0, 1fr); gap: 1rem; }
.kfb-scheme-dialog summary { cursor: pointer; color: #475569; font-size: .875rem; padding-bottom: .75rem; }
.kfb-scheme-guide { border: 1px solid #e2e8f0; border-radius: .5rem; padding: .75rem 1rem 0; }
.kfb-scheme-dialog :deep(.formkit-outer) { max-width: 100%; }
.kfb-scheme-hint { color: #64748b; font-size: .875rem; line-height: 1.6; overflow-wrap: anywhere; }
.kfb-scheme-sample { min-height: 8rem; padding: 1.5rem; margin: 1rem 0; background: #fafbf9; color: #172033; border: 1px solid #e1e7df; border-radius: .5rem; font-size: 1.75rem; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; font-synthesis: none; }
@media (max-width: 480px) { .kfb-scheme-mark { display: none; } .kfb-scheme-sample { padding: 1rem; font-size: 1.5rem; } }
</style>

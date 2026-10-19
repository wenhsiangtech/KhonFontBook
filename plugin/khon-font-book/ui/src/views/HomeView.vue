<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { utils } from '@halo-dev/ui-shared'
import { Dialog, Toast, VAlert, VButton, VCard, VEmpty, VEntity, VEntityContainer, VEntityField, VLoading, VModal, VPageHeader, VPagination } from '@halo-dev/components'
import { isAxiosError } from 'axios'
import { fontsApi } from '../api'
import SchemePanel from './SchemePanel.vue'
import RulePanel from './RulePanel.vue'
import type { Catalog, ExistingImport, FaceView, FamilyUpdate, FontAsset, FontFace as FontFaceResource, FontFamily } from '../api/generated'

const catalog = ref<Catalog>()
const coverUrl = '/plugins/khon-font-book/assets/cover-9dfb46d3.png'
const resourceRevision = ref(0)
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const formError = ref('')
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const section = ref('library')
const sections = [
  { id: 'library', label: '字体库' },
  { id: 'schemes', label: '配对方案' },
  { id: 'rules', label: '主题规则' },
  { id: 'assets', label: '文件记录' },
]
const importVisible = ref(false)
const importMode = ref('upload')
const familyVisible = ref(false)
const editingFamily = ref<FontFamily>()
const familyForm = ref<FamilyUpdate>({ displayName: '', enabled: true })
const selected = ref<FontFaceResource>()
const detailVisible = ref(false)
const details = ref({ familyId: '', enabled: true, source: '', license: '', category: '' })
const sample = ref('KhonFontBook — Aa Bb 012345\n中英混排与缺字回退预览。')
const previewStatus = ref('尚未加载预览')
const previewFamily = ref('sans-serif')
let loadedFont: FontFace | undefined
let previewGeneration = 0
const canManage = computed(() => utils.permission.has(['khon-font-book:manage']))
const rows = computed(() => (catalog.value?.faces ?? []).flatMap(v => v.face ? [{ ...v, face: v.face }] : []))
const familyOptions = computed(() => (catalog.value?.families ?? []).map(f => ({ value: f.metadata.name, label: f.spec.displayName + (f.spec.enabled ? '' : '（已停用）') })))
const attachmentOptions = computed(() => (catalog.value?.attachments ?? []).map(a => ({ value: a.name, label: `${a.displayName} · ${a.size} 字节` })))
const importFamilyOptions = computed(() => [{ value: '', label: '根据真实元数据创建新字体族' }, ...familyOptions.value])
const asset = computed(() => catalog.value?.assets?.find(a => a.metadata.name === selected.value?.spec.assetId))

function message(ex: unknown) {
  if (isAxiosError(ex)) return ex.response?.data?.detail || ex.response?.data?.title || ex.message
  return ex instanceof Error ? ex.message : '操作失败，请刷新后重试'
}
function clearPreview() {
  previewGeneration++
  if (loadedFont) document.fonts.delete(loadedFont)
  loadedFont = undefined
  previewFamily.value = 'sans-serif'
  previewStatus.value = '尚未加载预览'
}
async function load() {
  loading.value = true
  error.value = ''
  try {
    catalog.value = (await fontsApi.getFontCatalog({ page: page.value, size: size.value, keyword: keyword.value })).data
    resourceRevision.value++
    if (selected.value) selected.value = catalog.value.faces?.find(v => v.face?.metadata.name === selected.value?.metadata.name)?.face
  } catch (ex) { error.value = message(ex) }
  finally { loading.value = false }
}
function familyName(id?: string) { return catalog.value?.families?.find(f => f.metadata.name === id)?.spec.displayName || id || '未关联' }
function fileSize(bytes = 0) { return bytes < 1024 * 1024 ? `${(bytes / 1024).toFixed(1)} KiB` : `${(bytes / 1024 / 1024).toFixed(1)} MiB` }
function startImport() { importMode.value = 'upload'; formError.value = ''; importVisible.value = true }
type ImportForm = ExistingImport & { upload?: { file: File }[] }
async function importFont(data: ImportForm) {
  busy.value = true
  formError.value = ''
  try {
    const result = importMode.value === 'upload'
      ? await fontsApi.importFontFile({ file: data.upload?.[0]?.file as File, source: data.source || '', license: data.license || '', category: data.category, familyId: data.familyId })
      : await fontsApi.importFontAttachment({ existingImport: data })
    Toast.success(result.data.message || '字体已导入')
    importVisible.value = false
    page.value = 1
    keyword.value = ''
    await load()
    if (result.data.face) openFace(result.data.face)
  } catch (ex) { formError.value = message(ex); await load() }
  finally { busy.value = false }
}
function openFace(face: FontFaceResource) {
  clearPreview()
  selected.value = face
  const a = catalog.value?.assets?.find(item => item.metadata.name === face.spec.assetId)
  details.value = { familyId: face.spec.familyId || '', enabled: face.spec.enabled ?? false, source: a?.spec.source || '', license: a?.spec.license || '', category: a?.spec.category || '' }
  formError.value = ''
  detailVisible.value = true
}
async function preview() {
  if (!selected.value) return
  clearPreview()
  const generation = previewGeneration
  previewStatus.value = '正在读取实际文件并校验摘要…'
  try {
    const { data } = await fontsApi.getFontPreview({ id: selected.value.metadata.name }, { responseType: 'blob' })
    const url = URL.createObjectURL(data)
    try {
      const m = selected.value.spec.metadata
      const font = new FontFace(`kfb-preview-${selected.value.metadata.name}-${generation}`, `url(${url})`, { weight: String(m?.weight || 400), style: m?.style || 'normal' })
      await font.load()
      if (generation !== previewGeneration) return
      document.fonts.add(font)
      loadedFont = font
      previewFamily.value = `${font.family}, sans-serif`
      previewStatus.value = '实际字体已加载；缺失字符使用系统字体回退'
    } finally { URL.revokeObjectURL(url) }
  } catch (ex) {
    let reason = message(ex)
    if (isAxiosError(ex) && ex.response?.data instanceof Blob) {
      try {
        const problem = JSON.parse(await ex.response.data.text())
        reason = problem.detail || problem.title || reason
      } catch { /* Keep the network error when the response is not ProblemDetail. */ }
    }
    if (generation === previewGeneration) previewStatus.value = `加载失败，已回退系统字体：${reason}`
  }
}
async function saveDetails() {
  const f = selected.value
  const a = asset.value
  if (!f || !a) return
  busy.value = true
  formError.value = ''
  let savedAsset = false
  try {
    await fontsApi.updateFontAsset({ id: a.metadata.name, assetUpdate: { version: a.metadata.version ?? undefined, source: details.value.source, license: details.value.license, category: details.value.category } })
    savedAsset = true
    await fontsApi.updateFontFace({ id: f.metadata.name, faceUpdate: { version: f.metadata.version ?? undefined, familyId: details.value.familyId, enabled: details.value.enabled } })
    Toast.success('字形与来源信息已保存')
    clearPreview()
    detailVisible.value = false
    await load()
  } catch (ex) { formError.value = `${savedAsset ? '来源信息已保存，但字形更新失败。' : ''}${message(ex)}`; await load() }
  finally { busy.value = false }
}
async function recheck() {
  if (!selected.value) return
  busy.value = true
  formError.value = ''
  clearPreview()
  try { await fontsApi.recheckFontFace({ id: selected.value.metadata.name }); Toast.success('实际文件重新校验通过'); await load() }
  catch (ex) { formError.value = message(ex) }
  finally { busy.value = false }
}
function openFamily(family?: FontFamily) {
  editingFamily.value = family
  familyForm.value = { displayName: family?.spec.displayName || '', enabled: family?.spec.enabled ?? true, version: family?.metadata.version ?? undefined }
  formError.value = ''
  familyVisible.value = true
}
async function saveFamily(data: FamilyUpdate) {
  busy.value = true
  formError.value = ''
  try {
    if (editingFamily.value) await fontsApi.updateFontFamily({ id: editingFamily.value.metadata.name, familyUpdate: { ...data, version: editingFamily.value.metadata.version ?? undefined } })
    else await fontsApi.createFontFamily({ familyUpdate: data })
    familyVisible.value = false
    Toast.success('字体族已保存')
    await load()
  } catch (ex) { formError.value = message(ex) }
  finally { busy.value = false }
}
async function remove(kind: 'family' | 'face' | 'asset', resource: FontFamily | FontFaceResource | FontAsset) {
  const refs = await fontsApi.getFontReferences({ kind, id: resource.metadata.name }).catch(ex => { Toast.error(message(ex)); return undefined })
  if (!refs) return
  if (refs.data.items?.length) { Dialog.info({ title: '资源仍被引用', description: refs.data.items.map(r => `${r.kind}：${r.displayName || r.id}`).join('；') }); return }
  Dialog.warning({ title: '删除资源记录？', description: `Halo 附件将保留。${refs.data.boundary || ''}`, confirmText: '删除记录', onConfirm: async () => {
    busy.value = true
    try {
      const args = { id: resource.metadata.name, version: resource.metadata.version ?? 0 }
      if (kind === 'family') await fontsApi.deleteFontFamily(args)
      if (kind === 'face') await fontsApi.deleteFontFace(args)
      if (kind === 'asset') await fontsApi.deleteFontAsset(args)
      if (kind === 'face') { detailVisible.value = false; clearPreview() }
      Toast.success('记录已删除，附件保留')
      await load()
    } catch (ex) { Toast.error(message(ex)) }
    finally { busy.value = false }
  } })
}
function closeDetails() { clearPreview(); detailVisible.value = false }
function availability(v: FaceView) { return v.availability === 'READY' ? '附件记录存在' : v.availability === 'URL_CHANGED' ? '地址已变更' : v.availability === 'INCOMPLETE' ? '导入未完成' : '附件不可用' }
onMounted(load)
onBeforeUnmount(clearPreview)
</script>

<template>
  <VPageHeader title="KhonFontBook"><template #actions>
    <VButton :loading="loading" @click="load">刷新</VButton>
    <VButton v-if="canManage" type="secondary" :disabled="busy || !catalog?.runtime?.available" @click="startImport">导入字体</VButton>
  </template></VPageHeader>
  <div class="kfb-page">
    <VAlert v-if="error" type="error" title="无法加载字体资源" :description="error" />
    <VAlert v-if="catalog && !catalog.runtime?.available" type="warning" title="字体解析环境不可用" :description="catalog.runtime?.message" />
    <header class="kfb-overview">
      <div class="kfb-overview-title"><img class="kfb-cover" :src="coverUrl" alt="KhonFontBook 封面" width="64" height="64" /><div><p class="kfb-eyebrow">KHON FONT BOOK</p><h2>字体工作台</h2><p class="kfb-hint">收藏字体，搭配中英，为主题中的文字选择合适的表达。</p></div></div>
      <div class="kfb-overview-meta"><span v-if="catalog" class="kfb-badge" :class="{ 'kfb-badge-ready': catalog.runtime?.available }">{{ catalog.runtime?.available ? '解析环境就绪' : '解析环境待检查' }}</span><span class="kfb-hint">WOFF2 / TTF · 单文件上限 20 MiB</span></div>
    </header>
    <nav class="kfb-navigation" aria-label="字体工作区">
      <VButton v-for="item in sections" :key="item.id" :type="section === item.id ? 'secondary' : 'default'" :aria-pressed="section === item.id" :aria-controls="`kfb-section-${item.id}`" @click="section = item.id">{{ item.label }}</VButton>
    </nav>
    <section id="kfb-section-schemes" v-show="section === 'schemes'" class="kfb-section" aria-label="配对方案"><SchemePanel :resource-revision="resourceRevision" /></section>
    <section id="kfb-section-rules" v-show="section === 'rules'" class="kfb-section" aria-label="主题规则"><RulePanel :resource-revision="resourceRevision" /></section>
    <section id="kfb-section-library" v-show="section === 'library'" class="kfb-section" aria-label="字体库">
    <div class="kfb-section-heading"><div><h3>我的字体库</h3><p class="kfb-hint">查看字形信息，按需加载真实字体预览。</p></div><span v-if="catalog" class="kfb-count">{{ catalog.families?.length || 0 }} 个字体族</span></div>
    <VCard title="字形">
      <template #header><div class="kfb-card-heading"><strong>字形<span v-if="catalog" class="kfb-count">{{ catalog.total || 0 }} 条查询结果</span></strong><form class="kfb-search" @submit.prevent="page = 1; load()"><FormKit v-model="keyword" type="search" label="搜索字体族" placeholder="输入字体族名称" /><VButton @click="page = 1; load()">搜索</VButton></form></div></template>
      <VLoading v-if="loading && !catalog" />
      <VEmpty v-else-if="!rows.length" title="没有匹配的字形" message="导入真实 WOFF2 / TTF，或调整搜索条件。" />
      <div v-else class="kfb-list" tabindex="0" aria-label="字形列表，可横向滚动"><VEntityContainer><VEntity v-for="row in rows" :key="row.face.metadata.name">
        <template #start><VEntityField :title="familyName(row.face.spec.familyId)" :description="`${row.face.spec.metadata?.weight} · ${row.face.spec.metadata?.style} · ${row.face.spec.metadata?.characterCount} 个字符`" @click="openFace(row.face)" /></template>
        <template #end><span class="kfb-badge" :class="{ 'kfb-badge-ready': row.face.spec.enabled }">{{ row.face.spec.enabled ? '已启用' : '已停用' }}</span><VEntityField :description="availability(row)" /><VButton size="sm" @click="openFace(row.face)">详情与预览</VButton></template>
      </VEntity></VEntityContainer></div>
      <template v-if="catalog?.total" #footer><VPagination v-model:page="page" v-model:size="size" :size-options="[10, 20, 50]" :total="catalog.total" @change="load" /></template>
    </VCard>
    <VCard title="字体族">
      <template #actions><VButton v-if="canManage" size="sm" @click="openFamily()">创建字体族</VButton></template>
      <VEmpty v-if="!catalog?.families?.length" title="尚无字体族" message="导入时可按真实名称创建，或先创建用于归组的字体族。" />
      <div v-else class="kfb-list" tabindex="0" aria-label="字体族列表，可横向滚动"><VEntityContainer><VEntity v-for="family in catalog.families" :key="family.metadata.name">
        <template #start><VEntityField :title="family.spec.displayName" :description="family.spec.enabled ? '已启用' : '已停用；其下字形不应对外应用'" /></template>
        <template v-if="canManage" #end><VButton size="sm" @click="openFamily(family)">编辑与启停</VButton><VButton size="sm" :disabled="busy" @click="remove('family', family)">删除</VButton></template>
      </VEntity></VEntityContainer></div>
    </VCard>
    </section>
    <section id="kfb-section-assets" v-show="section === 'assets'" class="kfb-section" aria-label="文件记录">
    <VCard title="文件资源记录">
      <template #actions><span class="kfb-count">{{ catalog?.assets?.length || 0 }} 个文件</span></template>
      <p class="kfb-hint">删除前检查内部及已登记外部引用；删除记录保留 Halo 附件。外部引用由登记来源撤销或确认迁移，未登记的使用无法检测。</p>
      <div v-if="catalog?.assets?.length" class="kfb-list" tabindex="0" aria-label="文件资源列表，可横向滚动"><VEntityContainer><VEntity v-for="item in catalog.assets" :key="item.metadata.name">
        <template #start><VEntityField :title="item.spec.category || '未分类'" :description="`${fileSize(item.spec.size)} · ${item.spec.source} · ${item.status?.state}`" /></template>
        <template v-if="canManage" #end><VButton size="sm" :disabled="busy" @click="remove('asset', item)">检查引用并删除</VButton></template>
      </VEntity></VEntityContainer></div>
      <VEmpty v-else title="尚无文件资源" />
    </VCard>
    </section>
    <details class="kfb-environment"><summary>存储与公开接入说明</summary><div><p v-if="catalog" class="kfb-hint">{{ catalog.policyName }} · {{ catalog.storageMessage }}。支持静态、单色 WOFF2 / TTF；可变字体、颜色字体和字体集合会被拒绝。</p><p class="kfb-hint">启用且可用的字体、方案及来源/授权说明可通过公开 API 读取，请勿填写秘密。主题注入开关不关闭显式公开接入。</p></div></details>
  </div>
  <VModal v-model:visible="importVisible" title="导入 WOFF2 / TTF" :width="650" :layer-closable="!busy">
    <div class="kfb-dialog">
    <p class="kfb-hint">导入后识别真实字体名称、字重与样式，再选择如何使用。</p>
    <VAlert v-if="formError" type="error" title="导入未完成" :description="formError" />
    <FormKit v-model="importMode" type="radio" label="导入方式" :options="[{ value: 'upload', label: '上传文件' }, { value: 'attachment', label: '选择已有附件' }]" :disabled="busy" />
    <FormKit type="form" :actions="false" :disabled="busy" submit-label="校验并导入" @submit="importFont">
      <FormKit v-if="importMode === 'upload'" type="file" name="upload" label="WOFF2 / TTF 文件" accept=".woff2,.ttf" validation="required" />
      <FormKit v-else type="select" name="attachmentName" label="专用策略中的已有字体附件" placeholder="请选择附件" :options="attachmentOptions" validation="required" />
      <FormKit type="select" name="familyId" label="归属字体族" :options="importFamilyOptions" value="" />
      <FormKit type="text" name="source" label="字体来源" validation="required|length:1,1000" help="填写实际来源地址或说明。" />
      <FormKit type="textarea" name="license" label="授权与使用范围说明" validation="required|length:1,2000" help="请确认允许上传、公开托管和网站使用；元数据不代替授权证明。" />
      <FormKit type="text" name="category" label="分类" validation="length:0,80" />
      <FormKit type="submit" label="校验并导入" :disabled="busy" />
    </FormKit>
    </div>
  </VModal>
  <VModal v-if="selected && detailVisible" title="字形详情与真实预览" :width="760" :layer-closable="!busy" @close="closeDetails">
    <div class="kfb-dialog">
    <div class="kfb-font-heading"><h3>{{ selected.spec.metadata?.family }}</h3><p class="kfb-hint">{{ selected.spec.metadata?.subfamily }} · 字重 {{ selected.spec.metadata?.weight }} · {{ selected.spec.metadata?.style }}</p><span class="kfb-count">{{ selected.spec.metadata?.characterCount }} 个字符 · 静态字形</span></div>
    <details class="kfb-environment"><summary>查看稳定标识</summary><p class="kfb-id">{{ selected.metadata.name }}</p></details>
    <VAlert v-if="formError" type="error" title="操作未完成" :description="formError" />
    <div class="kfb-toolbar"><VButton :disabled="busy" @click="preview">加载真实字体预览</VButton><VButton v-if="canManage" :disabled="busy" @click="recheck">重新校验附件</VButton></div>
    <FormKit v-model="sample" type="textarea" label="预览样文" />
    <p class="kfb-sample" :style="{ fontFamily: previewFamily, fontWeight: selected.spec.metadata?.weight, fontStyle: selected.spec.metadata?.style }">{{ sample }}</p>
    <p role="status" class="kfb-hint">{{ previewStatus }}。只影响此预览区域；停用字体也可手动预览。</p>
    <FormKit v-if="canManage" v-model="details" type="form" :actions="false" :disabled="busy" submit-label="保存字形与来源信息" @submit="saveDetails">
      <FormKit type="select" name="familyId" label="归属字体族" :options="familyOptions" validation="required" />
      <FormKit type="checkbox" name="enabled" label="启用此字形" />
      <FormKit type="text" name="source" label="字体来源" validation="required|length:1,1000" />
      <FormKit type="textarea" name="license" label="授权说明" validation="required|length:1,2000" />
      <FormKit type="text" name="category" label="分类" validation="length:0,80" />
      <FormKit type="submit" label="保存字形与来源信息" :disabled="busy" />
    </FormKit>
    <div v-else class="kfb-hint"><p>来源：{{ asset?.spec.source }}</p><p>授权：{{ asset?.spec.license }}</p><p>分类：{{ asset?.spec.category || '未分类' }}</p></div>
    </div>
    <template #footer><div class="kfb-toolbar"><VButton @click="closeDetails">关闭</VButton><VButton v-if="canManage" :disabled="busy" @click="remove('face', selected)">删除字形记录</VButton></div></template>
  </VModal>
  <VModal v-model:visible="familyVisible" :title="editingFamily ? '编辑字体族' : '创建字体族'" :width="540" :layer-closable="!busy">
    <div>
    <VAlert v-if="formError" type="error" title="保存未完成" :description="formError" />
    <FormKit v-model="familyForm" type="form" :actions="false" :disabled="busy" submit-label="保存字体族" @submit="saveFamily">
      <FormKit type="text" name="displayName" label="字体族名称" validation="required|length:1,160" />
      <FormKit type="checkbox" name="enabled" label="启用此字体族" />
      <FormKit type="submit" label="保存字体族" :disabled="busy" />
    </FormKit>
    </div>
  </VModal>
</template>

<style scoped>
.kfb-page { display: grid; grid-template-columns: minmax(0, 1fr); gap: 1rem; padding: 1rem; }
.kfb-section { display: grid; grid-template-columns: minmax(0, 1fr); gap: 1rem; min-width: 0; }
.kfb-overview { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 1.5rem; padding: 1.5rem; border: 1px solid #e2e8f0; border-radius: .75rem; background: #fff; }
.kfb-overview-title { display: flex; align-items: center; gap: 1rem; min-width: 0; }
.kfb-overview-title > div { min-width: 0; }
.kfb-cover { display: block; flex: 0 0 4rem; width: 4rem; height: 4rem; object-fit: contain; border-radius: .75rem; }
.kfb-eyebrow { color: #64748b; font-size: .65rem; letter-spacing: .14em; margin-bottom: .25rem; }
.kfb-overview h2 { font-size: 1.35rem; font-weight: 600; letter-spacing: -.02em; color: #1e293b; }
.kfb-overview .kfb-hint { margin-top: .25rem; }
.kfb-overview-meta { display: flex; flex-direction: column; align-items: flex-start; gap: .35rem; }
.kfb-navigation { display: flex; flex-wrap: wrap; gap: .5rem; }
.kfb-navigation :deep(button) { min-height: 2.75rem; }
.kfb-section-heading, .kfb-card-heading { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: .75rem; }
.kfb-section-heading h3, .kfb-font-heading h3 { color: #1e293b; font-size: 1.1rem; font-weight: 600; }
.kfb-card-heading { width: 100%; padding: 1rem; }
.kfb-card-heading > strong { display: flex; align-items: center; flex-wrap: wrap; gap: .5rem; }
.kfb-search { display: flex; flex: 0 1 24rem; align-items: flex-end; flex-wrap: wrap; gap: .5rem; max-width: 100%; }
.kfb-search :deep(.formkit-outer) { margin: 0; padding: 0; min-width: 0; flex: 1 1 12rem; }
.kfb-count, .kfb-badge { display: inline-flex; align-items: center; width: fit-content; padding: .2rem .55rem; border: 1px solid #e2e8f0; border-radius: .35rem; background: #f8fafc; color: #64748b; font-size: .75rem; font-weight: 500; line-height: 1.5; overflow-wrap: anywhere; }
.kfb-badge-ready { color: #286049; background: #eff8f2; border-color: #d4e9db; }
.kfb-environment { color: #64748b; font-size: .8rem; line-height: 1.7; padding: .85rem 1rem; background: #f1f5f9; border-radius: .5rem; overflow-wrap: anywhere; }
.kfb-environment summary { cursor: pointer; width: fit-content; }
.kfb-environment[open] summary { margin-bottom: .5rem; }
.kfb-dialog { display: grid; grid-template-columns: minmax(0, 1fr); gap: 1rem; }
.kfb-dialog :deep(.formkit-outer) { max-width: 100%; }
.kfb-list { max-width: 100%; overflow-x: auto; }
.kfb-list:focus-visible { outline: 2px solid #4f46e5; outline-offset: 3px; border-radius: .3rem; }
.kfb-toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: .75rem; justify-content: space-between; }
.kfb-hint { color: #64748b; font-size: .875rem; line-height: 1.6; overflow-wrap: anywhere; }
.kfb-id { font-size: .8rem; overflow-wrap: anywhere; }
.kfb-sample { min-height: 8rem; padding: 1.5rem; background: #fafbf9; color: #172033; border: 1px solid #e1e7df; border-radius: .5rem; font-size: 1.75rem; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; }
@media (max-width: 480px) { .kfb-page { padding: .75rem; } .kfb-overview { padding: 1rem; gap: 1rem; } .kfb-overview-title { gap: .75rem; } .kfb-cover { flex-basis: 3rem; width: 3rem; height: 3rem; border-radius: .5rem; } .kfb-navigation > * { flex: 1 1 calc(50% - .5rem); } .kfb-search { width: 100%; } .kfb-sample { padding: 1rem; font-size: 1.5rem; } }
</style>


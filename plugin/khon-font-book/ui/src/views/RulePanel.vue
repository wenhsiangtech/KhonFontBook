<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { utils } from '@halo-dev/ui-shared'
import { Dialog, Toast, VAlert, VButton, VCard, VEmpty, VEntity, VEntityContainer, VEntityField, VModal, VPagination } from '@halo-dev/components'
import { isAxiosError } from 'axios'
import { fontsApi } from '../api'
import type { CompiledCss, FontRule, RuleCatalog, RuleOptions, RuleUpdate, RuleUpdateScopeEnum } from '../api/generated'
import RuleContentSelect from './RuleContentSelect.vue'
import SelectorProbe from './SelectorProbe.vue'

const props = defineProps<{ resourceRevision: number }>()
const catalog = ref<RuleCatalog>()
const options = ref<RuleOptions>()
const selected = ref<FontRule>()
const visible = ref(false)
const busy = ref(false)
const probeVisible = ref(false)
const probeRule = ref<FontRule>()
function openProbe(rule?: FontRule) { probeRule.value = rule; probeVisible.value = true }
function adoptCandidate(value: { selector: string; force: boolean }) { probeVisible.value = false; open(probeRule.value); form.value.selector = value.selector; form.value.force = value.force }
const loading = ref(false)
const error = ref('')
const formError = ref('')
const page = ref(1)
const size = ref(10)
const keyword = ref('')
const compiled = ref<CompiledCss>()
const compileError = ref('')
const compiling = ref(false)
let compileGeneration = 0
let loadGeneration = 0
const context = ref({ themeName: '', contentKind: 'Unknown', contentName: '' })
const canManage = computed(() => utils.permission.has(['khon-font-book:manage']))
const scopes = [
  { value: 'THEME_ALL', label: '整个主题' }, { value: 'HOME', label: '主页（站点根路径 /，不含分页）' }, { value: 'POST_ALL', label: '该主题全部文章页' },
  { value: 'POST_ONE', label: '指定文章' }, { value: 'PAGE_ONE', label: '指定页面' },
]
const scopeLabel = (scope?: string) => scopes.find(item => item.value === scope)?.label || scope
type Editor = { displayName: string; enabled: boolean; themeName: string; scope: RuleUpdateScopeEnum; contentName: string; selector: string; target: string; weight: string; order: number | string; force: boolean }
const blank = (): Editor => ({ displayName: '', enabled: true, themeName: '', scope: 'THEME_ALL', contentName: '', selector: '', target: '', weight: '', order: 0, force: false })
const form = ref<Editor>(blank())
const one = computed(() => ['POST_ONE', 'PAGE_ONE'].includes(form.value.scope))
function includeCurrent(values: { value: string; label: string }[], current: string, type: string) {
  if (current && !values.some(o => o.value === current)) values.push({ value: current, label: `${type}缺失或不在选项中：${current}` })
  return values
}
const themes = computed(() => includeCurrent((options.value?.themes || []).map(t => ({ value: t.id!, label: `${t.label} · ${t.id}` })), form.value.themeName, '主题'))
const targets = computed(() => includeCurrent((options.value?.targets || []).map(t => ({ value: `${t.kind}:${t.id}`, label: `${t.kind === 'scheme' ? '方案' : '字体族'} · ${t.label}${t.enabled ? '' : '（停用）'}` })), form.value.target, '目标'))
const weights = computed(() => {
  const target = options.value?.targets?.find(t => `${t.kind}:${t.id}` === form.value.target)
  return includeCurrent([{ value: '', label: '保留主题字重（不输出 font-weight）' }, ...(target?.weights || []).map(w => ({ value: String(w), label: `${w}（真实字重）` }))], form.value.weight, '字重')
})
async function reason(ex: unknown) {
  if (isAxiosError(ex)) return ex.response?.data?.detail || ex.response?.data?.title || ex.message
  return ex instanceof Error ? ex.message : '操作失败，请刷新后重试'
}
function clearCompilation() { compileGeneration++; compiled.value = undefined; compileError.value = '' }
async function load() {
  const run = ++loadGeneration
  loading.value = true; error.value = ''; clearCompilation()
  try {
    const [rules, choices] = await Promise.all([
      fontsApi.listFontRules({ page: page.value, size: size.value, keyword: keyword.value }),
      fontsApi.getFontRuleOptions({ contentKind: 'Post', page: 1, size: 1 }),
    ])
    if (run !== loadGeneration) return
    catalog.value = rules.data; options.value = choices.data
    if (!context.value.themeName) context.value.themeName = choices.data.themes?.[0]?.id || ''
  } catch (ex) { const message = await reason(ex); if (run === loadGeneration) error.value = message }
  finally { if (run === loadGeneration) loading.value = false }
}
function open(rule?: FontRule) {
  selected.value = rule
  const s = rule?.spec
  form.value = s ? { displayName: s.displayName || '', enabled: !!s.enabled, themeName: s.themeName || '', scope: s.scope || 'THEME_ALL', contentName: s.contentName || '', selector: s.selector || '', target: `${s.targetKind}:${s.targetId}`, weight: s.weight == null ? '' : String(s.weight), order: s.order || 0, force: !!s.force }
    : { ...blank(), themeName: options.value?.themes?.[0]?.id || '' }
  formError.value = ''; visible.value = true
}
watch(() => form.value.scope, (value, previous) => { if (value !== previous && visible.value) form.value.contentName = '' }, { flush: 'sync' })
watch(() => form.value.target, (value, previous) => { if (value !== previous && visible.value) form.value.weight = '' }, { flush: 'sync' })
watch(context, clearCompilation, { deep: true, flush: 'sync' })
watch(() => context.value.contentKind, () => { context.value.contentName = '' }, { flush: 'sync' })
async function save() {
  busy.value = true; formError.value = ''
  try {
    const [targetKind, targetId] = form.value.target.split(':')
    const request: RuleUpdate = { version: selected.value?.metadata.version ?? undefined, displayName: form.value.displayName, enabled: !!form.value.enabled, themeName: form.value.themeName, scope: form.value.scope, contentName: one.value ? form.value.contentName : '', selector: form.value.selector, targetKind, targetId, weight: form.value.weight === '' ? undefined : Number(form.value.weight), order: Number(form.value.order), force: !!form.value.force }
    selected.value = (selected.value ? await fontsApi.updateFontRule({ id: selected.value.metadata.name, ruleUpdate: request }) : await fontsApi.createFontRule({ ruleUpdate: request })).data
    const s = selected.value.spec
    context.value.contentKind = s.scope === 'HOME' ? 'Home' : s.scope === 'PAGE_ONE' ? 'SinglePage' : ['POST_ONE', 'POST_ALL'].includes(s.scope!) ? 'Post' : 'Unknown'
    context.value.themeName = s.themeName!; context.value.contentName = s.contentName || ''
    Toast.success('规则已保存，语法有效；实际页面匹配尚未验证')
    visible.value = false; await load()
  } catch (ex) { formError.value = await reason(ex) }
  finally { busy.value = false }
}
async function remove(rule: FontRule) {
  Dialog.warning({ title: '删除应用规则？', description: '删除规则并释放内部引用，字体、方案和附件保留。', confirmText: '删除规则', onConfirm: async () => {
    busy.value = true
    try { await fontsApi.deleteFontRule({ id: rule.metadata.name, version: rule.metadata.version ?? 0 }); await load(); Toast.success('规则已删除，引用已释放') }
    catch (ex) { error.value = await reason(ex) }
    finally { busy.value = false }
  } })
}
async function compile() {
  compiling.value = true; clearCompilation()
  const run = compileGeneration
  try {
    const { data } = await fontsApi.compileFontRules({ ...context.value, contentName: ['Unknown', 'Home'].includes(context.value.contentKind) ? '' : context.value.contentName })
    if (run === compileGeneration) compiled.value = data
  }
  catch (ex) { if (run === compileGeneration) compileError.value = await reason(ex) }
  finally { compiling.value = false }
}
watch(() => props.resourceRevision, load)
onMounted(load)
onBeforeUnmount(() => { loadGeneration++; clearCompilation() })
</script>

<template>
  <VCard title="主题应用规则">
    <template #actions><div class="kfb-rule-toolbar"><VButton size="sm" :loading="loading" @click="load">刷新规则</VButton><VButton size="sm" @click="openProbe()">选择器穿透查询</VButton><VButton v-if="canManage" size="sm" type="secondary" @click="open()">创建规则</VButton></div></template>
    <VAlert v-if="error" type="error" title="规则读取或操作失败" :description="error" />
    <div class="kfb-rule-guide"><strong>从选择器到主题文字</strong><p class="kfb-rule-hint">先用穿透查询检查实际文字元素，再保存规则。在「插件 → KhonFontBook → 主题字体应用」开启注入后，主题页面才会加载匹配规则。</p><details><summary>规则顺序、优先级与生效边界</summary><p class="kfb-rule-hint">注入默认关闭。规则按顺序升序、相同顺序按规则 ID 输出；后输出只在优先级和 importance 相同时占优。主题内联样式、!important 和子元素字体仍可能覆盖。字体停用或丢失时跳过不可用字形，全部不可用则跳过规则。编译检查不读取 DOM，不能证明选择器已匹配或字体已生效。</p></details></div>
    <form class="kfb-rule-search" @submit.prevent="page = 1; load()"><FormKit v-model="keyword" type="search" label="搜索规则或选择器" placeholder="输入规则名称或 CSS 选择器" /><VButton @click="page = 1; load()">搜索规则</VButton></form>
    <VEmpty v-if="!catalog?.items?.length" title="尚无匹配的应用规则" message="先导入字体或创建方案，再选择实际主题和页面范围。" />
    <div v-else class="kfb-rule-list" tabindex="0" aria-label="规则列表，可横向滚动"><VEntityContainer><VEntity v-for="item in catalog.items" :key="item.rule!.metadata.name">
      <template #start><VEntityField :title="item.rule!.spec.displayName"><template #description><p class="kfb-rule-hint"><span class="kfb-rule-state" :class="{ 'kfb-rule-state-on': item.rule!.spec.enabled }">{{ item.rule!.spec.enabled ? '启用' : '停用' }}</span> {{ item.rule!.spec.themeName }} · {{ scopeLabel(item.rule!.spec.scope) }} · 顺序 {{ item.rule!.spec.order }}</p><p class="kfb-rule-hint"><code class="kfb-rule-selector">{{ item.rule!.spec.selector }}</code></p><p class="kfb-rule-hint">{{ item.rule!.spec.contentName || (item.rule!.spec.scope === 'HOME' ? '站点根路径 /' : '范围内全部页面') }} · 字重 {{ item.rule!.spec.weight ?? '保留主题' }} · {{ item.rule!.spec.force ? '显式强制覆盖' : '普通优先级' }}</p><p class="kfb-rule-hint">语法已校验；实际匹配未验证。{{ item.diagnostics?.join('；') }}</p></template></VEntityField></template>
      <template #end><div class="kfb-rule-toolbar"><VButton size="sm" @click="openProbe(item.rule)">诊断选择器</VButton><VButton size="sm" @click="open(item.rule)">{{ canManage ? '编辑规则' : '规则详情' }}</VButton><VButton v-if="canManage" size="sm" :disabled="busy" @click="remove(item.rule!)">删除规则</VButton></div></template>
    </VEntity></VEntityContainer></div>
    <VPagination v-if="catalog?.total" v-model:page="page" v-model:size="size" :size-options="[10, 20, 50]" :total="catalog.total" @change="load" />
    <details class="kfb-rule-compile"><summary>高级检查 · 按主题与页面编译已保存规则</summary>
      <FormKit v-model="context.themeName" type="select" label="编译主题" placeholder="请选择" :options="(options?.themes || []).map(t => ({ value: t.id!, label: `${t.label} · ${t.id}` }))" />
      <FormKit v-model="context.contentKind" type="select" label="编译页面上下文" :options="[{ value: 'Home', label: '主页（/）' }, { value: 'Unknown', label: '其他主题页（仅全主题规则）' }, { value: 'Post', label: '文章页' }, { value: 'SinglePage', label: '独立页面' }]" />
      <RuleContentSelect v-if="['Post', 'SinglePage'].includes(context.contentKind)" v-model="context.contentName" :kind="context.contentKind === 'Post' ? 'Post' : 'SinglePage'" />
      <VButton :loading="compiling" :disabled="!context.themeName || ['Post', 'SinglePage'].includes(context.contentKind) && !context.contentName" @click="compile">编译已保存规则</VButton>
      <p class="kfb-rule-hint">结果是当前快照；修改字体、方案、规则或附件后需重新编译。此处不会将 CSS 应用到 Console 或真实主题。</p>
      <VAlert v-if="compileError" type="error" title="编译未完成" :description="compileError" />
      <div v-if="compiled"><p class="kfb-rule-hint" role="status">{{ compiled.matching }}</p><p class="kfb-rule-hint">输出 {{ compiled.ruleIds?.length || 0 }} 条规则 · {{ compiled.compilerVersion }}<br />内容 SHA-256：{{ compiled.sha256 }}</p><p v-for="line in compiled.diagnostics" :key="line" class="kfb-rule-hint">{{ line }}</p><pre class="kfb-rule-css">{{ compiled.css || '/* 此上下文无可输出规则 */' }}</pre></div>
    </details>
  </VCard>
  <VModal v-model:visible="probeVisible" title="选择器穿透查询与覆盖诊断" :width="950"><div><SelectorProbe v-if="probeVisible" :seed="probeRule?.spec.selector" @choose="adoptCandidate" /></div></VModal>
  <VModal v-model:visible="visible" :title="selected ? '应用规则详情' : '创建应用规则'" :width="800" :layer-closable="!busy">
    <div class="kfb-rule-dialog">
      <VAlert v-if="formError" type="error" title="规则保存未完成" :description="formError" />
      <p v-if="selected" class="kfb-rule-hint">稳定规则 ID：{{ selected.metadata.name }}；语法已校验，实际页面匹配未验证。</p>
      <FormKit v-if="visible" v-model="form" type="form" :actions="false" :disabled="busy || !canManage" @submit="save">
        <FormKit type="text" name="displayName" label="规则名称" validation="required|length:1,160" />
        <FormKit type="select" name="themeName" label="目标主题" placeholder="请选择" :options="themes" validation="required" />
        <FormKit type="select" name="scope" label="页面范围" :options="scopes" />
        <RuleContentSelect v-if="one" v-model="form.contentName" name="contentName" :kind="form.scope === 'PAGE_ONE' ? 'SinglePage' : 'Post'" :disabled="busy || !canManage" />
        <FormKit type="text" name="selector" label="CSS 选择器" placeholder=".post-title, #section-introduction" validation="required|length:1,1024" help="多个独立目标用半角逗号：.a, .b, #title；空格 .a .b 表示后代；.a.b 表示同一元素同时有两个 class；.a > .b 表示直接子元素。拒绝属性、伪类/元素、通配符及 CSS 片段。" />
        <p class="kfb-rule-hint">class 面向一组元素；指定单标题请选具体文章/页面并填写真实、稳定且唯一的标题 ID，例如 #section-introduction。不会自动寻找或创建 ID。</p>
        <FormKit type="select" name="target" label="字体族或方案" placeholder="请选择" :options="targets" validation="required" />
        <FormKit type="select" name="weight" label="可选真实字重" :options="weights" />
        <p class="kfb-rule-hint">默认只设置 font-family。保留主题字重时浏览器可能选邻近字形或合成粗体；选择显式字重会再次校验真实资源。方案沿用已保存的中英分配与回退链，直接字体族使用 sans-serif 回退。</p>
        <FormKit type="number" name="order" label="输出顺序" min="-1000000" max="1000000" step="1" validation="required" />
        <FormKit type="checkbox" name="force" label="显式强制覆盖（仅本规则的字体声明添加 !important）" />
        <FormKit type="checkbox" name="enabled" label="启用规则（停用仍保留内部引用）" />
        <FormKit v-if="canManage" type="submit" label="保存规则并校验语法" :disabled="busy" />
      </FormKit>
    </div>
  </VModal>
</template>

<style scoped>
.kfb-rule-toolbar { display: flex; gap: .75rem; flex-wrap: wrap; align-items: center; justify-content: space-between; }
.kfb-rule-list { max-width: 100%; overflow-x: auto; }
.kfb-rule-list:focus-visible { outline: 2px solid #4f46e5; outline-offset: 3px; }
.kfb-rule-guide { background: #f8fafc; padding: 1rem; border-radius: .5rem; margin-bottom: 1rem; }
.kfb-rule-guide strong { color: #334155; font-size: .9rem; }
.kfb-rule-guide summary { font-size: .8rem; color: #64748b; cursor: pointer; margin-top: .5rem; }
.kfb-rule-search { display: flex; flex-wrap: wrap; align-items: flex-end; gap: .5rem; margin-bottom: 1rem; }
.kfb-rule-search :deep(.formkit-outer) { margin: 0; padding: 0; min-width: 0; flex: 1 1 14rem; max-width: 24rem; }
.kfb-rule-state { display: inline-block; border-radius: .25rem; padding: 0 .35rem; background: #f1f5f9; color: #64748b; font-size: .75rem; }
.kfb-rule-state-on { background: #eff8f2; color: #286049; }
.kfb-rule-selector { display: inline-block; background: #f1f5f9; border-radius: .25rem; padding: .15rem .4rem; color: #334155; margin: .25rem 0; }
.kfb-rule-dialog { display: grid; grid-template-columns: minmax(0, 1fr); gap: 1rem; }
.kfb-rule-dialog :deep(.formkit-outer) { max-width: 100%; }
.kfb-rule-hint { color: #64748b; font-size: .875rem; line-height: 1.6; overflow-wrap: anywhere; }
.kfb-rule-compile { margin-top: 1.5rem; border-top: 1px solid #e2e8f0; padding-top: 1rem; }
.kfb-rule-compile summary { cursor: pointer; margin-bottom: 1rem; }
.kfb-rule-css { background: #0f172a; color: #e2e8f0; padding: 1rem; border-radius: .5rem; overflow: auto; max-height: 28rem; font-size: .8rem; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>

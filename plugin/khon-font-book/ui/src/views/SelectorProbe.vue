<script setup lang="ts">
import { ref, watch } from 'vue'
import { VAlert, VButton } from '@halo-dev/components'
import { diagnose, publicPageUrl, type Diagnosis } from '../selector-diagnostics'

const props = defineProps<{ seed?: string }>()
const emit = defineEmits<{ choose: [value: { selector: string; force: boolean }] }>()
const page = ref('/')
const selector = ref(props.seed || '')
const scripts = ref(false)
const frame = ref<HTMLIFrameElement>()
const url = ref('')
const ready = ref(false)
const error = ref('')
const result = ref<Diagnosis>()
watch([page, scripts], () => { ready.value = false; result.value = undefined })
watch(selector, () => { result.value = undefined })
function load() {
  error.value = ''; result.value = undefined; ready.value = false
  try { url.value = publicPageUrl(page.value, location.origin); if (frame.value) frame.value.src = url.value }
  catch (ex) { error.value = ex instanceof Error ? ex.message : '地址无效' }
}
function loaded() {
  result.value = undefined
  try {
    const doc = frame.value?.contentDocument
    if (!doc || !doc.body || !doc.location.href.startsWith(location.origin + '/')) throw new Error('无法访问预览文档；请检查跨域跳转、登录或站点的嵌入限制。')
    publicPageUrl(doc.location.href, location.origin)
    ready.value = true; error.value = ''
  } catch (ex) { ready.value = false; error.value = ex instanceof Error ? ex.message : '预览不可访问' }
}
function scan() {
  error.value = ''; result.value = undefined
  try {
    const doc = frame.value?.contentDocument
    if (!doc || !ready.value) throw new Error('请先加载目标页面。')
    publicPageUrl(doc.location.href, location.origin)
    result.value = diagnose(doc, selector.value)
  } catch (ex) { error.value = ex instanceof Error ? ex.message : '查询未完成' }
}
</script>

<template>
  <div class="kfb-probe">
    <p>在目标页面中找到真正承载文字的元素，再测试字体覆盖。采用候选后会填入规则草稿，保存后才会应用。</p>
    <section class="kfb-probe-step"><h3><span>1</span> 加载目标页面</h3>
    <FormKit v-model="page" type="text" label="待诊断的本站页面地址" placeholder="/ 或 /archives" />
    <FormKit v-model="scripts" type="checkbox" label="运行已信任主题的脚本（动态内容需要；改变后重新加载）" help="脚本与后台同源，可能访问本站登录态和资源；这不是安全隔离沙箱。仅对你信任的主题启用。" />
    <div class="kfb-probe-actions"><VButton @click="load">加载诊断页面</VButton><span v-if="ready" class="kfb-probe-badge kfb-probe-success" role="status">页面已就绪</span></div>
    <iframe v-if="url" ref="frame" :src="url" :sandbox="scripts ? 'allow-same-origin allow-scripts' : 'allow-same-origin'" title="字体选择器诊断预览" @load="loaded" />
    <details class="kfb-probe-help"><summary>页面脚本与隔离边界</summary><p>当前 URL 必须对应目标主题，其他主题请使用 Halo 的授权预览链接。默认不运行页面脚本，因此动态文字可能缺失；iframe / Shadow DOM 内部不能由普通主题规则直接穿透。临时测试结束会清理测试样式。</p></details>
    </section>
    <section class="kfb-probe-step"><h3><span>2</span> 查询文字元素</h3>
    <FormKit v-model="selector" type="text" label="查询 class / ID 或选择器" placeholder=".hero、#heading 或 .hero .headline" />
    <div class="kfb-probe-syntax"><p><code>.a, .b, #title</code> 半角逗号：多个独立目标</p><p><code>.a .b</code> 空格：后代元素</p><p><code>.a.b</code> 连写：同一元素同时有两个 class</p><p><code>.a &gt; .b</code> 大于号：直接子元素</p></div>
    <VButton :disabled="!ready" @click="scan">穿透查询文字元素</VButton>
    </section>
    <VAlert v-if="error" type="error" title="诊断未完成" :description="error" />
    <section v-if="result" class="kfb-probe-results" aria-live="polite"><h3><span>3</span> 选择覆盖候选</h3>
      <div class="kfb-probe-summary"><span>原选择器匹配 <strong>{{ result.matches }}</strong> 个元素</span><span>找到 <strong>{{ result.candidates.length }}</strong> 个候选</span></div>
      <p class="kfb-probe-notice">{{ result.injected ? '页面含插件字体样式链接。' : '页面未发现插件字体样式链接：请检查注入开关、主题和页面范围。' }}覆盖测试检查 CSS 优先级；实际字体加载和显示仍需在主题中确认。</p>
      <details v-if="result.notes.length" class="kfb-probe-help"><summary>查看诊断说明（{{ result.notes.length }}）</summary><p v-for="note in result.notes" :key="note">{{ note }}</p></details>
      <p v-if="!result.candidates.length">暂无可用候选，请检查选择器、页面地址或动态脚本选项。</p>
      <article v-for="item in result.candidates" :key="item.selector">
        <div class="kfb-probe-candidate"><code>{{ item.selector }}</code><span class="kfb-probe-badge" :class="{ 'kfb-probe-success': item.normal, 'kfb-probe-warning': !item.normal && item.force }">{{ item.normal ? '普通规则可覆盖' : item.force ? '需强制覆盖' : '未能覆盖' }}</span></div>
        <p class="kfb-probe-text">{{ item.text }}</p><p>匹配 {{ item.count }} 个元素{{ item.visible ? '' : ' · 当前不可见或未布局' }}</p>
        <details class="kfb-probe-help"><summary>当前字体与覆盖详情</summary><p>当前字体：{{ item.family }}</p><p>{{ item.normal ? '普通规则可覆盖' : item.force ? '普通规则被覆盖；显式 !important 可覆盖' : '两种规则均未覆盖，需检查内联 important / 样式隔离 / 动画' }}</p></details>
        <VButton v-if="item.normal || item.force" size="sm" @click="emit('choose', { selector: item.selector, force: !item.normal })">采用此候选{{ !item.normal ? '（建议强制覆盖）' : '' }}</VButton>
      </article>
    </section>
  </div>
</template>

<style scoped>
.kfb-probe p { color: #64748b; font-size: .85rem; line-height: 1.6; overflow-wrap: anywhere; }
.kfb-probe { display: grid; grid-template-columns: minmax(0, 1fr); gap: 1.25rem; }
.kfb-probe h3 { display: flex; align-items: center; gap: .5rem; color: #334155; font-weight: 600; margin-bottom: 1rem; }
.kfb-probe h3 > span { display: inline-grid; place-items: center; width: 1.5rem; height: 1.5rem; border-radius: 50%; background: #edf3f1; color: #36594d; font-size: .75rem; }
.kfb-probe-step { border: 1px solid #e2e8f0; padding: 1rem; border-radius: .5rem; min-width: 0; }
.kfb-probe :deep(.formkit-outer) { max-width: 100%; }
.kfb-probe iframe { display: block; width: 100%; height: 280px; border: 1px solid #cbd5e1; border-radius: .35rem; margin: .75rem 0; background: #fff; }
.kfb-probe-actions, .kfb-probe-candidate { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: .5rem; }
.kfb-probe-help { margin: .75rem 0; font-size: .8rem; color: #64748b; overflow-wrap: anywhere; }
.kfb-probe-help summary { cursor: pointer; }
.kfb-probe-syntax { padding: .75rem; background: #f8fafc; border-radius: .35rem; margin: .5rem 0 1rem; }
.kfb-probe-syntax code { display: inline-block; min-width: 8rem; color: #334155; }
.kfb-probe-summary { display: flex; flex-wrap: wrap; gap: .75rem 1.5rem; color: #64748b; font-size: .85rem; padding: 1rem; background: #f8fafc; border-radius: .5rem; }
.kfb-probe-summary strong { color: #1e293b; font-size: 1.2rem; font-variant-numeric: tabular-nums; }
.kfb-probe-notice { margin-top: .75rem; }
.kfb-probe article { padding: 1rem; margin-top: .75rem; border: 1px solid #e2e8f0; border-radius: .5rem; }
.kfb-probe code { overflow-wrap: anywhere; font-size: .85rem; }
.kfb-probe-candidate code { color: #334155; font-weight: 600; }
.kfb-probe p.kfb-probe-text { margin: .75rem 0 .25rem; color: #334155; }
.kfb-probe-badge { border-radius: .3rem; background: #f1f5f9; color: #64748b; padding: .2rem .5rem; font-size: .75rem; }
.kfb-probe-success { color: #286049; background: #eff8f2; }
.kfb-probe-warning { color: #92400e; background: #fff7ed; }
@media (max-width: 480px) { .kfb-probe-step, .kfb-probe article { padding: .75rem; } }
</style>

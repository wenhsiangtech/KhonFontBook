<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { VAlert, VButton, VPagination } from '@halo-dev/components'
import { fontsApi } from '../api'
import type { Choice } from '../api/generated'

const props = defineProps<{ modelValue: string; kind: 'Post' | 'SinglePage'; disabled?: boolean; name?: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const keyword = ref('')
const page = ref(1)
const size = ref(10)
const total = ref(0)
const contents = ref<Choice[]>([])
const error = ref('')
let generation = 0
const choices = computed(() => {
  const result = contents.value.map(c => ({ value: c.id!, label: `${c.label} · ${c.id}` }))
  if (props.modelValue && !result.some(c => c.value === props.modelValue)) result.push({ value: props.modelValue, label: `当前 ID（未在本页结果中）：${props.modelValue}` })
  return result
})
async function load() {
  const run = ++generation
  error.value = ''
  try {
    const { data } = await fontsApi.getFontRuleOptions({ contentKind: props.kind, page: page.value, size: size.value, keyword: keyword.value })
    if (run === generation) { contents.value = data.contents || []; total.value = data.total || 0 }
  } catch { if (run === generation) { contents.value = []; total.value = 0; error.value = '内容选项读取失败，请确认权限后重试。' } }
}
watch(() => props.kind, () => { page.value = 1; keyword.value = ''; contents.value = []; load() }, { immediate: true })
</script>

<template>
  <div class="kfb-content-picker">
    <VAlert v-if="error" type="error" title="内容读取失败" :description="error" />
    <div class="kfb-content-search"><FormKit v-model="keyword" type="search" label="搜索已发布公开内容" :disabled="disabled" /><VButton size="sm" :disabled="disabled" @click="page = 1; load()">搜索内容</VButton></div>
    <FormKit :model-value="modelValue" :name="name" type="select" label="稳定内容 ID" placeholder="请选择文章或页面" :options="choices" :disabled="disabled" @update:model-value="emit('update:modelValue', String($event || ''))" />
    <VPagination v-if="total" v-model:page="page" v-model:size="size" :size-options="[10, 20, 50]" :total="total" @change="load" />
    <p>只列已发布且公开的内容；保存和编译时服务端会再次检查。标题、slug 和 URL 都不是规则主键。</p>
  </div>
</template>

<style scoped>
.kfb-content-picker { padding: .75rem; border: 1px solid #e2e8f0; border-radius: .5rem; margin-bottom: 1rem; }
.kfb-content-search { display: flex; gap: .75rem; align-items: center; flex-wrap: wrap; }
p { color: #64748b; font-size: .8rem; line-height: 1.6; }
</style>

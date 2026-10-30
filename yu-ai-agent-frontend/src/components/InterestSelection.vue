<template>
  <section v-if="selection" class="interest-picker" aria-label="选择研究兴趣点">
    <header><div><h3>{{ interactive ? '这一轮，你想研究哪两个方向？' : '本次未确认的候选兴趣点' }}</h3><p>第 {{ selection.round }} 轮 · {{ interactive ? '请选择 2 个。确认前不会检索，等待不计入研究执行时间。' : '研究已停止或中断，以下候选仅供查看。' }}</p></div><span>{{ selected.length }} / 2</span></header>
    <div class="candidate-grid">
      <article v-for="(point, index) in selection.candidates" :key="point.id" :class="{ selected: selected.includes(point.id) }">
        <label><input type="checkbox" :checked="selected.includes(point.id)" :disabled="!interactive || working || submitted || (selected.length === 2 && !selected.includes(point.id))" @change="toggle(point.id)" /><strong>候选 {{ index + 1 }}</strong></label>
        <blockquote>{{ point.quote }}</blockquote>
        <dl><dt>为什么值得查</dt><dd>{{ point.reason }}</dd><dt>想弄清的问题</dt><dd>{{ point.question }}</dd><dt>暂定假设</dt><dd>{{ point.hypothesis || '未提出假设' }}</dd></dl>
        <details><summary>检索关键词</summary><p>{{ point.query }}</p></details>
      </article>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <footer><span>{{ !interactive ? '重新研究需要发起新的提问。' : submitted ? (stopping ? '正在停止研究…' : '已确认，正在继续研究…') : '可随时停止；刷新页面后仍可继续选择。' }}</span><div v-if="interactive"><button :disabled="working || submitted" @click="stop">停止研究</button><button class="confirm" :disabled="selected.length !== 2 || working || submitted" @click="confirm">{{ working ? '提交中…' : '确认这两个，继续研究' }}</button></div></footer>
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { reading } from '../api/reading'
import { interestSelectionRequest } from '../utils/researchTimeline'
const props = defineProps({ run: { type: Object, required: true } })
const emit = defineEmits(['updated'])
const interactive = computed(() => props.run.status === 'WAITING_FOR_SELECTION')
const selection = computed(() => ['WAITING_FOR_SELECTION', 'INTERRUPTED', 'CANCELLED', 'FAILED', 'LIMIT_REACHED'].includes(props.run.status) ? interestSelectionRequest(props.run) : null)
const selected = ref([]), working = ref(false), submitted = ref(false), stopping = ref(false), error = ref('')
watch(() => selection.value?.selectionId, () => { selected.value = []; error.value = ''; submitted.value = false; stopping.value = false }, { immediate: true })
function toggle(id) { selected.value = selected.value.includes(id) ? selected.value.filter(value => value !== id) : [...selected.value, id].slice(0, 2) }
async function confirm() {
  if (!interactive.value || !selection.value || selected.value.length !== 2 || working.value || submitted.value) return
  working.value = true; error.value = ''
  try {
    await reading(`/runs/${props.run.id}/interest-selection`, 'POST', { selectionId: selection.value.selectionId, selectedIds: selected.value })
    submitted.value = true; emit('updated')
  } catch (e) { error.value = e.response?.data?.message || e.message || '确认失败，请重试' }
  finally { working.value = false }
}
async function stop() {
  if (!interactive.value || working.value || submitted.value) return
  stopping.value = true; working.value = true; error.value = ''
  try { await reading(`/runs/${props.run.id}/stop`, 'POST'); submitted.value = true; emit('updated') }
  catch (e) { stopping.value = false; error.value = e.response?.data?.message || e.message || '停止失败，请重试' }
  finally { working.value = false }
}
</script>

<style scoped>
.interest-picker{margin:24px 0;border:1px solid #ddd;border-radius:16px;padding:20px;color:#222;font-size:13px}.interest-picker header,.interest-picker footer{display:flex;justify-content:space-between;gap:16px;align-items:center}.interest-picker h3{font-size:17px;margin:0 0 8px}.interest-picker header p,.interest-picker footer>span{font-size:12px;line-height:1.8;color:#737373}.interest-picker header>span{white-space:nowrap;color:#666}.candidate-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin:18px 0}.candidate-grid article{border:1px solid #e5e5e5;border-radius:12px;padding:15px;overflow-wrap:anywhere}.candidate-grid article.selected{border-color:#333;background:#f7f7f7}.candidate-grid label{display:flex;gap:10px;align-items:center;cursor:pointer}.candidate-grid input{accent-color:#171717;width:16px;height:16px}.candidate-grid blockquote{border-left:2px solid #ccc;padding-left:12px;margin:14px 0;line-height:1.8;white-space:pre-wrap}.candidate-grid dl{margin:0;line-height:1.8}.candidate-grid dt{color:#777;font-size:11px;margin-top:9px}.candidate-grid dd{margin:0;white-space:pre-wrap}.candidate-grid details{margin-top:14px;font-size:12px;color:#777}.candidate-grid summary{cursor:pointer}.candidate-grid details p{line-height:1.8}.interest-picker footer>div{display:flex;gap:8px;flex-wrap:wrap}.interest-picker button{border:1px solid #ddd;border-radius:20px;padding:9px 14px;background:white;color:#333;font:inherit;font-size:12px;cursor:pointer}.interest-picker button.confirm{background:#171717;color:white;border-color:#171717}.interest-picker button:disabled{opacity:.45;cursor:not-allowed}.interest-picker button:focus-visible,.candidate-grid input:focus-visible{outline:2px solid #737373;outline-offset:3px}.error{color:#b42318;line-height:1.8}@media(max-width:650px){.candidate-grid{grid-template-columns:1fr}.interest-picker{padding:15px}.interest-picker footer{align-items:flex-start;flex-direction:column}}
</style>

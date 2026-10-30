<template>
  <div class="template-workbench">
    <header class="workbench-header">
      <div><div class="eyebrow">READ · RESEARCH TEMPLATES</div><h1>研究模板工作台</h1><p>让每个研究环节的指导文字都可见、可选择。</p></div>
      <button @click="back">← 返回阅读{{ sessionId ? '会话' : '研究室' }}</button>
    </header>
    <div v-if="error" class="notice error" role="alert">{{ error }}<button @click="error = ''">关闭</button></div>
    <div v-if="message" class="notice" role="status">{{ message }}</div>
    <div class="workbench-layout">
      <aside class="template-list">
        <div class="list-title"><h2>模板库</h2><button :disabled="working" @click="refreshList">刷新</button></div>
        <p v-if="loading">正在读取模板…</p>
        <template v-for="group in groups" :key="group.title">
          <h3>{{ group.title }}</h3>
          <button v-for="item in group.items" :key="item.id" class="template-item" :class="{ selected: detail?.template.id === item.id }" :disabled="working" @click="select(item.id)">
            <strong>{{ item.name }}</strong><span>{{ item.engine === 'LEGACY_V2' ? '原版报告 · 基础行动协议' : '解释发展报告 · 扩展行动协议' }} · v{{ item.version }}</span><small>{{ item.description.split('\n')[0] }}</small>
          </button>
          <p v-if="!group.items.length" class="muted">暂无{{ group.title }}</p>
        </template>
        <p v-for="item in registry.errors || []" :key="item.id" class="error">{{ item.id }}：{{ item.error }}</p>
        <section class="create-box">
          <h3>新增自定义模板</h3><p class="muted">默认从原文查证模板完整复制，也可选择其他来源。</p>
          <label>复制来源<select v-model="copySource" :disabled="working"><option v-for="item in registry.templates" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
          <label>新模板名称<input v-model="copyName" maxlength="200" placeholder="留空则自动命名" :disabled="working" /></label>
          <button class="primary" :disabled="working || !copySource" @click="create(copySource)">新增自定义模板</button>
        </section>
      </aside>
      <main v-if="detail && draft" class="template-editor">
        <section class="editor-heading">
          <div class="badges"><span>{{ detail.template.official ? '官方 · 只读' : '自定义 · 可编辑' }}</span><span>已保存 v{{ detail.template.version }}</span><span v-if="dirty" class="draft-badge">草稿 · 未生效</span></div>
          <label>名称<input v-model="draft.name" maxlength="200" :disabled="detail.template.official || working" /></label>
          <section v-if="detail.template.official" class="template-guide" aria-label="模板选择参考"><strong>选择参考</strong><p>{{ draft.description }}</p></section>
          <label v-else>说明<textarea v-model="draft.description" rows="5" maxlength="2000" :disabled="working" /></label>
          <p class="muted">继承：{{ detail.template.baseId || '官方初始配置' }}{{ detail.template.baseVersion ? ' · v' + detail.template.baseVersion : '' }} · {{ detail.template.engine }} · {{ detail.effective.constraints.protocol }}</p>
          <div class="actions"><button :disabled="working || dirty" @click="create(detail.template.id)">复制已保存模板</button><button v-if="!detail.template.official" class="primary" :disabled="working || !dirty || !!validation" @click="save">保存新修订</button><button class="primary" :disabled="working || dirty" @click="apply">{{ sessionId ? '应用到当前会话' : '选用并返回阅读' }}</button><button v-if="dirty || conflict" :disabled="working" @click="reload">载入服务器最新版</button></div>
          <p v-if="validation" class="error">{{ validation }}</p>
          <p v-if="dirty" class="muted">尚未保存。下方“已保存版本实际指导文字”仍对应 v{{ detail.template.version }}，保存后才能应用。</p>
          <p v-else class="muted">修改并保存后，所有引用此模板的会话会在下一次提问使用新修订；在途请求和历史回答不变。</p>
        </section>
        <article v-for="([key, label, target, hint], index) in templateSections" :key="key" class="section-card">
          <div class="section-heading"><div><span class="section-number">{{ String(index + 1).padStart(2, '0') }}</span><h2>{{ label }}</h2></div><button v-if="!detail.template.official" :disabled="working" @click="resetSection(key)">恢复此项初始内容</button></div>
          <p class="muted">{{ target }} · {{ hint }}</p>
          <label :for="'section-' + key">{{ detail.template.official ? '指导文字（复制后可编辑）' : '可编辑的指导文字' }}</label>
          <textarea :id="'section-' + key" v-model="draft.sections[key]" rows="8" maxlength="16000" :readonly="detail.template.official" :disabled="working" spellcheck="false" />
          <p class="character-count">{{ draft.sections[key].length.toLocaleString() }} / 16,000 字符</p>
          <details><summary>固定协议与执行约束 · 只读</summary><pre>{{ fixedFor(key) }}</pre></details>
          <details><summary>已保存 v{{ detail.template.version }} 的实际指导文字</summary><p class="muted">使用与运行时相同的后端组装器；不包含具体问题、书籍正文和工具历史。</p><pre>{{ detail.effective.stages[key] }}</pre></details>
        </article>
        <section class="section-card"><h2>固定执行预算</h2><p>至少 {{ detail.effective.constraints.minimumInvestigations }} 轮，上限 {{ detail.effective.constraints.maximumInvestigations }} 轮；实际仍受总调用、工具和时间预算限制。</p><pre>{{ JSON.stringify(detail.effective.constraints.budgets, null, 2) }}</pre><p class="muted">指导文字不能增加工具权限、修改报告字段或突破这些限制。</p><details><summary>模板内容摘要</summary><code>{{ detail.digest }}</code></details></section>
      </main>
      <main v-else class="empty-editor"><h2>选择研究模板</h2><p>查看官方模板，或从原文查证模板创建你自己的研究方式。</p></main>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { reading } from '../api/reading'
import { editableTemplate, templateDirty, templateSections, templateValidation } from '../utils/researchTemplates'

const route = useRoute(), router = useRouter()
const sessionId = String(route.query.sessionId || '')
const registry = ref({ templates: [], errors: [] }), detail = ref(null), draft = ref(null)
const error = ref(''), message = ref(''), working = ref(false), loading = ref(true), conflict = ref(false)
const copyName = ref(''), copySource = ref('original-collaboration')
const dirty = computed(() => templateDirty(detail.value?.template, draft.value))
const validation = computed(() => draft.value ? templateValidation(draft.value) : '')
const groups = computed(() => [{ title: '官方模板', items: registry.value.templates.filter(t => t.official) }, { title: '自定义模板', items: registry.value.templates.filter(t => !t.official) }])
function accept(value) { detail.value = value; draft.value = editableTemplate(value.template); conflict.value = false }
async function perform(action) {
  working.value = true; error.value = ''; message.value = ''
  try { await action() } catch (e) { conflict.value = e.response?.status === 409; error.value = e.response?.data?.message || e.message; if (dirty.value) error.value += '；草稿仍保留，请勿关闭页面。' }
  finally { working.value = false }
}
async function refreshList() { await perform(async () => { registry.value = await reading('/research-templates') }) }
function canDiscard() { return !dirty.value || confirm('存在尚未保存的模板草稿。放弃这些编辑？') }
async function select(id) { if (!canDiscard()) return; await perform(async () => { accept(await reading('/research-templates/' + encodeURIComponent(id))) }) }
async function reload() { if (detail.value) await select(detail.value.template.id) }
async function create(sourceId) {
  if (!canDiscard()) return
  await perform(async () => { accept(await reading('/research-templates', 'POST', { sourceId, name: copyName.value || undefined })); registry.value = await reading('/research-templates'); copyName.value = ''; message.value = '已创建自定义模板，可逐项修改。' })
}
async function save() {
  if (validation.value || detail.value.template.official) return
  await perform(async () => { accept(await reading('/research-templates/' + detail.value.template.id, 'PUT', { expectedVersion: detail.value.template.version, ...draft.value })); registry.value = await reading('/research-templates'); message.value = '已保存新修订，下次研究会使用最新版。' })
}
function resetSection(key) { draft.value.sections[key] = detail.value.initialSections[key] }
async function apply() {
  if (dirty.value) return
  await perform(async () => {
    if (sessionId) {
      const session = await reading('/sessions/' + encodeURIComponent(sessionId))
      await reading('/sessions/' + encodeURIComponent(sessionId) + '/settings', 'PUT', { ...session.settings, modeId: 'collaborative-research', researchTemplateId: detail.value.template.id })
    }
    await router.push({ path: '/reading', query: sessionId ? { sessionId } : { templateId: detail.value.template.id } })
  })
}
function back() { router.push({ path: '/reading', query: sessionId ? { sessionId } : {} }) }
function fixedFor(key) {
  const c = detail.value.effective.constraints
  if (key === 'reportGuidance') return JSON.stringify(c.reportSchema, null, 2)
  if (key === 'continuationGuidance') return c.controlProtocol + '\n\n异常时仅补救一次：\n' + c.actionRepairProtocol + '\n至少一轮；当前轮数上限 ' + c.maximumInvestigations + '，受总预算限制。'
  if (key === 'investigationGuidance') return c.tools + '\n' + c.safety
  if (key === 'investigatorInterests') return '每轮一次提交1–2个兴趣点，使用固定字段；模板内兴趣规则是唯一生效来源。\n' + c.safety
  if (key === 'collaborationGuidance') return '执行引擎：' + c.engine + '；初稿→调查→整合，至少一轮；模板不能添加执行阶段。'
  if (key === 'draftGuidance' || key === 'integrationGuidance') return '正文使用Markdown；协议记录与正文分开。\n' + c.safety
  return c.safety
}
function beforeUnload(event) { if (dirty.value) { event.preventDefault(); event.returnValue = '' } }
onBeforeRouteLeave(() => canDiscard())
onMounted(async () => {
  window.addEventListener('beforeunload', beforeUnload)
  await perform(async () => {
    registry.value = await reading('/research-templates')
    if (route.query.templateId) accept(await reading('/research-templates/' + encodeURIComponent(String(route.query.templateId))))
    else if (sessionId) accept(await reading('/sessions/' + encodeURIComponent(sessionId) + '/research-template'))
    else accept(await reading('/research-templates/' + registry.value.defaultId))
  })
  loading.value = false
})
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
</script>

<style scoped>
.template-workbench{min-height:100vh;background:#ffffff;color:#171717;padding:28px;font-family:Inter,"Microsoft YaHei",sans-serif}.workbench-header{display:flex;justify-content:space-between;align-items:center;gap:20px;max-width:1440px;margin:0 auto 24px}.eyebrow{font-size:11px;letter-spacing:2px;color:#737373}h1{font-size:26px;letter-spacing:-.5px;margin:10px 0}h2{font-size:17px;margin:0}h3{font-size:12px;color:#737373;margin:20px 0 10px}.workbench-header p,.muted{font-size:12px;color:#737373;line-height:1.8}.workbench-layout{max-width:1440px;margin:auto;display:grid;grid-template-columns:280px minmax(0,1fr);gap:24px}.template-list{background:#ffffff;border:1px solid #e5e5e5;padding:18px;border-radius:16px;align-self:start;position:sticky;top:20px;max-height:calc(100vh - 40px);overflow:auto}.list-title,.actions,.section-heading,.badges{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.list-title,.section-heading{justify-content:space-between}.template-item{display:flex;width:100%;flex-direction:column;align-items:flex-start;gap:8px;text-align:left;margin:8px 0;padding:14px;border-radius:10px}.template-item.selected{border-color:#171717;background:#ededed}.template-item span,.template-item small{font-size:11px;line-height:1.7;color:#737373}.create-box{border-top:1px solid #e5e5e5;margin-top:22px;padding-top:4px}.template-editor{min-width:0}.editor-heading,.section-card{background:#ffffff;border:1px solid #e5e5e5;border-radius:14px;padding:24px;margin-bottom:18px}.badges{margin-bottom:18px}.badges span{font-size:11px;padding:5px 9px;background:#eeeeee;border-radius:5px}.badges .draft-badge{background:#f7e7c8;color:#775e30}label{display:block;font-size:12px;margin:12px 0 6px}input,select,textarea{box-sizing:border-box;width:100%;padding:11px;border:1px solid #dddddd;border-radius:8px;background:white;color:#171717;font:inherit;font-size:13px;line-height:1.8}textarea{resize:vertical;font-family:"Microsoft YaHei",sans-serif}textarea[readonly],input:disabled{background:#f7f7f7}button{border:1px solid #dddddd;background:#ffffff;color:#171717;padding:9px 13px;border-radius:8px;cursor:pointer;font-size:12px}button.primary{background:#171717;border-color:#171717;color:white}button:not(:disabled):hover{background:#f0f0f0}button.primary:not(:disabled):hover{background:#333}button:focus-visible,input:focus-visible,textarea:focus-visible,select:focus-visible,summary:focus-visible{outline:2px solid #737373;outline-offset:3px}button:disabled{opacity:.5;cursor:not-allowed}.actions{margin-top:16px}.actions select{width:auto;max-width:100%}.create-box button{margin-top:14px;width:100%}.section-heading>div{display:flex;gap:12px;align-items:center}.section-number{font-size:13px;color:#999999}.character-count{text-align:right;font-size:11px;color:#888888}.section-card details{margin-top:12px;padding-top:12px;border-top:1px solid #eeeeee}summary{font-size:12px;cursor:pointer;line-height:1.8;color:#666666}pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px;line-height:1.9;max-height:380px;overflow:auto;background:#f7f7f7;padding:15px;border-radius:8px}code{overflow-wrap:anywhere;font-size:11px}.notice{max-width:1392px;margin:0 auto 18px;padding:16px 24px;background:#f3f3f3;border-radius:10px;font-size:13px;line-height:1.8}.notice button{margin-left:16px}.error{color:#9a4839}.notice.error{background:#f5e6dc}.empty-editor{padding:50px;background:#ffffff;border-radius:14px}@media(max-width:900px){.template-workbench{padding:16px}.workbench-layout{grid-template-columns:1fr}.template-list{position:static;max-height:360px}.workbench-header{align-items:flex-start}h1{font-size:23px}.editor-heading,.section-card{padding:18px}}
.template-guide{font-size:13px;line-height:1.9;margin-top:9px;padding:16px;background:#f7f7f7;border-radius:10px;font-weight:400;color:#444}.template-guide p{white-space:pre-line;margin-top:8px}
</style>

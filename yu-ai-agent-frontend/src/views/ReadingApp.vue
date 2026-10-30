<template>
  <div class="reading-app" @keydown.esc="inspectorOpen = false; libraryOpen = false">
    <aside class="library panel" :class="{ 'mobile-open': libraryOpen }" aria-label="书架与会话">
      <button class="library-close quiet" @click="libraryOpen = false" aria-label="关闭侧栏">关闭 ×</button>
      <div class="brand"><span class="brand-mark">阅</span><div><h1>阅读研究室</h1><p>从文本出发，让理解有据可循</p></div></div>
      <button class="primary wide" @click="newSession" :disabled="busy">＋ 开始新对话</button>
      <div class="section-label"><span>你的书架</span><button class="quiet" @click="reloadKnowledge">刷新</button></div>
      <label class="upload" :class="{ disabled: uploading }">
        <input type="file" multiple accept=".epub,.pdf,.doc,.docx,.txt,.md" :disabled="uploading" @change="uploadFiles" />
        <strong>{{ uploading ? '上传中…' : '＋ 上传书籍或文档' }}</strong><span>EPUB、PDF、Word、TXT、MD / 20 MB</span>
      </label>
      <p v-if="!knowledge.length" class="muted small">书架还没有资料。上传后将自动解析并建立索引。</p>
      <div class="books">
        <div v-for="kb in knowledge" :key="kb.id" class="book" :class="{ selected: settings.knowledgeBaseIds.includes(kb.id) }">
          <label><input type="checkbox" :value="kb.id" v-model="settings.knowledgeBaseIds" :disabled="busy || kb.status !== 'READY'" />
            <div><strong :title="kb.name">{{ kb.name }}</strong><span><b class="format">{{ kb.format }}</b> {{ labels[kb.status] || kb.status }}<template v-if="kb.status === 'READY'"> · {{ kb.chunkCount }} 个片段</template></span></div>
          </label>
          <p v-if="kb.error" class="book-error">{{ kb.error }}</p>
          <div class="book-actions"><button @click="rename(kb)" :disabled="busy">改名</button><button @click="rebuild(kb)" :disabled="processing(kb) || busy">{{ kb.status === 'FAILED' ? '重试' : '重建' }}</button><button @click="removeBook(kb)" :disabled="processing(kb) || busy">删除</button></div>
        </div>
      </div>
      <div class="section-label"><span>最近对话</span><span>{{ sessions.length }}</span></div>
      <div class="sessions"><div v-for="item in sessions" :key="item.id" class="session" :class="{ active: sessionId === item.id }">
        <button class="session-title" @click="selectSession(item)" :disabled="busy">{{ item.title }}</button><button class="quiet" title="删除会话" @click="removeSession(item)" :disabled="busy">×</button>
      </div></div>
      <div class="library-note">原文与索引保存在本机<br />只有你选中的资料参与检索</div>
    </aside>

    <main class="conversation">
      <header class="conversation-header"><button class="mobile-nav" @click="libraryOpen = true" aria-label="打开书架与会话">☰</button><div><div class="eyebrow">阅读助手</div><h2>{{ currentTitle }}</h2></div><span class="selection-count">{{ settings.knowledgeBaseIds.length }} 份资料 · {{ settings.modeId ? '研究模式' : '普通问答' }}</span><button class="mobile-settings" @click="inspectorOpen = true">设置与引用</button></header>
      <div v-if="error" class="error-banner" role="alert"><span>{{ error }}</span><button @click="error = ''">×</button></div>
      <div ref="chatArea" class="chat-area">
        <div v-if="!runs.length" class="welcome"><div class="welcome-icon">⌑</div><div class="eyebrow">每一次追问，都回到原文</div><h2>把书里的问题，继续问下去。</h2><p>在左侧选择一本或多本书，用普通问答理解文本，<br />或选择研究模式，沿着证据深入探索。</p><div class="suggestions"><button v-for="suggestion in suggestions" :key="suggestion" @click="question = suggestion">{{ suggestion }} ↗</button></div></div>
        <article v-for="run in runs" :key="run.id" class="exchange">
          <div class="question-row"><span class="speaker">你</span><div>{{ run.question }}</div></div>
          <div class="answer-row"><span class="assistant-mark">阅</span><div class="answer-body">
            <div class="answer-meta">阅读助手 <span>{{ runLabel(run) }}</span></div>
            <p v-if="researchTemplateLabel(run)" class="muted small">{{ researchTemplateLabel(run) }}</p>
            <p v-if="usageSummary(run)" class="muted small">{{ usageSummary(run) }}</p>
            <div v-if="run.answer" class="markdown" v-html="renderAnswer(run)" @click="citationClick($event, run)"></div>
            <div v-else-if="run.status === 'RUNNING'" class="working"><i></i> 正在查找和核对资料…</div>
            <p v-else class="muted">本次请求没有生成回答。</p>
            <div v-if="run.sources?.length" class="source-chips"><button v-for="source in run.sources" :key="source.id" @click="selectedSource = source">[{{ source.id }}] {{ source.title }} <span>{{ ['BOOK', 'FULL_BOOK'].includes(source.type) ? '原文' : '资料' }}</span></button></div>
            <InterestSelection :run="run" @updated="reloadRuns" />
            <ResearchTimeline v-if="hasCurrentTrace(run.events)" :run="run" @source="selectedSource = $event" />
            <details v-if="hasCurrentTrace(run.events)" class="trace"><summary>查看研究过程 · {{ toolCount(run) }} 次工具调用</summary><div v-for="(event, index) in run.events.filter(e => e.type.startsWith('tool') || ['phase', 'research_record', 'context', 'investigation', 'collaboration_validation', 'research_template', 'book_overview', 'history_summary', 'research_draft', 'interest_selection', 'answer_revision', 'model_output'].includes(e.type))" :key="index" class="trace-event"><strong>{{ traceLabel(event) }}</strong><p>{{ traceText(event) }}</p><details v-if="['research_draft', 'answer_revision'].includes(event.type)"><summary>查看完整解释</summary><details v-if="event.type === 'answer_revision'"><summary>对照上一版回答</summary><pre style="white-space: pre-wrap; overflow-wrap: anywhere">{{ previousAnswer(run, event) }}</pre></details><pre style="white-space: pre-wrap; overflow-wrap: anywhere">{{ event.data.answer }}</pre></details><details v-if="event.type === 'book_overview'"><summary>查看书籍概览</summary><pre style="white-space: pre-wrap; overflow-wrap: anywhere">{{ event.data.bookOverview }}</pre></details><details v-if="event.type === 'collaboration_validation' && event.data.rawOutput"><summary>查看模型公开输出{{ event.data.outputTruncated ? '（记录已裁剪）' : '' }}</summary><pre style="white-space: pre-wrap; overflow-wrap: anywhere">{{ event.data.rawOutput }}</pre></details><button v-for="source in event.data.sources || []" :key="source.id" class="quiet" @click="selectedSource = source">[{{ source.id }}] {{ source.location }}</button></div></details>
          </div></div>
        </article>
      </div>
      <div class="composer-wrap">
        <div class="composer"><textarea v-model="question" rows="3" placeholder="提出你的阅读问题… Enter 发送，Shift + Enter 换行" :disabled="busy" @keydown.enter.exact.prevent="send" /><div class="composer-bottom"><span>{{ settings.modeId ? '多轮检索与证据核对' : '从选中的原文中寻找回答' }} · {{ question.length.toLocaleString() }} / 10,000 字符</span><button v-if="busy" class="stop" @click="stop" :disabled="!activeRun?.id || activeRun.id === 'pending'">■ 停止生成</button><button v-else class="primary" @click="send" :disabled="!question.trim() || !settings.knowledgeBaseIds.length">发送 ↑</button></div></div><p class="composer-note">全文超限时会报错，不自动截断。回答可能存在误读，请核对原文。</p>
      </div>
    </main>

    <button v-if="inspectorOpen || libraryOpen" class="drawer-backdrop" aria-label="关闭侧栏" @click="inspectorOpen = false; libraryOpen = false"></button>
    <aside aria-label="研究设置与证据原文" class="inspector panel" :class="{ 'mobile-open': inspectorOpen }">
      <div class="section-label"><span>研究设置</span><button class="mobile-settings quiet" @click="inspectorOpen = false">关闭</button></div>
      <label class="field-label">回答模式<select v-model="settings.modeId" :disabled="busy"><option value="">普通问答</option><option v-if="retiredModeName" :value="settings.modeId" disabled>{{ retiredModeName }}已移除，请重新选择</option><option v-for="mode in modes" :key="mode.id" :value="mode.id">{{ mode.name }}</option></select></label>
      <label v-if="settings.modeId === 'collaborative-research'" class="field-label">兴趣点选择方式<select v-model="settings.interestSelectionMode" :disabled="busy"><option value="AUTO">自动选择</option><option value="MANUAL">我来选择兴趣点</option></select><span class="muted small">{{ settings.interestSelectionMode === 'MANUAL' ? '每轮提出四个候选，等你确认两个后才开始检索。' : '模型自动选择一至两个兴趣点，直接开展调查。' }}</span></label>
      <div v-if="settings.modeId === 'collaborative-research'">
        <label class="field-label">研究模板<select v-model="settings.researchTemplateId" :disabled="busy"><option value="">主线发展（默认）</option><option v-if="settings.researchTemplateId && !templates.some(t => t.id === settings.researchTemplateId)" :value="settings.researchTemplateId" disabled>当前模板缺失，请重新选择</option><option v-for="item in templates" :key="item.id" :value="item.id">{{ item.name }} · v{{ item.version }}</option></select></label>
        <p class="muted small template-guide">{{ selectedTemplate?.description || '在模板工作台查看、复制和编辑八个研究环节。' }}</p>
        <button class="quiet" :disabled="busy" @click="openTemplates">查看与编辑研究模板 ↗</button>
        <p v-for="item in templateErrors" :key="item.id" class="book-error">{{ item.id }}：{{ item.error }}</p>
      </div>
      <button class="save-settings" @click="saveSettings" :disabled="busy || !sessionId">保存会话设置</button><p class="muted small">切换在下一条消息生效。历史回答与引用保留。</p>
      <div class="section-label"><span>证据原文</span><button v-if="selectedSource" class="quiet" @click="selectedSource = null">关闭</button></div>
      <div v-if="selectedSource" class="source-detail"><div class="eyebrow">{{ ['BOOK', 'FULL_BOOK'].includes(selectedSource.type) ? '书籍原文' : selectedSource.type === 'WEB_SEARCH' ? '网页搜索摘要' : '资料片段' }} · {{ selectedSource.id }}</div><h3>{{ selectedSource.title }}</h3><p class="source-location">{{ selectedSource.location }}</p><a v-if="safeUrl(selectedSource.url)" :href="selectedSource.url" target="_blank" rel="noopener noreferrer" class="external-link">打开原网页 ↗</a><pre>{{ selectedSource.text }}</pre></div>
      <div v-else class="source-empty">点击回答中的引用或来源卡片，<br />在这里查看对应文本。</div>
    </aside>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { researchRunLabel } from '../utils/researchCompletion'
import { hasCurrentTrace, hasOriginalEvidence, researchDecisionLabel } from '../utils/researchTimeline'
import { useRoute, useRouter } from 'vue-router'
import { renderReadingMarkdown } from '../utils/readingCitations'
import DOMPurify from 'dompurify'
import { reading, streamReading } from '../api/reading'
import ResearchTimeline from '../components/ResearchTimeline.vue'
import InterestSelection from '../components/InterestSelection.vue'

const knowledge = ref([]), sessions = ref([]), runs = ref([]), modes = ref([]), modeErrors = ref([])
const templates = ref([]), templateErrors = ref([])
const route = useRoute(), router = useRouter()
const sessionId = ref(''), question = ref(''), error = ref(''), sending = ref(false), uploading = ref(false)
const selectedSource = ref(null), chatArea = ref(null)
const inspectorOpen = ref(false), libraryOpen = ref(false)
watch(selectedSource, value => { if (value) inspectorOpen.value = true })
const retiredModeNames = { 'deep-research': '深度研究', 'compare-views': '观点比较', 'structure-outline': '结构梳理', 'general-research': '通用研究' }
const retiredModeName = computed(() => retiredModeNames[settings.value.modeId] || '')
const settings = ref({ knowledgeBaseIds: [], modeId: '', researchTemplateId: '', interestSelectionMode: 'AUTO' })
const selectedTemplate = computed(() => templates.value.find(t => t.id === (settings.value.researchTemplateId || 'mainline-development')))
const labels = { PENDING: '待处理', PARSING: '解析中', EMBEDDING: '向量化中', READY: '已入库', FAILED: '失败' }
const toolLabels = { selectInterests: '选择兴趣点与假设', searchKnowledge: '知识库检索', searchPassages: '混合检索', searchExactText: '精确文字搜索', readPassage: '展开原文' }
const phaseLabels = { RESEARCHER: '主 Agent 更新判断', INVESTIGATOR: '副 Agent 调查原文' }
function traceLabel(event) { return event.type.startsWith('tool') ? `${event.type === 'tool_call' ? '调用' : '结果'} · ${toolLabels[event.data.tool] || event.data.tool}` : ({ research_template: '实际研究模板', research_draft: '主 Agent 完整初稿', book_overview: '书籍概览', history_summary: '对话历史记忆', interest_selection: '副 Agent 选择兴趣点', answer_revision: '主 Agent 回答变化', investigation: '调查发现', collaboration_validation: '协作协议校验', model_output: '模型输出诊断', context: '上下文预算', phase: phaseLabels[event.data.phase] || '研究阶段', research_record: '覆盖与研究记录' })[event.type] || event.type }
function traceText(event) {
  const d = event.data
  switch (event.type) {
    case 'research_template': return d.name + ' · v' + d.version + ' · ' + d.engine + '\n内容摘要：' + d.digest
    case 'model_output': return [d.phase, '请求格式：' + d.requestedFormat, '结束原因：' + d.finishReason, '输出 ' + d.outputCharacters + ' 字符 / ' + (d.outputTokens ?? '未知') + ' Token', '单次输出上限 ' + d.maxOutputTokens + ' Token'].join(' · ')
    case 'history_summary': return (({ BUFFERED: '未达阈值，保留摘要与新问答', COMPRESSED: '历史已整体压缩', FALLBACK: '压缩失败，使用有限历史回退', READY: '历史摘要已准备' })[d.status] || d.status) + ' · 本次摘要调用 ' + d.calls + ' 次 · 约 ' + (d.estimatedTokens ?? '未知') + ' Token' + (d.thresholdTokens ? ' · 触发阈值 ' + d.thresholdTokens + ' / 摘要上限 ' + d.targetTokens : '') + (d.counting ? '\n计数：' + d.counting : '') + (d.reason ? '\n' + d.reason : '')
    case 'book_overview': return '与初稿在同一次调用中生成 · ' + d.characters + ' 字符 · 估算 ' + d.estimatedTokens + ' Token；仅作修订背景，不是引用证据。'
    case 'research_draft': return '完整初稿已生成，副 Agent 将直接评议这份回答。'
    case 'interest_selection': return d.points.map(p => `${p.id}\n原句：${p.quote}\n选择理由：${p.reason}\n追问：${p.question}\n暂定假设：${p.hypothesis || '未提出新假设'}\n混合查询：${p.query}`).join('\n\n')
    case 'answer_revision': return '完整回答已更新；主智能体判断：' + researchDecisionLabel(d) + (d.executionIssue ? '\n执行说明：' + d.executionIssue : '') + (d.reason ? '\n' + d.reason : '')
    case 'investigation': return (d.report.findings || []).map(f => `${f.pointId} · ${f.id} · ${({ SUPPORT: '支持材料', COUNTER: '反向材料', CONDITION: '成立条件', NEW: '新发现' })[f.kind] || f.kind}\n${f.statement} [${f.sourceIds.join('][')}]`).join('\n\n') + '\n待查：' + ((d.report.gaps || []).join('；') || '无')
    case 'collaboration_validation': return (d.role || '') + ' · ' + (d.attempt ? '第 ' + d.attempt + ' 次 · ' : '') + (({ NORMALIZED: '输出格式已规范化', INVALID: '校验失败', REPAIRING: '修复中', VALID: '修复通过', FAILED: '修复失败' })[d.status] || '') + '\n' + (d.error || d.message || '')
    case 'context': return `${({ PREFLIGHT: '输入检查', COMPACT: '整理上下文', SYNTHESIS: '准备汇总' })[d.action] || d.action} · ${d.characters}/${d.characterLimit} 字符 · 估算 ${d.estimatedTokens}/${d.inputTokenLimit} Token · 预留 ${d.reservedTokens} Token${d.omittedSourceIds?.length ? ' · 暂移出原文：' + d.omittedSourceIds.join('、') : ''}`
    case 'research_record': return '模型调用 ' + d.modelCalls + ' 次；局部调查 ' + d.investigations.length + ' 轮；' + (d.complete ? '已结束研究' : '部分完成') + '。全书已提供主 Agent，局部复核覆盖与全文输入分别记录。' + (d.stopReason ? '\n结束原因：' + d.stopReason : '')
    case 'tool_result':
      if (!d.error && d.tool === 'selectInterests') return (d.data?.points?.length || 0) + (d.data?.evidence ? ' 个兴趣点，原文见混合检索记录' : ' 个兴趣点，候选见混合检索记录')
      if (!d.error && ['searchPassages', 'searchExactText'].includes(d.tool)) {
        if (hasOriginalEvidence(d)) return '直接召回 ' + (d.data.passages?.length || 0) + ' 个原文分片 · 新增 ' + d.data.newCharacters + ' 字符'
        const hits = Array.isArray(d.data) ? d.data : d.data?.hits || []
        return `${d.data?.trigger || '搜索结果'} · ${hits.length} 个候选位置（需读取核对）\n` + hits.map(h => `字符 ${h.start}–${h.end}\n${h.text}`).join('\n')
      }
  }
  return d.query || d.error || d.message || `${d.sources?.length || 0} 条原文来源`
}
const suggestions = ['这本书的核心论证是什么？请提供原文依据。', '作者如何定义核心概念？它在不同章节中有变化吗？', '这些书对同一个问题有哪些共同点和分歧？']
const currentTitle = computed(() => sessions.value.find(s => s.id === sessionId.value)?.title || '新的阅读对话')
const activeRun = computed(() => runs.value.find(r => ['RUNNING', 'WAITING_FOR_SELECTION'].includes(r.status)))
const busy = computed(() => sending.value || !!activeRun.value)
const modeDescription = computed(() => modes.value.find(m => m.id === settings.value.modeId)?.description || '从选中的知识库检索一次并回答。')
let poll, controller, disposed = false

function fail(e) { error.value = e.response?.data?.message || e.response?.data?.detail || e.message || '操作失败' }
async function attempt(fn) { try { return await fn() } catch (e) { fail(e) } }
function processing(kb) { return ['PENDING', 'PARSING', 'EMBEDDING'].includes(kb.status) }
function safeUrl(url) { try { return ['http:', 'https:'].includes(new URL(url).protocol) } catch { return false } }
function renderAnswer(run) {
  return DOMPurify.sanitize(renderReadingMarkdown(run.answer, run.sources || []))
}
function citationClick(event, run) {
  const link = event.target.closest('a')
  if (link?.getAttribute('href')?.startsWith('#source-')) {
    event.preventDefault()
    selectedSource.value = run.sources.find(s => `#source-${s.id}` === link.getAttribute('href')) || null
  } else if (link) { event.preventDefault(); if (safeUrl(link.href)) window.open(link.href, '_blank', 'noopener,noreferrer') }
}
function runLabel(run) { return researchRunLabel(run) }
function usageSummary(run) {
  const rows = (run.events || []).filter(e => e.type === 'usage').map(e => e.data)
  if (!rows.length) return ''
  const sum = key => rows.reduce((n, r) => n + (Number(r[key]) || 0), 0).toLocaleString()
  const known = key => rows.every(r => typeof r[key] === 'number' && r[key] >= 0)
  return `${rows.length} 次调用 · 输入 ${known('promptTokens') ? sum('promptTokens') : '未知'} / 输出 ${known('completionTokens') ? sum('completionTokens') : '未知'} Token · 缓存命中 ${known('cacheHitTokens') ? sum('cacheHitTokens') : '未知'} / 未命中 ${known('cacheMissTokens') ? sum('cacheMissTokens') : '未知'} · 推理 ${known('reasoningTokens') ? sum('reasoningTokens') : '未知'}`
}
function previousAnswer(run, event) { return run.events.slice(0, run.events.indexOf(event)).filter(e => ['research_draft', 'answer_revision'].includes(e.type)).at(-1)?.data.answer || '没有上一版回答' }
function toolCount(run) { return run.events.filter(e => e.type === 'tool_call').length }
async function scroll() { await nextTick(); if (chatArea.value) chatArea.value.scrollTop = chatArea.value.scrollHeight }
async function reloadKnowledge() { return attempt(async () => { knowledge.value = await reading('/knowledge-bases') }) }
async function reloadSessions() { sessions.value = await reading('/sessions') }
async function newSession() { await attempt(async () => { const item = await reading('/sessions', 'POST'); await reloadSessions(); await selectSession(item) }) }
async function selectSession(item) {
  if (sending.value) return
  await attempt(async () => { sessionId.value = item.id; settings.value = { modeId: item.settings.modeId || '', researchTemplateId: item.settings.researchTemplateId || '', interestSelectionMode: item.settings.interestSelectionMode || 'AUTO', knowledgeBaseIds: [...item.settings.knowledgeBaseIds] }; selectedSource.value = null; runs.value = await reading(`/sessions/${item.id}/messages`); await scroll() })
}
async function removeSession(item) {
  if (!confirm(`删除对话“${item.title}”及其记录？`)) return
  await attempt(async () => { await reading(`/sessions/${item.id}`, 'DELETE'); await reloadSessions(); if (sessionId.value === item.id) { runs.value = []; selectedSource.value = null; sessionId.value = ''; if (sessions.value.length) await selectSession(sessions.value[0]) } })
}
async function uploadFiles(event) {
  const files = [...event.target.files]; uploading.value = true; error.value = ''
  try { for (const file of files) { const data = new FormData(); data.append('file', file); await reading('/knowledge-bases', 'POST', data) } } catch (e) { fail(e) }
  finally { uploading.value = false; event.target.value = ''; await reloadKnowledge() }
}
async function rename(kb) { const name = prompt('知识库名称', kb.name); if (name?.trim()) await attempt(async () => { await reading(`/knowledge-bases/${kb.id}`, 'PATCH', { name }); await reloadKnowledge() }) }
async function rebuild(kb) { if (kb.status === 'READY' && !confirm('重新解析并调用向量模型建立索引？')) return; await attempt(async () => { await reading(`/knowledge-bases/${kb.id}/rebuild`, 'POST'); await reloadKnowledge() }) }
async function removeBook(kb) { if (!confirm(`删除“${kb.name}”的原文件和索引？历史引用仍然保留。`)) return; await attempt(async () => { await reading(`/knowledge-bases/${kb.id}`, 'DELETE'); settings.value.knowledgeBaseIds = settings.value.knowledgeBaseIds.filter(id => id !== kb.id); await reloadKnowledge() }) }
async function saveSettings() { return attempt(async () => { await reading('/sessions/' + sessionId.value + '/settings', 'PUT', settings.value); await reloadSessions(); const saved = sessions.value.find(s => s.id === sessionId.value); if (saved) settings.value = { ...saved.settings, knowledgeBaseIds: [...saved.settings.knowledgeBaseIds] }; templates.value = (await reading('/research-templates')).templates }) }
async function openTemplates() { await attempt(async () => { if (sessionId.value) { await reading('/sessions/' + sessionId.value + '/settings', 'PUT', settings.value); await reloadSessions() } if (sessionId.value) sessionStorage.setItem('reading-template-draft:' + sessionId.value, question.value); await router.push({ path: '/reading/templates', query: { ...(sessionId.value ? { sessionId: sessionId.value } : {}) } }) }) }
function researchTemplateLabel(run) { const entry = (run.events || []).find(e => e.type === 'research_template'); if (entry) return '研究模板：' + entry.data.name + ' · v' + entry.data.version; return '' }
async function send() {
  if (question.value.length > 10000) { error.value = '提问文本最多 10,000 字符，请缩短后再发送。'; return }
  if (busy.value || !question.value.trim() || !settings.value.knowledgeBaseIds.length) return
  error.value = ''
  if (retiredModeName.value) { error.value = `${retiredModeName.value}已移除，请在回答模式中重新选择。历史回答与引用仍可查看。`; return }
  if (settings.value.modeId === 'collaborative-research' && !settings.value.knowledgeBaseIds.length) { error.value = '协作研究需要选择已入库的知识库'; return }
  if (!sessionId.value) { const saved = { ...settings.value, knowledgeBaseIds: [...settings.value.knowledgeBaseIds] }; await newSession(); if (!sessionId.value) return; settings.value = saved }
  const message = question.value; question.value = ''; sending.value = true; selectedSource.value = null
  const run = { id: 'pending', question: message, answer: '', status: 'RUNNING', sources: [], events: [] }
  runs.value.push(run); await scroll(); controller = new AbortController()
  try {
    if (sessionId.value) sessionStorage.removeItem('reading-template-draft:' + sessionId.value)
    await streamReading(sessionId.value, { message, settings: settings.value }, (type, data) => {
      if (disposed) return
      const current = runs.value[runs.value.length - 1]
      if (type === 'research_template') { settings.value.researchTemplateId = data.id }
      if (['phase', 'research_record', 'usage', 'context', 'investigation', 'collaboration_validation', 'research_template', 'book_overview', 'history_summary', 'research_draft', 'interest_selection', 'interest_candidates', 'interest_choice', 'answer_revision', 'model_output', 'model_call'].includes(type)) current.events.push({ type, data }); else if (type === 'status') { current.id = data.runId; current.status = data.status || current.status; current.events.push({ type, data });  }
      else if (type.startsWith('tool')) { current.events.push({ type, data }); if (data.sources) for (const source of data.sources) if (!current.sources.some(s => s.id === source.id)) current.sources.push(source) }
      else if (type === 'citations') current.sources = data
      else if (type === 'answer') current.answer = data
      else if (type === 'complete' || type === 'paused') current.status = data.status
      else if (type === 'error') error.value = data
      scroll()
    }, controller.signal)
  } catch (e) { if (e.name !== 'AbortError') fail(e) }
  finally {
    sending.value = false
    if (!disposed) await attempt(async () => { runs.value = await reading(`/sessions/${sessionId.value}/messages`); await reloadSessions(); await scroll() })
  }
}
async function reloadRuns() {
  const id = sessionId.value
  if (id) await attempt(async () => { const saved = await reading(`/sessions/${id}/messages`); if (sessionId.value === id) runs.value = saved })
}
async function stop() { if (activeRun.value?.id && activeRun.value.id !== 'pending') await attempt(() => reading(`/runs/${activeRun.value.id}/stop`, 'POST')) }
onMounted(async () => {
  await attempt(async () => {
    const [books, chats, registry, templateRegistry] = await Promise.all([reading('/knowledge-bases'), reading('/sessions'), reading('/modes'), reading('/research-templates')])
    knowledge.value = books; sessions.value = chats; modes.value = registry.modes; modeErrors.value = registry.errors; templates.value = templateRegistry.templates; templateErrors.value = templateRegistry.errors
    if (route.query.sessionId) { const target = chats.find(s => s.id === String(route.query.sessionId)); if (target) { await selectSession(target); question.value = sessionStorage.getItem('reading-template-draft:' + sessionId.value) || '' } else throw new Error('返回的会话不存在') } else if (route.query.templateId) { settings.value.modeId = 'collaborative-research'; settings.value.researchTemplateId = String(route.query.templateId) } else if (chats.length) await selectSession(chats[0])
  })
  if (error.value) error.value += '；请确认后端已启用 reading 配置并连接 PostgreSQL＋pgvector。'
  poll = setInterval(async () => { if (disposed) return; if (knowledge.value.some(processing)) await reloadKnowledge(); if (activeRun.value && !sending.value) await attempt(async () => { runs.value = await reading(`/sessions/${sessionId.value}/messages`); await reloadSessions() }) }, 2500)
})
onBeforeUnmount(() => { disposed = true; clearInterval(poll); controller?.abort() })
</script>

<style scoped>
.reading-app{--ink:#171717;--line:#e8e8e8;--muted:#737373;display:grid;grid-template-columns:260px minmax(0,1fr);height:100dvh;background:#fff;color:var(--ink);font-size:14px}
button{border:1px solid var(--line);background:#fff;color:var(--ink);border-radius:10px;padding:9px 13px;font:inherit;font-size:13px;transition:background .15s}
button:not(:disabled):hover{background:#ececec}button:disabled{opacity:.45;cursor:not-allowed}button:focus-visible,a:focus-visible,input:focus-visible,select:focus-visible,textarea:focus-visible,summary:focus-visible{outline:2px solid #737373;outline-offset:3px}
.panel{overflow:auto;padding:22px 18px}.library{background:#f9f9f9;display:flex;flex-direction:column;gap:15px}.library>*{flex-shrink:0}.back{font-size:12px;color:var(--muted)}
.brand{display:flex;align-items:center;gap:11px;margin:10px 0 14px}.brand-mark,.assistant-mark{display:grid;place-items:center;background:#171717;color:#fff;border-radius:50%;width:34px;height:34px;font-size:17px;flex-shrink:0}.brand h1{font-size:17px;font-weight:600;letter-spacing:0}.brand p{font-size:11px;color:var(--muted);margin-top:5px}
.primary{background:#171717;border-color:#171717;color:white;padding:11px 16px}.primary:not(:disabled):hover{background:#353535}.wide{width:100%;text-align:left}.quiet{border:0;background:transparent;padding:4px;color:var(--muted);font-size:12px}.section-label{display:flex;justify-content:space-between;align-items:center;font-size:12px;font-weight:600;color:#666;margin-top:10px}
.upload{display:flex;flex-direction:column;gap:8px;padding:18px 10px;border:1px dashed #d4d4d4;border-radius:12px;text-align:center;cursor:pointer;background:transparent}.upload:hover{background:#f0f0f0}.upload input{display:none}.upload strong{font-size:12px;font-weight:500}.upload span{font-size:10px;color:var(--muted)}.disabled{opacity:.5;pointer-events:none}.muted{color:var(--muted);line-height:1.8}.small{font-size:12px}.template-guide{white-space:pre-line;padding:12px 0;line-height:1.9}
.books{display:flex;flex-direction:column;gap:6px}.book{border:1px solid transparent;border-radius:10px;padding:11px;background:transparent}.book.selected{background:#ebebeb;border-color:#e5e5e5}.book label{display:flex;gap:10px;align-items:flex-start;cursor:pointer}.book input{margin-top:4px;accent-color:#171717}.book label div{min-width:0}.book strong{display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden;font-size:12px;line-height:1.7;font-weight:500;overflow-wrap:anywhere}.book label span{display:block;font-size:10px;color:var(--muted);margin-top:4px}.format{text-transform:uppercase;font-weight:400}.book-actions{display:flex;gap:12px;margin:8px 0 0 24px}.book-actions button{border:0;background:none;font-size:11px;padding:0;color:#737373}.book-error{color:#b42318;font-size:12px;overflow-wrap:anywhere;line-height:1.7}
.sessions{display:flex;flex-direction:column;gap:3px;max-height:240px;overflow:auto}.session{display:flex;border-radius:10px;flex-shrink:0;min-width:0}.session.active{background:#e9e9e9}.session-title{flex:1;min-width:0;text-align:left;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;border:0;background:transparent;padding:11px;font-size:12px}.library-note{margin-top:auto;padding-top:20px;color:#8a8a8a;font-size:11px;line-height:1.9}
.conversation{min-height:0;min-width:0;display:flex;flex-direction:column}.conversation-header{min-height:76px;padding:16px 28px;display:flex;align-items:center;gap:16px;background:#ffffffed}.conversation-header>div{min-width:0;flex:1}.eyebrow{font-size:11px;color:#8a8a8a;letter-spacing:.4px}.conversation-header h2{font-size:16px;font-weight:500;margin-top:5px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.selection-count{font-size:12px;color:var(--muted);white-space:nowrap}.mobile-settings{display:block;border-color:#e5e5e5;border-radius:20px;background:#fff;font-size:12px;white-space:nowrap}.mobile-nav,.library-close{display:none}
.error-banner{background:#fff4f2;color:#a12b20;display:flex;align-items:center;justify-content:space-between;padding:11px 24px;font-size:12px;line-height:1.7}.error-banner button{border:0;background:transparent}.chat-area{overflow:auto;flex:1;scrollbar-gutter:stable;padding:28px 32px 12px}.welcome{max-width:740px;margin:clamp(40px,14vh,180px) auto 0}.welcome-icon{display:none}.welcome .eyebrow{display:none}.welcome h2{font-family:inherit;font-size:32px;font-weight:600;letter-spacing:-.8px;line-height:1.5;margin:16px 0}.welcome p{font-size:15px;line-height:1.9;color:var(--muted)}.suggestions{display:flex;flex-wrap:wrap;gap:10px;margin-top:28px}.suggestions button{font-size:12px;padding:12px 14px;background:white;border-color:#e5e5e5;text-align:left;border-radius:14px;line-height:1.6}
.exchange{max-width:760px;margin:0 auto 42px}.question-row{display:flex;justify-content:flex-end;gap:0;margin:0 0 30px}.speaker{display:none}.question-row>div{max-width:85%;padding:12px 20px;background:#f4f4f4;border-radius:22px;line-height:1.8;white-space:pre-wrap;overflow-wrap:anywhere;font-size:15px}.answer-row{display:flex;gap:14px}.assistant-mark{width:27px;height:27px;font-size:14px;margin-top:2px}.answer-body{min-width:0;flex:1}.answer-meta{font-size:13px;margin:5px 0 14px;font-weight:600}.answer-meta span{font-size:11px;font-weight:400;color:#8a8a8a;margin-left:10px}.markdown{font-size:16px;line-height:1.9;overflow-wrap:anywhere}.markdown :deep(p){margin:12px 0}.markdown :deep(h1),.markdown :deep(h2),.markdown :deep(h3){margin:24px 0 12px;font-size:20px;line-height:1.6}.markdown :deep(ul),.markdown :deep(ol){padding-left:24px}.markdown :deep(a){color:#333;text-decoration:underline;text-underline-offset:3px}.markdown :deep(pre){overflow:auto;padding:16px;background:#f7f7f7;border:1px solid #ededed;border-radius:12px;font-size:13px}.markdown :deep(blockquote){border-left:3px solid #d4d4d4;padding-left:16px;color:#666}.markdown :deep(table){border-collapse:collapse;display:block;overflow:auto}.markdown :deep(td),.markdown :deep(th){border-bottom:1px solid var(--line);padding:10px;text-align:left}
.source-chips{display:flex;flex-wrap:wrap;gap:6px;margin-top:20px}.source-chips button{font-size:11px;background:#fafafa;padding:6px 10px;border-radius:18px;max-width:100%;text-align:left;overflow-wrap:anywhere}.source-chips span{color:#8a8a8a}.trace{margin-top:20px;font-size:12px;color:#737373}.trace summary{cursor:pointer}.trace-event{margin-top:10px;background:#fafafa;border:1px solid #eee;padding:12px;border-radius:10px;overflow-wrap:anywhere}.trace-event p{margin:6px 0;line-height:1.8}.working{color:#737373;display:flex;align-items:center;gap:9px;font-size:13px}.working i{width:7px;height:7px;background:#737373;border-radius:50%;animation:pulse 1.2s infinite}@keyframes pulse{50%{opacity:.25}}
.composer-wrap{width:100%;max-width:824px;align-self:center;padding:14px 32px 18px}.composer{border:1px solid #e7e7e7;background:#f4f4f4;border-radius:26px;padding:17px 18px 13px;box-shadow:0 2px 8px #00000004}.composer:focus-within{border-color:#c9c9c9}.composer textarea{resize:none;width:100%;border:0;outline:0;font:inherit;font-size:15px;color:var(--ink);line-height:1.7;background:transparent}.composer textarea::placeholder{color:#888}.composer-bottom{display:flex;justify-content:space-between;align-items:center;gap:12px;margin-top:7px}.composer-bottom span,.composer-note{font-size:11px;color:#8a8a8a}.composer-bottom .primary{border-radius:24px;min-width:72px;padding:10px 15px}.composer-note{text-align:center;margin-top:10px;line-height:1.6}.stop{color:#a12b20}
.drawer-backdrop{position:fixed;inset:0;background:#0003;border:0;border-radius:0;z-index:20;padding:0}.drawer-backdrop:not(:disabled):hover{background:#0003}.inspector{display:none}.inspector.mobile-open{display:flex;position:fixed;right:0;top:0;flex-direction:column;gap:17px;width:340px;max-width:92vw;height:100dvh;z-index:21;background:white;border-left:1px solid var(--line);box-shadow:-8px 0 36px #0000000d;padding:24px;overflow:auto}.field-label{display:flex;flex-direction:column;gap:9px;font-size:12px;color:#666}.inspector select,.prompt-input{width:100%;border:1px solid #ddd;border-radius:10px;background:#fff;padding:11px;font:inherit;font-size:13px;color:var(--ink)}.save-settings{background:#171717;color:white}.save-settings:not(:disabled):hover{background:#333}.source-detail h3{font-size:15px;font-weight:600;margin:12px 0;line-height:1.6;overflow-wrap:anywhere}.source-location{font-size:12px;color:#777;margin-bottom:10px}.external-link{font-size:12px;color:#333;text-decoration:underline}.source-detail pre{white-space:pre-wrap;overflow-wrap:anywhere;font-family:inherit;font-size:13px;line-height:1.9;margin-top:14px;padding:15px;background:#f7f7f7;border-radius:12px;max-height:440px;overflow:auto}.source-empty{color:#888;font-size:12px;line-height:1.9;padding:15px 0}
@media(max-width:900px){.reading-app{grid-template-columns:220px minmax(0,1fr)}.panel{padding:20px 14px}.selection-count{display:none}.conversation-header{padding:16px 22px}.chat-area{padding:24px}.composer-wrap{padding:12px 24px 16px}.welcome h2{font-size:28px}}
@media(max-width:650px){.reading-app{display:flex;height:100dvh}.library{display:none}.library.mobile-open{display:flex;position:fixed;left:0;top:0;width:280px;max-width:86vw;height:100dvh;z-index:22;padding:20px}.library-close{display:block;align-self:flex-end}.mobile-nav{display:block;border:0;background:transparent;font-size:20px;padding:6px}.conversation{width:100%;height:100dvh}.conversation-header{min-height:68px;padding:12px 14px;gap:10px}.conversation-header .eyebrow{display:none}.conversation-header h2{margin:0;font-size:15px}.mobile-settings{font-size:11px;padding:8px 10px}.chat-area{padding:20px 16px}.welcome{margin-top:12vh}.welcome h2{font-size:26px}.welcome p{font-size:14px}.suggestions{flex-direction:column;align-items:flex-start}.question-row>div{max-width:92%;font-size:14px}.answer-row{gap:10px}.markdown{font-size:15px}.composer-wrap{padding:10px 12px max(12px,env(safe-area-inset-bottom))}.composer{border-radius:22px;padding:14px}.composer-bottom span{font-size:10px}.composer-note{font-size:10px}}
@media(prefers-reduced-motion:reduce){button{transition:none}.working i{animation:none}}
</style>

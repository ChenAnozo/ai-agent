<template>
  <details v-if="rounds.length" class="research-timeline" open>
    <summary>副智能体调查 · {{ rounds.length }} 轮</summary>
    <section v-for="round in rounds" :key="round.number" class="round">
      <div class="round-heading"><strong>第 {{ round.number }} 轮</strong><span>{{ roundStatus(round) }}</span></div>
      <p v-if="round.retrievalMode" class="note">取证方式：{{ round.retrievalMode === 'BROAD' ? '直接召回原文' : '原文定位与展开' }}</p>
      <p class="counts">副智能体模型调用 {{ round.modelCalls }} 次 · 模型发起工具 {{ round.modelTools }} 次 · 自动检索 {{ round.automaticTools }} 次</p>
      <p class="note">模型调用包括工具决策、报告生成及报告修复；一次模型调用可发起多个工具。工具次数按发起次数统计，包含失败。</p>
      <article v-for="point in round.points" :key="point.id" class="interest">
        <strong>{{ point.id }} · 兴趣点</strong>
        <blockquote>{{ point.quote }}</blockquote>
        <dl><dt>选择理由</dt><dd>{{ point.reason }}</dd><dt>追问</dt><dd>{{ point.question }}</dd><dt>暂定假设</dt><dd>{{ point.hypothesis || '未提出' }}</dd><dt>初始 query</dt><dd class="query">{{ point.query }}</dd></dl>
      </article>
      <p v-if="!round.points.length" class="note">{{ waitingRound === round.number ? '候选已生成，等待你确认两个兴趣点。' : run.status === 'RUNNING' ? '正在选择兴趣点…' : '本轮未取得有效兴趣点' }}</p>
      <details v-if="round.operations.length" class="tools" open>
        <summary>工具时间线 · {{ round.operations.length }} 次发起</summary>
        <ol>
          <li v-for="op in round.operations" :key="op.key" :class="{ automatic: op.origin === 'AUTO', failed: op.result?.error }">
            <div class="operation-heading"><strong>{{ labels[op.tool] || op.tool }}</strong><span>{{ { MODEL: '模型发起', AUTO: '自动触发' }[op.origin] }}{{ op.step ? ' · 工具决策第 ' + op.step + ' 步' : '' }}</span></div>
            <p v-if="op.pointId">兴趣点：{{ op.pointId }}</p>
            <p v-else-if="op.origin === 'MODEL' && op.tool !== 'selectInterests'" class="note">本轮补查，未绑定单个兴趣点</p>
            <p v-if="op.parentOperationId" class="note">由 {{ op.parentOperationId }} 自动触发</p>
            <p v-if="query(op)" class="query">{{ toolArguments(op).semanticQuery ? '关键词查询：' : '' }}{{ query(op) }}</p>
            <p v-if="toolArguments(op).semanticQuery" class="query">语义查询：{{ toolArguments(op).semanticQuery }}</p>
            <p v-if="range(op)">{{ range(op) }}</p>
            <p v-if="op.result?.modelFeedbackCharacters != null" class="note">模型反馈 {{ op.result.modelFeedbackCharacters }} 字符 · 完整结果 {{ op.result.traceCharacters }} 字符</p>
            <p v-if="op.result?.error" class="error">执行失败：{{ op.result.error }}</p>
            <p v-else-if="!op.result" class="note">{{ run.status === 'WAITING_FOR_SELECTION' ? '等待你确认兴趣点…' : run.status === 'RUNNING' ? '等待工具结果…' : '未记录结果（可能已停止）' }}</p>
            <p v-else-if="op.result.data?.alreadyProvided" class="note">该范围已提供，无新增原文</p>
            <details v-if="['searchPassages', 'searchExactText'].includes(op.tool) && op.result && !op.result.error"><summary>{{ hits(op).length }} 个{{ hasOriginalEvidence(op.result) ? '原文分片' : '候选位置' }}</summary><div v-for="(hit, index) in hits(op)" :key="index" class="candidate"><p>字符 {{ hit.start }}–{{ hit.end }}<template v-if="op.tool === 'searchPassages'"> · BM25 排名 {{ hit.lexicalRank || '未命中' }} / 向量排名 {{ hit.vectorRank || '未命中' }}</template></p><p v-if="hasOriginalEvidence(op.result)">{{ hit.alreadyProvided ? '已提供，无新增正文' : '新增正文 ' + hit.newCharacters + ' 字符' }} · 来源 {{ (hit.sourceIds || []).join('、') }}</p><p v-else>{{ hit.text }}</p></div><p v-if="op.result.data?.stopReason" class="note">{{ op.result.data.stopReason }}</p></details>
            <button v-for="source in op.result?.sources || []" :key="source.id" @click="$emit('source', source)">[{{ source.id }}] {{ source.location }} · 查看原文</button>
            <details class="raw"><summary>完整工具参数与结果</summary><pre>{{ pretty({ operationId: op.operationId, arguments: toolArguments(op), result: op.result?.data, error: op.result?.error }) }}</pre></details>
          </li>
        </ol>
      </details>
      <details v-if="round.report" class="report"><summary>调查报告 · {{ statusLabels[round.report.status] || round.report.status || '调查发现' }}</summary>
        <div v-for="finding in round.report.findings || []" :key="finding.id">
          <p>{{ finding.pointId }} · {{ kinds[finding.kind] || finding.kind }}：{{ finding.statement }} <button v-for="id in finding.sourceIds || []" :key="id" @click="selectSource(id)">[{{ id }}]</button></p>
          <p v-if="finding.explanatoryImpact" class="note">解释影响：{{ finding.explanatoryImpact }}</p>
        </div>
        <p v-for="(gap, index) in round.report.gaps || []" :key="index">待查：{{ gap }}</p>
        <p v-if="round.report.stopReason">停止原因：{{ round.report.stopReason }}</p>
      </details>
      <p v-if="round.revision" class="note">主智能体判断：{{ researchDecisionLabel(round.revision) }}{{ round.revision.reason ? ' · ' + round.revision.reason : '' }}</p>
      <p v-if="round.revision?.executionIssue" class="note">执行说明：{{ round.revision.executionIssue }}</p>
      <p v-if="round.number === rounds.at(-1)?.number && executionRecord?.stopReason" class="note">实际执行：{{ executionRecord.status === 'LIMIT_REACHED' ? '达到执行限制' : '研究未完成' }} · {{ executionRecord.stopReason }}</p>
      <details v-if="!Object.hasOwn(round.revision || {}, 'decision') && round.revision?.researchState" class="report" open>
        <summary>本轮认识发展与继续依据</summary>
        <p>{{ stopReasons[round.revision.researchState.stopReason] || round.revision.researchState.stopReason }}</p>
        <p>{{ round.revision.researchState.reason }}</p>
        <p v-if="round.revision.researchState.nextInvestigation">下一调查：{{ round.revision.researchState.nextInvestigation }}</p>
        <p v-if="round.revision.researchState.expectedBenefit">预期增益：{{ round.revision.researchState.expectedBenefit }}</p>
        <p class="note">这是模型的公开研究判断；交付解释可以保留假设和未决问题，实际执行限制见研究记录。</p>
      </details>
    </section>
  </details>
</template>

<script setup>
import { computed } from 'vue'
import { investigationRounds, toolArguments, candidateHits, hasOriginalEvidence, pendingInterestSelection, researchDecisionLabel } from '../utils/researchTimeline'
const props = defineProps({ run: { type: Object, required: true } })
const emit = defineEmits(['source'])
const rounds = computed(() => investigationRounds(props.run.events))
const executionRecord = computed(() => (props.run.events || []).filter(e => e.type === 'research_record').at(-1)?.data)
const waitingRound = computed(() => pendingInterestSelection(props.run)?.round)
const labels = { selectInterests: '选择兴趣点', searchPassages: '混合检索', searchExactText: '精确文字搜索', readPassage: '展开原文' }
const kinds = { SUPPORT: '支持材料', COUNTER: '反向材料', CONDITION: '成立条件', NEW: '新发现' }
const statusLabels = { VALID: '已通过校验', PARTIAL: '部分完成', INCOMPLETE: '未完成' }
const stopReasons = { RESPONSE_READY: '本轮解释可交付', CONTINUE: '例外追加调查', EVIDENCE_LIMIT: '关键事实或结论受资料限制' }
function roundStatus(round) { if (waitingRound.value === round.number) return '等待你选择'; return round.revision ? '已更新回答' : round.report ? '调查已交付' : props.run.status === 'RUNNING' ? '调查中' : '已停止' }
function query(op) { const args = toolArguments(op); return args.query || args.text || op.result?.data?.query || op.query || '' }
function range(op) { const data = op.result?.data || {}; const args = toolArguments(op); return data.start != null && data.end != null ? `实际读取字符 ${data.start}–${data.end}` : args.start != null ? `请求起点 ${args.start}${args.end != null ? '，终点 ' + args.end : ''}${args.length != null ? '，长度 ' + args.length : ''}` : '' }
function hits(op) { return candidateHits(op.result) }
function pretty(value) { return JSON.stringify(value, null, 2) }
function selectSource(id) { const source = (props.run.sources || []).find(s => s.id === id); if (source) emit('source', source) }
</script>

<style scoped>
.research-timeline{margin-top:20px;color:#333333;font-size:13px}.research-timeline summary{cursor:pointer;line-height:1.7}.round{margin-top:12px;padding:14px;border:1px solid #e5e5e5;border-radius:10px;background:#fafafa;overflow-wrap:anywhere}.round-heading,.operation-heading{display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap}.round-heading span,.operation-heading span,.note{font-size:11px;color:#737373}.counts{font-size:11px;line-height:1.8;margin:8px 0}.note{line-height:1.8;margin:6px 0}.interest{margin:12px 0;padding:12px;background:white;border-radius:8px}.interest blockquote{margin:8px 0;padding-left:10px;border-left:3px solid #d4d4d4;white-space:pre-wrap;line-height:1.8}dl{margin:0;display:grid;grid-template-columns:70px 1fr;gap:7px;font-size:11px;line-height:1.8}dt{color:#737373}dd{margin:0;white-space:pre-wrap}.query{white-space:pre-wrap;line-height:1.8;color:#171717}.tools ol{padding-left:22px}.tools li{padding:10px;margin:8px 0;background:white;border-radius:7px}.tools li.automatic{border-left:3px solid #cccccc}.tools li.failed{border-left:3px solid #b67561}.tools p,.report p{line-height:1.8;margin:6px 0}.candidate{padding:8px;background:#f5f5f5;margin:6px 0}.error{color:#a04e40}button{cursor:pointer;margin:4px;border:1px solid #e5e5e5;border-radius:6px;padding:5px 8px;background:#fafafa;color:#171717;font-size:11px}.raw{margin-top:8px;color:#737373}.raw pre{white-space:pre-wrap;max-height:260px;overflow:auto;font-size:11px;line-height:1.7}.report{margin-top:12px}@media(max-width:650px){dl{grid-template-columns:1fr;gap:3px}dd{margin-bottom:7px}}
</style>

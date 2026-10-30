import test from 'node:test'
import assert from 'node:assert/strict'
import { hasCurrentTrace, investigationRounds, candidateHits, hasOriginalEvidence, pendingInterestSelection, interestSelectionRequest, researchDecisionLabel } from './researchTimeline.js'
const e = (type, data) => ({ type, data })

test('old research records keep their content without reconstructing a timeline', () => {
  for (const protocol of ['output-review-v1', 'output-review-v2', 'output-review-v3', 'output-review-v4']) {
    const run = {
      question: '旧问题', answer: '旧回答 [S1]', sources: [{ id: 'S1', text: '原文' }],
      events: [e('plan', {}), e('stage', {}), e('answer_skill', {}), e('research_state', {}),
        e('usage', { promptTokens: 20, completionTokens: 10 }),
        e('answer_revision', { round: 1, protocol, answer: '旧正文' }),
        e('tool_call', { tool: 'searchKnowledge' }),
        e('tool_result', { round: 1, tool: 'readChapter', data: {} })],
    }
    const saved = structuredClone(run)
    assert.equal(hasCurrentTrace(run.events), false)
    assert.deepEqual(investigationRounds(run.events), [])
    assert.deepEqual(run, saved)
  }
  assert.equal(hasCurrentTrace(), false)
  assert.deepEqual(investigationRounds(), [])
})

test('both current template engines retain reports and answer revisions', () => {
  const decision = { action: 'CONTINUE', stopReason: 'CONTINUE', reason: '发现重要的新联系', nextInvestigation: '检索反向机制', expectedBenefit: '改变核心归因' }
  const finding = { id: 'F1', kind: 'CONDITION', statement: '作者没有直接讨论这个对象', explanatoryImpact: '作者归因边界，不是机制反证', sourceIds: ['S2'] }
  for (const [engine, protocol] of [['LEGACY_V2', 'output-review-basic-v1'], ['DEVELOPMENT_V4', 'output-review-v4']]) {
    const events = [e('research_template', { engine }),
      e('investigation', { round: 1, protocol, report: { findings: [finding] } }),
      e('answer_revision', { round: 1, protocol, answer: '保留假设的解释', researchState: decision })]
    assert.equal(hasCurrentTrace(events), true)
    const rounds = investigationRounds(events)
    assert.equal(rounds.length, 1)
    assert.deepEqual(rounds[0].revision.researchState, decision)
    assert.deepEqual(rounds[0].report.findings[0], finding)
  }
  assert.equal(hasCurrentTrace([e('research_template', { engine: 'LEGACY_V2' })]), true)
  assert.equal(hasCurrentTrace([e('tool_call', { tool: 'searchKnowledge' })]), false)
})

test('current broad and focused evidence preserve ranks, positions and source links', () => {
  const passage = { start: 10, end: 20, lexicalRank: 1, vectorRank: 2, sourceIds: ['S2'], newCharacters: 10 }
  const rounds = investigationRounds([
    e('research_template', {}),
    e('phase', { round: 1, retrievalMode: 'BROAD' }),
    e('tool_call', { round: 1, tool: 'searchPassages', operationId: 'one' }),
    e('tool_result', { round: 1, tool: 'searchPassages', operationId: 'one', data: { retrievalMode: 'BROAD', passages: [passage] } }),
  ])
  assert.equal(rounds[0].retrievalMode, 'BROAD')
  assert.deepEqual(candidateHits(rounds[0].operations[0].result), [passage])
  assert.deepEqual(candidateHits({ data: { hits: [passage] } }), [passage])
  assert.deepEqual(candidateHits({ data: [passage, { title: '非候选' }] }), [passage])
})

test('full evidence is recognized by payload while historical focused excerpts stay candidates', () => {
  const passage = { passageId: 'P1', start: 1000, end: 2200, sourceIds: ['S2', 'S3'], lexicalRank: 1, vectorRank: 2 }
  const source = { id: 'S3', text: '完整正文'.repeat(300) }
  for (const retrievalMode of ['FOCUSED', 'BROAD']) {
    const result = { data: { retrievalMode, passages: [passage], sources: [source] } }
    assert.equal(hasOriginalEvidence(result), true)
    assert.deepEqual(candidateHits(result), [passage])
    assert.deepEqual(result.data.sources, [source])
    assert.equal(hasOriginalEvidence({ data: { retrievalMode, passages: [passage], sources: [] } }), true)
  }
  const legacy = { data: { retrievalMode: 'FOCUSED', hits: [{ start: 1000, end: 2200, text: '旧摘录' }] } }
  const snapshot = structuredClone(legacy)
  assert.equal(hasOriginalEvidence(legacy), false)
  assert.equal(candidateHits(legacy)[0].text, '旧摘录')
  assert.deepEqual(legacy, snapshot)
  assert.equal(hasOriginalEvidence(), false)
})

test('nested automatic operations pair by id and count independently of model steps', () => {
  const events = [
    e('research_template', {}),
    e('model_call', { phase: 'collaboration_draft', role: 'RESEARCHER' }),
    e('model_call', { round: 1, role: 'INVESTIGATOR' }),
    e('interest_selection', { round: 1, points: [{ id: 'R1-I1', query: '条件' }] }),
    e('tool_call', { round: 1, operationId: 'parent', tool: 'selectInterests', origin: 'MODEL' }),
    e('tool_call', { round: 1, operationId: 'child', parentOperationId: 'parent', tool: 'searchPassages', origin: 'AUTO' }),
    e('tool_result', { round: 1, operationId: 'child', tool: 'searchPassages', error: '检索失败' }),
    e('tool_result', { round: 1, operationId: 'parent', tool: 'selectInterests', data: { points: [] } }),
    e('model_call', { round: 1, role: 'INVESTIGATOR', phase: 'investigation_report' }),
    e('model_call', { round: 1, role: 'INVESTIGATOR', phase: 'investigation_report' }),
    e('usage', { round: 1, phase: 'investigation_report' }),
    e('model_call', { round: 1, role: 'RESEARCHER' }),
    e('answer_revision', { round: 1, action: 'CONTINUE' }),
    e('model_call', { round: 2, role: 'INVESTIGATOR' }),
    e('tool_call', { round: 2, operationId: 'next', tool: 'readPassage', origin: 'MODEL' }),
  ]
  const rounds = investigationRounds(events)
  assert.equal(rounds.length, 2)
  assert.equal(rounds[0].modelCalls, 3)
  assert.equal(rounds[0].modelTools, 1)
  assert.equal(rounds[0].automaticTools, 1)
  assert.equal(rounds[0].operations[1].result.error, '检索失败')
  assert.equal(rounds[0].operations[0].result.tool, 'selectInterests')
  assert.equal(rounds[1].operations[0].result, null)
  assert.equal(rounds[1].modelTools, 1)
})

test('missing or unknown operation ids never pair results by tool name or order', () => {
  const rounds = investigationRounds([
    e('research_template', {}),
    e('model_call', { round: 1, role: 'INVESTIGATOR' }),
    e('tool_call', { round: 1, operationId: 'first', tool: 'searchPassages', origin: 'MODEL' }),
    e('tool_call', { round: 1, operationId: 'second', tool: 'searchPassages', origin: 'MODEL' }),
    e('tool_result', { round: 1, tool: 'searchPassages', data: { hits: [] } }),
    e('tool_result', { round: 1, operationId: 'unknown', tool: 'searchPassages', data: { hits: [] } }),
    e('tool_result', { round: 1, operationId: 'first', tool: 'searchPassages', data: { hits: [] } }),
    e('tool_call', { round: 1, tool: 'readPassage' }),
    e('usage', { round: 1, phase: 'investigation_report' }),
  ])
  assert.equal(rounds[0].operations.length, 2)
  assert.deepEqual(rounds[0].operations[0].result.data, { hits: [] })
  assert.equal(rounds[0].operations[1].result, null)
  assert.equal(rounds[0].modelCalls, 1)
})

test('pending manual choices survive reload and ignore earlier confirmed rounds', () => {
  const first = { selectionId: 'first', round: 1, candidates: [{ id: 'R1-I1' }] }
  const second = { selectionId: 'second', round: 2, candidates: [{ id: 'R2-I1' }] }
  const run = { status: 'WAITING_FOR_SELECTION', events: [e('interest_candidates', first)] }
  assert.deepEqual(pendingInterestSelection(JSON.parse(JSON.stringify(run))), first)
  run.events.push(e('interest_choice', { selectionId: 'first', selectedIds: ['R1-I1', 'R1-I2'] }))
  assert.equal(pendingInterestSelection(run), null)
  run.events.push(e('interest_candidates', second))
  assert.deepEqual(pendingInterestSelection(run), second)
  run.status = 'INTERRUPTED'
  assert.equal(pendingInterestSelection(run), null)
  assert.deepEqual(interestSelectionRequest(run), second)
  run.status = 'CANCELLED'
  assert.equal(pendingInterestSelection(run), null)
})


test('new research events omit retrieval mode while retaining full evidence and expansion', () => {
  const passage = { start: 1000, end: 2200, sourceIds: ['S2'] }
  const source = { id: 'S2', text: '完整原文' }
  const events = [e('research_template', {}),
    e('tool_call', { round: 1, operationId: 'search', tool: 'searchPassages', origin: 'AUTO' }),
    e('tool_result', { round: 1, operationId: 'search', tool: 'searchPassages', data: { passages: [passage], sources: [source] } }),
    e('tool_call', { round: 1, operationId: 'expand', tool: 'readPassage', origin: 'MODEL' }),
    e('tool_result', { round: 1, operationId: 'expand', tool: 'readPassage', data: { start: 500, end: 2700 } })]
  const snapshot = structuredClone(events)
  const [round] = investigationRounds(events)
  assert.equal(Object.hasOwn(round, 'retrievalMode'), false)
  assert.equal(round.automaticTools, 1)
  assert.equal(round.modelTools, 1)
  assert.equal(hasOriginalEvidence(round.operations[0].result), true)
  assert.deepEqual(candidateHits(round.operations[0].result), [passage])
  assert.equal(round.operations[1].tool, 'readPassage')
  assert.deepEqual(events, snapshot)
})

test('new two-state decisions and historical controls remain readable without mutation', () => {
  for (const [record, label] of [
    [{ decision: 'COMPLETE' }, '成功完成'],
    [{ decision: 'CONTINUE' }, '值得继续'],
    [{ decision: '', action: 'FINISH' }, '结束状态未确认'],
    [{ action: 'FINISH', reason: '旧理由' }, '结束研究'],
    [{ action: 'CONTINUE', researchState: { nextInvestigation: '旧计划' } }, '继续调查'],
  ]) {
    const snapshot = structuredClone(record)
    assert.equal(researchDecisionLabel(record), label)
    assert.deepEqual(record, snapshot)
  }
  const events = [e('research_template', { protocol: 'output-review-v5' }),
    e('answer_revision', { round: 1, decision: 'CONTINUE', controlValidated: true }),
    e('research_record', { status: 'LIMIT_REACHED', stopReason: '剩余调用不足' })]
  assert.equal(investigationRounds(events)[0].revision.decision, 'CONTINUE')
})

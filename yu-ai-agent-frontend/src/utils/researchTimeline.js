// Template-backed research supports both current engines (v2 and v4).
export function hasCurrentTrace(events = []) {
  return events.some(e => e.type === 'research_template')
}

export function investigationRounds(events = []) {
  if (!hasCurrentTrace(events)) return []
  const rounds = new Map()
  for (const event of events) {
    const d = event.data || {}
    const currentRound = Number(d.round)
    if (!(currentRound > 0)) continue
    if (!rounds.has(currentRound)) rounds.set(currentRound, { number: currentRound, points: [], operations: [], modelCalls: 0, report: null, revision: null })
    const round = rounds.get(currentRound)
    if (d.retrievalMode) round.retrievalMode = d.retrievalMode
    if (event.type === 'interest_selection') round.points = d.points || []
    if (event.type === 'model_call' && d.role === 'INVESTIGATOR') round.modelCalls++
    if (event.type === 'tool_call' && d.operationId) round.operations.push({ ...d, key: d.operationId, result: null })
    if (event.type === 'tool_result' && d.operationId) {
      const op = round.operations.find(o => o.operationId === d.operationId)
      if (op) op.result = d
    }
    if (event.type === 'investigation') round.report = d.report
    if (event.type === 'answer_revision') round.revision = d
  }
  return [...rounds.values()].map(round => {
    return { ...round, modelTools: round.operations.filter(o => o.origin === 'MODEL').length, automaticTools: round.operations.filter(o => o.origin === 'AUTO').length }
  }).sort((a, b) => a.number - b.number)
}

export function toolArguments(operation) {
  if (typeof operation.arguments === 'string') {
    try { return JSON.parse(operation.arguments) } catch { return { raw: operation.arguments } }
  }
  return operation.arguments || {}
}

export function candidateHits(result) {
  const data = result?.data
  return Array.isArray(data) ? data.filter(h => h.start != null) : data?.passages || data?.hits || []
}

export function interestSelectionRequest(run) {
  const events = run.events || []
  const pending = events.filter(e => e.type === 'interest_candidates').at(-1)?.data
  if (!pending || events.some(e => e.type === 'interest_choice' && e.data?.selectionId === pending.selectionId)) return null
  return pending
}

export function pendingInterestSelection(run) {
  return run.status === 'WAITING_FOR_SELECTION' ? interestSelectionRequest(run) : null
}

// Inspect each result rather than the mode: historical FOCUSED results contain excerpts.
export function hasOriginalEvidence(result) {
  return Array.isArray(result?.data?.passages) && Array.isArray(result?.data?.sources)
}

// New controls are strict; only historical records fall back to the old action field.
export function researchDecisionLabel(revision = {}) {
  if (Object.hasOwn(revision, 'decision')) {
    return ({ COMPLETE: '成功完成', CONTINUE: '值得继续' })[revision.decision] || '结束状态未确认'
  }
  return ({ FINISH: '结束研究', CONTINUE: '继续调查' })[revision.action] || '结束状态未确认'
}

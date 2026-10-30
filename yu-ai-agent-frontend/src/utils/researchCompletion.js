export function researchRunLabel(run) {
  const record = (run.events || []).filter(e => e.type === 'research_record').at(-1)?.data
  if (run.status === 'INCOMPLETE' && record?.completion?.investigationCompleted === true && record?.completion?.controlValidated === false) return '结束状态确认失败'
  return ({ RUNNING: '正在研究', WAITING_FOR_SELECTION: '等待你选择兴趣点', COMPLETED: '已完成', CANCELLED: '已停止', LIMIT_REACHED: '达到上限', INCOMPLETE: '调查未完成', FAILED: '失败', INTERRUPTED: '服务中断' })[run.status] || run.status
}

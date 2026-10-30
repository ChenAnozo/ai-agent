export const templateSections = [
  ['primaryRole', '主智能体的基本角色', '主智能体 · 每次理解问题和回答', '定位、解释倾向及与用户观点的关系'],
  ['draftGuidance', '初稿的生成要求', '主智能体 · 调查之前', '怎样展开初步解释、联系和假设'],
  ['collaborationGuidance', '整体协作方法', '主副智能体 · 全程', '分工、研究目标与协作侧重点'],
  ['investigatorInterests', '副智能体的兴趣与偏好', '副智能体 · 选择兴趣点及报告', '关注什么，怎样判断研究价值'],
  ['investigationGuidance', '副智能体怎样调查', '副智能体 · 检索与读取', '怎样提出追问、处理材料和反例'],
  ['reportGuidance', '调查报告怎样反馈', '副智能体 · 报告生成', '反馈重点、解释影响与详略'],
  ['integrationGuidance', '主智能体怎样整合结果', '主智能体 · 每轮调查后', '材料取舍、解释组织与必要修正'],
  ['continuationGuidance', '怎样决定继续研究', '主智能体 · 更新回答与行动修复', '什么样的潜在认识增益值得追加'],
]
export function editableTemplate(template) {
  return { name: template.name, description: template.description, sections: Object.fromEntries(templateSections.map(([key]) => [key, template.sections[key]])) }
}
export function templateDirty(template, draft) {
  return !!template && !!draft && JSON.stringify(editableTemplate(template)) !== JSON.stringify({ name: draft.name, description: draft.description, sections: Object.fromEntries(templateSections.map(([key]) => [key, draft.sections[key]])) })
}
export function templateValidation(draft) {
  if (!draft?.name?.trim() || draft.name.length > 200) return '模板名称须为1–200字符。'
  if (draft.description.length > 2000) return '模板说明最多2000字符。'
  for (const [key, label] of templateSections) if (!draft.sections[key]?.trim() || draft.sections[key].length > 16000) return `${label}须为1–16000字符。`
  return ''
}

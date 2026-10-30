import test from 'node:test'
import assert from 'node:assert/strict'
import { editableTemplate, templateDirty, templateSections, templateValidation } from './researchTemplates.js'

const template = { name: '初始模板', description: '', sections: Object.fromEntries(templateSections.map(([key]) => [key, key + ' 初始内容'])) }

test('editing all eight fields cannot mutate the selected saved revision', () => {
  const draft = editableTemplate(template)
  assert.equal(templateSections.length, 8)
  assert.equal(templateDirty(template, draft), false)
  for (const [key] of templateSections) draft.sections[key] = '新指导文字'
  assert.equal(templateDirty(template, draft), true)
  for (const [key] of templateSections) assert.equal(template.sections[key], key + ' 初始内容')
  assert.equal(templateValidation(draft), '')
})

test('restoring initial fields clears dirty status and invalid drafts remain visible', () => {
  const draft = editableTemplate(template)
  draft.sections.integrationGuidance = ''
  assert.match(templateValidation(draft), /主智能体怎样整合结果/)
  assert.equal(draft.sections.integrationGuidance, '')
  draft.sections.integrationGuidance = template.sections.integrationGuidance
  assert.equal(templateDirty(template, draft), false)
  draft.name = ''
  assert.match(templateValidation(draft), /名称/)
  draft.name = '有效名称'
  draft.sections.primaryRole = '字'.repeat(16001)
  assert.match(templateValidation(draft), /主智能体的基本角色/)
})

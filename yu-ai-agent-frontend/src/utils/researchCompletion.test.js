import test from 'node:test'
import assert from 'node:assert/strict'
import { researchRunLabel } from './researchCompletion.js'

test('a control failure is distinct from incomplete evidence and historical unknowns', () => {
  const events = [{ type: 'research_record', data: { completion: { investigationCompleted: true, controlValidated: false } } }]
  assert.equal(researchRunLabel({ status: 'INCOMPLETE', events }), '结束状态确认失败')
  assert.equal(researchRunLabel({ status: 'INCOMPLETE', events: [] }), '调查未完成')
  assert.equal(researchRunLabel({ status: 'INCOMPLETE', events: [{ type: 'research_record', data: { completion: { investigationCompleted: false, controlValidated: false } } }] }), '调查未完成')
  assert.equal(researchRunLabel({ status: 'LIMIT_REACHED', events }), '达到上限')
  assert.equal(researchRunLabel({ status: 'COMPLETED', events }), '已完成')
})

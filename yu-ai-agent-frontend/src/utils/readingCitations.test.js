import test from 'node:test'
import assert from 'node:assert/strict'
import { renderReadingMarkdown } from './readingCitations.js'

const sources = [{ id: 'S7' }, { id: 'S11' }, { id: 'S15' }]

test('historical compact and spaced citations link to the same source ids', () => {
  const html = renderReadingMarkdown('材料[S11]，限定条件[ S11 ]，新材料[ S15 ]。[\tS7\u3000]', sources)
  assert.equal((html.match(/href="#source-S11"/g) || []).length, 2)
  assert.match(html, /href="#source-S15"/)
  assert.match(html, /href="#source-S7"/)
})

test('citations work in emphasis lists and blockquotes without corrupting existing links', () => {
  const html = renderReadingMarkdown('**判断[ S15 ]**\n\n- 依据[S11]\n\n> 引文[ S7 ]\n\n[S15](#source-S15) [S11](https://example.com)', sources)
  assert.match(html, /<strong>判断<a href="#source-S15">/)
  assert.match(html, /<li>依据<a href="#source-S11">/)
  assert.match(html, /<blockquote>/)
  assert.equal((html.match(/href="#source-S15"/g) || []).length, 2)
  assert.match(html, /href="https:\/\/example.com"/)
  assert.doesNotMatch(html, /<a[^>]*>\s*<a/)
})

test('code and missing sources remain text and source availability is scoped to each answer', () => {
  const html = renderReadingMarkdown('`[ S15 ]`\n\n```text\n[S11]\n```\n\n[S99] [S15]', sources)
  assert.match(html, /<code>\[ S15 \]<\/code>/)
  assert.equal((html.match(/href="#source-/g) || []).length, 1)
  assert.doesNotMatch(html, /href="#source-S99"/)
  assert.doesNotMatch(renderReadingMarkdown('[S15]', []), /href="#source-/)
})

test('adjacent citations are independent links while explicit reference links remain intact', () => {
  const html = renderReadingMarkdown('证据[ S7 ][ S11 ][ S15 ]，另见[S11][S15]。\n\n[S15][reference]\n\n[reference]: https://example.com', sources)
  assert.equal((html.match(/href="#source-/g) || []).length, 5)
  assert.match(html, /href="https:\/\/example.com"/)
})

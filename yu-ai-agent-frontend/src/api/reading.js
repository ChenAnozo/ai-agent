import { BASE_URL, request } from './index'

export async function reading(path, method = 'GET', data) {
  return (await request({ url: `/reading${path}`, method, data })).data
}

export async function streamReading(sessionId, body, onEvent, signal) {
  const response = await fetch(`${BASE_URL}/reading/sessions/${sessionId}/messages`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), signal
  })
  if (!response.ok) {
    const error = await response.json().catch(() => ({}))
    throw new Error(error.message || error.detail || `请求失败：HTTP ${response.status}`)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = '', complete = false
  function dispatch(block) {
    const lines = block.split('\n')
    const type = lines.find(line => line.startsWith('event:'))?.slice(6).trim() || 'message'
    const value = lines.filter(line => line.startsWith('data:')).map(line => line.slice(5).replace(/^ /, '')).join('\n')
    if (!value) return
    let data
    try { data = JSON.parse(value) } catch { data = value }
    if (type === 'complete' || type === 'paused') complete = true
    onEvent(type, data)
  }
  try {
    while (true) {
      const { value, done } = await reader.read()
      buffer += done ? decoder.decode() : decoder.decode(value, { stream: true })
      buffer = buffer.replace(/\r\n/g, '\n')
      const blocks = buffer.split('\n\n')
      buffer = blocks.pop() || ''
      blocks.forEach(dispatch)
      if (done) { if (buffer.trim()) dispatch(buffer); break }
    }
    if (!complete) throw new Error('连接已中断，任务可能仍在运行。请从会话历史查看状态。')
  } finally { reader.releaseLock() }
}

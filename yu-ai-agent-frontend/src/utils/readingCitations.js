import { Marked } from 'marked'

const sourceMarker = /^\[[^\S\r\n]*(S\d+)[^\S\r\n]*\]/

// Render citations as inline Markdown tokens, leaving code and existing links intact.
// The caller must sanitize the resulting HTML before using v-html.
export function renderReadingMarkdown(markdown, sources = []) {
  const available = new Set(sources.map(source => source.id))
  const parser = new Marked({
    extensions: [{
      name: 'sourceCitation',
      level: 'inline',
      start(text) { return text.indexOf('[') },
      tokenizer(text) {
        const match = sourceMarker.exec(text)
        if (!match || !available.has(match[1])) return
        const tail = text.slice(match[0].length)
        if (tail.startsWith('(') || (tail.startsWith('[') && !sourceMarker.test(tail))) return
        return { type: 'sourceCitation', raw: match[0], id: match[1] }
      },
      renderer(token) { return `<a href="#source-${token.id}">[${token.id}]</a>` },
    }],
  })
  return parser.parse(markdown || '', { async: false })
}

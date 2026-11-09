// Markdown to Telegram's HTML (https://core.telegram.org/bots/api#html-style).
//
// Covers what agents write plus Telegram's own extras:
//   **bold**  *italic* / _italic_  __underline__  ~~strike~~  ||spoiler||  `code`
//   ```lang fenced code```          > quote          > [!expand] first line: a quote that folds up
//   > [!NOTE] / [!TIP] / [!WARNING] … (GitHub alerts) become a quote with an emoji title
//   # headings (bold)  - bullets (•)  - [x] tasks (✅ / ⬜)  --- rules  | tables | (monospace, columns lined up)
// Links are written out rather than hidden behind their text: Telegram asks before opening a hidden link, but opens
// a bare one.

const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

const ALERTS: Record<string, string> = { NOTE: 'ℹ️ Note', TIP: '💡 Tip', IMPORTANT: '❗ Important', WARNING: '⚠️ Warning', CAUTION: '🛑 Caution' };

// Inline styles, on text that is already escaped.
function inline(s: string): string {
  return s
    .replace(/\*\*(.+?)\*\*/g, '<b>$1</b>')
    .replace(/(?<![\w_])__(.+?)__(?![\w_])/g, '<u>$1</u>')
    .replace(/(?<![\w*])\*(?![\s*])(.+?)(?<![\s*])\*(?![\w*])/g, '<i>$1</i>')
    .replace(/(?<![\w_])_(?![\s_])(.+?)(?<![\s_])_(?![\w_])/g, '<i>$1</i>')
    .replace(/~~(.+?)~~/g, '<s>$1</s>')
    .replace(/\|\|(.+?)\|\|/g, '<tg-spoiler>$1</tg-spoiler>')
    .replace(/\[([^\]\n]+)\]\((https?:\/\/[^)\s]+)\)/g, (_, t, u) => (t === u ? u : `${t}\n${u}`));
}

// A Markdown table as monospace text with its columns lined up (Telegram has no tables).
function table(lines: string[], plain: (l: string) => string): string {
  const rows = lines.map(plain)
    .filter(l => !/^\s*\|?[\s:|-]+\|?\s*$/.test(l)) // the |---|:--:| separator row
    .map(l => l.trim().replace(/^\||\|$/g, '').split('|').map(c => c.trim().replace(/\*\*|`/g, '')));
  if (!rows.length) return '';
  const widths = rows[0].map((_, i) => Math.max(...rows.map(r => [...(r[i] || '')].length)));
  const pad = (c: string, w: number) => c + ' '.repeat(Math.max(0, w - [...c].length));
  const out = rows.map(r => widths.map((w, i) => pad(r[i] || '', w)).join('  ').trimEnd());
  out.splice(1, 0, widths.map(w => '─'.repeat(w)).join('  '));
  return `<pre>${esc(out.join('\n'))}</pre>`;
}

function quote(lines: string[]): string {
  let body = lines.map(l => l.replace(/^\s*> ?/, ''));
  let attr = '';
  const head = body[0].trim().match(/^\[!(\w+)\]$/);
  if (head && head[1].toLowerCase() === 'expand') { attr = ' expandable'; body = body.slice(1); }
  else if (head && ALERTS[head[1].toUpperCase()]) body = [`**${ALERTS[head[1].toUpperCase()]}**`, ...body.slice(1)];
  return `<blockquote${attr}>${body.map(l => line(l)).join('\n').trim()}</blockquote>`;
}

function line(l: string): string {
  let m;
  if ((m = l.match(/^#{1,6} +(.+)$/))) return `<b>${inline(esc(m[1]))}</b>`;
  if (/^\s*([-*_])\s*\1\s*\1[\s\-*_]*$/.test(l)) return '──────────';
  if ((m = l.match(/^(\s*)[-*+] +\[([ xX])\] +(.*)$/))) return `${m[1]}${m[2] === ' ' ? '⬜' : '✅'} ${inline(esc(m[3]))}`;
  if ((m = l.match(/^(\s*)[-*+] +(.*)$/))) return `${m[1]}• ${inline(esc(m[2]))}`;
  return inline(esc(l));
}

export function html(md: string): string {
  // Code first, so nothing inside it is touched: fenced blocks, then inline code.
  const held: string[] = [];
  const raw: string[] = []; // the same, as plain text, for tables
  const hold = (h: string, t: string) => { raw.push(t); return `\u0000${held.push(h) - 1}\u0000`; };
  const plain = (l: string) => l.replace(/\u0000(\d+)\u0000/g, (_, n) => raw[+n]);
  let s = md.replace(/```([\w+-]*)[^\n]*\n([\s\S]*?)```/g, (_, lang: string, code: string) =>
    hold(lang ? `<pre><code class="language-${lang}">${esc(code.trimEnd())}</code></pre>` : `<pre>${esc(code.trimEnd())}</pre>`, code));
  s = s.replace(/`([^`\n]+)`/g, (_, c: string) => hold(`<code>${esc(c)}</code>`, c));

  // Then block by block: runs of quote lines and table lines, everything else a line at a time.
  const lines = s.split('\n');
  const out: string[] = [];
  for (let i = 0; i < lines.length;) {
    const run = (test: (l: string) => boolean) => { const r: string[] = []; while (i < lines.length && test(lines[i])) r.push(lines[i++]); return r; };
    if (/^\s*>/.test(lines[i])) out.push(quote(run(l => /^\s*>/.test(l))));
    else if (/^\s*\|.*\|\s*$/.test(lines[i])) out.push(table(run(l => /^\s*\|.*\|\s*$/.test(l)), plain));
    else out.push(line(lines[i++]));
  }
  return out.join('\n').replace(/\u0000(\d+)\u0000/g, (_, n) => held[+n]);
}

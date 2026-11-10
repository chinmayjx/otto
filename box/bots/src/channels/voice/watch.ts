// A live view of what calls hear and do, for tuning by ear: transcription as it streams in (the user's words, and
// whatever noise was taken for words), the voice's own words, voice detection on either side, and the call's log
// lines (states, decisions). Only with OTTO_VOICE_WATCH=1: then the voice server serves it at /voice/watch, a page
// fed by server-sent events from /voice/watch/events, the last few hundred lines first.
import type http from 'node:http';

export const watching = process.env.OTTO_VOICE_WATCH === '1';

interface Line { t: number; call: string; kind: string; text: string; lp?: number }
const recent: Line[] = [];
const clients = new Set<http.ServerResponse>();

/** One line for the page: kind is user, user-partial, voice, voice-partial, vad or log. */
export function tap(call: string, kind: string, text: string, lp?: number) {
  if (!watching) return;
  const l: Line = { t: Date.now(), call, kind, text, ...(lp === undefined ? {} : { lp }) };
  recent.push(l);
  if (recent.length > 400) recent.shift();
  const data = `data: ${JSON.stringify(l)}\n\n`;
  for (const c of clients) c.write(data);
}

/** The page and its feed; false when it's not one of theirs. */
export function serveWatch(req: http.IncomingMessage, res: http.ServerResponse, path: string): boolean {
  if (!watching || (path !== '/voice/watch' && path !== '/voice/watch/events')) return false;
  if (path === '/voice/watch') {
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
    res.end(PAGE);
    return true;
  }
  res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-store', connection: 'keep-alive' });
  for (const l of recent) res.write(`data: ${JSON.stringify(l)}\n\n`);
  clients.add(res);
  const ping = setInterval(() => res.write(': ping\n\n'), 15_000);
  req.on('close', () => { clients.delete(res); clearInterval(ping); });
  return true;
}

const PAGE = `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Voice watch</title><style>
:root{--bg:#111;--fg:#ddd;--dim:#777;--user:#7fd1ff;--voice:#b9f29a;--vad:#e0b36a;--bad:#ff7a8a}
@media (prefers-color-scheme:light){:root:not([data-theme=dark]){--bg:#fafafa;--fg:#222;--dim:#888;--user:#0b6ea8;--voice:#2f7d14;--vad:#9a6400;--bad:#c2233b}}
body{margin:0;background:var(--bg);color:var(--fg);font:13px/1.45 ui-monospace,Menlo,monospace}
header{position:sticky;top:0;background:var(--bg);padding:8px 12px;border-bottom:1px solid #8884;display:flex;gap:10px;align-items:center;flex-wrap:wrap}
#log{padding:6px 12px 40px}.l{white-space:pre-wrap;word-break:break-word;padding:1px 0}
.t{color:var(--dim)}.user{color:var(--user)}.voice{color:var(--voice)}.vad{color:var(--vad)}.log{color:var(--dim)}
.partial{opacity:.6;font-style:italic}.bad{color:var(--bad)}label{color:var(--dim)}
</style></head><body><header><b>Voice watch</b><span id="st" class="t">connecting…</span>
<label><input type="checkbox" id="logs" checked> log lines</label><label><input type="checkbox" id="vad" checked> detection</label>
<button id="clr">clear</button></header><div id="log"></div><script>
const box=document.getElementById('log'),st=document.getElementById('st');let partial={};
const fmt=t=>new Date(t).toLocaleTimeString([], {hour12:false})+'.'+String(t%1000).padStart(3,'0');
function show(l){
  if(l.kind==='log'&&!logs.checked)return; if(l.kind==='vad'&&!vad.checked)return;
  const kind=l.kind.replace('-partial','');const key=l.call+kind;
  let el=l.kind.endsWith('-partial')?partial[key]:null;
  if(!el){el=document.createElement('div');el.className='l';box.appendChild(el)}
  if(l.kind.endsWith('-partial'))partial[key]=el;else{if(partial[key]&&partial[key]!==el)partial[key].remove();delete partial[key]}
  const who={user:'you  ',voice:'otto ',vad:'     ',log:'     '}[kind]||kind;
  const lp=l.lp===undefined?'':' (lp '+l.lp.toFixed(2)+')';
  el.innerHTML='';const t=document.createElement('span');t.className='t';t.textContent=fmt(l.t)+' '+l.call.slice(0,4)+' ';
  const s=document.createElement('span');s.className=kind+(l.kind.endsWith('-partial')?' partial':'')+(l.lp!==undefined&&l.lp<-0.5?' bad':'');
  s.textContent=who+l.text+lp;el.append(t,s);
  if(innerHeight+scrollY>=document.body.scrollHeight-80)scrollTo(0,document.body.scrollHeight);
}
clr.onclick=()=>{box.innerHTML='';partial={}};
const es=new EventSource('/voice/watch/events');es.onopen=()=>st.textContent='live';es.onerror=()=>st.textContent='reconnecting…';
es.onmessage=e=>show(JSON.parse(e.data));
</script></body></html>`;

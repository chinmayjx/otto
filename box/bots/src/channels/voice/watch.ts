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
  res.write(': watching\n\n'); // headers out now, through any proxy
  for (const l of recent) res.write(`data: ${JSON.stringify(l)}\n\n`);
  clients.add(res);
  const ping = setInterval(() => res.write(': ping\n\n'), 15_000);
  req.on('close', () => { clients.delete(res); clearInterval(ping); });
  return true;
}

const PAGE = `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Voice watch</title><style>
:root{--bg:#111;--fg:#e4e4e4;--dim:#7a7a7a;--line:#8883;--voice:#b9f29a;--vad:#e0b36a;--bad:#ff7a8a}
@media (prefers-color-scheme:light){:root{--bg:#fafafa;--fg:#1d1d1d;--dim:#888;--voice:#2f7d14;--vad:#9a6400;--bad:#c2233b}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.5 system-ui,sans-serif;height:100vh;display:flex;flex-direction:column}
header{padding:8px 12px;border-bottom:1px solid var(--line);display:flex;gap:12px;align-items:center}
#st{color:var(--dim);font-size:12px}button{font:inherit;font-size:12px}
main{flex:1;display:grid;grid-template-columns:1fr 1fr;min-height:0}
@media (max-width:760px){main{grid-template-columns:1fr;grid-template-rows:45% 55%}}
section{display:flex;flex-direction:column;min-height:0;border-right:1px solid var(--line)}
h2{margin:0;padding:6px 12px;font-size:11px;letter-spacing:.06em;text-transform:uppercase;color:var(--dim);border-bottom:1px solid var(--line)}
.scroll{flex:1;overflow-y:auto;padding:10px 12px}
#words{font-size:17px;line-height:1.6}#words .w.bad{color:var(--bad)}#words .w.partial{opacity:.55}
#logs{font:12px/1.45 ui-monospace,Menlo,monospace}.l{white-space:pre-wrap;word-break:break-word}
.t{color:var(--dim)}.voice{color:var(--voice)}.vad{color:var(--vad)}.log{color:var(--dim)}.user{color:var(--fg)}.bad{color:var(--bad)}
</style></head><body><header><b>Voice watch</b><span id="st">connecting…</span><button id="clr">clear</button></header>
<main><section><h2>Heard (as OpenAI writes it down; red: low confidence)</h2><div class="scroll" id="wbox"><div id="words"></div></div></section>
<section><h2>Log</h2><div class="scroll" id="lbox"><div id="logs"></div></div></section></main><script>
const words=document.getElementById('words'),logs=document.getElementById('logs'),wbox=document.getElementById('wbox'),lbox=document.getElementById('lbox');
let partial=null;
const fmt=t=>new Date(t).toLocaleTimeString([], {hour12:false})+'.'+String(t%1000).padStart(3,'0');
const stick=b=>b.scrollTop+b.clientHeight>=b.scrollHeight-60;
function word(l){
  const keep=stick(wbox);
  if(!partial){partial=document.createElement('span');words.appendChild(partial);words.appendChild(document.createTextNode(' '))}
  partial.className='w'+(l.kind==='user-partial'?' partial':'')+(l.lp!==undefined&&l.lp<-0.5?' bad':'');
  partial.textContent=l.text;partial.title=fmt(l.t)+(l.lp===undefined?'':' lp '+l.lp.toFixed(2));
  if(l.kind==='user')partial=null;
  if(keep)wbox.scrollTop=wbox.scrollHeight;
}
function line(l){
  if(l.kind==='voice-partial')return;
  const keep=stick(lbox);
  const el=document.createElement('div');el.className='l';
  const t=document.createElement('span');t.className='t';t.textContent=fmt(l.t)+' ';
  const s=document.createElement('span');const lp=l.lp===undefined?'':' (lp '+l.lp.toFixed(2)+')';
  s.className=l.kind+(l.lp!==undefined&&l.lp<-0.5?' bad':'');
  s.textContent=({user:'heard: ',voice:'otto: ',vad:'',log:''}[l.kind]??'')+l.text+lp;
  el.append(t,s);logs.appendChild(el);
  if(keep)lbox.scrollTop=lbox.scrollHeight;
}
function show(l){ if(l.kind==='user'||l.kind==='user-partial')word(l); if(l.kind!=='user-partial')line(l) }
document.getElementById('clr').onclick=()=>{words.innerHTML='';logs.innerHTML='';partial=null};
const st=document.getElementById('st');const es=new EventSource('/voice/watch/events');
es.onopen=()=>st.textContent='live';es.onerror=()=>st.textContent='reconnecting…';es.onmessage=e=>show(JSON.parse(e.data));
</script></body></html>`;

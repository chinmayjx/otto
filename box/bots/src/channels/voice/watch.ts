// A live view of what calls hear and do, for tuning by ear: transcription as it streams in (the user's words, and
// whatever noise was taken for words), the voice's own words, voice detection on either side, and the call's log
// lines (states, decisions). Only with OTTO_VOICE_WATCH=1: then the voice server serves it at /voice/watch, a page
// fed by server-sent events from /voice/watch/events, the last few hundred lines first.
import type http from 'node:http';

export const watching = process.env.OTTO_VOICE_WATCH === '1';

interface Line { t: number; call: string; kind: string; text: string; lp?: number; id?: string; role?: string }
const recent: Line[] = [];
const clients = new Set<http.ServerResponse>();

/** One line for the page: kind is user, user-partial, voice, voice-partial, vad or log; or, for the voice model's
 * conversation, item (a new item: id, role, text), item-text (an item's text once known) or item-gone (deleted). */
export function tap(call: string, kind: string, text: string, lp?: number, id?: string, role?: string) {
  if (!watching) return;
  const l: Line = { t: Date.now(), call, kind, text, ...(lp === undefined ? {} : { lp }), ...(id ? { id } : {}), ...(role ? { role } : {}) };
  recent.push(l);
  if (recent.length > 2000) recent.shift();
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
#logs{font:12px/1.45 ui-monospace,Menlo,monospace}
.tabs{display:flex;border-bottom:1px solid var(--line)}.tabs button{flex:1;background:none;border:0;color:var(--dim);padding:6px;font-size:11px;letter-spacing:.06em;text-transform:uppercase}
.tabs button.on{color:var(--fg);box-shadow:inset 0 -2px 0 var(--voice)}
.it{margin:0 0 8px;padding:6px 9px;border-radius:8px;background:#8881}.it .r{font-size:11px;color:var(--dim);text-transform:uppercase;letter-spacing:.05em}
.it.user{border-left:3px solid var(--fg)}.it.assistant{border-left:3px solid var(--voice)}.it.system{border-left:3px solid var(--dim)}
.it.function_call,.it.function_call_output{border-left:3px solid var(--vad)}.it.gone{opacity:.45;text-decoration:line-through}.it .x{white-space:pre-wrap;word-break:break-word}.l{white-space:pre-wrap;word-break:break-word}
.t{color:var(--dim)}.voice{color:var(--voice)}.vad{color:var(--vad)}.log{color:var(--dim)}.user{color:var(--fg)}.bad{color:var(--bad)}
</style></head><body><header><b>Voice watch</b><span id="st">connecting…</span><button id="clr">clear</button></header>
<main><section><h2>Heard (as OpenAI writes it down; red: low confidence)</h2><div class="scroll" id="wbox"><div id="words"></div></div></section>
<section><div class="tabs"><button id="tc" class="on">Voice model's conversation</button><button id="tl">Log</button></div>
<div class="scroll" id="cbox"><div id="conv"></div></div><div class="scroll" id="lbox" style="display:none"><div id="logs"></div></div></section></main><script>
const words=document.getElementById('words'),logs=document.getElementById('logs'),wbox=document.getElementById('wbox'),lbox=document.getElementById('lbox');
let partial=null;const items={};const conv=document.getElementById('conv'),cbox=document.getElementById('cbox');
const tc=document.getElementById('tc'),tl=document.getElementById('tl');
tc.onclick=()=>{tc.className='on';tl.className='';cbox.style.display='';lbox.style.display='none'};
tl.onclick=()=>{tl.className='on';tc.className='';lbox.style.display='';cbox.style.display='none'};
const ROLE={user:'you',assistant:'voice',system:'system note',function_call:'hand-off to Otto',function_call_output:'tool result'};
function item(l){
  const keep=stick(cbox);let el=items[l.id];
  if(l.kind==='item'){
    if(!el){el=document.createElement('div');items[l.id]=el;el.innerHTML='<div class="r"></div><div class="x"></div>';conv.appendChild(el)}
    el.className='it '+(l.role||'');el.querySelector('.r').textContent=(ROLE[l.role]||l.role||'item')+' · '+fmt(l.t);
    if(l.text)el.querySelector('.x').textContent=l.text;else if(l.role==='user')el.querySelector('.x').textContent='…';
  }else if(el){
    if(l.kind==='item-gone'){el.classList.add('gone');el.querySelector('.r').textContent+=' · dropped'}
    else{el.querySelector('.x').textContent=l.text+(l.lp===undefined?'':'  (lp '+l.lp.toFixed(2)+')');if(l.lp!==undefined&&l.lp<-0.5)el.querySelector('.x').classList.add('bad')}
  }
  if(keep)cbox.scrollTop=cbox.scrollHeight;
}
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
function show(l){ if(l.kind.startsWith('item'))return item(l); if(l.kind==='user'||l.kind==='user-partial')word(l); if(l.kind!=='user-partial')line(l) }
document.getElementById('clr').onclick=()=>{words.innerHTML='';logs.innerHTML='';conv.innerHTML='';partial=null;for(const k in items)delete items[k]};
const st=document.getElementById('st');const es=new EventSource('/voice/watch/events');
es.onopen=()=>st.textContent='live';es.onerror=()=>st.textContent='reconnecting…';es.onmessage=e=>show(JSON.parse(e.data));
</script></body></html>`;

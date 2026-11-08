// Devbox: whether the box is running, its metrics, and starting or stopping it. The API is the box's control panel
// (config.panel), on a machine that stays up while the box is stopped. Files, Desktop and Terminal use stoppedCard() when they can't reach the dev box.

import { config, app, ask, esc, toast } from './ui.js';

const PANEL = config.panel;
const LABELS = { pending: 'Starting…', running: 'Running', stopping: 'Stopping…', stopped: 'Stopped',
  'shutting-down': 'Shutting down…', terminated: 'Terminated' };

export async function devboxStatus() {
  if (!PANEL) throw new Error('no panel configured');
  const r = await fetch(`${PANEL}/api/status`);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

async function act(what) {
  const r = await fetch(`${PANEL}/api/${what}`, { method: 'POST', headers: { 'X-Panel': '1' } });
  if (!r.ok) throw new Error(await r.text());
}

const GB = b => (b / 1073741824).toFixed(1);
const upFor = min => min < 60 ? `${min} min` : `${min / 60 | 0} h ${min % 60} min`;
const meter = p => `<div class="meter"><i style="width:${Math.min(100, p)}%" class="${p >= 90 ? 'full' : p >= 75 ? 'hot' : ''}"></i></div>`;

// --- The section ------------------------------------------------------------------------------------------

export function devbox() {
  app.className = 'devbox';
  app.innerHTML = '<div class="scroll"><div class="dv-head"><p class="empty">Checking…</p></div><div class="dv-metrics"></div></div>';
  const head = app.querySelector('.dv-head'), box = app.querySelector('.dv-metrics');
  let status = null, statusTimer = 0, metricsTimer = 0;

  async function refresh() {
    clearTimeout(statusTimer);
    try {
      status = await devboxStatus();
      drawHead();
      const busy = !['running', 'stopped'].includes(status.state);
      statusTimer = setTimeout(refresh, busy ? 3000 : 15000);
      if (status.state === 'running') { if (!metricsTimer) metrics(); }
      else { clearTimeout(metricsTimer); metricsTimer = 0; box.innerHTML = ''; }
    } catch (e) {
      head.innerHTML = PANEL ? '<p class="empty">Can\'t reach the box\'s control panel. Is Tailscale on?</p>'
        : '<p class="empty">No control panel is set up for this box.</p>';
      if (!PANEL) return;
      statusTimer = setTimeout(refresh, 10000);
    }
  }

  function drawHead() {
    const s = status, on = s.state === 'running', off = s.state === 'stopped';
    const sub = on ? `up ${upFor(s.up_minutes)}${s.up_minutes < 3 ? ' · Claude is ready in about a minute' : ''}`
      : off ? 'not costing compute' : 'this takes a minute or two';
    head.innerHTML = `<span class="dot ${on ? '' : off ? 'off' : 'busy'}"></span>
      <div class="dv-state"><b>${esc(LABELS[s.state] || s.state)}</b><small>${esc(s.name)} · ${esc(s.type)} · ${sub}</small></div>
      ${on ? '<button class="btn line" data-act="stop">Stop</button>' : off ? '<button class="btn fill" data-act="start">Start</button>' : ''}`;
  }

  head.onclick = async e => {
    const b = e.target.closest('[data-act]');
    if (!b) return;
    if (b.dataset.act === 'stop' && !await ask('Stop the devbox?', 'Running Claude chats close; they reopen on the next start.', 'Stop')) return;
    b.disabled = true;
    try { await act(b.dataset.act); } catch (err) { toast(`Couldn't ${b.dataset.act}: ${err.message}`); }
    refresh();
  };

  async function metrics() {
    clearTimeout(metricsTimer);
    metricsTimer = setTimeout(metrics, 5000);
    if (document.hidden) return;
    try {
      const r = await fetch(`${PANEL}/api/metrics`);
      if (!r.ok) throw new Error();
      drawMetrics(await r.json());
      box.classList.remove('stale');
    } catch {
      box.classList.add('stale');
    }
  }

  function drawMetrics(m) {
    const mem = m.mem.used / m.mem.total * 100, idle = m.idle;
    box.innerHTML = `<div class="dv-sec"><div class="dv-stats">
        <div><div class="v">${Math.round(m.cpu.pct)}%</div>${meter(m.cpu.pct)}<div class="l">CPU · ${m.cpu.cores} cores</div></div>
        <div><div class="v">${Math.round(mem)}%</div>${meter(mem)}<div class="l">${GB(m.mem.used)} of ${GB(m.mem.total)} GB</div></div>
        <div><div class="v">${m.claude_chats}</div><div class="meter" style="visibility:hidden"></div><div class="l">Claude chats</div></div></div></div>
      <div class="dv-sec"><h5>Last 30 minutes</h5>${chart(m.history)}
        <div class="legend"><span><b style="background:var(--cpu)"></b>CPU</span><span><b style="background:var(--mem)"></b>Memory</span></div></div>
      <div class="dv-sec"><h5>Auto-stop</h5><div class="kv"><span>${!idle ? 'Unknown' : idle.busy ? `Busy (${esc(idle.busy)})` : `Idle ${idle.idle_minutes} min`}</span>
        <span class="k">${!idle ? '' : idle.busy || idle.shutdown_in > 0 ? `stops ${idle.shutdown_in} min after going idle` : 'stopping soon'}</span></div></div>
      <div class="dv-sec"><h5>Disks</h5>${m.disks.map(d => `<div class="kv"><span>${esc(d.mount)}</span><span class="k">${GB(d.used)} / ${GB(d.total)} GB</span></div>${meter(d.used / d.total * 100)}`).join('')}
        <div class="kv"><span class="k">Load 1 / 5 / 15 min</span><span>${m.cpu.load.map(l => l.toFixed(2)).join(' / ')}</span></div></div>
      <div class="dv-sec last"><h5>Memory by program</h5>${m.top.slice(0, 10).map(t =>
        `<div class="kv"><span>${esc(t.name)}${t.count > 1 ? ` <span class="k">×${t.count}</span>` : ''}</span><span class="k">${GB(t.bytes)} GB</span></div>`).join('')}</div>`;
  }

  function chart(h) {
    if (!h || h.length < 2) return '<p class="note">Collecting…</p>';
    const t0 = h.at(-1)[0] - 1800;
    const x = t => Math.max(0, (t - t0) / 1800 * 300), y = v => 82 - v / 100 * 80;
    const line = (i, c) => `<polyline fill="none" stroke="var(${c})" stroke-width="1.6" vector-effect="non-scaling-stroke" stroke-linejoin="round" points="${
      h.map(p => `${x(p[0]).toFixed(1)},${y(p[i]).toFixed(1)}`).join(' ')}"/>`;
    const grid = [25, 50, 75].map(v => `<line x1="0" x2="300" y1="${y(v)}" y2="${y(v)}" stroke="#222226" vector-effect="non-scaling-stroke"/>`).join('');
    return `<svg class="chart" viewBox="0 0 300 84" preserveAspectRatio="none">${grid}${line(2, '--mem')}${line(1, '--cpu')}</svg>`;
  }

  const onVisible = () => { if (!document.hidden) { refresh(); if (status?.state === 'running') metrics(); } };
  document.addEventListener('visibilitychange', onVisible);
  refresh();
  return () => { clearTimeout(statusTimer); clearTimeout(metricsTimer); document.removeEventListener('visibilitychange', onVisible); };
}

// --- When Files, Desktop or Terminal can't reach the dev box ------------------------------------------------

const never = new Promise(() => {});

/**
 * The request, raced against the panel's status: Tailscale gives no quick error when the devbox is off (requests
 * just hang), but the control panel answers at once. Resolves {res} with the request's result, or {down} with
 * the panel's status as soon as that says the devbox isn't running. If the panel can't be reached, only the request
 * counts.
 */
export function orDown(request) {
  const down = devboxStatus().then(s => s.state === 'running' ? never : { down: s }, () => never);
  return Promise.race([request.then(res => ({ res })), down]);
}

/**
 * In el: that the devbox is stopped (or starting, stopping), to be started from the Devbox section. Resolves once
 * it is running again, for the caller to try again.
 */
export function downCard(el, s) {
  return new Promise(resolve => {
    const draw = () => {
      const off = s.state === 'stopped';
      el.innerHTML = `<div class="stopped-card"><b>${off ? 'The devbox is off' : esc(LABELS[s.state] || s.state)}</b>
        ${off ? 'Start it from the Devbox section.' : 'This takes a minute or two.'}</div>`;
    };
    const poll = async () => {
      try { s = await devboxStatus(); } catch { /* keep the last state */ }
      if (s.state === 'running') return resolve();
      draw();
      setTimeout(poll, s.state === 'stopped' ? 10000 : 3000);
    };
    draw();
    setTimeout(poll, 3000);
  });
}

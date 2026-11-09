// When a schedule runs, in the user's time zone (the box keeps UTC): a time of day on some days, a cron line, or
// once. Every kind comes down to a cron line or a single instant, and `next` finds the first time after a moment.
//
//   {at: "08:00"}                          every day at 08:00
//   {at: "08:00", every: "weekday"}        Monday to Friday ("day", "weekday", "weekend", or days: "mon,thu")
//   {cron: "0 8 * * 1"}                    minute hour day-of-month month day-of-week (0 or 7 is Sunday)
//   {once: "2026-11-10 18:00"}             one time

export interface When {
  at?: string;
  every?: string;
  cron?: string;
  once?: string;
}

const DAYS = ['sun', 'mon', 'tue', 'wed', 'thu', 'fri', 'sat'];
const DAY_NAMES = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
const MONTHS = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec'];

interface Cron { min: number[]; hour: number[]; dom: Set<number>; mon: Set<number>; dow: Set<number>; anyDom: boolean; anyDow: boolean }

/** A time of day, "8:05" or "08:05", as [hour, minute]. */
function clock(s: string): [number, number] {
  const m = /^(\d{1,2}):(\d{2})$/.exec(s.trim());
  if (!m || +m[1] > 23 || +m[2] > 59) throw new Error(`not a time of day: "${s}" (use HH:MM, 24-hour)`);
  return [+m[1], +m[2]];
}

/** The days an `every` names, as a cron day-of-week field. */
function days(every: string | undefined): string {
  const e = (every ?? 'day').trim().toLowerCase();
  if (e === 'day' || e === 'daily' || e === 'days') return '*';
  if (e === 'weekday' || e === 'weekdays') return '1-5';
  if (e === 'weekend' || e === 'weekends') return '0,6';
  return e.split(/[\s,]+/).filter(Boolean).map(d => {
    const i = DAYS.indexOf(d.slice(0, 3));
    if (i < 0 || !DAY_NAMES[i].toLowerCase().startsWith(d)) throw new Error(`not a day: "${d}" (use day, weekday, weekend, or days like mon,thu)`);
    return String(i);
  }).join(',');
}

/** The cron line a schedule comes down to; null for a one-time one. */
export function cronLine(w: When): string | null {
  if (w.once) return null;
  if (w.cron) return w.cron.trim();
  if (!w.at) throw new Error('a schedule needs --at (with --every), --cron, or --once');
  const [h, m] = clock(w.at);
  return `${m} ${h} * * ${days(w.every)}`;
}

function field(s: string, lo: number, hi: number, names?: string[]): number[] {
  const out = new Set<number>();
  for (const part of s.split(',')) {
    const [range, stepS] = part.split('/');
    const step = stepS === undefined ? 1 : Number(stepS);
    if (!Number.isInteger(step) || step < 1) throw new Error(`bad step in "${part}"`);
    const val = (v: string) => {
      const i = names?.indexOf(v.toLowerCase().slice(0, 3)) ?? -1;
      const n = i >= 0 ? i + (lo === 1 ? 1 : 0) : Number(v);
      if (!Number.isInteger(n) || n < lo || n > hi) throw new Error(`"${v}" is out of range ${lo}-${hi}`);
      return n;
    };
    let a: number, b: number;
    if (range === '*') { a = lo; b = hi; } else if (range.includes('-')) { const [x, y] = range.split('-'); a = val(x); b = val(y); } else { a = val(range); b = stepS === undefined ? a : hi; }
    for (let i = a; i <= b; i += step) out.add(i);
  }
  return [...out].sort((x, y) => x - y);
}

export function parseCron(line: string): Cron {
  const f = line.trim().split(/\s+/);
  if (f.length !== 5) throw new Error(`a cron line has 5 fields (minute hour day month weekday): "${line}"`);
  const dow = new Set(field(f[4], 0, 7, DAYS).map(d => d % 7));
  return {
    min: field(f[0], 0, 59), hour: field(f[1], 0, 23), dom: new Set(field(f[2], 1, 31)), mon: new Set(field(f[3], 1, 12, MONTHS)), dow,
    anyDom: f[2] === '*', anyDow: f[4] === '*',
  };
}

/** A moment's wall clock in a time zone. */
function local(t: number, tz: string) {
  const p: Record<string, number> = {};
  for (const x of new Intl.DateTimeFormat('en-US', { timeZone: tz, hourCycle: 'h23', year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric' }).formatToParts(t)) {
    if (x.type !== 'literal') p[x.type] = Number(x.value);
  }
  return { y: p.year, mo: p.month, d: p.day, h: p.hour, mi: p.minute, s: p.second };
}

/** The moment a wall-clock time in a time zone is. */
export function instant(y: number, mo: number, d: number, h: number, mi: number, tz: string): number {
  const guess = Date.UTC(y, mo - 1, d, h, mi);
  const off = (t: number) => { const l = local(t, tz); return Date.UTC(l.y, l.mo - 1, l.d, l.h, l.mi, l.s) - Math.floor(t / 1000) * 1000; };
  let t = guess - off(guess);
  const o2 = off(t);
  if (guess - o2 !== t) t = guess - o2;
  return t;
}

/** "2026-11-10 18:00" (or with a T) in the time zone, as a moment. */
export function parseOnce(s: string, tz: string): number {
  const m = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{1,2}):(\d{2})$/.exec(s.trim());
  if (!m) throw new Error(`not a date and time: "${s}" (use "YYYY-MM-DD HH:MM")`);
  return instant(+m[1], +m[2], +m[3], +m[4], +m[5], tz);
}

/** Throws if the time zone isn't one. */
export function checkZone(tz: string) {
  new Intl.DateTimeFormat('en-US', { timeZone: tz });
}

/** Checks a schedule's timing, throwing with what's wrong. */
export function check(w: When, tz: string) {
  const kinds = [w.once, w.cron, w.at].filter(Boolean).length;
  if (kinds !== 1) throw new Error('a schedule needs exactly one of --at (with --every), --cron, or --once');
  if (w.every && !w.at) throw new Error('--every goes with --at');
  if (w.once) parseOnce(w.once, tz);
  else parseCron(cronLine(w)!);
}

/** The first time the schedule runs after `after`, or null when it never will again. */
export function next(w: When, after: number, tz: string): number | null {
  if (w.once) { const t = parseOnce(w.once, tz); return t > after ? t : null; }
  const c = parseCron(cronLine(w)!);
  const start = local(after, tz);
  for (let i = 0; i < 366 * 5; i++) {
    const day = new Date(Date.UTC(start.y, start.mo - 1, start.d + i));
    const y = day.getUTCFullYear(), mo = day.getUTCMonth() + 1, d = day.getUTCDate(), dow = day.getUTCDay();
    if (!c.mon.has(mo)) continue;
    // As cron has it: with both day fields set, either one matching is enough.
    const okDom = c.dom.has(d), okDow = c.dow.has(dow);
    if (!(c.anyDom && c.anyDow) && !(c.anyDom ? okDow : c.anyDow ? okDom : okDom || okDow)) continue;
    for (const h of c.hour) for (const mi of c.min) {
      const t = instant(y, mo, d, h, mi, tz);
      if (t > after) return t;
    }
  }
  return null;
}

/** The schedule in words: "every day at 08:00", "weekdays at 08:00", "once, 10 Nov 2026 18:00", "cron 0 8 * * 1". */
export function describe(w: When): string {
  if (w.once) return `once, ${w.once}`;
  if (w.cron) return `cron ${w.cron}`;
  const d = days(w.every);
  const on = d === '*' ? 'every day' : d === '1-5' ? 'weekdays' : d === '0,6' ? 'weekends'
    : d.split(',').map(i => DAY_NAMES[+i].slice(0, 3)).join(', ');
  return `${on} at ${w.at}`;
}

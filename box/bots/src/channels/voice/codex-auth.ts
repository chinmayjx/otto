// The Codex ChatGPT sign-in (~/.codex/auth.json, or $CODEX_HOME) as a way into the Realtime API.
//
// A call does not use the access token itself. That token mints a short-lived realtime key
// (POST /v1/realtime/client_secrets), and the call is opened with the key. The access token lasts
// days; within two minutes of expiry this refreshes it the way Codex does and writes the new tokens
// back, so Codex stays signed in. A failed refresh leaves the file alone.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const CLIENT_ID = 'app_EMoamEEZ73f0CkXaXp7hrann';
const TOKEN_URL = 'https://auth.openai.com/oauth/token';
const SECRETS_URL = 'https://api.openai.com/v1/realtime/client_secrets';
/** Refresh this long before the access token actually expires, so a call doesn't start on a dying one. */
const MARGIN_S = 120;

export interface CodexAuth {
  auth_mode?: string;
  tokens?: {
    id_token?: string;
    access_token?: string;
    refresh_token?: string;
    oauth_provenance?: { issuer?: string; client_id?: string };
    [k: string]: unknown;
  };
  last_refresh?: string;
  [k: string]: unknown;
}

export function codexHome(): string {
  return process.env.CODEX_HOME || path.join(os.homedir(), '.codex');
}

export function authFile(): string {
  return path.join(codexHome(), 'auth.json');
}

/** `exp` from a JWT, in seconds, or null when the token isn't a JWT or has no exp. */
export function jwtExp(token: string): number | null {
  const part = token.split('.')[1];
  if (!part) return null;
  try {
    const claims = JSON.parse(Buffer.from(part, 'base64url').toString());
    return typeof claims.exp === 'number' ? claims.exp : null;
  } catch {
    return null;
  }
}

/** True when the token should still be good for a call that starts now. */
export function accessTokenUsable(token: string, nowS = Date.now() / 1000): boolean {
  if (!token) return false;
  const exp = jwtExp(token);
  return exp === null || exp - nowS > MARGIN_S;
}

/** The auth file with a refresh response merged in. Keeps every field the response doesn't replace. */
export function applyRefresh(current: CodexAuth, data: { access_token?: string; id_token?: string; refresh_token?: string }, now = new Date()): CodexAuth {
  if (typeof data.access_token !== 'string' || !data.access_token) throw new Error('Codex refresh returned no access token');
  return {
    ...current,
    tokens: {
      ...current.tokens,
      access_token: data.access_token,
      id_token: typeof data.id_token === 'string' ? data.id_token : current.tokens?.id_token,
      refresh_token: typeof data.refresh_token === 'string' ? data.refresh_token : current.tokens?.refresh_token,
    },
    last_refresh: now.toISOString(),
  };
}

function readAuth(): CodexAuth {
  let raw: string;
  try {
    raw = fs.readFileSync(authFile(), 'utf8');
  } catch (e: any) {
    if (e.code === 'ENOENT') throw new Error('Codex is not signed in (no auth file). Run `codex login`.');
    throw e;
  }
  const o = JSON.parse(raw);
  if (o?.auth_mode && o.auth_mode !== 'chatgpt') throw new Error(`Codex auth is "${o.auth_mode}", not a ChatGPT sign-in. Run \`codex login\`.`);
  if (typeof o?.tokens?.access_token !== 'string') throw new Error('Codex auth has no access token. Run `codex login`.');
  return o;
}

function writeAuth(o: CodexAuth): void {
  const file = authFile();
  const tmp = `${file}.${process.pid}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(o, null, 2) + '\n', { mode: 0o600 });
  fs.renameSync(tmp, file);
}

let pending: Promise<string> | null = null;

/** A usable access token, refreshing the stored one first when it's near expiry. */
export function codexAccessToken(): Promise<string> {
  const token = readAuth().tokens!.access_token!;
  if (accessTokenUsable(token)) return Promise.resolve(token);
  return pending ??= refresh().finally(() => { pending = null; });
}

async function refresh(): Promise<string> {
  const current = readAuth();
  const token = current.tokens!.access_token!;
  if (accessTokenUsable(token)) return token;
  const refreshToken = current.tokens!.refresh_token;
  if (typeof refreshToken !== 'string' || !refreshToken) throw new Error('Codex sign-in has expired and has no refresh token. Run `codex login`.');
  const r = await fetch(TOKEN_URL, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      client_id: current.tokens!.oauth_provenance?.client_id || CLIENT_ID,
      grant_type: 'refresh_token',
      refresh_token: refreshToken,
      scope: 'openid profile email',
    }),
  });
  if (!r.ok) {
    const again = safeRead();
    if (again && accessTokenUsable(again.tokens?.access_token || '')) return again.tokens!.access_token!;
    throw new Error(`Codex sign-in could not be refreshed (${r.status}). Run \`codex login\`.`);
  }
  const data = await r.json().catch(() => ({}));
  const next = applyRefresh(current, data);
  // Codex may have refreshed while this request was out. Don't overwrite a newer sign-in.
  const latest = safeRead();
  if (latest?.tokens?.refresh_token && latest.tokens.refresh_token !== refreshToken && accessTokenUsable(latest.tokens.access_token || '')) {
    return latest.tokens.access_token!;
  }
  writeAuth(next);
  return next.tokens!.access_token!;
}

function safeRead(): CodexAuth | null {
  try { return readAuth(); } catch { return null; }
}

/** A short-lived realtime key for this session, minted with the Codex access token. */
export async function mintRealtimeKey(accessToken: string, session: unknown): Promise<string> {
  const r = await fetch(SECRETS_URL, {
    method: 'POST',
    headers: { authorization: `Bearer ${accessToken}`, 'content-type': 'application/json' },
    body: JSON.stringify({ session }),
  });
  const text = await r.text();
  if (!r.ok) throw new Error(`OpenAI refused a realtime key (${r.status}): ${redact(text).slice(0, 300)}`);
  let value = '';
  try { value = JSON.parse(text).value; } catch { /* the message below */ }
  if (!value) throw new Error('OpenAI returned no realtime key');
  return value;
}

function redact(s: string): string {
  return s.replace(/(sk-[\w-]{6,}|ek_[\w-]{6,}|eyJ[\w-]{20,})/g, '[key]');
}

// The Codex ChatGPT sign-in (~/.codex/auth.json, or $CODEX_HOME) as a way into the Realtime API.
//
// A call does not use the access token itself. That token mints a short-lived realtime key
// (POST /v1/realtime/client_secrets), and the call is opened with the key. The access token lasts
// days. Only reads the file: Codex itself renews the sign-in whenever it runs. Renewing it here too
// would race Codex for the one-use refresh token, and a refresh token used twice can get the whole
// sign-in revoked, signing Codex out.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const SECRETS_URL = 'https://api.openai.com/v1/realtime/client_secrets';
/** A token this close to expiry isn't used, so a call doesn't start on a dying one. */
const MARGIN_S = 60;

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

/** The access token, while it's still good for a call that starts now. */
export function codexAccessToken(): Promise<string> {
  try {
    const token = readAuth().tokens!.access_token!;
    if (!accessTokenUsable(token)) {
      throw new Error('The Codex sign-in has expired. Run any codex command (it renews the sign-in), or `codex login`.');
    }
    return Promise.resolve(token);
  } catch (e) {
    return Promise.reject(e);
  }
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

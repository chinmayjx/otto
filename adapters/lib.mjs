// What the adapters share: where Otto is, the instructions block, and small file edits that leave the rest of
// a user's file as it was.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const OTTO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const HOME = process.env.HOME;

const BEGIN = '<!-- otto:begin (managed by otto/adapters; edits inside are replaced) -->';
const END = '<!-- otto:end -->';

/** Puts Otto's instructions into an agent's instructions file, between markers, replacing an earlier copy. */
export function installInstructions(file) {
  const block = `${BEGIN}\n${fs.readFileSync(path.join(OTTO, 'adapters/instructions.md'), 'utf8').trim()}\n${END}`;
  let text = '';
  try { text = fs.readFileSync(file, 'utf8'); } catch {}
  const re = new RegExp(`${escape(BEGIN)}[\\s\\S]*?${escape(END)}`);
  text = re.test(text) ? text.replace(re, block) : `${text.replace(/\s*$/, '')}${text.trim() ? '\n\n' : ''}${block}\n`;
  write(file, text);
}

export function write(file, text) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  // Keep a symlinked file a symlink (e.g. ~/.codex/AGENTS.md -> ~/.claude/CLAUDE.md).
  const real = fs.existsSync(file) ? fs.realpathSync(file) : file;
  fs.writeFileSync(real + '.otto-tmp', text);
  fs.renameSync(real + '.otto-tmp', real);
}

/** The first line of a prompt, shortened, as a tab group's title. */
export function titleOf(text) {
  const first = String(text).split('\n').map(s => s.trim()).find(Boolean) ?? '';
  return first.length > 40 ? first.slice(0, 40).replace(/\s+\S*$/, '') + '…' : first;
}

const escape = s => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

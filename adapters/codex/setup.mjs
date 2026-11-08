#!/usr/bin/env node
// Sets Codex up to use Otto, in ~/.codex/config.toml (a copy of the old one is kept as config.toml.otto-bak):
//   - otto's session id comes from this adapter (OTTO_SESSION_COMMAND, set for every command Codex runs);
//   - the eyes MCP server is registered;
//   - the workspace sandbox may use the network, so otto can reach the relay on localhost (without it, otto
//     and the artifact hub fail with DNS or connection errors);
//   - Otto's instructions are in ~/.codex/AGENTS.md, between markers.
// `setup.mjs trust <dir>...` also marks project folders trusted: Codex won't work in an untrusted folder, and
// its phone apps say only "Can't verify project trust".
// It checks Codex's own login but never touches it.
import fs from 'node:fs';
import path from 'node:path';
import { parse, stringify } from 'smol-toml';
import { OTTO, HOME, installInstructions, write } from '../lib.mjs';

const codexHome = process.env.CODEX_HOME ?? path.join(HOME, '.codex');
const file = path.join(codexHome, 'config.toml');
const old = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '';
const config = parse(old);

const policy = (config.shell_environment_policy ??= {});
policy.set = { ...policy.set, OTTO_SESSION_COMMAND: `node ${path.join(OTTO, 'adapters/codex/session.mjs')}` };
config.mcp_servers ??= {};
config.mcp_servers['otto-eyes'] = { command: 'node', args: [path.join(OTTO, 'mcp/dist/main.js')] };
config.sandbox_workspace_write = { ...config.sandbox_workspace_write, network_access: true };
config.projects ??= {};
const trust = process.argv[2] === 'trust' ? process.argv.slice(3) : [];
for (const dir of trust) config.projects[path.resolve(dir)] = { ...config.projects[path.resolve(dir)], trust_level: 'trusted' };

const text = stringify(config) + '\n';
if (text !== old) {
  if (old) fs.writeFileSync(file + '.otto-bak', old);
  write(file, text);
}
console.log(`config: ${file} (session command, otto-eyes, sandbox network${trust.length ? `, trusted: ${trust.join(', ')}` : ''})`);

const instructions = path.join(codexHome, 'AGENTS.md');
installInstructions(instructions);
console.log(`instructions: ${instructions}${fs.lstatSync(instructions).isSymbolicLink() ? ` (-> ${fs.realpathSync(instructions)})` : ''}`);

if (!fs.existsSync(path.join(codexHome, 'auth.json'))) console.log('Codex is not logged in yet: run `codex login`.');

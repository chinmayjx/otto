#!/usr/bin/env node
// Sets Claude Code up to use Otto, for every session of this user:
//   - otto's session id comes from this adapter (OTTO_SESSION_COMMAND in ~/.claude/settings.json's env);
//   - otto is allowed without a prompt (Bash(otto:*));
//   - the eyes MCP server is registered (user scope);
//   - Otto's instructions are in ~/.claude/CLAUDE.md, between markers.
// It checks Claude's own login but never touches it.
import fs from 'node:fs';
import path from 'node:path';
import { OTTO, HOME, installInstructions, write } from '../lib.mjs';

const settingsFile = path.join(HOME, '.claude/settings.json');
const settings = fs.existsSync(settingsFile) ? JSON.parse(fs.readFileSync(settingsFile, 'utf8')) : {};
settings.env = { ...settings.env, OTTO_SESSION_COMMAND: `node ${path.join(OTTO, 'adapters/claude/session.mjs')}` };
settings.permissions ??= {};
settings.permissions.allow = [...new Set([...(settings.permissions.allow ?? []), 'Bash(otto:*)'])];
write(settingsFile, JSON.stringify(settings, null, 2) + '\n');
console.log(`settings: ${settingsFile} (session command, Bash(otto:*) allowed)`);

// User-scope MCP servers live in ~/.claude.json (what `claude mcp add --scope user` writes).
const eyes = path.join(OTTO, 'mcp/dist/main.js');
const stateFile = path.join(HOME, '.claude.json');
const state = fs.existsSync(stateFile) ? JSON.parse(fs.readFileSync(stateFile, 'utf8')) : {};
state.mcpServers = { ...state.mcpServers, 'otto-eyes': { type: 'stdio', command: 'node', args: [eyes], env: {} } };
write(stateFile, JSON.stringify(state, null, 2) + '\n');
console.log(`MCP: otto-eyes -> node ${eyes} (${stateFile})`);

const instructions = path.join(HOME, '.claude/CLAUDE.md');
installInstructions(instructions);
console.log(`instructions: ${instructions}`);

if (!fs.existsSync(path.join(HOME, '.claude/.credentials.json'))) console.log('Claude Code is not logged in yet: run `claude` and log in.');

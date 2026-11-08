#!/usr/bin/env node
// otto-eyes: an MCP server (stdio) with three tools that return screenshots inline, for agents that speak MCP.
// Everything else agents do through the otto CLI; each tool here is the CLI command its description names,
// with the image returned instead of a file path, downscaled to about 1,500px on its long side.
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { z } from 'zod';
import { loadConfig, session, browser, phone, phoneScale, desktopShot } from '@otto/cli';

const MAX = 1500;
const config = loadConfig();
const server = new McpServer({ name: 'otto-eyes', version: '0.1.0' });

const image = (data: Buffer, mimeType: string, note: string) => ({
  content: [{ type: 'image' as const, data: data.toString('base64'), mimeType }, { type: 'text' as const, text: note }],
});
const failed = (e: any) => ({ content: [{ type: 'text' as const, text: e.message }], isError: true });

server.registerTool('phone_screenshot', {
  description: 'Screenshot of the phone, inline. Same as `otto phone screenshot`. Scaled to about 1500px on its long side unless full=true; tap coordinates are in full-screen pixels (divide by the scale given). Prefer `otto phone ui` to find elements.',
  inputSchema: { full: z.boolean().optional().describe('Full resolution') },
}, async ({ full }) => {
  try {
    const scale = full ? 1 : await phoneScale(config, MAX);
    const { result, body } = await phone(config, 'screenshot', { scale, format: 'jpg', quality: 80 });
    return image(body!, `image/${result.format}`, `${result.width}x${result.height}${scale < 1 ? ` (scaled by ${scale}: divide image coordinates by ${scale} for taps)` : ''}`);
  } catch (e) { return failed(e); }
});

server.registerTool('browser_screenshot', {
  description: "Screenshot of your session's current tab in the phone's browser, inline. Same as `otto browser screenshot`. For reading or acting on the page, `otto browser snapshot` is cheaper and gives element refs.",
  inputSchema: { full: z.boolean().optional().describe('The whole page, not just what is in view') },
}, async ({ full }) => {
  try {
    const png = Buffer.from(await (await browser(config, session(config), 'screenshot', { full })).arrayBuffer());
    return image(png, 'image/png', 'phone browser, current tab');
  } catch (e) { return failed(e); }
});

server.registerTool('desktop_screenshot', {
  description: "Screenshot of the box's desktop (the VNC display), inline. Same as `otto desktop screenshot`. Scaled to about 1500px on its long side unless full=true.",
  inputSchema: { full: z.boolean().optional().describe('Full resolution') },
}, async ({ full }) => {
  try {
    return image(desktopShot(config, full ? 0 : MAX), 'image/png', `desktop ${config.display}`);
  } catch (e) { return failed(e); }
});

await server.connect(new StdioServerTransport());

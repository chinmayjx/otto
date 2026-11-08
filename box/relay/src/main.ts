#!/usr/bin/env node
// otto-relay: the box's side of the phone. Holds the phone's tunnel on the box's tailnet address and serves the
// phone's browser and device API to agents on 127.0.0.1. See api.ts for the local API.
import { loadConfig } from './config.js';
import { Sessions } from './sessions.js';
import { Browser } from './browser.js';
import { Device } from './device.js';
import { Tunnel } from './tunnel.js';
import { Clips } from './clips.js';
import { serve, serveBox } from './api.js';

const config = loadConfig();
const sessions = new Sessions(config.stateFile);
const tunnel = new Tunnel(config.token);
const browser = new Browser(tunnel, sessions);
const device = new Device(tunnel);
const clips = new Clips(config.clipsDir);

tunnel.listen(config.tailnetHost, config.tunnelPort, config.pipePort);
serve(config.localPort, browser, device, sessions, clips);
serveBox(tunnel, clips);
clips.prune();

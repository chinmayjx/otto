#!/usr/bin/env node
// otto-relay: the box's side of the phone. Holds the phone's links (browser and device) on the box's tailnet
// address and serves them to agents on 127.0.0.1. See api.ts for the local API.
import { loadConfig } from './config.js';
import { Sessions } from './sessions.js';
import { Browser } from './browser.js';
import { Device } from './device.js';
import { serve } from './api.js';

const config = loadConfig();
const sessions = new Sessions(config.stateFile);
const browser = new Browser(sessions, config.browserToken);
const device = new Device(config.deviceToken);

browser.listen(config.tailnetHost, config.browserPort, config.pipePort);
device.listen(config.tailnetHost, config.devicePort);
serve(config.localPort, browser, device, sessions);

// A simulated Shelly Plug M Gen3, enough of one to run power-journal.js on a
// PC. The script under test is not copied or reimplemented here -- the real
// file is loaded and executed against these stand-ins, so what passes is what
// gets uploaded.
//
// The limits are the ones measured on the device, not the ones in the docs:
// a storage value is dropped above 1022 bytes, the thirteenth entry is
// refused, and both failures are silent. Modelling them here is the point --
// they are what the script has to notice by reading back.

'use strict';

const fs = require('fs');
const path = require('path');

const SOURCE = path.join(__dirname, '..', 'power-journal.js');

const STORAGE_VALUE_LIMIT = 1022;
const STORAGE_ITEM_LIMIT = 12;

function createPlug(options) {
  const opt = options || {};

  const plug = {
    unixtime: opt.unixtime || 1785870000,
    uptimeMs: opt.uptimeMs === undefined ? 3600000 : opt.uptimeMs,
    // The meter is carried at full precision and reported quantised, the way
    // the plug does it: aenergy.total has three decimals in Wh, so whole mWh.
    // Rounding on the way in instead would make the simulated plug run a few
    // percent fast at low power and quietly poison every average in here.
    meterExact: opt.meterMwh || 0,
    watt: opt.watt === undefined ? 0 : opt.watt,
    output: opt.output === undefined ? true : opt.output,
    storage: Object.assign({}, opt.storage),
    kvs: Object.assign({}, opt.kvs),
    // Set to make every KVS.Set fail, the way a plug that has dropped off the
    // network would.
    kvsFail: false,
    kvsWrites: 0,
    kvsRev: 0,
    storageWrites: 0,
    logs: [],
    timers: [],
    pending: [],
    nextTimerId: 1,
    verbose: !!opt.verbose,
  };

  function print(message) {
    plug.logs.push(String(message));
    if (plug.verbose) console.log('   ' + message);
  }

  const storage = {
    setItem(key, value) {
      if (typeof value !== 'string') return;
      if (value.length > STORAGE_VALUE_LIMIT) return;
      if (!Object.prototype.hasOwnProperty.call(plug.storage, key) &&
          Object.keys(plug.storage).length >= STORAGE_ITEM_LIMIT) return;
      plug.storage[key] = value;
      plug.storageWrites++;
    },
    getItem(key) {
      return Object.prototype.hasOwnProperty.call(plug.storage, key) ? plug.storage[key] : null;
    },
    removeItem(key) {
      delete plug.storage[key];
    },
  };

  const Shelly = {
    getComponentStatus(name) {
      if (name === 'sys') {
        return { unixtime: plug.unixtime, uptime: Math.floor(plug.uptimeMs / 1000) };
      }
      if (name === 'switch:0') {
        return {
          output: plug.output,
          apower: plug.output ? plug.watt : 0,
          aenergy: { total: Math.floor(plug.meterExact) / 1000 },
        };
      }
      return null;
    },
    getUptimeMs() {
      return plug.uptimeMs;
    },
    // RPC is asynchronous on the device, so callbacks queue here and only run
    // when the harness drains them. That is what lets a test see the script
    // with a write still in flight.
    call(method, params, callback, userdata) {
      if (!callback) return;
      plug.pending.push(() => {
        if (method === 'KVS.Get') {
          const value = plug.kvs[params.key];
          if (value === undefined) {
            callback(null, -105, 'No such key', userdata);
            return;
          }
          callback({ etag: 'e', value: JSON.parse(JSON.stringify(value)) }, 0, '', userdata);
          return;
        }
        if (method === 'KVS.Set') {
          if (plug.kvsFail) {
            callback(null, -104, 'simulated failure', userdata);
            return;
          }
          const text = JSON.stringify(params.value);
          if (text.length > 253) {
            callback(null, -103, 'value too long', userdata);
            return;
          }
          plug.kvs[params.key] = JSON.parse(text);
          plug.kvsWrites++;
          callback({ rev: ++plug.kvsRev }, 0, '', userdata);
          return;
        }
        callback(null, -1, 'unsupported ' + method, userdata);
      });
    },
  };

  const Timer = {
    set(ms, repeat, callback) {
      const timer = { id: plug.nextTimerId++, ms, repeat, callback };
      plug.timers.push(timer);
      return timer.id;
    },
    clear(id) {
      plug.timers = plug.timers.filter((t) => t.id !== id);
    },
  };

  const HTTPServer = {
    registerEndpoint(name, handler) {
      plug.endpoint = { name, handler };
    },
  };

  function drain() {
    let guard = 0;
    while (plug.pending.length > 0 && guard++ < 100) {
      plug.pending.shift()();
    }
  }

  function advance(seconds) {
    plug.unixtime += seconds;
    plug.uptimeMs += seconds * 1000;
    const drawn = plug.output ? plug.watt : 0;
    plug.meterExact += (drawn * 1000 * seconds) / 3600;
  }

  // What the script would read right now.
  function meter() {
    return Math.floor(plug.meterExact);
  }

  // A counter that was reset, or replaced by a device that does not keep it.
  function setMeter(mwh) {
    plug.meterExact = mwh;
  }

  // Runs the one-shot timers the startup path sets, advancing the clock by
  // each one's delay, until sampling has begun.
  function settle(rounds) {
    for (let i = 0; i < (rounds || 8); i++) {
      const timer = plug.timers.find((t) => !t.repeat);
      if (!timer) return;
      plug.timers = plug.timers.filter((t) => t !== timer);
      advance(timer.ms / 1000);
      timer.callback();
      drain();
    }
  }

  function sampleTimer() {
    return plug.timers.find((t) => t.repeat);
  }

  // One sampling interval: time moves, the meter moves with it, the script
  // takes its reading.
  function tick(count) {
    const timer = sampleTimer();
    if (!timer) throw new Error('the script is not sampling yet');
    for (let i = 0; i < (count === undefined ? 1 : count); i++) {
      advance(timer.ms / 1000);
      timer.callback();
      drain();
    }
  }

  // Hold this many watts for this many samples.
  function feed(watt, count) {
    plug.watt = watt;
    plug.output = watt > 0;
    tick(count);
  }

  function boot() {
    let source = fs.readFileSync(SOURCE, 'utf8');
    // PJ_STRIPPED=1 runs the tests against what the device actually gets,
    // which is the file with its comments removed to fit the 20480 byte limit.
    if (process.env.PJ_STRIPPED === '1') source = require('../tools/strip').strip(source);
    const factory = new Function(
      'Shelly', 'Script', 'Timer', 'HTTPServer', 'print',
      '\n' + source + '\nreturn selftest();\n'
    );
    plug.pj = factory(Shelly, { storage }, Timer, HTTPServer, print);
    drain();
    settle();
    return plug.pj;
  }

  // Cuts the power: the device comes back with the same flash but a fresh
  // uptime, and the clock has moved on without anyone watching.
  function powerCut(outageSeconds) {
    plug.unixtime += outageSeconds;
    plug.uptimeMs = 0;
    plug.timers = [];
    plug.pending = [];
    plug.logs = [];
    plug.pj = null;
  }

  // Restarts only the script; the device kept running throughout.
  function restartScript() {
    plug.timers = [];
    plug.pending = [];
    plug.logs = [];
    plug.pj = null;
  }

  function request(query) {
    const response = { headers: null, code: 0, body: null, sent: false };
    response.send = () => { response.sent = true; };
    plug.endpoint.handler({ query: query || '', method: 'GET' }, response);
    return response;
  }

  // Every block in the archive, oldest first, read back the way the app would.
  function archive() {
    const meta = plug.storage.m;
    if (!meta) return [];
    const pages = meta.split('|')[2];
    if (!pages) return [];
    const out = [];
    for (const key of pages.split(',')) {
      const page = plug.storage[key];
      if (!page) continue;
      const fields = page.split('|');
      let at = Number(fields[0]);
      for (let i = 1; i < fields.length; i++) {
        const comma = fields[i].indexOf(',');
        const duration = Number(comma < 0 ? fields[i] : fields[i].slice(0, comma));
        const energy = comma < 0 ? 0 : Number(fields[i].slice(comma + 1));
        out.push({ start: at, duration, energy, page: key });
        at += duration;
      }
    }
    return out;
  }

  function logsMatching(fragment) {
    return plug.logs.filter((line) => line.indexOf(fragment) >= 0);
  }

  return Object.assign(plug, {
    boot, tick, feed, advance, settle, drain, powerCut, restartScript,
    request, archive, logsMatching, sampleTimer, meter, setMeter,
  });
}

module.exports = { createPlug, STORAGE_VALUE_LIMIT, STORAGE_ITEM_LIMIT };

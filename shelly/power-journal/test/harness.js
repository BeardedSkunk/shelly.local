// A simulated Shelly Plug M Gen3, enough of one to run power-journal.js on a
// PC. The script under test is not copied or reimplemented here -- the real
// file is loaded and executed against these stand-ins, so what passes is what
// gets uploaded.
//
// The limits are the ones measured on the device, not the ones in the docs:
// a storage value is dropped above 1022 bytes, the thirteenth entry is
// refused, and both failures are silent. Modelling them here is the point --
// they are what the script has to notice by reading back.
//
// The decoder at the bottom is deliberately a second, independent
// implementation of the page format. If it and the script ever disagree, one
// of them is wrong, and a test that reads the archive back through this one
// is a real check rather than a tautology.

'use strict';

const fs = require('fs');
const path = require('path');

const SOURCE = path.join(__dirname, '..', 'power-journal.js');

const STORAGE_VALUE_LIMIT = 1022;
const STORAGE_ITEM_LIMIT = 12;
const A64 = '#$%&\'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[]^_`abc';

function createPlug(options) {
  const opt = options || {};

  const plug = {
    unixtime: opt.unixtime || 1785870000,
    uptimeMs: opt.uptimeMs === undefined ? 3600000 : opt.uptimeMs,
    utcOffset: opt.utcOffset === undefined ? 7200 : opt.utcOffset,
    // Two lifetime counters, exactly as the plug keeps them: gross counts
    // everything that crossed the meter in either direction, returned counts
    // only the part that went back out. Both are carried at full precision and
    // reported quantised, because aenergy.total has three decimals in Wh.
    // Rounding on the way in instead would make the simulated plug run a few
    // percent fast at low power and quietly poison every average in here.
    grossExact: opt.grossMwh || 0,
    returnedExact: opt.returnedMwh || 0,
    grossVisible: opt.grossMwh || 0,
    returnedVisible: opt.returnedMwh || 0,
    // The real plug does not advance its counters continuously -- they stand
    // still for minutes and then jump by a fixed quantum -- so this holds the
    // reported value back until that long has passed. Zero means a counter
    // that keeps up.
    meterStepSec: opt.meterStepSec || 0,
    // The truer model of the two, and the one a Plug M Gen3 actually follows:
    // the counter does not step on a clock, it steps when a whole packet of
    // energy has accrued. About 206 mWh, so five hours at forty milliwatts and
    // seven seconds at a hundred watts. Zero means a counter of infinite
    // resolution, which is what every test that does not care assumes.
    meterQuantumMwh: opt.meterQuantumMwh || 0,
    lastStepAt: opt.unixtime || 1785870000,
    watt: opt.watt === undefined ? 0 : opt.watt,
    // Reverse metering: with it on the plug reports a plant's generation as
    // positive, so every reading means the opposite of what its sign says.
    reverse: opt.reverse === undefined ? false : opt.reverse,
    restartRequired: opt.restartRequired === undefined ? false : opt.restartRequired,
    output: opt.output === undefined ? true : opt.output,
    storage: Object.assign({}, opt.storage),
    kvs: Object.assign({}, opt.kvs),
    // Set to make every KVS.Set fail, the way a plug that has dropped off the
    // network would.
    kvsFail: false,
    kvsWrites: 0,
    kvsRev: 0,
    storageWrites: 0,
    // The attic: other scripts on the device, and every code append made to
    // them. Names matter because the journal finds the attic by name.
    scripts: opt.scripts === undefined ? [{ id: 9, name: 'pj-attic', code: '// attic\n' }] : opt.scripts,
    atticWrites: [],
    putCodeFail: false,
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
        return {
          unixtime: plug.unixtime,
          uptime: Math.floor(plug.uptimeMs / 1000),
          utc_offset: plug.utcOffset,
          // True after a config change that only takes effect on reboot.
          // Reverse metering is one of those, which is why the script has to
          // look at this before believing what GetConfig tells it.
          restart_required: plug.restartRequired,
        };
      }
      if (name === 'switch:0') {
        return {
          output: plug.output,
          apower: plug.output ? plug.watt : 0,
          aenergy: { total: Math.floor(plug.grossVisible) / 1000 },
          ret_aenergy: { total: Math.floor(plug.returnedVisible) / 1000 },
        };
      }
      return null;
    },
    getUptimeMs() {
      return plug.uptimeMs;
    },
    // Synchronous on the device, verified on a Plug M Gen3: no RPC needed to
    // find out which way round the meter is reporting.
    getComponentConfig(name) {
      if (name !== 'switch:0') return null;
      return { id: 0, reverse: plug.reverse };
    },
    // RPC is asynchronous on the device, so callbacks queue here and only run
    // when the harness drains them. That is what lets a test see the script
    // with a write still in flight.
    call(method, params, callback, userdata) {
      if (!callback) return;
      plug.pending.push(() => {
        if (method === 'KVS.Get') {
          const value = plug.kvs[params.key];
          if (value === undefined) return callback(null, -105, 'No such key', userdata);
          return callback({ etag: 'e', value: JSON.parse(JSON.stringify(value)) }, 0, '', userdata);
        }
        if (method === 'KVS.Set') {
          if (plug.kvsFail) return callback(null, -104, 'simulated failure', userdata);
          const text = JSON.stringify(params.value);
          if (text.length > 253) return callback(null, -103, 'value too long', userdata);
          plug.kvs[params.key] = JSON.parse(text);
          plug.kvsWrites++;
          return callback({ rev: ++plug.kvsRev }, 0, '', userdata);
        }
        if (method === 'Script.List') {
          return callback({ scripts: plug.scripts.map((s) => ({ id: s.id, name: s.name })) }, 0, '', userdata);
        }
        if (method === 'Script.PutCode') {
          if (plug.putCodeFail) return callback(null, -103, 'simulated failure', userdata);
          const target = plug.scripts.find((s) => s.id === params.id);
          if (!target) return callback(null, -105, 'no such script', userdata);
          target.code = params.append ? target.code + params.code : params.code;
          plug.atticWrites.push({ id: params.id, code: params.code });
          return callback({ len: target.code.length }, 0, '', userdata);
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
    while (plug.pending.length > 0 && guard++ < 200) {
      plug.pending.shift()();
    }
  }

  function advance(seconds) {
    plug.unixtime += seconds;
    plug.uptimeMs += seconds * 1000;
    const drawn = plug.output ? plug.watt : 0;
    const mwh = (Math.abs(drawn) * 1000 * seconds) / 3600;
    plug.grossExact += mwh;
    if (drawn < 0) plug.returnedExact += mwh;
    if (plug.meterQuantumMwh) {
      const q = plug.meterQuantumMwh;
      plug.grossVisible = Math.floor(plug.grossExact / q) * q;
      plug.returnedVisible = Math.floor(plug.returnedExact / q) * q;
    } else if (!plug.meterStepSec || plug.unixtime - plug.lastStepAt >= plug.meterStepSec) {
      plug.lastStepAt = plug.unixtime;
      plug.grossVisible = plug.grossExact;
      plug.returnedVisible = plug.returnedExact;
    }
  }

  // What the script would compute right now, signed.
  function netMwh() {
    return Math.floor(plug.grossVisible) - 2 * Math.floor(plug.returnedVisible);
  }

  // Counters that were reset, or a device that does not keep them.
  function setMeters(grossMwh, returnedMwh) {
    plug.grossExact = grossMwh;
    plug.grossVisible = grossMwh;
    plug.returnedExact = returnedMwh || 0;
    plug.returnedVisible = returnedMwh || 0;
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

  // One sampling interval: time moves, the meters move with it, the script
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

  // Hold this many watts for this many samples. Negative means exporting.
  function feed(watt, count) {
    plug.watt = watt;
    plug.output = true;
    tick(count);
  }

  // Hold this many watts for this many seconds, whatever the sample interval.
  function feedFor(watt, seconds) {
    const timer = sampleTimer();
    feed(watt, Math.round(seconds / (timer.ms / 1000)));
  }

  function boot() {
    let source = fs.readFileSync(SOURCE, 'utf8');
    // PJ_STRIPPED=1 runs the tests against what the device actually gets,
    // which is the file with its comments removed and its names shortened to
    // fit the 20480 byte limit.
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

  // Every block of one tier, oldest first, read back the way the app would.
  function tierBlocks(tier) {
    const meta = parseMeta(plug.storage.m);
    if (!meta) return [];
    const out = [];
    for (const key of meta.tiers[tier].pages) {
      const page = plug.storage[key];
      if (page === undefined) continue;
      for (const block of decodePage(page)) out.push(Object.assign({ page: key }, block));
    }
    return out;
  }

  function logsMatching(fragment) {
    return plug.logs.filter((line) => line.indexOf(fragment) >= 0);
  }

  return Object.assign(plug, {
    boot, tick, feed, feedFor, advance, settle, drain, powerCut, restartScript,
    request, tierBlocks, logsMatching, sampleTimer, netMwh, setMeters,
  });
}

// ---------------------------------------------------------------- the format

// Grid seconds and energy unit per tier, mirrored from the script's CFG. A
// test that changes one has to change the other, which is the point: the
// numbers are part of the format, not an implementation detail.
const TIERS = [
  { grid: 1, unit: 1 },
  { grid: 900, unit: 100 },
  { grid: 3600, unit: 1000 },
  { grid: 86400, unit: 10000 },
];

function encode(n) {
  let out = '';
  n = Math.round(n);
  for (;;) {
    const group = n % 32;
    n = Math.floor(n / 32);
    out += A64[n > 0 ? group + 32 : group];
    if (n === 0) return out;
  }
}
const encodeSigned = (n) => encode(n < 0 ? -n * 2 - 1 : n * 2);

function decodeAt(text, i) {
  let n = 0;
  let shift = 1;
  for (;;) {
    const code = text.charCodeAt(i++);
    const v = code < 92 ? code - 35 : code - 93 + 57;
    if (v < 0 || v > 63) throw new Error('bad character in page: ' + JSON.stringify(text[i - 1]));
    n += (v % 32) * shift;
    if (v < 32) return [n, i];
    shift *= 32;
  }
}
function decodeSignedAt(text, i) {
  const [v, next] = decodeAt(text, i);
  return [v % 2 === 1 ? -(v + 1) / 2 : v / 2, next];
}

// [{ tier, start, duration, energy }, ...] in real seconds and real mWh.
function decodePage(text) {
  const tier = text.charCodeAt(0) - 48;
  if (tier < 0 || tier >= TIERS.length) throw new Error('bad tier digit: ' + text[0]);
  const { grid, unit } = TIERS[tier];
  let [at, i] = decodeAt(text, 1);
  const out = [];
  while (i < text.length) {
    let steps, units;
    [steps, i] = decodeAt(text, i);
    [units, i] = decodeSignedAt(text, i);
    out.push({ tier, start: at, duration: steps * grid, energy: units * unit });
    at += steps * grid;
  }
  return out;
}

function parseMeta(text) {
  if (typeof text !== 'string') return null;
  const fields = text.split('|');
  if (fields.length !== 5) return null;
  const tiers = fields[4].split(';').map((row) => {
    const parts = row.split(',');
    return {
      pages: parts[0] === '' ? [] : parts[0].split('.'),
      bs: Number(parts[1]), acc: Number(parts[2]),
      ps: Number(parts[3]), pd: Number(parts[4]), pe: Number(parts[5]), pr: Number(parts[6]),
      cy: Number(parts[7]),
    };
  });
  return {
    version: Number(fields[0]), g: Number(fields[1]), attic: Number(fields[2]),
    // 1 while the plug reports with reverse metering, which the script has
    // already undone on everything it stored.
    rev: Number(fields[3]), tiers,
  };
}

module.exports = {
  createPlug, decodePage, parseMeta, encode, encodeSigned, decodeAt, decodeSignedAt,
  STORAGE_VALUE_LIMIT, STORAGE_ITEM_LIMIT, TIERS, A64,
};

// power-journal.js -- a local power journal for the Shelly Plug M Gen3, mJS.
//
// Records what the plug consumes, entirely on the device: no cloud, no broker,
// no server, no outbound connection of any kind. Stretches of roughly constant
// power are folded into blocks. The block that is still running lives in the
// device KVS, finished blocks go into the script's own storage.
//
// The script never touches the switch. It only ever reads switch:0, so it
// cannot interfere with whatever else is driving the relay.
//
// Two decisions in here are worth knowing before reading the rest.
//
// The archive is flat text, not JSON. A JSON.parse() on a damaged string
// raises an uncatchable SyntaxError that takes the whole script down -- try
// and catch parse fine on FW 2.0.0 but do not catch this. So the archive is
// stored in a shape a plain scanning loop can read, and a scanning loop cannot
// throw. The KVS entry stays JSON because KVS.Get hands it back already
// decoded, so nothing has to parse it either.
//
// The block tolerance is measured against a reference power fixed when the
// block opens, not against the block's running average. An average that is
// dragged along by every sample it accepts never notices a slow ramp, and a
// phone tapering off at the end of a charge is exactly such a ramp.

// ------------------------------------------------------------------- config

let CFG = {
  switch_id: 0,
  // How often the meter is read. Sampling costs no flash, only a little CPU.
  sample_ms: 10000,
  // A running non-null block is refreshed in the KVS at most this often.
  checkpoint_s: 1800,
  // A sample has to be off by this share of the reference power ...
  change_ratio: 0.10,
  // ... and by at least this much, so small loads do not flicker.
  min_change_mw: 200,
  // ... in this many samples in a row before a new block is opened.
  confirm_samples: 3,
  // 2024-01-01. Anything earlier means the clock has not been set yet.
  min_valid_unix: 1704067200,
  // A storage value holds 1022 bytes; pages close early to leave room for the
  // separator and the longest block that could still arrive.
  page_limit: 980,
  // Eleven page slots exist, one always stays free for copy-on-write.
  max_pages: 10,
  kvs_key: 'pj/current',
  endpoint: 'journal',
  // 0 silent, 1 errors and recovery, 2 blocks and writes, 3 every sample.
  log_level: 2,
  // Test mode logs what it would persist and writes nothing, so it cannot
  // damage a real journal. With test_feed set, these watt values are used in
  // place of the meter, cycling forever.
  test_mode: false,
  test_feed: []
};

// ---------------------------------------------------------------- constants

let VERSION = 1;
let META_KEY = 'm';
// Twelve storage slots exist in total. One holds the metadata, the other
// eleven can hold pages -- but CFG.max_pages keeps one of those free at all
// times, because every archive write copies a page into a spare slot before
// switching the metadata over to it.
let PAGE_KEYS = ['p0', 'p1', 'p2', 'p3', 'p4', 'p5', 'p6', 'p7', 'p8', 'p9', 'p10'];
let KVS_VALUE_LIMIT = 253;

// -------------------------------------------------------------------- state

// A block is { start, ref, energy, meter, zero }:
//   start   unix second the block began
//   ref     reference power in mW the tolerance is measured against
//   energy  mWh drawn since the block began
//   meter   where the plug's lifetime counter stood at the last accounted sample
//   zero    true while nothing is being drawn
let ST = {
  blk: null,        // the running block, null while bootstrapping
  cand: [],         // samples that disagree with the running block
  boot: [],         // samples collected before the first block exists
  meta: null,       // { g: generation, pages: [key, ...] }
  archiveEnd: null, // unix second the archive reaches up to
  cur: null,        // what the KVS held when the script started
  lastKvsWrite: 0,
  kvsBusy: false,
  kvsDirty: false,
  lastUnix: 0,
  timeProbe: 0,
  feedIndex: 0,
  feedMeter: 0
};

function log(level, message) {
  if (CFG.log_level >= level) print('[pj] ' + message);
}

// ------------------------------------------------------------------ numbers

function isNum(x) {
  return typeof x === 'number' && x === x;
}

// Number('') is 0 and Number('abc') is NaN, neither of which may pass for a
// field that should hold a count. Nothing in here can throw.
function toInt(text) {
  if (typeof text !== 'string' || text.length === 0) return null;
  let n = Number(text);
  if (!isNum(n)) return null;
  return Math.round(n);
}

// ------------------------------------------------------------------ storage

function stGet(key) {
  let value = Script.storage.getItem(key);
  return typeof value === 'string' ? value : null;
}

// Writes and reads back. The firmware drops a value over 1022 bytes, or one
// that would be a thirteenth entry, without reporting an error -- so the read
// back is the only way to know whether it landed.
function stPut(key, value) {
  Script.storage.setItem(key, value);
  if (stGet(key) === value) return true;
  log(1, 'storage slot ' + key + ' refused ' + value.length + ' bytes');
  return false;
}

function stDel(key) {
  Script.storage.removeItem(key);
}

// ----------------------------------------------------------------- metadata

// Metadata: "<version>|<generation>|<page key>,<page key>,..."
// The page list is chronological. Slots not named in it are stale or were
// half written, and are ignored rather than trusted.
function metaParse(text) {
  if (text === null) return null;
  let fields = text.split('|');
  if (fields.length !== 3) return null;
  let generation = toInt(fields[1]);
  if (toInt(fields[0]) === null || generation === null) return null;
  let pages = [];
  let i;
  if (fields[2].length > 0) {
    let names = fields[2].split(',');
    for (i = 0; i < names.length; i++) {
      if (PAGE_KEYS.indexOf(names[i]) < 0) return null;
      pages.push(names[i]);
    }
  }
  return { g: generation, pages: pages };
}

function metaText(meta) {
  return VERSION + '|' + meta.g + '|' + meta.pages.join(',');
}

function metaWrite(meta) {
  if (!stPut(META_KEY, metaText(meta))) return false;
  ST.meta = meta;
  return true;
}

// Metadata gone or unreadable: look at every slot and rebuild the order from
// the pages themselves. Nothing is deleted here. A page that will not parse is
// left where it is and merely left out of the list, because throwing away an
// archive is never the automatic answer.
function metaRebuild() {
  let found = [];
  let i, n, best, text;
  for (i = 0; i < PAGE_KEYS.length; i++) {
    text = stGet(PAGE_KEYS[i]);
    if (text === null) continue;
    if (pageEnd(text) === null) {
      log(1, 'page ' + PAGE_KEYS[i] + ' is damaged and stays out of the archive');
      continue;
    }
    found.push({ key: PAGE_KEYS[i], start: toInt(text.split('|')[0]) });
  }
  let used = [];
  let pages = [];
  for (i = 0; i < found.length; i++) used.push(false);
  for (n = 0; n < found.length; n++) {
    best = -1;
    for (i = 0; i < found.length; i++) {
      if (used[i]) continue;
      if (best < 0 || found[i].start < found[best].start) best = i;
    }
    used[best] = true;
    pages.push(found[best].key);
  }
  return { g: 0, pages: pages };
}

// ------------------------------------------------------------------ archive

// Page: "<start unix>|<block>|<block>|..."
//
// A block is "<duration>,<energy in mWh>", or a bare "<duration>" when nothing
// was drawn -- a null block has no energy, so it carries no second number and
// says what it is by its own shape.
//
// Only the page start is stored. Every block begins where the one before it
// ended, which holds because the archive is gapless by construction: any
// stretch nobody accounted for is filed as a null block (see fillGap).

// Walks a page and returns the unix second it reaches up to, or null if the
// page does not hold together. Doubles as the page validator.
function pageEnd(text) {
  let fields = text.split('|');
  if (fields.length < 2) return null;
  let at = toInt(fields[0]);
  if (at === null) return null;
  let i, comma, duration;
  for (i = 1; i < fields.length; i++) {
    comma = fields[i].indexOf(',');
    duration = toInt(comma < 0 ? fields[i] : fields[i].slice(0, comma));
    if (duration === null || duration < 0) return null;
    at += duration;
  }
  return at;
}

function archiveEndTime() {
  if (ST.meta.pages.length === 0) return null;
  let text = stGet(ST.meta.pages[ST.meta.pages.length - 1]);
  if (text === null) return null;
  return pageEnd(text);
}

function freeSlot(meta) {
  let i;
  for (i = 0; i < PAGE_KEYS.length; i++) {
    if (meta.pages.indexOf(PAGE_KEYS[i]) < 0) return PAGE_KEYS[i];
  }
  return null;
}

// Copy on write throughout: the new version of a page goes into a spare slot
// and is read back before the metadata is switched over to it, and the old
// slot is only released afterwards. Losing power before the metadata write
// leaves the old page valid, losing it after leaves the new one valid. There
// is no moment at which the archive is neither.
function archiveAppend(startTime, durationSec, energyMwh, isZero) {
  if (durationSec <= 0) {
    log(1, 'block at ' + startTime + ' had no duration and was not archived');
    return false;
  }
  let field = isZero ? ('' + durationSec) : (durationSec + ',' + energyMwh);
  if (CFG.test_mode) {
    log(2, 'test mode: would archive ' + field);
    ST.archiveEnd = startTime + durationSec;
    return true;
  }

  let meta = ST.meta;
  let lastKey = meta.pages.length > 0 ? meta.pages[meta.pages.length - 1] : null;
  let page = lastKey !== null ? stGet(lastKey) : null;
  let slot = freeSlot(meta);
  let pages = [];
  let dropped = null;
  let i;

  if (slot === null) {
    log(1, 'no free storage slot, block at ' + startTime + ' is lost');
    return false;
  }

  if (page !== null && page.length + 1 + field.length <= CFG.page_limit) {
    if (!stPut(slot, page + '|' + field)) { stDel(slot); return false; }
    for (i = 0; i < meta.pages.length - 1; i++) pages.push(meta.pages[i]);
    pages.push(slot);
    if (!metaWrite({ g: meta.g + 1, pages: pages })) { stDel(slot); return false; }
    stDel(lastKey);
  } else {
    if (!stPut(slot, startTime + '|' + field)) { stDel(slot); return false; }
    for (i = 0; i < meta.pages.length; i++) pages.push(meta.pages[i]);
    pages.push(slot);
    if (pages.length > CFG.max_pages) {
      // Nothing summarises old data yet -- shelly.local is meant to carry it
      // off long before this. Until then the oldest page gives way, and says so.
      dropped = pages[0];
      pages = pages.slice(1);
      log(1, 'archive is full, oldest page ' + dropped + ' dropped');
    }
    if (!metaWrite({ g: meta.g + 1, pages: pages })) { stDel(slot); return false; }
    if (dropped !== null) stDel(dropped);
  }

  ST.archiveEnd = startTime + durationSec;
  log(2, 'archived ' + field + ' in ' + slot);
  return true;
}

// ---------------------------------------------------------------------- KVS

// The running block, and only the running block. Long field names on purpose:
// this entry is read by people and by shelly.local, and a full one uses about
// 110 of the 253 bytes a KVS value may hold, so readability is free here. In
// the archive it is not, which is why that one is terse.
//
//   {"version":1,"start_time":1785870000,"duration_sec":1800,
//    "energy_mwh":1750,"meter_total_mwh":184800,"watt":3.5}
//
// energy_mwh is what this block has drawn. meter_total_mwh is something else
// entirely: it is where the plug's lifetime counter stood when this was
// written. Energy is never measured directly, only as a difference of that
// counter, so recovery needs the bookmark to work out how much flowed while
// the script was away -- and to notice a counter that was reset.
//
// watt is the block's average, not the current draw. The live value would mean
// a flash write every ten seconds; whoever wants it reads Switch.GetStatus,
// which costs nothing.
//
// A null block needs none of this and says so by leaving it out:
//
//   {"version":1,"start_time":1785870000,"watt":0}
function kvsPayload(now) {
  let block = ST.blk;
  let payload = { version: VERSION, start_time: block.start };
  if (block.zero) {
    payload.watt = 0;
    return payload;
  }
  let duration = now - block.start;
  if (duration < 1) duration = 1;
  payload.duration_sec = duration;
  payload.energy_mwh = block.energy;
  payload.meter_total_mwh = block.meter;
  payload.watt = Math.round(block.energy * 3600 / duration) / 1000;
  return payload;
}

function kvsWrite(now, reason) {
  let payload = kvsPayload(now);
  if (CFG.test_mode) {
    log(2, 'test mode: would write ' + JSON.stringify(payload));
    ST.lastKvsWrite = now;
    ST.kvsDirty = false;
    return;
  }
  let text = JSON.stringify(payload);
  if (text.length > KVS_VALUE_LIMIT) {
    log(1, 'KVS entry would be ' + text.length + ' bytes and does not fit');
    return;
  }
  if (ST.kvsBusy) {
    log(1, 'a KVS write is still in flight, this one waits');
    ST.kvsDirty = true;
    return;
  }
  ST.kvsBusy = true;
  ST.kvsDirty = false;
  ST.lastKvsWrite = now;
  Shelly.call('KVS.Set', { key: CFG.kvs_key, value: payload }, kvsWritten, reason);
}

function kvsWritten(result, code, message, reason) {
  ST.kvsBusy = false;
  if (code !== 0) {
    // Do not book a failure as done: mark it and let the next sample retry,
    // rather than leaving the entry stale for another half hour.
    ST.kvsDirty = true;
    log(1, 'KVS write failed (' + reason + '): ' + message);
    return;
  }
  log(2, 'KVS updated (' + reason + ')');
}

// ------------------------------------------------------------ block finding

// A sample disagrees with the running block when it is at least the block's
// tolerance away from the reference power fixed when the block opened.
// Crossing between drawing nothing and drawing something always counts,
// however small the something is -- 0.1 W is a non-null block, and at that
// level the tolerance floor alone would not have noticed.
function deviates(block, power) {
  let isZero = power === 0;
  if (isZero !== block.zero) return true;
  if (isZero) return false;
  let tolerance = Math.max(Math.round(block.ref * CFG.change_ratio), CFG.min_change_mw);
  return Math.abs(power - block.ref) >= tolerance;
}

// Moves the meter difference into the block. Only a forward difference counts:
// a counter that went backwards was reset, and the new reading becomes the
// base rather than negative energy.
function accumulate(block, meter) {
  let delta = meter - block.meter;
  if (delta < 0) {
    log(1, 'energy counter fell from ' + block.meter + ' to ' + meter + ', taking the new value as the base');
  } else {
    block.energy += delta;
  }
  block.meter = meter;
}

function onSample(now, power, meter) {
  if (ST.blk === null) {
    bootstrap(now, power, meter);
    return;
  }
  if (!deviates(ST.blk, power)) {
    if (ST.cand.length > 0) {
      // Back inside the tolerance before the change was confirmed. The energy
      // held back during the candidacy is picked up by this one difference,
      // because the block's meter never moved while candidates were pending.
      log(3, 'candidate run of ' + ST.cand.length + ' dropped');
      ST.cand = [];
    }
    accumulate(ST.blk, meter);
    maybeCheckpoint(now);
    return;
  }
  // While candidates collect, the block's meter deliberately stays put, so the
  // energy of these samples is still unassigned. Whichever side wins gets it.
  ST.cand.push({ t: now, p: power, m: meter });
  if (ST.cand.length < CFG.confirm_samples) return;
  switchBlock(now, meter);
}

function switchBlock(now, meter) {
  let first = ST.cand[0];
  let last = ST.cand[ST.cand.length - 1];
  accumulate(ST.blk, first.m);
  closeBlock(first.t);
  // The new block reaches back to the first disagreeing sample, because that
  // is where the level actually changed. Its reference comes from the last
  // one: the first sample is often still half inside the old level, or a spike
  // that happens to be what started the run.
  ST.blk = { start: first.t, ref: last.p, energy: 0, meter: first.m, zero: last.p === 0 };
  ST.cand = [];
  accumulate(ST.blk, meter);
  log(2, 'new block at ' + first.t + ', reference ' + last.p + ' mW');
  kvsWrite(now, 'new block');
}

function closeBlock(endTime) {
  let block = ST.blk;
  log(2, 'block ' + block.start + ' closed after ' + (endTime - block.start) + ' s with ' + block.energy + ' mWh');
  archiveAppend(block.start, endTime - block.start, block.energy, block.zero);
}

// No block yet: take a few samples before committing to one, so a single odd
// reading at startup does not define the first block. Unlike a block change,
// nothing has just happened here -- there is no transition for the early
// samples to be contaminated by -- so the reference is their mean, which is
// the steadier estimate of the level.
function bootstrap(now, power, meter) {
  ST.boot.push({ t: now, p: power, m: meter });
  if (ST.boot.length < CFG.confirm_samples) return;
  let first = ST.boot[0];
  let sum = 0;
  let i;
  for (i = 0; i < ST.boot.length; i++) sum += ST.boot[i].p;
  let ref = Math.round(sum / ST.boot.length);
  // A single non-zero reading among zeros still makes this a non-null block:
  // zero means nothing was drawn at all, and something was.
  ST.blk = { start: first.t, ref: ref, energy: 0, meter: first.m, zero: ref === 0 };
  ST.boot = [];
  accumulate(ST.blk, meter);
  fillGap(ST.blk.start);
  log(2, 'first block at ' + first.t + ', reference ' + ref + ' mW');
  kvsWrite(now, 'first block');
}

// A running non-null block is refreshed at most every checkpoint_s. Null
// blocks are never refreshed: their duration is now minus their start, so
// there is nothing a second write could add.
function maybeCheckpoint(now) {
  if (ST.kvsDirty) {
    kvsWrite(now, 'retry');
    return;
  }
  if (ST.blk.zero) return;
  if (now - ST.lastKvsWrite < CFG.checkpoint_s) return;
  kvsWrite(now, 'checkpoint');
}

// Keeps the archive gapless, which is what lets a block's start be the sum of
// everything before it. Any stretch nobody accounted for is filed as a null
// block: the script was not there to see it, and nothing it did not see was
// consumed as far as it can honestly say.
function fillGap(untilTime) {
  if (ST.archiveEnd === null) return;
  if (untilTime <= ST.archiveEnd) return;
  log(1, 'filling ' + (untilTime - ST.archiveEnd) + ' s of unrecorded time with a null block');
  archiveAppend(ST.archiveEnd, untilTime - ST.archiveEnd, 0, true);
}

// ---------------------------------------------------------------- measuring

function sample() {
  let sw = Shelly.getComponentStatus('switch:' + CFG.switch_id);
  let sys = Shelly.getComponentStatus('sys');
  if (!sw || !sys) {
    log(1, 'could not read the component status');
    return;
  }
  let now = sys.unixtime;
  if (!isNum(now) || now < CFG.min_valid_unix) {
    log(1, 'unix time is not valid, sample skipped');
    return;
  }
  if (now < ST.lastUnix) {
    log(1, 'unix time jumped backwards, sample skipped');
    ST.lastUnix = now;
    return;
  }
  ST.lastUnix = now;
  if (!isNum(sw.apower) || !sw.aenergy || !isNum(sw.aenergy.total)) {
    log(1, 'measurement is not usable, sample skipped');
    return;
  }

  let power = sw.output === false ? 0 : Math.max(0, Math.round(sw.apower * 1000));
  let meter = Math.round(sw.aenergy.total * 1000);

  if (CFG.test_mode && CFG.test_feed.length > 0) {
    power = Math.max(0, Math.round(CFG.test_feed[ST.feedIndex % CFG.test_feed.length] * 1000));
    ST.feedIndex++;
    ST.feedMeter += Math.round(power * CFG.sample_ms / 3600000);
    meter = ST.feedMeter;
  }

  log(3, now + ': ' + power + ' mW, meter ' + meter + ' mWh');
  onSample(now, power, meter);
}

// ----------------------------------------------------------------- start up

function begin() {
  ST.meta = metaParse(stGet(META_KEY));
  if (ST.meta === null) {
    log(1, 'metadata missing or damaged, rebuilding it from the pages');
    ST.meta = metaRebuild();
  }
  ST.archiveEnd = archiveEndTime();
  log(2, 'archive holds ' + ST.meta.pages.length + ' pages and reaches to ' + ST.archiveEnd);
  Shelly.call('KVS.Get', { key: CFG.kvs_key }, onKvsRead);
}

function onKvsRead(result, code) {
  if (code === 0 && result) ST.cur = curParse(result.value);
  if (ST.cur === null) log(2, 'no usable running block in the KVS');
  waitForTime();
}

// KVS.Get returns the value already decoded, so nothing here parses a string.
// That is what keeps a damaged entry from taking the script down with it.
function curParse(value) {
  if (typeof value !== 'object' || value === null) return null;
  if (!isNum(value.start_time) || value.start_time < CFG.min_valid_unix) return null;
  if (!isNum(value.duration_sec)) return { start: value.start_time, zero: true };
  if (!isNum(value.energy_mwh) || !isNum(value.meter_total_mwh) || !isNum(value.watt)) return null;
  return {
    start: value.start_time,
    zero: false,
    dur: value.duration_sec,
    energy: value.energy_mwh,
    meter: value.meter_total_mwh,
    watt: value.watt
  };
}

// Nothing persistent happens before the clock can be trusted. The plug has no
// backup clock, so after a power cut this waits for NTP -- that is the normal
// case on every boot, not an exception. Two readings five seconds apart have
// to agree that time is moving forwards, and neither may predate the archive.
function waitForTime() {
  let sys = Shelly.getComponentStatus('sys');
  let now = sys && isNum(sys.unixtime) ? sys.unixtime : 0;
  let usable = now >= CFG.min_valid_unix && (ST.archiveEnd === null || now >= ST.archiveEnd);
  if (usable && ST.timeProbe > 0 && now > ST.timeProbe) {
    recover(now);
    return;
  }
  if (!usable) log(1, 'waiting for a valid unix time');
  ST.timeProbe = usable ? now : 0;
  Timer.set(5000, false, waitForTime);
}

function recover(now) {
  let sw = Shelly.getComponentStatus('switch:' + CFG.switch_id);
  let meter = sw && sw.aenergy && isNum(sw.aenergy.total) ? Math.round(sw.aenergy.total * 1000) : 0;
  let bootTs = now - Math.floor(Shelly.getUptimeMs() / 1000);
  let cur = ST.cur;

  if (cur === null) {
    log(2, 'no block to take over, starting from scratch');
  } else if (ST.archiveEnd !== null && cur.start < ST.archiveEnd) {
    // Power was lost between archiving a block and writing its successor to
    // the KVS. The archive already holds it; this entry is a leftover and
    // archiving it again would double it.
    log(1, 'the block in the KVS is already archived, dropping it');
  } else if (cur.zero) {
    // A null block survives a reboot unchanged. Nothing was drawn while the
    // plug was away, which is precisely what a null block records, so the
    // outage belongs to it and its start stays where it was. Normal detection
    // closes it as soon as power comes back.
    ST.blk = { start: cur.start, ref: 0, energy: 0, meter: meter, zero: true };
    log(2, 'continuing the null block that started at ' + cur.start);
  } else if (cur.start + cur.dur >= bootTs - 5) {
    // The checkpoint is younger than this boot, so the script restarted but
    // the device did not: the block never actually stopped. Its reference is
    // not stored, so the average it was last written with stands in -- for a
    // block that held steady those are the same number.
    ST.blk = {
      start: cur.start,
      ref: Math.round(cur.watt * 1000),
      energy: cur.energy,
      meter: cur.meter,
      zero: false
    };
    accumulate(ST.blk, meter);
    log(2, 'script restart, continuing the block that started at ' + cur.start);
    kvsWrite(now, 'recovery');
  } else {
    recoverAfterReboot(now, bootTs, cur, meter);
  }
  startSampling();
}

// The plug lost power while a block was running and nobody recorded when. The
// energy counter still knows how much flowed since the last checkpoint, so the
// block's own average says how long that much energy would have taken; the
// block ends there, and everything from there to the boot is the outage. The
// measured energy is preserved either way -- only the moment it stopped is a
// guess, and it is bounded by the boot.
function recoverAfterReboot(now, bootTs, cur, meter) {
  let refMw = Math.round(cur.watt * 1000);
  let checkpoint = cur.start + cur.dur;
  let endTs = checkpoint;
  let energy = cur.energy;

  if (meter >= cur.meter) {
    let delta = meter - cur.meter;
    energy += delta;
    if (refMw > 0) {
      endTs = checkpoint + Math.round(delta * 3600 / refMw);
      if (endTs > bootTs) endTs = bootTs;
      if (endTs < checkpoint) endTs = checkpoint;
    }
  } else {
    log(1, 'energy counter is below the checkpoint, closing the block at the checkpoint');
  }

  ST.blk = { start: cur.start, ref: refMw, energy: energy, meter: meter, zero: false };
  closeBlock(endTs);
  ST.blk = { start: endTs, ref: 0, energy: 0, meter: meter, zero: true };
  log(2, 'reboot recovery: block closed at ' + endTs + ', the outage is a null phase');
  kvsWrite(now, 'recovery');
}

function startSampling() {
  if (ST.blk !== null) fillGap(ST.blk.start);
  Timer.set(CFG.sample_ms, true, sample);
  log(1, 'sampling every ' + (CFG.sample_ms / 1000) + ' s');
}

// ------------------------------------------------------------ read out over HTTP

// Script.storage cannot be reached over RPC at all, so this is the only way
// for shelly.local -- or a browser -- to see the archive. One page per request,
// because the whole archive does not fit in the script's memory at once.
//
//   /script/<id>/journal                  index and running block
//   /script/<id>/journal?page=p3          one page, expanded
//   /script/<id>/journal?page=p3&raw=1    that page exactly as stored
function onRequest(request, response) {
  let query = typeof request.query === 'string' ? request.query : '';
  let page = queryValue(query, 'page');
  response.headers = { 'Content-Type': 'application/json' };
  response.code = 200;
  response.body = page === null ? httpIndex() : httpPage(page, queryValue(query, 'raw') !== null);
  response.send();
}

function queryValue(query, name) {
  let at = query.indexOf(name + '=');
  if (at < 0) return null;
  let rest = query.slice(at + name.length + 1);
  let amp = rest.indexOf('&');
  return amp < 0 ? rest : rest.slice(0, amp);
}

function httpIndex() {
  let pages = ST.meta.pages.length === 0 ? '[]' : '["' + ST.meta.pages.join('","') + '"]';
  let current = ST.blk === null ? 'null' : JSON.stringify(kvsPayload(ST.lastUnix));
  return '{"version":' + VERSION +
    ',"generation":' + ST.meta.g +
    ',"pages":' + pages +
    ',"archive_end":' + (ST.archiveEnd === null ? 'null' : ST.archiveEnd) +
    ',"current":' + current + '}';
}

// Blocks come back as [start_time, duration_sec, energy_mwh] triples with the
// field names given once alongside. Repeating the names on every block would
// treble the response, and there are about 25 KB of script memory to build it
// in. Average watt is energy_mwh * 3600 / duration_sec.
function httpPage(key, raw) {
  if (PAGE_KEYS.indexOf(key) < 0) return '{"error":"no such page"}';
  let text = stGet(key);
  if (text === null) return '{"error":"page is empty"}';
  if (raw) return '{"page":"' + key + '","raw":"' + text + '"}';
  let fields = text.split('|');
  let at = toInt(fields[0]);
  if (at === null) return '{"error":"page is damaged"}';
  let out = '{"page":"' + key + '","fields":["start_time","duration_sec","energy_mwh"],"blocks":[';
  let i, comma, duration, energy;
  for (i = 1; i < fields.length; i++) {
    comma = fields[i].indexOf(',');
    duration = toInt(comma < 0 ? fields[i] : fields[i].slice(0, comma));
    energy = comma < 0 ? 0 : toInt(fields[i].slice(comma + 1));
    if (duration === null || energy === null) break;
    if (i > 1) out += ',';
    out += '[' + at + ',' + duration + ',' + energy + ']';
    at += duration;
  }
  return out + ']}';
}

// --------------------------------------------------------------------- main

function main() {
  log(1, 'power journal v' + VERSION + ' starting' + (CFG.test_mode ? ' in test mode' : ''));
  HTTPServer.registerEndpoint(CFG.endpoint, onRequest);
  begin();
}

// Handed to the test harness on the PC, which runs this very file against a
// simulated plug. Nothing on the device calls it.
function selftest() {
  return {
    CFG: CFG, ST: ST,
    sample: sample, onSample: onSample, deviates: deviates,
    pageEnd: pageEnd, metaParse: metaParse, metaText: metaText,
    archiveAppend: archiveAppend, kvsPayload: kvsPayload,
    httpIndex: httpIndex, httpPage: httpPage
  };
}

main();

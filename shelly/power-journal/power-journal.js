// power-journal.js -- a local power journal for the Shelly Plug M Gen3, mJS.
//
// Records what the plug puts through, entirely on the device: no cloud, no
// broker, no server, no outbound connection of any kind. Stretches of roughly
// constant power are folded into blocks. The block that is still running lives
// in the device KVS, finished blocks go into the script's own storage.
//
// The script never touches the switch. It only ever reads switch:0, so it
// cannot interfere with whatever else is driving the relay.
//
// Four decisions in here are worth knowing before reading the rest.
//
// Numbers are stored as varints over a 64 character alphabet, five payload
// bits per character and a sixth saying whether another character follows. A
// number therefore carries its own length and no separators are needed at all.
// Signed values are zigzagged first, because power runs backwards when a solar
// plant is plugged in.
//
// The archive is flat text, never JSON. JSON.parse() on a damaged string
// raises an uncatchable SyntaxError that takes the whole script down -- try
// and catch parse fine on FW 2.0.0 but do not catch this. A scanning loop
// cannot throw. The KVS entry stays JSON because KVS.Get hands it back already
// decoded, so nothing has to parse that either.
//
// The archive is a resolution pyramid. Every closed block is fed to all tiers
// at once: tier 0 keeps it verbatim, tier 1 buckets it into quarter hours,
// tier 2 into hours, tier 3 into days. Tiers do not cascade into one another,
// they are each driven straight from the block stream -- that keeps every
// bucket boundary exactly on the clock, which a cascade cannot do, because the
// seam between two source pages almost never lands on a grid line.
//
// The block tolerance is measured against a reference power fixed when the
// block opens, not against the block's running average. An average that is
// dragged along by every sample it accepts never notices a slow ramp.

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
  // Power at or below this counts as nothing at all, in either direction.
  zero_mw: 0,
  // 2024-01-01. Anything earlier means the clock has not been set yet.
  min_valid_unix: 1704067200,
  // A storage value holds 1022 bytes; pages close early to leave room for the
  // longest block that could still arrive.
  page_limit: 1010,
  // One row per tier: grid in seconds, energy unit in mWh, how many pages the
  // tier may hold, and how many grid steps one block may be merged across.
  //
  // Tier 0 is the raw stream and has no grid. The coarse tiers store their
  // duration in grid steps and their energy in the coarser unit, which is
  // where most of the space saving comes from -- a quarter hour is the number
  // 1, not the number 900.
  //
  // Merging folds neighbouring buckets of equal power into one block, so a
  // night collapses to a single entry. Days are never merged: the day tier
  // exists to be read as days.
  tiers: [
    [1, 1, 1, 0],
    [900, 100, 3, 4096],
    [3600, 1000, 3, 4096],
    [86400, 10000, 3, 1]
  ],
  // A second script that never runs. Day pages pushed out of storage are
  // appended to its source as comments, which is the only writable space left
  // on the device once the twelve storage slots are spoken for.
  attic_name: 'pj-attic',
  attic_limit: 19800,
  kvs_key: 'current_power',
  endpoint: 'journal',
  // 0 silent, 1 errors and recovery, 2 blocks and writes, 3 every sample.
  log_level: 2,
  // Test mode logs what it would persist and writes nothing, so it cannot
  // damage a real journal.
  test_mode: false
};

// ---------------------------------------------------------------- constants

let VERSION = 3;
let META_KEY = 'm';
// Twelve storage slots exist in total. One holds the metadata, the other
// eleven can hold pages -- but the tier page allowances add up to ten, so one
// always stays free, because every archive write copies a page into a spare
// slot before switching the metadata over to it.
let SLOTS = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k'];
let KVS_VALUE_LIMIT = 253;
// 64 printable characters. No contiguous 64 wide window of printable ASCII
// avoids both " and \, and both have to stay out because pages travel inside
// JSON responses -- so the alphabet is two runs, 35..91 and 93..99, which
// still decodes with a single comparison.
let A64 = '#$%&\'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[]^_`abc';

// -------------------------------------------------------------------- state

// A block is { start, ref, energy, meter, zero }:
//   start   unix second the block began
//   ref     reference power in mW the tolerance is measured against, signed
//   energy  net mWh through the plug since the block began, signed
//   meter   where the net counter stood at the last accounted sample
//   zero    true while nothing at all is flowing
let ST = {
  blk: null,        // the running block, null while bootstrapping
  cand: [],         // samples that disagree with the running block
  boot: [],         // samples collected before the first block exists
  meta: null,       // see metaParse
  red: [],          // one reducer per tier, index 0 unused
  q: [],            // closed blocks waiting to be archived, see queueBlock
  archiveEnd: null, // unix second the archive reaches up to
  cur: null,        // what the KVS held when the script started
  offset: 0,        // seconds east of UTC, so day buckets land on local midnight
  gross: null,      // last aenergy.total, which only ever climbs
  atticSlot: null,  // a slot handed to the attic and not yet released
  lastKvsWrite: 0,
  kvsBusy: false,
  kvsDirty: false,
  lastUnix: 0,
  timeProbe: 0,
  flip: false,     // the meter orientation turned since the last run
  stale: false     // the KVS entry was written by an older version of this
};

function log(level, message) {
  if (CFG.log_level >= level) print('[pj] ' + message);
}

// ------------------------------------------------------------------ numbers

function isNum(x) {
  return typeof x === 'number' && x === x;
}

// mJS has no Math.min.
function mn(a, b) {
  return a < b ? a : b;
}

// Number('') is 0 and Number('abc') is NaN, neither of which may pass for a
// field that should hold a count. Nothing in here can throw.
function toInt(text) {
  if (typeof text !== 'string' || text.length === 0) return null;
  let n = Number(text);
  if (!isNum(n)) return null;
  return Math.round(n);
}

// ------------------------------------------------------------------- codec

// Little endian base 32 with a continuation bit. Five payload bits per
// character, so 19750 is three characters where decimal needs five plus a
// separator.
function enc(n) {
  if (!isNum(n) || n < 0) n = 0;
  n = Math.round(n);
  let out = '';
  let g;
  while (true) {
    g = n % 32;
    n = Math.floor(n / 32);
    if (n > 0) g = g + 32;
    out = out + A64.slice(g, g + 1);
    if (n === 0) return out;
  }
}

// Zigzag, so a small negative number stays small. Energy is signed: a solar
// plant runs the meter backwards.
function encZ(n) {
  if (!isNum(n)) n = 0;
  n = Math.round(n);
  return enc(n < 0 ? -n * 2 - 1 : n * 2);
}

// The decoder carries its cursor in one shared object rather than returning a
// pair. A page holds hundreds of numbers and an allocation per number is what
// the 25 KB of script memory cannot afford.
let DEC = { i: 0, ok: true };

function dec(text) {
  let n = 0;
  let shift = 1;
  let c, v;
  while (true) {
    if (DEC.i >= text.length) { DEC.ok = false; return 0; }
    c = text.charCodeAt(DEC.i);
    DEC.i = DEC.i + 1;
    v = c < 92 ? c - 35 : c - 93 + 57;
    if (v < 0 || v > 63) { DEC.ok = false; return 0; }
    n = n + (v % 32) * shift;
    if (v < 32) return n;
    shift = shift * 32;
    // 2^35, past anything this stores. A damaged page cannot spin forever.
    if (shift > 34359738368) { DEC.ok = false; return 0; }
  }
}

function decZ(text) {
  let v = dec(text);
  return v % 2 === 1 ? -(v + 1) / 2 : v / 2;
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
  log(1, 'slot ' + key + ' refused ' + value.length + ' bytes');
  return false;
}

function stDel(key) {
  Script.storage.removeItem(key);
}

// -------------------------------------------------------------------- pages

// Page: "<tier digit><page start><duration><energy><duration><energy>..."
//
// The tier digit is plain ASCII so a rebuild can tell which pyramid level a
// slot belongs to without the metadata. The start is an absolute unix second;
// every duration after it is in grid steps, every energy in the tier's unit
// and zigzagged. Nothing separates the fields because varints do not need it.
//
// Only the page start is stored. Every block begins where the one before it
// ended, which holds because the archive is gapless by construction: any
// stretch nobody accounted for is filed as a null block (see fillGap).

// Walks a page and reports what it holds, or null if it does not hold
// together. Doubles as the page validator. Allocates nothing per block.
function pageInfo(text) {
  if (text === null || text.length < 2) return null;
  let tier = text.charCodeAt(0) - 48;
  if (tier < 0 || tier >= CFG.tiers.length) return null;
  let grid = CFG.tiers[tier][0];
  DEC.i = 1;
  DEC.ok = true;
  let start = dec(text);
  if (!DEC.ok || start < CFG.min_valid_unix) return null;
  let at = start;
  let n = 0;
  let d;
  while (DEC.i < text.length) {
    d = dec(text);
    decZ(text);
    if (!DEC.ok || d < 0) return null;
    at = at + d * grid;
    n = n + 1;
  }
  return { tier: tier, start: start, end: at, blocks: n };
}

// ----------------------------------------------------------------- metadata

// Metadata: "<version>|<generation>|<attic bytes>|<tier>;<tier>;<tier>;<tier>"
//
// A tier is "<slots joined by .>,<bucket start>,<bucket mWh>,<pending start>,
// <pending seconds>,<pending units>,<pending mW>,<carry mWh>", where -1 stands
// for "none". The reducer state rides along because it changes exactly when a
// page does, so it costs no extra write. Losing it costs one partial bucket
// and one carry, nothing more.
function metaParse(text) {
  if (text === null) return null;
  let fields = text.split('|');
  if (fields.length !== 5) return null;
  if (toInt(fields[0]) !== VERSION) return null;
  let generation = toInt(fields[1]);
  let attic = toInt(fields[2]);
  let rev = toInt(fields[3]);
  if (generation === null || attic === null || rev === null) return null;
  let rows = fields[4].split(';');
  if (rows.length !== CFG.tiers.length) return null;
  let tiers = [];
  let i, j, parts, names, pages, nums, ok;
  for (i = 0; i < rows.length; i++) {
    parts = rows[i].split(',');
    if (parts.length !== 8) return null;
    pages = [];
    if (parts[0].length > 0) {
      names = parts[0].split('.');
      for (j = 0; j < names.length; j++) {
        if (SLOTS.indexOf(names[j]) < 0) return null;
        pages.push(names[j]);
      }
    }
    nums = [];
    ok = true;
    for (j = 1; j < 8; j++) {
      if (toInt(parts[j]) === null) ok = false;
      else nums.push(toInt(parts[j]));
    }
    if (!ok) return null;
    tiers.push({
      pages: pages, bs: nums[0], acc: nums[1], ps: nums[2],
      pd: nums[3], pe: nums[4], pr: nums[5], cy: nums[6]
    });
  }
  return { g: generation, attic: attic, rev: rev, tiers: tiers };
}

// Built one short concatenation at a time, and inlined into the writer rather
// than kept as a function of its own. Both are for the same reason: this runs
// at the deep end of the only chain in the script that nests at all, and mJS
// gives up on a stack it thinks is about to overflow -- counting a long
// expression's operands towards it, not just the frames. This one statement
// used to hold fifteen.
function metaWrite() {
  let meta = ST.meta;
  meta.g = meta.g + 1;
  let text = VERSION + '|' + meta.g + '|' + meta.attic + '|' + meta.rev + '|';
  let i, t, row;
  for (i = 0; i < meta.tiers.length; i++) {
    t = meta.tiers[i];
    if (i > 0) text = text + ';';
    row = t.pages.join('.');
    row = row + ',' + t.bs;
    row = row + ',' + Math.round(t.acc);
    row = row + ',' + t.ps;
    row = row + ',' + t.pd;
    row = row + ',' + t.pe;
    row = row + ',' + t.pr;
    row = row + ',' + Math.round(t.cy);
    text = text + row;
  }
  return stPut(META_KEY, text);
}

// Metadata gone or unreadable: look at every slot and rebuild from the pages
// themselves, which is possible only because each page names its own tier.
// Nothing is deleted here. A page that will not parse is left where it is and
// merely left out, because throwing away an archive is never the automatic
// answer. Reducer state cannot be recovered and starts empty.
function metaRebuild() {
  let tiers = [];
  let found = [];
  let i, j, text, info, best;
  for (i = 0; i < CFG.tiers.length; i++) {
    tiers.push({ pages: [], bs: -1, acc: 0, ps: -1, pd: 0, pe: 0, pr: 0, cy: 0 });
  }
  for (i = 0; i < SLOTS.length; i++) {
    text = stGet(SLOTS[i]);
    if (text === null) continue;
    info = pageInfo(text);
    if (info === null) {
      log(1, 'page ' + SLOTS[i] + ' is damaged and stays out of the archive');
      continue;
    }
    found.push({ key: SLOTS[i], tier: info.tier, start: info.start });
  }
  for (i = 0; i < CFG.tiers.length; i++) {
    while (true) {
      best = -1;
      for (j = 0; j < found.length; j++) {
        if (found[j] === null || found[j].tier !== i) continue;
        if (best < 0 || found[j].start < found[best].start) best = j;
      }
      if (best < 0) break;
      tiers[i].pages.push(found[best].key);
      found[best] = null;
    }
  }
  return { g: 0, attic: 0, rev: 0, tiers: tiers };
}

// ------------------------------------------------------------------ archive

function freeSlot() {
  let i, j, taken;
  for (i = 0; i < SLOTS.length; i++) {
    if (SLOTS[i] === ST.atticSlot) continue;
    taken = false;
    for (j = 0; j < ST.meta.tiers.length; j++) {
      if (ST.meta.tiers[j].pages.indexOf(SLOTS[i]) >= 0) taken = true;
    }
    if (!taken) return SLOTS[i];
  }
  return null;
}

function archiveEndTime() {
  let pages = ST.meta.tiers[0].pages;
  if (pages.length === 0) return null;
  let info = pageInfo(stGet(pages[pages.length - 1]));
  return info === null ? null : info.end;
}

// Copy on write throughout: the new version of a page goes into a spare slot
// and is read back before the metadata is switched over to it, and the old
// slot is only released afterwards. Losing power before the metadata write
// leaves the old page valid, losing it after leaves the new one valid. There
// is no moment at which the archive is neither.
function tierWrite(tier, start, durationSec, units) {
  let grid = CFG.tiers[tier][0];
  let steps = Math.round(durationSec / grid);
  if (steps < 1) return false;
  let field = enc(steps) + encZ(units);

  if (CFG.test_mode) {
    log(2, 'test mode: tier ' + tier + ' would take ' + start + ' +' + durationSec + 's ' + units);
    return true;
  }

  let row = ST.meta.tiers[tier];
  let lastKey = row.pages.length > 0 ? row.pages[row.pages.length - 1] : null;
  let page = lastKey !== null ? stGet(lastKey) : null;
  let slot = freeSlot();
  let retired = null;
  let i;

  if (slot === null) {
    log(1, 'no free slot, tier ' + tier + ' block at ' + start + ' is lost');
    return false;
  }

  if (page !== null && page.length + field.length <= CFG.page_limit) {
    if (!stPut(slot, page + field)) { stDel(slot); return false; }
    row.pages[row.pages.length - 1] = slot;
    if (!metaWrite()) { row.pages[row.pages.length - 1] = lastKey; stDel(slot); return false; }
    stDel(lastKey);
  } else {
    if (!stPut(slot, '' + tier + enc(start) + field)) { stDel(slot); return false; }
    row.pages.push(slot);
    if (row.pages.length > CFG.tiers[tier][2]) {
      retired = row.pages[0];
      let kept = [];
      for (i = 1; i < row.pages.length; i++) kept.push(row.pages[i]);
      row.pages = kept;
    }
    if (!metaWrite()) { return false; }
    if (retired !== null) retirePage(tier, retired);
  }
  return true;
}

// The oldest page of a tier has been pushed out. Every tier but the last just
// drops it -- the tier below already holds the same stretch at a coarser
// resolution. The day tier has nothing below it, so its pages go to the attic.
function retirePage(tier, key) {
  if (tier !== CFG.tiers.length - 1) {
    log(1, 'tier ' + tier + ' full, page ' + key + ' dropped');
    stDel(key);
    return;
  }
  let text = stGet(key);
  if (text === null) return;
  // The slot stays reserved until the attic has it, so the copy on write of
  // the next append cannot hand the same slot out from under the transfer.
  ST.atticSlot = key;
  atticStore(text);
}

// ------------------------------------------------------------------- attic

// A script that is created, never started and left disabled. Its source is
// twenty kilobytes of writable space that the storage limit cannot touch, and
// a comment is the only shape that space can safely take.
function atticStore(text) {
  if (ST.meta.attic >= CFG.attic_limit) {
    log(1, 'attic is full at ' + ST.meta.attic + ' bytes, day page dropped');
    atticDone();
    return;
  }
  Shelly.call('Script.List', {}, function (result, code) {
    if (code !== 0 || !result || !result.scripts) {
      log(1, 'attic: cannot list scripts, day page dropped');
      atticDone();
      return;
    }
    let id = null;
    let i;
    for (i = 0; i < result.scripts.length; i++) {
      if (result.scripts[i].name === CFG.attic_name) id = result.scripts[i].id;
    }
    if (id === null) {
      log(1, 'attic: no script named ' + CFG.attic_name + ', day page dropped');
      atticDone();
      return;
    }
    Shelly.call('Script.PutCode', { id: id, code: '\n//' + text, append: true }, atticStored);
  });
}

function atticStored(result, code, message) {
  if (code !== 0) {
    log(1, 'attic write failed: ' + message);
  } else {
    ST.meta.attic = result && isNum(result.len) ? result.len : ST.meta.attic + 3;
    log(2, 'attic holds ' + ST.meta.attic + ' bytes');
  }
  atticDone();
}

function atticDone() {
  if (ST.atticSlot === null) return;
  stDel(ST.atticSlot);
  ST.atticSlot = null;
}

// ------------------------------------------------------------------ tiering

// Where the grid line at or before this second sits. The offset is what makes
// a day bucket start at local midnight rather than at 22:00 or 23:00 the
// evening before; for the quarter hour and hour grids it changes nothing,
// because every sane UTC offset is a multiple of both.
//
// Local time is UTC plus the offset, so it is the shifted value that has to
// land on the grid, and the shift comes back off afterwards. Getting this
// backwards puts the day boundary at four in the morning, which is what it did
// until a plug showed it.
function bucketStart(when, grid) {
  return Math.floor((when + ST.offset) / grid) * grid - ST.offset;
}

// Feeds one closed block into one tier. The block's energy is spread over the
// buckets it covers in proportion to the time it spends in each, which is the
// only distribution available -- within a block, constant power is exactly
// what the block asserts.
function feedTier(tier, from, until, mwh) {
  let row = ST.meta.tiers[tier];
  let grid = CFG.tiers[tier][0];
  let span = until - from;
  if (span <= 0) return;
  let at = from;
  let edge, step;
  while (at < until) {
    // A stale bucket means the stream skipped ahead, which happens on the
    // first block after a restart. Close what is open and start again here.
    if (row.bs < 0 || at >= row.bs + grid) {
      if (row.bs >= 0) emitBucket(tier, row.bs, row.acc);
      row.bs = bucketStart(at, grid);
      row.acc = 0;
    }
    edge = row.bs + grid;
    step = mn(until, edge) - at;
    row.acc = row.acc + mwh * step / span;
    at = at + step;
    if (at >= edge) {
      emitBucket(tier, row.bs, row.acc);
      row.bs = edge;
      row.acc = 0;
    }
  }
}

// A finished bucket. It is not written straight away: a bucket that carries
// the same power as the one before it is merged into it instead, which is what
// turns a night into a single entry rather than thirty two identical ones.
//
// A coarse tier stores whole units, and a bucket can hold less than one -- a
// thirty second switch-on at three watts is 25 mWh against a quarter hour unit
// of 100. Two separate things have to be decided about such a bucket, and they
// pull in opposite directions.
//
// What level it is, is decided by the bucket alone. Under half a unit there is
// nothing at this resolution to tell it apart from nothing at all, so it reads
// as null and merges into the run around it. That threshold is 0.2 W over a
// quarter hour, 0.5 W over an hour and 0.2 W over a day -- the same order as
// the 200 mW floor below which the block detector does not react either.
//
// What gets booked is decided by the bucket plus everything earlier buckets
// could not express. The remainder is carried rather than dropped, so a night
// of brief switch-ons comes out as one long block that honestly says a watt
// hour flowed, rather than as forty blocks each saying zero -- or, if the carry
// were allowed to decide the level too, as ten blocks interrupting the night
// for no reason anyone could see. Nothing is lost; it is merely attributed to
// the stretch it happened in rather than to the minute.
function emitBucket(tier, start, mwh) {
  let row = ST.meta.tiers[tier];
  let grid = CFG.tiers[tier][0];
  let unit = CFG.tiers[tier][1];
  let span = CFG.tiers[tier][3] * grid;
  let own = Math.round(mwh / unit);
  let mw = own === 0 ? 0 : Math.round(mwh * 3600 / grid);
  let total = mwh + row.cy;
  let units = Math.round(total / unit);
  row.cy = total - units * unit;
  if (row.ps >= 0 && row.ps + row.pd === start && row.pd + grid <= span &&
      !levelChanged(row.pr, mw)) {
    row.pd = row.pd + grid;
    row.pe = row.pe + units;
    return;
  }
  if (row.ps >= 0) tierWrite(tier, row.ps, row.pd, row.pe);
  row.ps = start;
  row.pd = grid;
  row.pe = units;
  row.pr = mw;
}

// Every closed block goes to every tier at once. Tier 0 takes it verbatim,
// the others bucket it. Nothing cascades, so a bucket boundary is always a
// real quarter hour, hour or midnight.
//
// The coarse tiers are fed first and tier 0 is written last, because only a
// page write persists the metadata and tier 0 is the one that writes on every
// single block. Doing it the other way round would leave a bucket that is
// still filling unrecorded until the tier happened to close a page, and a
// restart would then find a reducer whose state is older than its own archive.
function emitBlock(start, durationSec, mwh) {
  let i;
  for (i = 1; i < CFG.tiers.length; i++) feedTier(i, start, start + durationSec, mwh);
  tierWrite(0, start, durationSec, mwh);
}

// Closed blocks are not archived where they are found. They queue here and are
// written at the top of the next sample, because mJS refuses a call stack it
// thinks is about to overflow and the archive is the deepest thing the script
// does -- feedTier, emitBucket, tierWrite, metaWrite and the storage call are
// five frames on their own. Reached from a block that closed inside an RPC
// callback during recovery, that chain ran twelve deep and the plug killed the
// script mid-write. Queueing puts it at a fixed two.
//
// The archive's reach moves when a block is queued rather than when it is
// written, so a gap check made before the queue drains does not see a hole
// that is already accounted for.
function queueBlock(start, durationSec, mwh) {
  if (durationSec <= 0) {
    log(1, 'block at ' + start + ' had no duration and was not archived');
    return;
  }
  ST.q.push({ s: start, d: durationSec, e: mwh });
  ST.archiveEnd = start + durationSec;
}

function drainBlocks() {
  if (ST.q.length === 0) return;
  let queued = ST.q;
  // Swapped out first: archiving can close a bucket, and nothing may append to
  // the list being walked.
  ST.q = [];
  let i;
  for (i = 0; i < queued.length; i++) emitBlock(queued[i].s, queued[i].d, queued[i].e);
}

// ---------------------------------------------------------------------- KVS

// The running block, and only the running block. Long field names on purpose:
// this entry is read by people and by shelly.local, and a full one uses about
// 170 of the 253 bytes a KVS value may hold, so readability is free here. In
// the archive it is not, which is why that one is varints.
//
// energy_mwh is what this block has put through, signed: negative means the
// plug exported more than it drew. meter_net_mwh and meter_gross_mwh are
// something else entirely -- they are where the plug's lifetime counters stood
// when this was written. Energy is never measured directly, only as a
// difference of those counters, so recovery needs the bookmark to work out how
// much flowed while the script was away. The gross one only ever climbs, which
// is what makes a counter reset detectable at all.
//
// watt is the block's average, not the current draw. The live value would mean
// a flash write every ten seconds; whoever wants it reads Switch.GetStatus,
// which costs nothing.
//
// reference_watt is the level the block was opened at, measured from apower.
// It is not decoration: this plug advances its counters in jumps rather than
// continuously, so a young block has counted no energy yet and its average
// reads 0 W for the first minutes. The reference is right immediately, and it
// is what a restart has to restore -- a block resumed with a reference of zero
// would disagree with its own load on the very next sample and split itself in
// three.
//
// A null block needs none of this and says so by leaving it out:
//
//   {"version":2,"start_time":1785870000,"watt":0}
function kvsPayload(now) {
  let block = ST.blk;
  let payload = { start_time: block.start };
  if (block.zero) {
    payload.watt = 0;
    return payload;
  }
  let duration = now - block.start;
  if (duration < 1) duration = 1;
  payload.duration_sec = duration;
  payload.energy_mwh = block.energy;
  payload.meter_net_mwh = block.meter;
  payload.meter_gross_mwh = ST.gross === null ? 0 : ST.gross;
  payload.watt = Math.round(block.energy * 3600 / duration) / 1000;
  payload.reference_watt = block.ref / 1000;
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

function isZero(mw) {
  return (mw < 0 ? -mw : mw) <= CFG.zero_mw;
}

// Two power levels differ enough to be called a change: at least a share of
// the reference and at least the floor. The reference is taken in magnitude,
// so an exporting plant gets the same relative tolerance a consuming one does,
// and a sign flip is always a change because the gap is then the sum of both.
function levelChanged(ref, power) {
  let refZero = isZero(ref);
  let powerZero = isZero(power);
  if (refZero !== powerZero) return true;
  if (powerZero) return false;
  let magnitude = ref < 0 ? -ref : ref;
  let gap = power - ref;
  if (gap < 0) gap = -gap;
  return gap >= Math.max(Math.round(magnitude * CFG.change_ratio), CFG.min_change_mw);
}

function deviates(block, power) {
  if (isZero(power) !== block.zero) return true;
  if (block.zero) return false;
  return levelChanged(block.ref, power);
}

// Moves the net counter difference into the block. The difference is signed
// and a negative one is perfectly normal -- that is a plant exporting. A reset
// is not detected here but on the gross counter, which is the only one that
// cannot legitimately fall.
function accumulate(block, meter) {
  block.energy = block.energy + (meter - block.meter);
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
  // A sample taken at T reports a counter that already covers the interval
  // ending at T, so the energy of the interval the change happened in lands in
  // whichever block owns T. For a null block that is wrong in a way that shows:
  // a block that records that nothing flowed must not carry energy. So a null
  // block hands its final interval to its successor instead, which is also
  // where the load that ended it actually belongs. Nothing is lost either way,
  // the counter difference only moves across the boundary.
  let handover = ST.blk.meter;
  if (!ST.blk.zero) {
    accumulate(ST.blk, first.m);
    handover = first.m;
  }
  closeBlock(first.t);
  // The new block reaches back to the first disagreeing sample, because that
  // is where the level actually changed. Its reference comes from the last
  // one: the first sample is often still half inside the old level, or a spike
  // that happens to be what started the run.
  ST.blk = { start: first.t, ref: last.p, energy: 0, meter: handover, zero: isZero(last.p) };
  ST.cand = [];
  accumulate(ST.blk, meter);
  log(2, 'new block at ' + first.t + ', reference ' + last.p + ' mW');
  kvsWrite(now, 'new block');
}

function closeBlock(endTime) {
  let block = ST.blk;
  log(2, 'block ' + block.start + ' closed after ' + (endTime - block.start) + ' s with ' + block.energy + ' mWh');
  queueBlock(block.start, endTime - block.start, block.energy);
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
  for (i = 0; i < ST.boot.length; i++) sum = sum + ST.boot[i].p;
  let ref = Math.round(sum / ST.boot.length);
  ST.blk = { start: first.t, ref: ref, energy: 0, meter: first.m, zero: isZero(ref) };
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
// block: the script was not there to see it, and nothing it did not see went
// through as far as it can honestly say.
function fillGap(untilTime) {
  if (ST.archiveEnd === null) return;
  if (untilTime <= ST.archiveEnd) return;
  log(1, 'filling ' + (untilTime - ST.archiveEnd) + ' s of unrecorded time with a null block');
  queueBlock(ST.archiveEnd, untilTime - ST.archiveEnd, 0);
}

// ---------------------------------------------------------------- measuring

// The plug keeps two lifetime counters. aenergy.total counts everything that
// passed through in either direction, ret_aenergy.total counts only the part
// that went back out. So what actually crossed the meter, signed, is
// total - 2 * returned: positive drawn, negative exported.
function netMeter(sw) {
  let gross = Math.round(sw.aenergy.total * 1000);
  let returned = sw.ret_aenergy && isNum(sw.ret_aenergy.total) ? Math.round(sw.ret_aenergy.total * 1000) : 0;
  // The gross counter is not oriented. It is only ever compared with itself to
  // notice a reset, and it climbs whichever way the energy is going.
  return { gross: gross, net: orient(gross - 2 * returned) };
}

// Everything the script measures passes through here, and everything it stores
// is therefore in one fixed convention: positive is drawn from the grid.
//
// The plug's reverse metering flag exists to make a solar plant read positive,
// which is nicer on a live display and useless in an archive -- the flag can be
// flipped at any point in a plug's life, and the same physical afternoon would
// then be recorded twice with opposite signs. Nothing in the numbers could tell
// the two apart afterwards, so the sign is settled here, once, on the way in.
function orient(mw) {
  return ST.meta !== null && ST.meta.rev === 1 ? -mw : mw;
}

// Which way round the plug is reporting, decided at startup and then left
// alone.
//
// Startup is the only moment this can be read safely, and it is enough:
// changing reverse needs a device restart, and a restart starts this script.
// Until that restart happens the plug still measures the old way while
// GetConfig already reports the new flag, so a pending restart means the
// answer on file is the true one and the configured one is a promise.
function orientation(current) {
  let sys = Shelly.getComponentStatus('sys');
  if (sys && sys.restart_required === true) {
    log(1, 'a restart is pending, keeping the meter orientation that is in force');
    return current;
  }
  let cfg = Shelly.getComponentConfig('switch:' + CFG.switch_id);
  if (!cfg || typeof cfg.reverse !== 'boolean') return current;
  return cfg.reverse ? 1 : 0;
}

function sample() {
  // First, and unconditionally: whatever is queued has to be archived even if
  // this sample turns out to be unusable. See queueBlock for why it happens
  // here rather than where the block closed.
  drainBlocks();
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
  if (isNum(sys.utc_offset)) ST.offset = sys.utc_offset;
  if (!isNum(sw.apower) || !sw.aenergy || !isNum(sw.aenergy.total)) {
    log(1, 'measurement is not usable, sample skipped');
    return;
  }

  let power = sw.output === false ? 0 : orient(Math.round(sw.apower * 1000));
  let reading = netMeter(sw);

  // The gross counter cannot fall. If it did, it was reset, and the running
  // block rebases on the new reading rather than booking the whole lifetime
  // total as a single second of energy.
  if (ST.gross !== null && reading.gross < ST.gross && ST.blk !== null) {
    log(1, 'energy counters were reset, rebasing');
    ST.blk.meter = reading.net;
  }
  ST.gross = reading.gross;

  log(3, now + ': ' + power + ' mW, net ' + reading.net + ' mWh');
  onSample(now, power, reading.net);
  // Again, because this sample may have closed a block. Here it costs nothing:
  // the sample has already returned to this frame, so the archive still runs
  // shallow, and a block reaches the archive in the same sample that ended it
  // rather than ten seconds later where a power cut could take it.
  drainBlocks();
}

// ----------------------------------------------------------------- start up

function begin() {
  let raw = stGet(META_KEY);
  // An archive from an older version of this script is thrown away rather than
  // carried forward. From version 3 the stored energy is oriented -- positive
  // is drawn from the grid whatever the plug's reverse flag says -- so older
  // pages mean something else, and there is nothing in them that says which.
  // Mixing the two would put a silent sign flip in the middle of the history,
  // which is worse than starting again.
  let i;
  if (raw !== null && toInt(raw.split('|')[0]) !== VERSION) {
    log(1, 'the archive is version ' + raw.split('|')[0] + ' and this is ' + VERSION + ', starting a new one');
    for (i = 0; i < SLOTS.length; i++) stDel(SLOTS[i]);
    stDel(META_KEY);
    raw = null;
    // The running block in the KVS was written by that older script and means
    // what it meant, which is no longer knowable. Starting the block again
    // costs one stretch; keeping it would poison the first sample.
    ST.stale = true;
  }
  ST.meta = metaParse(raw);
  if (ST.meta === null) {
    if (raw !== null) log(1, 'metadata missing or damaged, rebuilding it from the pages');
    ST.meta = metaRebuild();
  }
  let was = ST.meta.rev;
  ST.meta.rev = orientation(was);
  // The running block in the KVS carries a bookmark into the plug's lifetime
  // counter, and that bookmark is only comparable with readings taken the same
  // way round. When the orientation has changed since it was written, the
  // bookmark is turned with it -- otherwise the very next sample would book the
  // difference between plus and minus the whole lifetime total as the energy of
  // one ten second interval.
  ST.flip = ST.meta.rev !== was;
  log(2, 'meter orientation ' + (ST.meta.rev === 1 ? 'reversed' : 'normal') +
    (ST.flip ? ', turned since the last run' : ''));
  ST.archiveEnd = archiveEndTime();
  let counts = [];
  for (i = 0; i < ST.meta.tiers.length; i++) counts.push(ST.meta.tiers[i].pages.length);
  log(2, 'archive pages ' + counts.join('/') + ', reaching to ' + ST.archiveEnd);
  Shelly.call('KVS.Get', { key: CFG.kvs_key }, onKvsRead);
}

function onKvsRead(result, code) {
  if (code === 0 && result && !ST.stale) ST.cur = curParse(result.value);
  if (ST.stale) log(1, 'the running block in the KVS predates this version, starting again');
  if (ST.cur === null) log(2, 'no usable running block in the KVS');
  waitForTime();
}

// KVS.Get returns the value already decoded, so nothing here parses a string.
// That is what keeps a damaged entry from taking the script down with it.
function curParse(value) {
  if (typeof value !== 'object' || value === null) return null;
  if (!isNum(value.start_time) || value.start_time < CFG.min_valid_unix) return null;
  if (!isNum(value.duration_sec)) return { start: value.start_time, zero: true };
  if (!isNum(value.energy_mwh) || !isNum(value.meter_net_mwh) || !isNum(value.watt)) return null;
  // Everything signed turns together, or none of it does. The gross counter is
  // not signed and stays as it is.
  let sign = ST.flip ? -1 : 1;
  return {
    start: value.start_time,
    zero: false,
    dur: value.duration_sec,
    energy: sign * value.energy_mwh,
    meter: sign * value.meter_net_mwh,
    gross: isNum(value.meter_gross_mwh) ? value.meter_gross_mwh : null,
    watt: sign * value.watt,
    reference: sign * (isNum(value.reference_watt) ? value.reference_watt : value.watt)
  };
}

// Nothing persistent happens before the clock can be trusted. The plug has no
// backup clock, so after a power cut this waits for NTP -- that is the normal
// case on every boot, not an exception. Two readings five seconds apart have
// to agree that time is moving forwards, and neither may predate the archive.
function waitForTime() {
  let sys = Shelly.getComponentStatus('sys');
  let now = sys && isNum(sys.unixtime) ? sys.unixtime : 0;
  if (sys && isNum(sys.utc_offset)) ST.offset = sys.utc_offset;
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
  let usable = sw && sw.aenergy && isNum(sw.aenergy.total);
  let reading = usable ? netMeter(sw) : { gross: 0, net: 0 };
  let power = sw && isNum(sw.apower) && sw.output !== false ? orient(Math.round(sw.apower * 1000)) : 0;
  let bootTs = now - Math.floor(Shelly.getUptimeMs() / 1000);
  let cur = ST.cur;
  ST.gross = usable ? reading.gross : null;

  if (cur === null) {
    log(2, 'no block to take over, starting from scratch');
  } else if (ST.archiveEnd !== null && cur.start < ST.archiveEnd) {
    // Power was lost between archiving a block and writing its successor to
    // the KVS. The archive already holds it; this entry is a leftover and
    // archiving it again would double it.
    log(1, 'the block in the KVS is already archived, dropping it');
  } else if (cur.zero) {
    // A null block survives a reboot unchanged. Nothing flowed while the plug
    // was away, which is precisely what a null block records, so the outage
    // belongs to it and its start stays where it was. Normal detection closes
    // it as soon as something happens.
    ST.blk = { start: cur.start, ref: 0, energy: 0, meter: reading.net, zero: true };
    log(2, 'continuing the null block that started at ' + cur.start);
  } else if (cur.start + cur.dur >= bootTs - 5) {
    // The checkpoint is younger than this boot, so the script restarted but
    // the device did not: the block never actually stopped.
    let ref = Math.round(cur.reference * 1000);
    // An entry written before the counter had moved carries no usable level.
    // Taking what is flowing right now beats resuming with a reference of
    // zero, which would disagree with the load immediately.
    if (isZero(ref)) ref = power;
    ST.blk = { start: cur.start, ref: ref, energy: cur.energy, meter: cur.meter, zero: false };
    accumulate(ST.blk, reading.net);
    log(2, 'script restart, continuing the block that started at ' + cur.start);
    kvsWrite(now, 'recovery');
  } else {
    recoverAfterReboot(now, bootTs, cur, reading);
  }
  startSampling();
}

// The plug lost power while a block was running and nobody recorded when. The
// counters still know how much flowed since the last checkpoint, so the
// block's own average says how long that much energy would have taken; the
// block ends there, and everything from there to the boot is the outage. The
// measured energy is preserved either way -- only the moment it stopped is a
// guess, and it is bounded by the boot.
function recoverAfterReboot(now, bootTs, cur, reading) {
  let refMw = Math.round(cur.reference * 1000);
  let averageMw = Math.round(cur.watt * 1000);
  // The average is the honest rate to divide the leftover energy by. Early in
  // a block it can still be zero because the counters had not moved yet, and
  // then the level the block opened at is the better guess.
  let rateMw = averageMw !== 0 ? averageMw : refMw;
  if (rateMw < 0) rateMw = -rateMw;
  let checkpoint = cur.start + cur.dur;
  let endTs = checkpoint;
  let energy = cur.energy;
  // The gross counter is the one that says whether this is the same counter at
  // all. Without it, or if it fell, the leftover cannot be trusted.
  let continuous = cur.gross === null || reading.gross >= cur.gross;

  if (continuous) {
    let delta = reading.net - cur.meter;
    energy = energy + delta;
    let magnitude = delta < 0 ? -delta : delta;
    if (rateMw > 0) {
      endTs = checkpoint + Math.round(magnitude * 3600 / rateMw);
      if (endTs > bootTs) endTs = bootTs;
      if (endTs < checkpoint) endTs = checkpoint;
    }
  } else {
    log(1, 'energy counters were reset while away, closing the block at the checkpoint');
  }

  ST.blk = { start: cur.start, ref: refMw, energy: energy, meter: reading.net, zero: false };
  closeBlock(endTs);
  ST.blk = { start: endTs, ref: 0, energy: 0, meter: reading.net, zero: true };
  log(2, 'reboot recovery: block closed at ' + endTs + ', the outage is a null phase');
  kvsWrite(now, 'recovery');
}

function startSampling() {
  if (ST.blk !== null) fillGap(ST.blk.start);
  Timer.set(CFG.sample_ms, true, sample);
  log(1, 'sampling every ' + (CFG.sample_ms / 1000) + ' s');
}

// --------------------------------------------------------- read out over HTTP

// Script.storage cannot be reached over RPC at all, so this is the only way
// for shelly.local -- or a browser -- to see the archive.
//
//   /script/<id>/journal                     index and running block
//   /script/<id>/journal?page=c              one page, decoded
//   /script/<id>/journal?page=c&raw=1        that page exactly as stored
//   /script/<id>/journal?page=c&skip=200&max=100
//
// A tier 1 page can hold well over two hundred blocks and the response is
// built in the same 25 KB the rest of the script lives in, so it comes in
// slices rather than all at once.
function onRequest(request, response) {
  let query = typeof request.query === 'string' ? request.query : '';
  let page = queryValue(query, 'page');
  response.headers = { 'Content-Type': 'application/json' };
  response.code = 200;
  if (page === null) {
    response.body = httpIndex();
  } else {
    let skip = toInt(queryValue(query, 'skip'));
    let max = toInt(queryValue(query, 'max'));
    response.body = httpPage(page, queryValue(query, 'raw') !== null,
                             skip === null ? 0 : skip, max === null ? 250 : max);
  }
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
  let out = '{"version":' + VERSION + ',"generation":' + ST.meta.g +
    ',"utc_offset":' + ST.offset + ',"attic_bytes":' + ST.meta.attic + ',"tiers":[';
  // A coarse tier's most recent stretch is not on a page yet: the bucket that
  // is still filling, and the merged run waiting to see whether the next
  // bucket joins it. Both are reported here, because a reader that only saw
  // the pages would think the tier stops hours before it does.
  let i, row;
  for (i = 0; i < ST.meta.tiers.length; i++) {
    row = ST.meta.tiers[i];
    if (i > 0) out = out + ',';
    out = out + '{"grid_sec":' + CFG.tiers[i][0] + ',"unit_mwh":' + CFG.tiers[i][1] +
      ',"pages":' + (row.pages.length === 0 ? '[]' : '["' + row.pages.join('","') + '"]') +
      ',"pending":' + (row.ps < 0 ? 'null' : '[' + row.ps + ',' + row.pd + ',' + row.pe * CFG.tiers[i][1] + ']') +
      ',"open_bucket":' + (row.bs < 0 ? 'null' : row.bs) +
      ',"open_mwh":' + Math.round(row.acc) +
      ',"carry_mwh":' + Math.round(row.cy) + '}';
  }
  return out + '],"reversed":' + (ST.meta.rev === 1 ? 'true' : 'false') +
    ',"archive_end":' + (ST.archiveEnd === null ? 'null' : ST.archiveEnd) +
    ',"current":' + (ST.blk === null ? 'null' : JSON.stringify(kvsPayload(ST.lastUnix))) + '}';
}

// Blocks come back as [start_time, duration_sec, energy_mwh] triples in real
// units, with the field names given once alongside. Repeating the names on
// every block would treble the response.
function httpPage(key, raw, skip, max) {
  if (SLOTS.indexOf(key) < 0) return '{"error":"no such page"}';
  let text = stGet(key);
  if (text === null) return '{"error":"page is empty"}';
  if (raw) return '{"page":"' + key + '","raw":"' + text + '"}';
  let tier = text.charCodeAt(0) - 48;
  if (tier < 0 || tier >= CFG.tiers.length) return '{"error":"page is damaged"}';
  let grid = CFG.tiers[tier][0];
  let unit = CFG.tiers[tier][1];
  DEC.i = 1;
  DEC.ok = true;
  let at = dec(text);
  if (!DEC.ok) return '{"error":"page is damaged"}';
  let out = '{"page":"' + key + '","tier":' + tier + ',"grid_sec":' + grid +
    ',"start":' + at + ',"fields":["start_time","duration_sec","energy_mwh"],"blocks":[';
  let n = 0;
  let sent = 0;
  let duration, energy;
  while (DEC.i < text.length) {
    duration = dec(text) * grid;
    energy = decZ(text) * unit;
    if (!DEC.ok) break;
    if (n >= skip && n < skip + max) {
      if (sent > 0) out = out + ',';
      out = out + '[' + at + ',' + duration + ',' + energy + ']';
      sent = sent + 1;
    }
    at = at + duration;
    n = n + 1;
  }
  return out + '],"skip":' + skip + ',"returned":' + sent + ',"total":' + n + ',"end":' + at + '}';
}

// --------------------------------------------------------------------- main

function main() {
  log(1, 'power journal v' + VERSION + ' starting' + (CFG.test_mode ? ' in test mode' : ''));
  HTTPServer.registerEndpoint(CFG.endpoint, onRequest);
  begin();
}

// Handed to the test harness on the PC, which runs this very file against a
// simulated plug. Nothing on the device calls it.
//
// The keys are quoted because the uploader renames every top-level name to one
// or two characters, and it leaves strings alone -- so quoting is what keeps
// the tests able to find these after the source has been squeezed.
function selftest() {
  return {
    'CFG': CFG, 'ST': ST,
    'enc': enc, 'encZ': encZ, 'dec': dec, 'decZ': decZ, 'DEC': DEC,
    'sample': sample, 'onSample': onSample, 'deviates': deviates, 'levelChanged': levelChanged,
    'pageInfo': pageInfo, 'metaParse': metaParse, 'metaWrite': metaWrite, 'metaRebuild': metaRebuild,
    'tierWrite': tierWrite, 'feedTier': feedTier, 'emitBlock': emitBlock, 'bucketStart': bucketStart,
    'queueBlock': queueBlock, 'drainBlocks': drainBlocks,
    'kvsPayload': kvsPayload, 'httpIndex': httpIndex, 'httpPage': httpPage
  };
}

main();

// Acceptance tests for the power journal.
//
//   node test/acceptance.js                 run them
//   node test/acceptance.js -v              and show the script's own log
//   PJ_STRIPPED=1 node test/acceptance.js   against what the device gets
//
// The tests drive the real power-journal.js through the simulated plug in
// harness.js. Nothing is reimplemented, so a passing run says something about
// the file that gets uploaded rather than about a copy of it.

'use strict';

const {
  createPlug, decodePage, parseMeta, encode, decodeAt,
  STORAGE_VALUE_LIMIT, A64,
} = require('./harness');

const VERBOSE = process.argv.indexOf('-v') >= 0;

let checks = 0;
let failures = 0;

function test(name, body) {
  process.stdout.write('\n' + name + '\n');
  try {
    body();
  } catch (error) {
    failures++;
    console.log('   !! threw: ' + error.message);
    console.log((error.stack || '').split('\n').slice(1, 4).join('\n'));
  }
}

function ok(condition, what) {
  checks++;
  if (condition) {
    console.log('   ok   ' + what);
  } else {
    failures++;
    console.log('   FAIL ' + what);
  }
}

function eq(actual, expected, what) {
  const same = actual === expected;
  ok(same, what + '  (' + JSON.stringify(actual) + (same ? '' : ' != ' + JSON.stringify(expected)) + ')');
}

function near(actual, expected, tolerance, what) {
  ok(Math.abs(actual - expected) <= tolerance,
    what + '  (' + actual + ' vs ' + expected + ' +/- ' + tolerance + ')');
}

// A plug that has already been through startup and is sampling. waitForTime
// wants two probes that agree time is moving forwards, hence the settle.
function running(options) {
  const plug = createPlug(Object.assign({ verbose: VERBOSE }, options));
  plug.boot();
  plug.settle(4);
  return plug;
}

const sum = (list, pick) => list.reduce((total, item) => total + pick(item), 0);

function gapless(blocks) {
  for (let i = 1; i < blocks.length; i++) {
    if (blocks[i - 1].start + blocks[i - 1].duration !== blocks[i].start) return false;
  }
  return true;
}

// ---------------------------------------------------------------------------

test('1  the codec round trips, including negatives', () => {
  const pj = running().pj;

  let bad = 0;
  for (let n = 0; n < 3000; n++) {
    pj.DEC.i = 0; pj.DEC.ok = true;
    if (pj.dec(pj.enc(n)) !== n) bad++;
  }
  eq(bad, 0, 'three thousand unsigned values survive the script codec');

  bad = 0;
  for (let n = -2000; n < 2000; n++) {
    pj.DEC.i = 0; pj.DEC.ok = true;
    if (pj.decZ(pj.encZ(n)) !== n) bad++;
  }
  eq(bad, 0, 'four thousand signed values survive it too');

  bad = 0;
  for (const n of [0, 31, 32, 1023, 1024, 19750, 19429, 86400, 2000000, 1785870000]) {
    if (decodeAt(pj.enc(n), 0)[0] !== n) bad++;
    pj.DEC.i = 0; pj.DEC.ok = true;
    if (pj.dec(encode(n)) !== n) bad++;
  }
  eq(bad, 0, 'and the script and the harness agree in both directions');

  eq(A64.length, 64, 'the alphabet has 64 characters');
  eq(new Set(A64).size, 64, 'all of them distinct');
  ok(!/["\\]/.test(A64), 'none of them a quote or a backslash, so a page survives JSON');
  ok([...A64].every((c) => c.charCodeAt(0) > 32 && c.charCodeAt(0) < 127), 'all printable ASCII');

  eq(pj.enc(19750).length, 3, '19750 costs three characters where decimal costs five');
  // Zigzag spends one bit on the sign, and a character boundary falls every
  // five, so only values just under a boundary pay for it. This is one of them.
  eq(pj.encZ(19429).length, 4, 'carrying a sign costs 19429 a fourth character');
  eq(pj.encZ(-19429).length, 4, 'and the negative of it exactly the same');
  eq(pj.encZ(3000).length, pj.enc(3000).length, 'while most values pay nothing for it');

  eq(pj.pageInfo(''), null, 'an empty page is refused rather than trusted');
  eq(pj.pageInfo('9' + pj.enc(1785870000)), null, 'so is one naming a tier that does not exist');
  eq(pj.pageInfo('0' + pj.enc(100)), null, 'and one whose start predates the clock');
});

test('2  a first block appears and the page names its tier', () => {
  const plug = running();
  plug.feed(500, 3);
  eq(plug.logsMatching('first block at').length, 1, 'the first block is announced once');
  eq(plug.kvs['current_power'].version, undefined, 'the KVS entry carries no version field');
  near(plug.kvs['current_power'].reference_watt, 500, 1, 'the reference is the level it opened at');

  plug.feedFor(500, 300);
  plug.feed(50, 3);
  const blocks = plug.tierBlocks(0);
  ok(blocks.length >= 1, 'a block reached the archive');
  const page = plug.storage[parseMeta(plug.storage.m).tiers[0].pages[0]];
  eq(page[0], '0', 'the page begins with its tier digit');
  eq(decodePage(page)[0].start, blocks[0].start, 'and decodes to the start the script reports');
});

test('3  a change of level splits a block, a sign flip always does', () => {
  const plug = running();
  plug.feed(500, 10);
  plug.feed(505, 10);
  eq(plug.tierBlocks(0).length, 0, 'one percent is not a change');

  plug.feed(50, 4);
  plug.feed(50, 10);
  eq(plug.tierBlocks(0).length, 1, 'a tenfold drop is');

  const before = plug.tierBlocks(0).length;
  plug.feed(900, 2);
  plug.feed(50, 5);
  eq(plug.tierBlocks(0).length, before, 'a two sample excursion is not, it has to be confirmed');

  const pj = plug.pj;
  ok(pj.levelChanged(300000, -300000), 'plus and minus three hundred watts are different levels');
  ok(pj.levelChanged(-300000, 300000), 'and so they are the other way round');
  ok(!pj.levelChanged(-300000, -305000), 'two exporting levels one percent apart are not');
  ok(pj.levelChanged(-300000, -400000), 'a third more export is');
  // Under low_mw the plug cannot tell one level from another, so everything
  // down there is one level and nothing in it is a change. Leaving it is.
  ok(!pj.levelChanged(0, 300), 'nothing to a third of a watt is not a change any more');
  ok(!pj.levelChanged(1200, 0), 'nor is the flapping back the other way');
  ok(pj.levelChanged(0, 2000), 'but climbing out of the low zone is');
  ok(pj.levelChanged(1200, 40000), 'and so is a real load arriving');
});

test('4  exporting is recorded as negative energy', () => {
  const plug = running();
  plug.feed(-400, 3);
  near(plug.kvs['current_power'].reference_watt, -400, 1, 'the running block knows it is exporting');

  plug.feedFor(-400, 1800);
  plug.feed(0, 4);
  plug.feed(0, 3);

  const blocks = plug.tierBlocks(0);
  ok(blocks.length >= 1, 'the export block was archived');
  ok(blocks[0].energy < 0, 'with negative energy  (' + blocks[0].energy + ' mWh)');
  const watt = (blocks[0].energy * 3600) / blocks[0].duration / 1000;
  near(watt, -400, 15, 'and it reads back as about minus four hundred watts');
});

test('5  a low block costs almost nothing and keeps what flowed', () => {
  const plug = running();
  plug.feed(0, 5);
  eq(plug.kvs['current_power'].watt, 0, 'the KVS says nothing is flowing');
  eq(plug.kvs['current_power'].duration_sec, undefined, 'and leaves out what a null block does not need');

  const writes = plug.kvsWrites;
  plug.feedFor(0, 4 * 3600);
  eq(plug.kvsWrites, writes, 'four hours of nothing cost no further KVS write');

  plug.feed(600, 4);
  plug.feedFor(600, 60);
  const blocks = plug.tierBlocks(0);
  eq(blocks[0].energy, 0, 'nothing flowed, so the archived block holds nothing');
  const page = plug.storage[parseMeta(plug.storage.m).tiers[0].pages[0]];
  ok(page.length <= 1 + 7 + 3 + 1, 'and the whole page is only ' + page.length + ' characters');
});

test('5b  a low block holds the energy that flowed below the resolution', () => {
  // What a doorbell transformer looks like on a Plug M: apower flips between
  // 0 and about 1.2 W every few seconds and means neither, while the meter
  // quietly collects the real 0.85 W. The old detector cut a block at every
  // flip -- 21 of 28 archived blocks were empty fragments. This is the whole
  // point of the low zone: one block, and the energy still right.
  const plug = running();
  let flip = 0;
  for (let i = 0; i < 180; i++) {
    // The plug reports one or the other; the harness meters what is really
    // flowing, which is neither and lies between them.
    plug.watt = (flip = 1 - flip) ? 1.2 : 0;
    plug.tick();
  }
  eq(plug.tierBlocks(0).length, 0, 'half an hour of flapping cut no block at all');

  const running_block = plug.kvs['current_power'];
  eq(running_block.start_time !== undefined, true, 'the block is still the first one');
  ok(running_block.energy_mwh > 0,
    'and it has been collecting energy throughout  (' + running_block.energy_mwh + ' mWh)');

  // And it ends the moment something real happens.
  plug.feed(600, 4);
  plug.feedFor(600, 120);
  const blocks = plug.tierBlocks(0);
  eq(blocks.length, 1, 'a real load closes it, exactly once');
  ok(blocks[0].energy > 0, 'the low stretch kept its energy  (' + blocks[0].energy + ' mWh)');
  near(blocks[0].duration, 1800, 40, 'and covers the whole flapping stretch');
});

test('6  quarter hours land on real quarter hours and never get shorter', () => {
  const plug = running();
  // Deliberately ragged: the level changes at times that are not quarter hours.
  plug.feedFor(400, 1000);
  plug.feedFor(1500, 1400);
  plug.feedFor(300, 2000);
  plug.feedFor(1200, 1700);
  plug.feedFor(250, 2600);

  const quarters = plug.tierBlocks(1);
  ok(quarters.length >= 3, 'the quarter hour tier holds ' + quarters.length + ' blocks');
  ok(quarters.every((b) => b.start % 900 === 0), 'every one starts on a real quarter hour');
  ok(quarters.every((b) => b.duration >= 900), 'none is shorter than a quarter hour');
  ok(quarters.every((b) => b.duration % 900 === 0), 'and every duration is a whole number of them');
  ok(gapless(quarters), 'and they follow one another without a gap');
});

test('7  steady power merges, a staircase does not', () => {
  const plug = running();
  plug.feedFor(500, 6 * 3600);
  // A tier is fed by blocks that have closed, so the run only flushes once the
  // stretch after it has closed too -- not merely started.
  plug.feed(0, 4);
  plug.feedFor(0, 2 * 900);
  plug.feed(900, 4);
  plug.feedFor(900, 900);

  const quarters = plug.tierBlocks(1);
  ok(quarters.length <= 2, 'six steady hours are ' + quarters.length + ' block(s), not twenty four');
  ok(quarters[0].duration >= 5 * 3600, 'the merged block spans ' + Math.round(quarters[0].duration / 3600) + ' hours');

  const other = running();
  for (const watt of [200, 800, 200, 900, 300, 1000, 250, 1100]) other.feedFor(watt, 1800);
  const steps = other.tierBlocks(1);
  ok(steps.length >= 6, 'a staircase keeps ' + steps.length + ' separate quarter hour blocks');
});

test('8  hours and days line up too, days on local midnight', () => {
  const plug = running({ utcOffset: 7200 });
  for (let i = 0; i < 8; i++) plug.feedFor(300 + i * 500, 1800);

  const hours = plug.tierBlocks(2);
  ok(hours.length >= 2, 'the hour tier holds ' + hours.length + ' blocks');
  ok(hours.every((b) => b.start % 3600 === 0), 'every hour block starts on the hour');
  ok(hours.every((b) => b.duration % 3600 === 0), 'and lasts whole hours');
  ok(gapless(hours), 'and they are contiguous');

  // Days go through the reducer directly: two simulated days at ten second
  // samples would be seventeen thousand ticks for one assertion. The check is
  // against local midnight itself rather than against an offset in UTC, so it
  // cannot pass with the shift applied the wrong way round -- which is exactly
  // how a day boundary once ended up at four in the morning.
  const localMidnight = (when, offset) => {
    const start = (offset === 7200 ? plug : running({ utcOffset: offset }))
      .pj.bucketStart(when, 86400);
    return new Date((start + offset) * 1000).toISOString().slice(11, 19);
  };
  eq(localMidnight(1785913800, 7200), '00:00:00', 'a day bucket starts at local midnight in summer');
  eq(localMidnight(1785913800, 3600), '00:00:00', 'and at local midnight once the clocks go back');
  eq(localMidnight(1785913800, 0), '00:00:00', 'and in UTC, where the two are the same');
  ok(plug.pj.bucketStart(1785913800, 86400) <= 1785913800, 'and never after the moment it is asked about');
});

test('9  the tiers agree about how much energy there was', () => {
  const plug = running();
  for (const watt of [600, 1800, 400, 2200, 900, 150, 1300]) plug.feedFor(watt, 2700);
  plug.feed(0, 4);
  plug.feedFor(0, 120);

  const index = JSON.parse(plug.request('').body);
  // A coarse tier's newest stretch is not on a page yet: a bucket is still
  // filling and a merged run is waiting to see whether the next bucket joins
  // it. Both are in the index, and a reader that ignored them would think the
  // tier stops hours short -- so the accounting has to include them.
  const total = (tier) => sum(plug.tierBlocks(tier), (b) => b.energy) +
    (index.tiers[tier].pending === null ? 0 : index.tiers[tier].pending[2]) +
    index.tiers[tier].open_mwh + index.tiers[tier].carry_mwh;

  const native = total(0);
  ok(native > 0, 'the native tier recorded ' + Math.round(native / 1000) + ' Wh');
  near(total(1) / native, 1, 0.02, 'the quarter hour tier accounts for the same energy');
  near(total(2) / native, 1, 0.02, 'and so does the hour tier');
  near(total(3) / native, 1, 0.02, 'and the day tier');
  ok(total(1) <= native * 1.01, 'and none of them claims more energy than actually flowed');
});

test('9b  brief tiny loads join the low run and are not thrown away', () => {
  const plug = running();
  // Thirty seconds of three watts, once per quarter hour, all night. Each is
  // 25 mWh -- an eighth of the 206 mWh packet the meter counts in, so the plug
  // cannot actually resolve a single one of them. They used to cut a native
  // block apiece, which is how a week of them turned F101's quiet nights into
  // 24 W spikes: whichever fragment was open when the counter finally ticked
  // was handed the whole packet. Now they stay inside the low run.
  const blips = 40;
  for (let quarter = 0; quarter < blips; quarter++) {
    plug.feedFor(0, 870);
    plug.feed(3, 3);
  }
  plug.feedFor(0, 1800);
  plug.feed(900, 4);
  plug.feedFor(900, 1800);
  plug.feed(0, 4);
  plug.feedFor(0, 1800);

  const native = plug.tierBlocks(0);
  ok(native.length < blips / 4,
    'natively the night stays ' + native.length + ' blocks rather than forty');

  // The load that is genuinely there still gets its own block: 900 W moves the
  // counter by far more than a packet, so nothing about it waits.
  const loud = native.find((b) => b.duration > 0 && b.energy * 3600 / b.duration > 100000);
  ok(loud, 'the real 900 W load is still a block of its own');

  const night = plug.tierBlocks(1).filter((b) => b.start < loud.start);
  ok(night.length <= 3, 'the quarter hour tier keeps ' + night.length + ' of them, not forty');
  ok(night.some((b) => b.duration > 8 * 900),
    'because the near-empty buckets merged into one long run');
  ok(night.some((b) => b.energy > 0),
    'and that run carries the energy rather than claiming zero  (' +
    night.map((b) => b.energy).join(', ') + ' mWh)');

  const index = JSON.parse(plug.request('').body);
  const booked = sum(plug.tierBlocks(1), (b) => b.energy) +
    (index.tiers[1].pending === null ? 0 : index.tiers[1].pending[2]) +
    index.tiers[1].open_mwh + index.tiers[1].carry_mwh;
  near(booked / sum(native, (b) => b.energy), 1, 0.02,
    'and not a milliwatt hour of the whole night went missing');
});

test('10  a page never exceeds what the device accepts', () => {
  const plug = running();
  for (let i = 0; i < 400; i++) {
    plug.pj.tierWrite(1, 1785870000 + i * 900, 900, 1200 + (i % 97) * 13);
  }
  const pages = parseMeta(plug.storage.m).tiers[1].pages;
  ok(pages.length > 0, 'the quarter hour tier filled ' + pages.length + ' page(s)');

  let widest = 0;
  let over = 0;
  for (const key of Object.keys(plug.storage)) {
    widest = Math.max(widest, plug.storage[key].length);
    if (plug.storage[key].length > STORAGE_VALUE_LIMIT) over++;
  }
  eq(over, 0, 'no slot exceeds the 1022 bytes the firmware silently drops');
  ok(widest > 900, 'and pages really are filled up, the widest is ' + widest + ' bytes');
  ok(Object.keys(plug.storage).length <= 12, 'never more than twelve slots are in use');
});

test('11  each tier keeps its own page allowance and drops the oldest', () => {
  const plug = running();
  for (let i = 0; i < 900; i++) {
    plug.pj.tierWrite(1, 1785870000 + i * 900, 900, 900 + (i % 61) * 29);
  }
  eq(parseMeta(plug.storage.m).tiers[1].pages.length, 3, 'the quarter hour tier stops at three pages');
  ok(plug.logsMatching('dropped').length > 0, 'and says when it drops one');

  const blocks = plug.tierBlocks(1);
  let ordered = true;
  for (let i = 1; i < blocks.length; i++) if (blocks[i].start < blocks[i - 1].start) ordered = false;
  ok(ordered, 'what is left is still in chronological order');
  ok(Object.keys(plug.storage).length <= 12, 'and still inside twelve slots');
});

test('12  day pages that fall out of storage go to the attic', () => {
  const plug = running();
  for (let i = 0; i < 1500; i++) {
    plug.pj.tierWrite(3, 1785870000 + i * 86400, 86400, 500 + (i % 37) * 11);
    plug.drain();
  }
  eq(parseMeta(plug.storage.m).tiers[3].pages.length, 3, 'the day tier stops at three pages');
  ok(plug.atticWrites.length > 0, plug.atticWrites.length + ' page(s) went to the attic instead of being lost');

  const attic = plug.scripts.find((s) => s.name === 'pj-attic');
  const lines = attic.code.split('\n').filter((line) => line.indexOf('//3') === 0);
  eq(lines.length, plug.atticWrites.length, 'each one is a comment line of its own');

  const recovered = decodePage(lines[0].slice(2));
  eq(recovered[0].tier, 3, 'and a line decodes back into a day page');
  ok(recovered.length > 10, 'holding ' + recovered.length + ' days');
  eq(recovered[0].duration, 86400, 'each entry one day long');
  ok(parseMeta(plug.storage.m).attic > 0, 'the metadata tracks the attic at ' +
    parseMeta(plug.storage.m).attic + ' bytes');

  let free = 0;
  for (const slot of 'abcdefghijk') if (plug.storage[slot] === undefined) free++;
  ok(free >= 1, 'and a spare slot is still free for the next copy on write');
});

test('13  a missing or full attic loses the page but not the script', () => {
  const plug = running({ scripts: [] });
  for (let i = 0; i < 1500; i++) {
    plug.pj.tierWrite(3, 1785870000 + i * 86400, 86400, 400);
    plug.drain();
  }
  ok(plug.logsMatching('attic').length > 0, 'it says the attic is missing');
  eq(parseMeta(plug.storage.m).tiers[3].pages.length, 3, 'and carries on with three day pages');
  ok(Object.keys(plug.storage).length <= 12, 'without leaking a slot');

  const full = running();
  full.pj.ST.meta.attic = 19900;
  for (let i = 0; i < 1500; i++) {
    full.pj.tierWrite(3, 1785870000 + i * 86400, 86400, 400);
    full.drain();
  }
  ok(full.logsMatching('attic is full').length > 0, 'a full attic says so');
  eq(full.atticWrites.length, 0, 'and nothing more is written to it');
});

test('14  metadata survives a round trip and can be rebuilt without itself', () => {
  const plug = running();
  plug.feedFor(700, 2 * 3600);
  plug.feedFor(1600, 2 * 3600);
  plug.feed(0, 4);
  plug.feedFor(0, 60);

  const pj = plug.pj;
  const text = plug.storage.m;
  const parsed = pj.metaParse(text);
  ok(parsed !== null, 'the metadata parses back');
  // Round trip through the real writer rather than a serialiser kept for the
  // test, so what is checked is what the device runs. The writer bumps the
  // generation on its way out, so wind it back to land on the same string.
  parsed.g = parsed.g - 1;
  pj.ST.meta = parsed;
  ok(pj.metaWrite(), 'and writes back');
  eq(plug.storage.m, text, 'identically');
  eq(pj.metaParse('nonsense'), null, 'nonsense is refused');
  eq(pj.metaParse('2|1|0|,-1,0,-1,0,0,0'), null, 'so is a row count that does not match the tiers');
  eq(pj.metaParse('1|1|0|' + text.split('|')[3]), null, 'and so is the wrong version');

  const before = JSON.stringify([0, 1, 2, 3].map((t) => plug.tierBlocks(t).length));
  const rebuilt = pj.metaRebuild();
  for (let t = 0; t < 4; t++) {
    eq(rebuilt.tiers[t].pages.join('.'), pj.ST.meta.tiers[t].pages.join('.'),
      'tier ' + t + ' is put back together from the tier digits alone');
  }
  eq(JSON.stringify([0, 1, 2, 3].map((t) => plug.tierBlocks(t).length)), before,
    'and nothing was deleted to do it');
});

test('15  the archive stays gapless across a restart and a power cut', () => {
  const plug = running();
  plug.feedFor(800, 2400);
  plug.feed(300, 4);
  plug.feedFor(300, 1200);

  plug.restartScript();
  plug.boot();
  plug.settle(4);
  plug.feedFor(300, 600);
  plug.feed(1400, 4);
  plug.feedFor(1400, 900);

  ok(gapless(plug.tierBlocks(0)), 'a script restart leaves no gap between blocks');
  ok(plug.logsMatching('script restart').length > 0, 'and it knows the device never stopped');

  plug.powerCut(3 * 3600);
  plug.boot();
  plug.settle(4);
  plug.feedFor(600, 1800);
  plug.feed(0, 4);
  plug.feedFor(0, 60);

  ok(gapless(plug.tierBlocks(0)), 'and neither does a three hour outage');
  ok(plug.logsMatching('null block').length > 0 || plug.logsMatching('outage').length > 0,
    'the outage itself is filed as time nothing happened');

  const quarters = plug.tierBlocks(1);
  ok(gapless(quarters), 'the quarter hour tier came through the outage gapless as well');
  ok(quarters.every((b) => b.start % 900 === 0), 'and still aligned to real quarter hours');
  ok(quarters.every((b) => b.duration >= 900), 'and still never finer than a quarter hour');
});

test('16  a block already in the archive is not archived twice', () => {
  const plug = running();
  plug.feedFor(900, 2400);
  plug.feed(200, 4);
  plug.feedFor(200, 600);
  const archived = plug.tierBlocks(0).length;

  plug.kvs['current_power'] = {
    start_time: plug.tierBlocks(0)[0].start, duration_sec: 60,
    energy_mwh: 10, meter_net_mwh: 5, meter_gross_mwh: 5, watt: 0.6, reference_watt: 0.6,
  };
  plug.powerCut(600);
  plug.boot();
  plug.settle(4);
  plug.feed(500, 4);
  plug.feedFor(500, 600);

  ok(plug.logsMatching('already archived').length > 0, 'it recognises the leftover');
  const starts = plug.tierBlocks(0).map((b) => b.start);
  eq(new Set(starts).size, starts.length, 'and no block start appears twice');
  ok(plug.tierBlocks(0).length >= archived, 'while the archive did not shrink');
});

test('17  a reset is noticed on the gross counter, not the net one', () => {
  const plug = running();
  plug.feedFor(1000, 1800);
  const before = plug.pj.ST.blk.energy;
  ok(before > 0, 'the block has counted ' + before + ' mWh');

  plug.setMeters(0, 0);
  plug.feed(1000, 2);
  ok(plug.logsMatching('reset').length > 0, 'the reset is spotted');
  // The block rebases on the new reading, so it keeps what it had and carries
  // on. Only the two sampling intervals since the reset may be added.
  near(plug.pj.ST.blk.energy, before, 6000,
    'and the block keeps what it counted instead of swallowing the lifetime total');

  const solar = running();
  solar.feedFor(-500, 1800);
  eq(solar.logsMatching('reset').length, 0, 'a plant running the net counter backwards is not a reset');
  ok(solar.pj.ST.blk.energy < 0, 'it is simply negative energy  (' + solar.pj.ST.blk.energy + ' mWh)');
});

test('18  the KVS entry fits, and says what it is', () => {
  const plug = running();
  plug.feedFor(-2400, 4000);
  const entry = plug.kvs['current_power'];
  const text = JSON.stringify(entry);
  ok(text.length <= 253, 'a full entry is ' + text.length + ' of the 253 bytes allowed');
  for (const key of ['start_time', 'duration_sec', 'energy_mwh',
    'meter_net_mwh', 'meter_gross_mwh', 'watt', 'reference_watt']) {
    ok(entry[key] !== undefined, 'it carries ' + key);
  }
  eq(Object.keys(entry).length, 7, 'and nothing else, no version among it');
  ok(entry.watt < 0, 'the average is negative while exporting');
  ok(entry.meter_gross_mwh > 0, 'while the gross counter only ever climbs');

  const before = plug.kvsWrites;
  plug.kvsFail = true;
  plug.feed(60, 4);
  plug.feedFor(60, 60);
  ok(plug.logsMatching('KVS write failed').length > 0, 'a failed write is reported');
  plug.kvsFail = false;
  plug.feedFor(60, 120);
  ok(plug.kvsWrites > before, 'and retried on a later sample rather than forgotten');
});

test('19  the HTTP endpoint hands out the index and a tier from a moment on', () => {
  const plug = running();
  for (const watt of [500, 1700, 350, 2100, 800]) plug.feedFor(watt, 2700);
  plug.feed(0, 4);
  plug.feedFor(0, 60);

  const index = JSON.parse(plug.request('').body);
  eq(index.api, 2, 'the index says which shape of read it offers');
  eq(index.version, 3, 'which is not the archive version, still 3');
  eq(index.tiers.length, 4, 'and lists four tiers');
  eq(index.tiers[1].grid_sec, 900, 'with the quarter hour grid named');
  eq(index.utc_offset, 7200, 'and the offset the day buckets use');
  ok(index.archive_end > 0, 'it says how far the archive reaches');
  ok(index.current !== null, 'and hands over the running block');

  const stored = plug.tierBlocks(1);
  const all = JSON.parse(plug.request('tier=1&from=0').body);
  eq(all.tier, 1, 'a read knows which tier it is');
  eq(all.grid_sec, 900, 'and at what resolution');
  eq(all.blocks.length, stored.length, 'from zero it hands over the whole tier');
  ok(all.blocks.every((b) => b.length === 3), 'each block a triple');
  ok(all.blocks.every((b) => b[1] % 900 === 0), 'with durations in real seconds, not grid steps');
  eq(all.blocks[0][0], stored[0].start, 'and the first start matches the archive');
  eq(all.tier_start, stored[0].start, 'tier_start says how far back the tier still reaches');
  eq(all.more, false, 'nothing is left over');
  eq(all.next, stored[stored.length - 1].start + stored[stored.length - 1].duration,
    'and next is where the tier ends');
  eq(all.generation, index.generation, 'the generation rides along, so a reader can spot a write');

  // The whole point of asking by time: what a reader already has, it does not
  // ask for again, and no slot name is ever involved.
  const cut = stored[Math.floor(stored.length / 2)].start;
  const since = JSON.parse(plug.request('tier=1&from=' + cut).body);
  ok(since.blocks.length < all.blocks.length, 'a later start asks for less');
  ok(since.blocks.every((b) => b[0] + b[1] > cut), 'and hands back nothing that ended before it');
  eq(since.blocks[0][0], cut, 'the block starting there is included');

  const sliced = JSON.parse(plug.request('tier=1&from=0&max=2').body);
  eq(sliced.blocks.length, 2, 'max cuts the response down');
  eq(sliced.more, true, 'and says there is more');
  eq(sliced.next, all.blocks[2][0], 'next points at the first block left out');
  const rest = JSON.parse(plug.request('tier=1&from=' + sliced.next).body);
  eq(sliced.blocks.length + rest.blocks.length, all.blocks.length,
    'so the slices join up into the whole tier with nothing lost or repeated');

  const empty = JSON.parse(plug.request('tier=3&from=0').body);
  eq(empty.tier_start, null, 'a tier with no pages says so rather than guessing');
  ok(JSON.parse(plug.request('tier=9').body).error !== undefined,
    'an unknown tier is an error, not a crash');
});

test('19a a page rewritten mid read costs the reader nothing', () => {
  const plug = running();
  for (const watt of [500, 1700, 350, 2100, 800]) plug.feedFor(watt, 2700);
  plug.feedFor(0, 600);

  const before = JSON.parse(plug.request('tier=1&from=0').body);
  ok(before.blocks.length > 0, 'the tier has blocks to hand out');

  // Exactly what tierWrite does to a page it appends to: the content is copied
  // into a spare slot, the metadata is switched over, and the old slot is
  // emptied. Under the old read-by-slot endpoint this was the race -- the
  // reader had been handed a slot name and the name went stale underneath it.
  const meta = plug.storage.m.split('|');
  const rows = meta[4].split(';');
  const row = rows[1].split(',');
  const keys = row[0].split('.');
  const taken = meta[4].split(';').map((r) => r.split(',')[0]).join('.').split('.');
  const spare = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k']
    .find((slot) => taken.indexOf(slot) < 0);
  ok(plug.storage[keys[0]] !== undefined, 'the page is where the metadata says');
  plug.storage[spare] = plug.storage[keys[0]];
  delete plug.storage[keys[0]];
  keys[0] = spare;
  row[0] = keys.join('.');
  rows[1] = row.join(',');
  meta[4] = rows.join(';');
  plug.storage.m = meta.join('|');
  plug.restartScript();
  plug.boot();

  const after = JSON.parse(plug.request('tier=1&from=0').body);
  eq(JSON.stringify(after.blocks), JSON.stringify(before.blocks),
    'and the reader sees the same blocks, from a slot it never had to name');
});

test('20  test mode writes nothing at all', () => {
  const plug = running();
  plug.pj.CFG.test_mode = true;
  const storageBefore = JSON.stringify(plug.storage);
  const kvsBefore = JSON.stringify(plug.kvs);
  for (const watt of [400, 1500, 200, 1900]) plug.feedFor(watt, 2000);
  eq(JSON.stringify(plug.storage), storageBefore, 'storage is untouched');
  eq(JSON.stringify(plug.kvs), kvsBefore, 'and so is the KVS');
  ok(plug.logsMatching('test mode').length > 0, 'while it says what it would have written');
});

test('21  the switch is never written to', () => {
  const source = require('fs').readFileSync(
    require('path').join(__dirname, '..', 'power-journal.js'), 'utf8');
  ok(source.indexOf('Switch.Set') < 0, 'the source never mentions Switch.Set');
  ok(source.indexOf('Switch.Toggle') < 0, 'nor Switch.Toggle');

  const plug = running();
  const output = plug.output;
  plug.feedFor(700, 3600);
  eq(plug.output, output, 'and the relay is where it was');
});

// Reverse metering makes a plant report its generation as positive. The flag
// can be flipped at any point in a plug's life and nothing in the numbers says
// which way round they were taken, so the script settles the sign on the way in
// and stores one convention: positive is drawn from the grid.
test('22  the stored sign does not depend on how the plug reports', () => {
  const plain = running();
  plain.feedFor(-400, 3600);
  plain.feed(0, 4);
  plain.feedFor(0, 60);
  const straight = plain.tierBlocks(0).find((b) => b.energy !== 0);
  ok(straight.energy < 0, 'a plant read straight is stored as negative  (' + straight.energy + ')');

  // The same plant on a plug with reverse metering: the meter now reports the
  // generation as positive, and the archive has to come out the same anyway.
  const reversed = running({ reverse: true });
  reversed.feedFor(400, 3600);
  reversed.feed(0, 4);
  reversed.feedFor(0, 60);
  const flipped = reversed.tierBlocks(0).find((b) => b.energy !== 0);
  ok(flipped.energy < 0, 'and so is the same plant read reversed  (' + flipped.energy + ')');
  near(flipped.energy, straight.energy, 2000, 'to within a rounding of the same figure');
  eq(parseMeta(reversed.storage.m).rev, 1, 'the metadata records which way the plug reports');
  ok(JSON.parse(reversed.request('').body).reversed === true,
    'and the index says so, so a reader never has to ask the plug');
});

test('23  a flag change that has not been rebooted into is not believed yet', () => {
  // Setting reverse only takes effect on a device restart. Until then the plug
  // still measures the old way while GetConfig already reports the new flag,
  // and adopting it would put a sign flip in the middle of the history.
  // Closing a block is what persists the metadata, so the run has to end
  // before there is anything on file to look at.
  const plug = running();
  plug.feedFor(-400, 1800);
  plug.feed(0, 4);
  plug.feedFor(0, 60);
  eq(parseMeta(plug.storage.m).rev, 0, 'it starts out reading straight');

  plug.reverse = true;
  plug.restartRequired = true;
  plug.restartScript();
  plug.boot();
  plug.settle(4);
  eq(parseMeta(plug.storage.m).rev, 0, 'a pending restart leaves the orientation alone');
  ok(plug.logsMatching('restart is pending').length > 0, 'and it says why');

  // The reboot happens, and now the flag is real.
  plug.restartRequired = false;
  plug.powerCut(60);
  plug.boot();
  plug.settle(4);
  plug.feedFor(400, 1800);

  // Checked while a block is actually running: a null block carries no energy
  // field at all, so waiting until the end would check nothing.
  const live = plug.kvs['current_power'];
  const plausible = 400 * 3600;
  ok(Math.abs(live.energy_mwh) < plausible,
    'and the running block did not swallow the lifetime counter  (' +
      live.energy_mwh + ' mWh)');

  plug.feed(0, 4);
  plug.feedFor(0, 60);
  eq(parseMeta(plug.storage.m).rev, 1, 'after the restart it is adopted');
  const blocks = plug.tierBlocks(0).filter((b) => b.energy !== 0);
  ok(blocks.every((b) => b.energy < 0),
    'and both halves of the history are stored the same way round');

  // The bookmark in the KVS points into the plug's lifetime counter and is only
  // comparable with readings taken the same way round. Left unturned, the first
  // sample after the flip books the difference between plus and minus the whole
  // lifetime total as the energy of one ten second interval -- which is exactly
  // what the plug did before this was handled: minus 6.4 kWh in a fresh block.
  ok(blocks.every((b) => Math.abs(b.energy) < plausible),
    'nor did any archived one');
});

// An archive written before the sign was settled means something else, and
// nothing in it says so. Carrying it forward would hide a flip in the middle of
// the history, which is worse than losing a few hours.
test('24  an archive from an older version is dropped rather than mixed in', () => {
  const plug = running();
  plug.feedFor(700, 3600);
  plug.feed(0, 4);
  plug.feedFor(0, 60);
  ok(plug.tierBlocks(0).length > 0, 'there is something to lose');

  plug.storage.m = '2|9|0|' + plug.storage.m.split('|').slice(4).join('|');
  plug.restartScript();
  plug.boot();
  plug.settle(4);
  eq(Object.keys(plug.storage).filter((k) => k !== 'm').length, 0, 'every page is gone');
  ok(plug.logsMatching('starting a new one').length > 0, 'and it says what it did');
});

// The Android app deploys the journal from a bundled copy rather than from
// this directory, and a Gradle build has no Node to generate it with. So the
// squeezed script is checked in, and a change here that nobody regenerated
// would otherwise ship an app that installs an old script on a plug.
test('25  the copy the app ships is the script in this directory', () => {
  const { build, TARGET } = require('../tools/asset');
  const fs = require('fs');
  ok(fs.existsSync(TARGET), 'the app carries a copy of the script');
  ok(fs.readFileSync(TARGET, 'utf8') === build(),
    'and it is up to date -- run node tools/asset.js if this fails');
});

// The plug refuses a call stack it believes is about to overflow, and it kills
// the script when it does -- mid-write, with the archive half switched over.
// Measured on a Plug M Gen3 running 2.0.0, the budget is 15 nested frames from
// a timer callback and 14 from an RPC callback, and those were counted with
// small functions; the ones in here are bigger and cost more. So the ceiling
// held below is well under the measured limit, and it is a real ceiling: the
// script once ran twelve deep on the recovery path and died there.
//
// Frames of the script read "at name (eval at boot (harness.js), ...)" because
// the harness loads the file with new Function. A frame without that is the
// harness itself, which is where the script's own chain ends.
test('26  the archive never nests deeper than the plug allows', () => {
  const CEILING = 10;
  let deepest = { n: 0, chain: '' };
  const realRound = Math.round;
  Math.round = function (x) {
    const names = [];
    for (const line of new Error().stack.split('\n').slice(2)) {
      if (!/eval at/.test(line)) break;
      const m = /at (?:new )?([A-Za-z_$][\w$]*)/.exec(line);
      names.push(m ? m[1] : '<anon>');
    }
    if (names.length > deepest.n) deepest = { n: names.length, chain: names.join(' <- ') };
    return realRound(x);
  };

  try {
    // Long enough that every tier fills a page, retires one, and reaches the
    // attic, and with a reboot in the middle so the recovery path is walked
    // too -- that is the entry point that is already two frames down before
    // the script's own code starts.
    const plug = running();
    let watt = 0;
    for (let round = 0; round < 2; round++) {
      for (let i = 0; i < 6 * 60 * 24 * 2; i++) {
        if (i % 5 === 0) watt = watt > 0 ? 0 : 300 + (i % 11) * 40;
        plug.watt = watt;
        plug.tick();
      }
      plug.powerCut(600);
      plug.boot();
      plug.settle(4);
    }
    ok(deepest.n > 0, 'the run actually reached the archive  (' + deepest.n + ' frames)');
    ok(deepest.n <= CEILING, 'and never deeper than ' + CEILING + ': ' + deepest.chain);
  } finally {
    Math.round = realRound;
  }
});

test('27  the clock is the plug\'s own, and the day grid never breaks', () => {
  // Europe/Berlin, 29 March 2026: 02:00 CET becomes 03:00 CEST, so that local
  // day is 23 hours long. A day block is a fixed 86400 seconds -- the page
  // format stores durations as multiples of the tier's grid and nothing else
  // fits -- so the grid cannot follow the offset. What it must do instead is
  // stay unbroken: whatever the offset does, day blocks keep meeting end to
  // end, because a page reconstructs every start by summing the durations
  // before it. A gap or an overlap here would not be an hour out of place, it
  // would silently move every block after it.
  const midnight = Date.UTC(2026, 2, 28, 23, 0, 0) / 1000; // 00:00 CET, 29.03
  const change = Date.UTC(2026, 2, 29, 1, 0, 0) / 1000;    // 02:00 CET -> 03:00 CEST
  const plug = running({ unixtime: midnight - 600, utcOffset: 3600 });

  let step = 0;
  while (plug.unixtime < midnight + 3 * 86400) {
    plug.feedFor(step++ % 2 ? 100 : 400, 1800);
    if (plug.unixtime >= change) plug.utcOffset = 7200;
  }

  const days = plug.tierBlocks(3);
  ok(days.length >= 2, 'the day tier filled  (' + days.length + ' blocks)');
  ok(gapless(days), 'and its blocks still meet end to end across the change');
  ok(days.every((b) => b.duration === 86400), 'each of them a whole day long');
  eq(days[0].start % 86400, midnight % 86400,
    'the grid still sits where it was anchored, one hour off local midnight now');

  const index = JSON.parse(plug.request('').body);
  eq(index.unixtime, plug.unixtime, 'the index reports the plug\'s own clock');
  eq(index.utc_offset, 7200, 'and the offset it is currently keeping');
});

// ---------------------------------------------------------------------------

console.log('\n' + '-'.repeat(64));
console.log((process.env.PJ_STRIPPED === '1' ? 'stripped source' : 'commented source') +
  ': ' + checks + ' checks, ' + failures + ' failed');
process.exit(failures === 0 ? 0 : 1);

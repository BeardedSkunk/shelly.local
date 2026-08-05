# Power journal

A Shelly script that records what a Plug M Gen3 puts through, entirely on the
device. No cloud, no broker, no server, no outbound connection at all.

Stretches of roughly constant power become *blocks*. The block that is still
running lives in the device KVS where anything on the network can read it;
finished blocks go into a resolution pyramid in the script's own storage, and
what falls out of the bottom of that goes into a second script that never runs.

The script never writes to the switch. It only reads `switch:0`, so it cannot
interfere with whatever else is driving the relay.

## The pyramid

Twelve storage slots exist on the device, each holding 1022 bytes. One is the
metadata and one always stays free for copy-on-write, which leaves ten pages.
They are divided like this:

| tier | grid | energy unit | pages | blocks/page | reaches back at least |
|---|---|---|---|---|---|
| native | — | 1 mWh | 1 | ~250 | the last few hundred blocks |
| quarter hour | 900 s | 100 mWh | 3 | 250 | **7.8 days** |
| hour | 3600 s | 1 Wh | 3 | 250 | **31 days** |
| day | 86400 s | 10 Wh | 3 | 250 | **2.1 years** |
| day, in the attic | 86400 s | 10 Wh | 20 | 250 | **13.7 years** more |

Those are floors, not estimates: they assume every bucket is a block of its
own and nothing ever merges. They also do not depend on the load — a block
costs four characters anywhere between a balcony plant and a 16 A heater,
because the duration is one grid step and the energy fits in three characters
across that whole range. Real use does better, often much better: a night
merges into one entry.

**End to end, day resolution reaches back 5750 days — about 15.7 years.**

Every closed block is fed to **all four tiers at once**. Tier 0 keeps it
verbatim; the others drop it into buckets on their grid. The tiers do not
cascade into one another, and that is the point: a cascade would have to
re-bucket a page whose edges fall wherever the previous page happened to end,
so its quarter hours would drift off the clock. Fed straight from the block
stream, every boundary is a real quarter hour, a real hour, and — because the
device's UTC offset is applied — a real local midnight.

Buckets of equal power are merged, so a night is one entry rather than thirty
two identical ones. A block is therefore **never shorter than its tier's grid,
but may be longer**. Days are the exception: they are never merged, because
the day tier exists to be read as days.

Each tier drops its oldest page when it is full. For the first three that
costs nothing — the tier below already holds the same stretch, more coarsely.
The day tier has nothing below it, so its pages go to the attic.

## The attic

A second script named `pj-attic`, created disabled and never started. Its
source is twenty kilobytes of writable space the storage limit cannot touch,
and a comment is the only shape that space can safely take:

```
// power-journal attic. This script never runs.
//3XPGJX$&GN*&SZ;&UM(&WTA...
//3g2LMX%$FN)$RQ<$TN...
```

The journal appends to it with `Script.PutCode`, which one script may do to
another; the call returns the new total length, which is also the capacity
counter. That is twenty more day pages, so **13.7 years** before anything is
genuinely lost — long enough that shelly.local will have carried the history
off first.

Deleting the journal takes its storage with it. The attic is a separate
script and survives, which is why `--remove` leaves it alone unless you also
pass `--with-attic`.

## Install

```bash
node tools/upload.js 192.168.178.23
```

Creates the attic if it is missing, uploads the journal, verifies it by
reading the code back, enables it so it survives a reboot, and starts it.

```bash
node tools/upload.js 192.168.178.23 --status
node tools/upload.js 192.168.178.23 --attic
node tools/upload.js 192.168.178.23 --remove
```

The upload shrinks the source first: comments and indentation go, and every
top-level name is renamed to one or two characters. A script may be 20480
bytes on the device and all of that counts, so the repository keeps the
explanation and the plug gets the code — 44 KB of source, 18.6 KB uploaded.

## Reading it

The running block, from anywhere on the network:

```bash
curl -s http://192.168.178.23/rpc/KVS.Get?key=current_power
```

```json
{"start_time":1785912166,"duration_sec":60,"energy_mwh":4807,
 "meter_net_mwh":-47658,"meter_gross_mwh":258350,"watt":288.42,
 "reference_watt":304}
```

- `energy_mwh` — what **this block** has put through, signed.
- `meter_net_mwh`, `meter_gross_mwh` — where the **plug's lifetime counters**
  stood when this was written. Energy is never measured directly, only as a
  difference of those, so recovery needs the bookmark to work out how much
  flowed while the script was away. The gross one only ever climbs, which is
  what makes a counter reset detectable at all.
- `watt` — the block's **average**, not the current draw. A live value would
  mean a flash write every ten seconds. Whoever wants it reads
  `Switch.GetStatus`, which costs nothing.
- `reference_watt` — the level the block was **opened** at, from `apower`.
- `duration_sec` — how much of the elapsed time is already accounted for in
  `energy_mwh`. The entry can be up to half an hour old, so without it a reader
  could not tell.

A null block needs none of that and says so by leaving it out:

```json
{"start_time":1785870000,"watt":0}
```

There is no version field. The entry is meant to be read at a glance, and the
one place a version actually matters — the archive — carries its own, in the
metadata and in every page's tier digit.

The archive is **not** reachable over RPC: `Script.storage` has no RPC methods
at all, it exists only inside the script. So the script serves it over HTTP:

```bash
curl -s http://192.168.178.23/script/2/journal
curl -s "http://192.168.178.23/script/2/journal?page=c"
curl -s "http://192.168.178.23/script/2/journal?page=c&skip=200&max=100"
curl -s "http://192.168.178.23/script/2/journal?page=c&raw=1"
```

The index gives every tier's pages, how far the archive reaches, and the live
block. It also gives each tier's `pending` and `open_bucket`: a coarse tier's
most recent stretch is not on a page yet, and a reader that ignored those would
think the tier stops hours before it does.

A page expands into `[start_time, duration_sec, energy_mwh]` triples in real
units, with the field names given once alongside — repeating them per block
would treble the response, and there are about 25 KB of script memory to build
it in. Hence `skip` and `max`: a quarter hour page can hold well over two
hundred blocks. Average watt is `energy_mwh * 3600 / duration_sec`.

## Two counters, and why energy is signed

The plug keeps `aenergy.total`, which counts everything that crossed the meter
in either direction, and `ret_aenergy.total`, which counts only the part that
went back out. So what actually flowed, signed, is

```
net = aenergy.total - 2 * ret_aenergy.total
```

On a balcony solar plant with nothing else attached the two are equal to the
millilitre and the net is exactly the negative of either — the plug exports
everything. Set `Switch.SetConfig {"reverse": true}` (a device reboot is
required) and the plant reads positive instead, which is nicer to look at; the
formula is unaffected, because only differences are ever used and the frozen
part cancels out.

Carrying the sign costs one bit, which the varint spends only when the value
happens to sit just under a character boundary. Measured over a simulated year
that is **0 to 6%** — cheap enough that giving up on bidirectional devices to
save it would be a bad trade.

## Storage formats

### Archive page

```
1SXPGJX$&GN*&SZ;&UM(&WTA
```

A tier digit, then the page's start as an absolute unix second, then one pair
per block: duration in grid steps, energy in the tier's unit and zigzagged.
Nothing separates the fields.

Numbers are varints over a 64 character alphabet — five payload bits per
character and a sixth saying whether another follows — so each carries its own
length. A quarter hour is the number `1`, not the number `900`.

The alphabet is two ASCII runs, 35..91 and 93..99. No contiguous 64 wide
window of printable ASCII avoids both `"` and `\`, and both have to stay out
because a page travels inside a JSON response; two runs still decode with a
single comparison.

The tier digit is there so a rebuild can tell which level a slot belongs to
without the metadata. Only the page start is stored: every block begins where
the one before it ended, which holds because the archive is **gapless by
construction** — any stretch nobody accounted for is filed as a null block.

This is flat text rather than JSON on purpose. `JSON.parse()` on a damaged
string raises an uncatchable `SyntaxError` that takes the whole script down —
`try`/`catch` parse fine on firmware 2.0.0 but do not catch it, verified on the
device. A scanning loop cannot throw. The KVS entry stays JSON because
`KVS.Get` hands it back already decoded, so nothing has to parse that either.

### Metadata

```
2|17|3048|d,-1,0,-1,0,0,0;b,1785884400,6657,1785877200,7200,31915,1582836;...
```

Version, generation, attic bytes, then one row per tier: its pages in
chronological order, the bucket currently filling, and the merged run waiting
to be written. The reducer state rides along because it changes exactly when a
page does, so it costs no extra write; losing it costs one partial bucket and
nothing else.

If the metadata is unreadable it is rebuilt by reading every slot, taking each
page's tier from its first character and sorting by start time. Nothing is
deleted to recover, ever.

## How a block ends

A sample has to disagree with the running block by at least the tolerance,
`max(10% of the reference power, 200 mW)`, in three samples in a row. The old
block then ends at the **first** of those three, and the new one begins there.

The tolerance is measured against a reference power fixed when the block
opened, **not** against the block's running average. An average dragged along
by every sample it accepts never notices a slow ramp. The reference is taken
in magnitude, so an exporting plant gets the same relative tolerance a
consuming one does — and a sign flip is always a change, because the gap is
then the sum of both sides.

Crossing between nothing and something always counts, however small the
something is.

### Loads too small for a coarse tier

A thirty second switch-on at three watts is 25 mWh. Natively that is a block
of its own; in the quarter hour tier, where the unit is 100 mWh, it is a
quarter of the smallest number that can be written down.

Two things get decided about such a bucket, and they are decided separately.
**What level it is** comes from the bucket alone: under half a unit there is
nothing at that resolution to tell it apart from nothing at all, so it reads as
null and merges into the run around it. That threshold works out at 0.2 W over
a quarter hour, 0.5 W over an hour and 0.2 W over a day — the same order as
the 200 mW floor below which the block detector does not react either.

**What gets booked** is the bucket plus everything earlier buckets could not
express. The remainder is carried, never dropped. So a night of brief
switch-ons comes out as *one* long block that honestly says a watt hour
flowed, rather than as forty blocks each claiming zero — and equally not as
ten blocks interrupting the night whenever the carry crossed a unit, which is
what letting the carry decide the level too would have produced.

Measured: 82 native blocks over a simulated night become a single quarter hour
block carrying 1000 mWh, with the tier's total still matching the native
total exactly.

A sample taken at T reports a counter that already covers the interval ending
at T, so the energy of the interval a change happened in lands in whichever
block owns T. For a null block that would show, since a block recording that
nothing flowed must not carry energy — so a null block hands its final
interval to its successor, which is where the load that ended it belongs
anyway. Nothing is lost; the counter difference only moves across the boundary.

## What it costs in flash

| | when |
|---|---|
| KVS | block opens, then every 30 min, and at recovery |
| KVS, null block | once, when it opens — never again |
| tier 0 | every time a block closes |
| tiers 1–3 | only when a bucket run ends |

Nothing is written every ten seconds. A null block that lasts twelve hours
costs exactly one write.

An archive append is three storage writes, not one: the new page goes to a
spare slot, then the metadata switches over, then the old slot is released.
Losing power before the metadata write leaves the old page valid, losing it
after leaves the new one valid; there is no moment at which the archive is
neither.

## Tests

```bash
node test/acceptance.js       # 137 checks
node test/acceptance.js -v    # and the script's own log
PJ_STRIPPED=1 node test/acceptance.js   # against what the device actually gets
```

The tests load the real `power-journal.js` and run it against a simulated plug
— nothing is reimplemented in the harness, so what passes is what gets
uploaded. The harness decodes pages with its own second implementation of the
format: if the two ever disagree, one of them is wrong, and reading the archive
back through it is a real check rather than a tautology.

The simulated limits are the ones measured on the device rather than the
documented ones: a storage value is dropped above **1022** bytes (not 1024),
the thirteenth entry is refused, both silently, and a KVS value may be **253**
characters exactly.

`CFG.test_mode` does the same job on the device: it logs what it would persist
and writes nothing, so it cannot damage a real journal.

## Device facts this relies on

Measured on a Plug M Gen3, `S3PL-30110EU`, firmware 2.0.0.

| | |
|---|---|
| `Script.storage` | 12 entries, **1022** bytes per value, `setItem`/`getItem`/`removeItem` only — no `getKeys`, and no RPC access at all |
| KVS value | **253** characters exactly |
| script code | 20480 bytes, comments included |
| scripts per device | at least 10 |
| `Script.PutCode` | one script may append to another and gets the new total length back |
| script memory | about 25 KB |
| `aenergy.total` | Wh with three decimals, so whole mWh — but it jumps by a fixed quantum, minutes apart, and the quantum is calibrated per unit (206.694 mWh on one plug here, 209 mWh on another) |
| `ret_aenergy.total` | the part that went back out; equal to `aenergy.total` on a pure producer |
| `Sys.GetStatus.utc_offset` | seconds east of UTC, which is what puts day buckets on local midnight |
| `JSON.parse` on bad input | uncatchable, kills the script |
| mJS | no `Math.min`, no `Array.shift`; `chr()` exists, and `str.at(i)` returns a byte value rather than a character |

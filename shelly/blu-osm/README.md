# blu-osm

The script that publishes a Shelly BLU H&T to openSenseMap, as a template.

It is checked in with placeholders rather than with values:

    {{OSM_URL}}          the box's data endpoint
    {{OSM_TOKEN}}        that box's access token
    {{OSM_TEMPERATURE}}  the box's temperature sensor id
    {{OSM_HUMIDITY}}     its humidity sensor id

The app fills them in when it deploys, from the box the user picked out of
their own account. That is the whole reason for the template: a token belongs
to one box and to one person, and a copy of it checked in here would be a copy
of it in every build of the app and in the repository's history.

`app/src/main/assets/blu-osm.js` is a copy of this file and is what the app
actually uploads. Keep the two in step.

## Changing the sensor

The script never names a sensor. It looks for BTHome components carrying the
object ids it wants — battery, humidity, temperature — and takes the first of
each. Which physical BLU H&T sits behind them is the plug's business, not the
script's, so a sensor can be replaced without touching a line of code.

That holds across the two models in this household. The one with the display
(`SBHT-003C`) also broadcasts a light reading and two objects the firmware has
no name for; the one without (`SBHT-203C`) broadcasts something the object table
calls a distance in millimetres. None of it is looked at. The three that matter
are the same on both.

Two tools do the pairing, both over plain RPC because nothing here goes through
the Shelly app:

    node tools/discover.js <plug-ip> [--watch]   what the plug hears
    node tools/pair.js <plug-ip> --list
    node tools/pair.js <plug-ip> --addr <mac> [--name "..."]
    node tools/pair.js <plug-ip> --unpair <mac>

`discover.js` finds out an address one does not know. It reports a sensor while
it is in pairing mode and not otherwise, so four presses on the sensor's button
first, and `--watch` until it turns up.

But the search does something else as well, and it is the thing that matters:
**it turns the receiver on**. A plug with no BLU paired to it keeps its
Bluetooth receiver off — `BLE.GetStatus` shows an address and no `flags`, while
a plug that has one shows `["advertising","scanning"]` — and `AddDevice` does
not switch it on by itself. It waits for a packet nobody is listening for,
until the plug drops the connection; over HTTP that arrives as `fetch failed`,
over a websocket as a plain connection error, and neither says anything about
the sensor.

On 12.08.2026 that cost half an hour of looking in the wrong places. The sensor
was lying next to the plug at −58 dBm and broadcasting; the plug heard nothing.
With `BTHome.StartDeviceDiscovery` in front of it the same call took 32 seconds.
`pair.js` now does that first, every time, so this is a footnote rather than a
trap. Reaching for the RPCs by hand, put the search first.

The pairing mode also expires while one is busy, quietly and without a sign, so
a sensor that was ready five minutes ago is not ready now. If nothing has been
heard within a minute, press again rather than waiting longer.

The sensor with the display says nothing about any of this — no pairing icon,
nothing that distinguishes searching from connected. It does say one thing, and
it is reliable: **the clock**. A BLU has no clock of its own and takes the time
over the air from a Shelly device, which is a setting of its own in the app. So
a display showing the right time is showing a working link, and a display
showing nonsense has none. It is a better answer than anything the plug can be
asked, because it comes from the far end.

The order matters, and it is the opposite of what one expects. `AddDevice` does
not answer straight away — it waits for a packet from the address it was given,
half a minute or so from a sensor in range and forever from one that is not
there. So the sensor goes to its place first and is paired afterwards, which is
also what makes the changeover free of any gap:

1. Learn the new sensor's address indoors, next to any plug: `discover.js`.
   Nothing is paired yet, and the station outside keeps sending.
2. Put the new sensor in its place, beside the old one, and pair it there as
   well: `pair.js --addr <new>`. Both are now on the plug and the script has
   not noticed a thing — it builds its map once, at start, and even a restart
   would find the older components first.
3. Leave them side by side until the new one has taken on the temperature
   outside, and watch what remains: `compare.js`. A sensor carried out of a warm
   room needs a good while, and until the difference stops moving it says
   nothing about the two devices.
4. From the desk: `pair.js --unpair <old>`, then restart the script so it maps
   itself onto the new components:

        curl -s "http://<plug-ip>/rpc/Script.Stop?id=<id>"
        curl -s "http://<plug-ip>/rpc/Script.Start?id=<id>"

   The new sensor has been delivering since step 2, so the first reading after
   the restart is a fresh one and openSenseMap sees no interruption at all.
5. Collect the old sensor whenever it suits. Unpaired, it is simply talking to
   nobody.

The five-minute archive survives all of this — `Script.storage` hangs on the
script id, not on the code or the sensor — except for whatever is still in RAM
when the script stops, at most half an hour. Those readings are already in
openSenseMap; the archive only exists for the times when they are not.

## Talking to a BLU H&T without the Shelly app

`tools/blu-gatt.py` reads and writes the sensor's own settings — the ones that
live in the device rather than in any plug: the offsets, the light thresholds,
Fahrenheit, the clock. It needs `bleak`, a Bluetooth pairing between the machine
and the sensor, and a press of the sensor's button per connection.

The protocol came out of a Bluetooth capture on 12.08.2026: developer options,
HCI snoop log, every setting touched once in the Shelly app with distinctive
values, then `adb bugreport` and the session decoded. What it does is ordinary
GATT — no encryption, no signature, no cloud. A write is an ATT Write Command
to a fixed characteristic, numbers little-endian, booleans a single byte. The
one oddity is the security key, which goes over as a 32-bit **big-endian**
integer while everything else is little-endian.

    0de178e5-…a000   temperature offset, tenths of a degree
    0de178e5-…a002   humidity offset, whole percent
    c1a32099-…841    dark threshold, default 50
    c1a32099-…842    bright threshold, default 500
    8645a7a9-…2519   Celsius or Fahrenheit
    611723f5-…bb59   invert the display
    68348d04-…49cc   Zigbee
    a9e33a3f-…ba2ad  twelve or twenty-four hour clock
    317c7868-…a92a   clock sync, on from the factory
    ca9d7a88-…08a3b0 energy saving, off from the factory
    d56a3410-…66a1   the clock, UTC seconds
    08b83239-…716e   the time zone, in minutes
    b0a7e40f-…24bdb  factory reset, write-only, a 1 empties the device

The mapping came out of the capture the hard way, from the values rather than
from the order things were done in, and three of the names were still wrong
until the device itself corrected them. On 18.08.2026 the manufacturer's own
characteristic table turned up, for the BLU H&T Display ZB, and it lists every
UUID above. It agrees with what the device had taught us, settles the two
switches nobody could see — clock sync and energy saving — and explains the
mystery symbol: the globe is not a symbol of its own, it is the Zigbee
indicator, which is why it moved with the Zigbee characteristic.

It also names the second of the two write-only characteristics: the factory
reset. It is kept here as a constant and deliberately without a command.

The table lives at
<https://shelly-api-docs.shelly.cloud/docs-ble/Devices/BLU_ZB/ht_display/>.
It is the page for the Zigbee variant; the display model here answers to
every UUID on it, so the two share their firmware.

The offsets are applied to what the sensor **broadcasts**, not merely to its
display: setting the temperature offset to 42.0 moved the reading the plug
receives by exactly 42.0. Anything reading this sensor sees the corrected value,
so an offset set here and one set in `blu-osm.js` would both apply.

The button press is not permission, it is the precondition: a BLU does not
advertise connectably in normal operation — that is what its battery life is
made of — and only a press opens a short window. Once a connection stands it
holds; three minutes and sixty-four reads went through one.

What the button does on its own, worked out at the device and afterwards found
line for line in the manufacturer's documentation:

    1×   setup mode
    2×   date instead of time
    3×   Celsius or Fahrenheit
    4×   invert the display
    5×   twelve or twenty-four hour clock

and inside setup mode, so after the first press:

    4×   Bluetooth pairing
    5×   Zigbee joining

Which is why six, seven, eight and nine presses did nothing: there is nothing
there. Pairing is 1× then 4×, and while it runs the lamp blinks once every two
seconds for a minute — the sign the display was searched for in vain. Note that
this is the display model's scheme; the one without a display documents pairing
as a ten-second hold, and a factory reset only shortly after the battery goes
in.

Over GATT the sensor will not give up the measured brightness. Two read-only
characteristics looked like the number and were not: they sat unmoved at 299 and
297 for three minutes under a torch, and fell by one each over an hour while the
battery went from 99 to 98 per cent. The manufacturer's table confirms what that
already suggested — they are the two cells, in hundredths of a volt.

But it does not keep the brightness to itself. It **broadcasts** it. The beacon
carries seven objects and the last of them, 0x64, is the light level:

    0x00 packet counter   0x01 battery %      0x15 battery low
    0x1e light or dark    0x2e humidity %     0x45 temperature 0.1 °C
    0x64 light level

`blu-gatt.py horchen` prints them, and it does so without touching the sensor at
all — no connection, no button, no write. Receiving is invisible to the far end.

What is not settled is how that number relates to the thresholds. On
18.08.2026 the sensor reported 0x64 = 126 and stayed on "dark" while the
threshold was walked down from 32767 to 63. Three things would explain it — the
spot really is darker than 63, since the factory calls anything under 50 dark;
or written thresholds are stored but not applied at once; or the decision is
re-made rarely rather than once a packet — and nothing yet says which.

One explanation is ruled out, by the first step itself. At a threshold of 32767
the device must decide "dark" however bright it is, and it reported bit 0. So 0
is dark and the bit is not inverted.

`probe` is for the timing question: one threshold, several packets in a row. If
the verdict changes on the third, it was time and not the value, and `bisect` —
which asks one packet per step — has been measuring too early.

That the light level was missed for a week has a cause worth writing down. The
decoder walks the objects in order and stops at the first id it does not know,
because past an unknown length nothing behind it can be trusted. 0x15 was not in
its table. The objects come sorted ascending, so 0x15 stands in front of 0x1e —
and the day the battery drops below 15 per cent and the sensor starts sending
it, light, humidity, temperature and brightness would all have vanished at once,
with a full battery being the only reason it had not happened yet.

When `bisect` is used, it moves **both** thresholds together to the same value on
purpose. The sensor has one for the way to dark and one for the way to light,
and between them it stays where it was; left alone, that hysteresis would have it
sitting on "dark" out of inertia and the reading would mean nothing. Equal
thresholds turn each step into a single comparison that does not care what came
before. Afterwards the originals go back — the factory pair is 50 and 500.

Each step costs a full minute, and there is no way around it. The beacon goes
out every sixty seconds; the only things that make the sensor speak sooner are a
press of its button and the battery falling below 15 per cent. A change from
light to dark is not among them, and there is no characteristic for the
interval. So a sixteen-step search takes a quarter of an hour, and the way to
shorten it is a narrower bracket — `--von` and `--bis` — not a faster sensor.

## Correcting a sensor

Two BLU H&T lying side by side may not agree, and the temptation is to write
the difference down as an offset and correct it away. On 12.08.2026 that would
have been wrong, and the day is worth keeping as a warning.

Overnight the newer sensor read 0.8 K above the older one and 4 points of
humidity below it, and it held that for hours — long enough to look like a
property of the device rather than a leftover from the warm room it came out
of. By the next midday both differences were gone: the same two sensors, the
same spot, agreeing to within 0.2 K and reading the same humidity to the
percent. In between the newer one had gone up to the flat for a firmware update
(v1.1.4 to v1.2.12) and come back. Whether the reading moved with the firmware
or with the hand that put it back down cannot be told apart afterwards, which is
the point: a difference is only worth correcting once it has survived being
disturbed.

So: measure in the quiet hours, on both sides of an interruption, and against a
third device of a different make in the same place. A difference that comes and
goes is not an offset. Correcting the 0.8 K on the evening it was measured would
have put a whole degree of error into the record the next morning.

Where the correction goes is decided by the hardware. The model with the
display carries `Temperatur-Offset` and `Feuchtigkeits-Offset` in the sensor
itself, reachable over Bluetooth from the Shelly app — that is the right place
for a sensor no script ever reads. The model without a display has no such
field, not even on firmware v1.2.12, so for anything feeding this script the
correction lives here instead.

The script keeps it out of the code. On its first start it creates two virtual
numbers, which appear among the plug's components in its web interface:

    Temperatur-Offset   K   default 0
    Feuchte-Offset      %   default 0

    curl -s "http://<plug-ip>/rpc/Number.Set?id=<id>&value=-0.8"

They are found by name, not by id, so a restart binds to the existing pair
rather than making a second one. A value takes effect at the next poll, within
a minute, without restarting anything.

The offset is applied in `update()`, at the single point where a reading enters
the script — so the KVS entry, the five-minute archive and openSenseMap all see
the same corrected number and cannot drift apart. What is already in the
archive stays as it was: those are finished records, not raw readings. A
correction therefore works from now on and not backwards, which also means the
series has a step in it on the day it is set.

## Uploading

    node tools/upload.js 192.168.178.24              upload and start
    node tools/upload.js 192.168.178.24 --no-start   upload only
    node tools/upload.js 192.168.178.24 --status     show what is there

The file is stripped on the way up — comments gone, names squeezed — because a
script may be 20480 bytes on the device and all of it counts. 36 KB of source
become about 12 KB uploaded, and the upload is read back and compared.

The four placeholders are filled from **the plug's own copy**, read back before
anything is written. That is not a trick: the plug is where a token
legitimately lives, an upgrade is by far the commonest reason to run this, and
it means the tool needs no secret of its own and none can leak into the
repository. A plug with nothing on it yet has to be told once, with `--url`,
`--token`, `--temperature` and `--humidity`.

Tests run twice, and the second run is the one that matters:

    node test/quarters.js                what the repository holds
    BLU_STRIPPED=1 node test/quarters.js what the plug gets

The stripper renames local variables as well as top-level ones, so the squeezed
file is genuinely a different file. `selftest()` is the handle the tests hold it
by — the name is in `strip.js`'s `KEEP` list and the keys it returns are quoted,
so one line serves both passes.

## Two scripts do not fit on one plug

A Shelly has **about 25 KB of script memory for everything that runs**, shared.
It has nothing to do with the space for the code: that is flash, 20480 bytes per
slot, ten slots or more, and the plug will happily *store* far more than it can
*run*.

Measured on the pump plug, `192.168.178.24`:

| | steady | peak |
|---|---|---|
| `blu-osm` | 6.9 KB | **12.7 KB** |
| `power-journal` | 8.5 KB | **13.3 KB** |

Two peaks together are 26 KB and the pool is 25.2. They fit almost always,
which is worse than not fitting at all: for weeks nothing happens, and then one
script's daily rollover lands on the other's send and whichever asked next is
killed. On **28.08.2026** that was `blu-osm`, and it was killed in the middle of
writing its meta record — see below. `power-journal` has since been stopped and
disabled here; it belongs on `192.168.178.23`, where it has a plug to itself.

`Script.GetStatus` is where this shows up. `enable:true, running:false` means
crashed, not switched off, and `errors` names which kind.

## Where the RAM actually goes

Getting this file lean taught three lessons, all measured on the plug rather
than deduced. The instrument is `Script.GetStatus`: `mem_used` is what the
script holds right now, garbage included, so it breathes -- 9.1 KB just after
start, 7.4 KB a minute later; `mem_peak` is the high-water mark of the running
instance and never goes down; `mem_free` is the shared pool. `tools/upload.js`
prints the status four seconds after starting the script, which is exactly the
start-up peak.

**Starting is the most expensive thing the script does.** The parse, the
component walk and the first full update -- first backfill attempt included --
all land inside one garbage-collection window: 15 KB peak with 11.3 KB of
code. A second script on the same plug has to leave room for that moment, not
for the steady state, and the most dangerous second is a script starting next
to a neighbour that is already busy.

**Transient churn beats standing structures.** Every `Script.storage.getItem`
materialises the whole value on the heap, a kilobyte per archive page. Two
paths multiplied that: `arcOldest` walked up to eleven pages for a number that
only changes at a page turn, once per backlog tick -- once a *minute* while
openSenseMap was down, which is half the story of the 28.08. death next to
`power-journal`. And `arcRead` fetched its page once per record, 96 reads for
a day's query. The first is now cached and invalidated on page turns, the
second reads once per page change; a backlog tick touches at most three pages,
and a test with a read counter keeps it that way.

**Property chains cannot be shortened, so route them through names that can.**
The stripper renames every top-level name and every local, but must leave
`JSON.stringify`, `Math.floor`, `Script.storage.getItem` and
`Shelly.getComponentStatus` exactly as written. One-purpose wrappers -- `jstr`,
`flo`, `stoGet`, `stoSet`, `cstat` -- turn 34 occurrences of
`JSON.stringify(` into 34 occurrences of a one-letter name: 775 bytes off the
stripped file in one commit. The rule when adding one: the wrapper's name must
not appear as a property anywhere in the file, or `strip.js` refuses the whole
build. The price is one stack frame at the deepest call chain, which is why
the frame budget is written out next to the wrappers.

## JSON.parse is a loaded gun

**In mJS a failing `JSON.parse` cannot be caught. It kills the script.** So it
must never see anything that is not known-good — and two places in this file
were doing exactly that:

- the meta record in `Script.storage`, which survives a crash and can therefore
  be *half* written, and
- the `from` and `count` of the `quarters` endpoint, which anybody who can reach
  the plug gets to choose.

The first one happened. The record was cut short, `JSON.parse` met a field
starting with `.`, and the script died on that line at every start — permanently
and out of reach, because `Script.storage` is per-script and not readable over
RPC at all. Only a deploy could clear it. The second one never happened, but a
single `?from=x` from anywhere on the network would have done the same.

Both now go through `arcNum`, which walks the digits itself and answers −1 to
anything else. A bad record costs a page of archive; a bad query string costs
nothing. Reading is done with `DIG.indexOf(t.slice(i, i + 1))` and **not** with
`t.at(i)`: `at` gives a byte value in mJS and a character in node, so
`t.at(i) - 48` is `NaN` under the tests — and `NaN` is neither below zero nor
above nine, so it slips through every range check and quietly becomes the page
number. The test suite caught that before it reached the plug.

`arcSaveMeta` also ends the record with `|z`. A write cut short is otherwise
perfectly readable and simply wrong — all digits, just fewer of them — and the
backfill would carry on from a place it never reached. A character that only
ever appears last is what tells "finished" from "torn".

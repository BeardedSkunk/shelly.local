// compare.js -- zwei BLU-Sensoren am selben Stecker nebeneinanderhalten
//
//   node tools/compare.js 192.168.178.24
//
// Beim Wechsel des Gartensensors haengen beide fuer eine Weile am selben Plug
// und liegen am selben Ort. Das ist die einzige Gelegenheit, ihren Unterschied
// zu sehen: danach misst nur noch einer, und ein Versatz von ein paar Zehnteln
// taucht in der openSenseMap-Reihe als Knick auf, den niemand mehr erklaeren
// kann.
//
// Der neue kommt aus der Wohnung und braucht eine Weile, bis er die Temperatur
// draussen angenommen hat. Bis dahin sagt die Differenz nichts. Sie ist erst zu
// gebrauchen, wenn sie sich nicht mehr bewegt.

const host = process.argv[2];
const every = Number(arg('--every') || 60);

function arg(flag) {
  const i = process.argv.indexOf(flag);
  return i > 0 ? process.argv[i + 1] : undefined;
}

if (!host || host.startsWith('--')) {
  console.error('Aufruf: node tools/compare.js <plug-ip> [--every <sekunden>]');
  process.exit(2);
}

async function rpc(method, params) {
  const res = await fetch('http://' + host + '/rpc', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: 1, method, params: params || {} }),
    signal: AbortSignal.timeout(15000),
  });
  const body = await res.json();
  if (body.error) throw new Error(method + ': ' + body.error.message);
  return body.result;
}

// Welche Komponente gehoert zu welchem Geraet: die Zuordnung steht in der
// Konfiguration jedes Sensors, ueber die Adresse.
//
// Gelesen wird ueber Shelly.GetComponents und nicht ueber Shelly.GetStatus --
// der grosse Statusaufruf kennt nur die fest eingebauten Komponenten und
// liefert fuer bthome ein leeres Objekt.
async function devices() {
  const comps = (await rpc('Shelly.GetComponents', { dynamic_only: true })).components;
  const devs = new Map();
  for (const c of comps) {
    if (c.key.startsWith('bthomedevice:')) {
      devs.set(c.config.addr, { addr: c.config.addr, name: c.config.name || c.config.addr, t: null, h: null, age: null });
    }
  }
  for (const c of comps) {
    if (!c.key.startsWith('bthomesensor:')) continue;
    const d = devs.get(c.config.addr);
    if (!d) continue;
    const value = c.status && c.status.value !== undefined ? c.status.value : null;
    if (c.config.obj_id === 69) {
      d.t = value;
      // Wie alt der Messwert ist, entscheidet, ob das Paar taugt: jeder Sensor
      // funkt fuer sich alle sechzig Sekunden, und wenn einer der beiden
      // haengengeblieben ist, vergleicht man zwei verschiedene Zeitpunkte.
      const ts = c.status && c.status.last_updated_ts;
      d.age = ts ? Math.round(Date.now() / 1000 - ts) : null;
    }
    if (c.config.obj_id === 46) d.h = value;
  }
  return [...devs.values()];
}

function pad(v, w) {
  return String(v).padStart(w);
}

// Ein Zehntelgrad Aufloesung heisst, dass ein einzelnes Wertepaar den Versatz
// nur auf ein Zehntel genau kennt -- und die Rundung faellt mal so, mal so aus.
// Ueber viele Paare mittelt sie sich heraus, und dann steht da eine Zahl, die
// mehr Stellen hat als jede einzelne Messung. Deshalb wird gesammelt.
const samples = [];

function summary(label, from) {
  const take = samples.slice(from);
  if (take.length === 0) return;
  const ds = take.map((s) => s.d).sort((a, b) => a - b);
  const mean = ds.reduce((a, b) => a + b, 0) / ds.length;
  console.log(
    '          ' + label + ': n=' + pad(ds.length, 3) +
    '   Mittel ' + (mean >= 0 ? '+' : '') + mean.toFixed(2) + ' K' +
    '   Spanne ' + ds[0].toFixed(1) + ' .. ' + ds[ds.length - 1].toFixed(1) + ' K'
  );
}

let sinceHour = 0;
let hour = new Date().getHours();

async function round() {
  const out = await devices();
  const now = new Date();
  const stamp = now.toTimeString().slice(0, 8);
  const cells = out.map((o) =>
    o.name + ' ' + (o.t === null ? '  --  ' : pad(o.t.toFixed(1), 5) + ' C') +
    (o.h === null ? '' : ' ' + pad(o.h, 3) + '%') +
    (o.age === null ? '' : ' ' + pad(o.age, 3) + 's')
  );

  let diff = '';
  if (out.length === 2 && out[0].t !== null && out[1].t !== null) {
    const d = out[1].t - out[0].t;
    diff = '   Unterschied ' + (d > 0 ? '+' : '') + d.toFixed(1) + ' K';
    // Nur frische Paare zaehlen. Drei Minuten sind grosszuegig -- der Sensor
    // sendet jede Minute, also ist alles darueber ein verpasstes Paket.
    const stale = out.some((o) => o.age !== null && o.age > 180);
    if (stale) diff += '  (alt, zaehlt nicht)';
    else samples.push({ at: now, d });
  }
  console.log(stamp + '  ' + cells.join('   |   ') + diff);

  // Zum Stundenwechsel eine Zwischenrechnung. So laesst sich hinterher am Log
  // ablesen, welche Stunden ruhig waren -- nachts, ohne Sonne auf den
  // Gehaeusen, steht der ehrlichste Versatz.
  if (now.getHours() !== hour) {
    summary('letzte Stunde', sinceHour);
    summary('seit Beginn  ', 0);
    sinceHour = samples.length;
    hour = now.getHours();
  }
}

const devs = await devices();
if (devs.length < 2) console.log('Nur ' + devs.length + ' Geraet(e) gekoppelt -- es gibt nichts zu vergleichen.');
for (const d of devs) console.log('  ' + d.name + ' = ' + d.addr);
console.log('');

process.on('SIGINT', () => {
  console.log('');
  summary('Gesamt       ', 0);
  process.exit(0);
});

await round();
setInterval(() => round().catch((e) => console.error(e.message)), every * 1000);

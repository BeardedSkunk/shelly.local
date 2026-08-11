// pair.js -- einen BLU H&T an einen Stecker koppeln, per RPC
//
//   node tools/pair.js 192.168.178.24 --list
//   node tools/pair.js 192.168.178.24 --addr fc:4d:6a:38:e2:f2 --name "BLU HT"
//   node tools/pair.js 192.168.178.24 --unpair fc:4d:6a:38:e2:f2
//
// Gekoppelt wird ohne die Shelly-App, weil hier nichts in die Cloud geht. Es
// sind zwei Schritte: das Geraet anmelden und dann die Messwerte einzeln
// freischalten, die das blu-osm-Skript braucht -- Batterie, Feuchte,
// Temperatur. obj_id 30 (hell/dunkel) bleibt weg, das Skript sieht es
// ohnehin nicht an.
//
// Der Stecker muss den Sensor dabei hoeren. Eine Adresse, die er noch nie
// empfangen hat, nimmt er nicht an: der Aufruf bricht ab und es entsteht
// nichts (am 25.08.2026 auf dem Ersatzstecker geprueft). Der Sensor gehoert
// also erst an seinen Platz und dann gekoppelt -- nicht umgekehrt.

const WANTED = [
  { obj_id: 1, name: 'Batterie' },
  { obj_id: 46, name: 'Feuchte' },
  { obj_id: 69, name: 'Temperatur' },
];

const host = process.argv[2];
const arg = (flag) => {
  const i = process.argv.indexOf(flag);
  return i > 0 ? process.argv[i + 1] : undefined;
};

if (!host || host.startsWith('--')) {
  console.error('Aufruf: node tools/pair.js <plug-ip> [--list | --addr <mac> | --unpair <mac>]');
  process.exit(2);
}

async function rpc(method, params) {
  const res = await fetch('http://' + host + '/rpc', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: 1, method, params: params || {} }),
    signal: AbortSignal.timeout(20000),
  });
  const body = await res.json();
  if (body.error) throw new Error(method + ': ' + body.error.message);
  return body.result;
}

async function devices() {
  const out = [];
  const comps = await rpc('Shelly.GetComponents', { dynamic_only: true });
  for (const c of comps.components) {
    if (c.key.startsWith('bthomedevice:')) {
      out.push({ id: c.config.id, addr: c.config.addr, name: c.config.name, status: c.status });
    }
  }
  return out;
}

async function list() {
  const found = await devices();
  if (found.length === 0) {
    console.log('Kein BLU-Geraet gekoppelt.');
    return;
  }
  for (const d of found) {
    const s = d.status || {};
    const age = s.last_updated_ts ? Math.round(Date.now() / 1000 - s.last_updated_ts) + ' s her' : 'nie gehoert';
    console.log(
      'bthomedevice:' + d.id + '  ' + d.addr + '  "' + (d.name || '') + '"' +
      '  RSSI ' + (s.rssi === undefined ? '?' : s.rssi) +
      '  Batterie ' + (s.battery === undefined ? '?' : s.battery + '%') +
      '  ' + age
    );
    const known = await rpc('BTHomeDevice.GetKnownObjects', { id: d.id });
    for (const o of known.objects) {
      console.log('    obj_id ' + String(o.obj_id).padStart(3) + '  ' + (o.component || '-- nicht freigeschaltet'));
    }
  }
}

async function pair(addr, name) {
  const before = await devices();
  if (before.some((d) => d.addr === addr)) {
    console.log(addr + ' ist bereits gekoppelt.');
  } else {
    const cfg = { addr };
    if (name) cfg.name = name;
    await rpc('BTHome.AddDevice', { config: cfg });
    console.log('Geraet angemeldet: ' + addr);
  }

  const dev = (await devices()).find((d) => d.addr === addr);
  if (!dev) throw new Error('Das Geraet ist nach dem Anmelden nicht da.');

  // Freischalten laesst sich nur, was der Stecker schon einmal empfangen hat.
  // Ein frisch angemeldeter Sensor hat oft erst die Haelfte gesendet, deshalb
  // ein paar Anlaeufe statt eines einzigen.
  for (let attempt = 1; attempt <= 10; attempt++) {
    const known = await rpc('BTHomeDevice.GetKnownObjects', { id: dev.id });
    const missing = [];
    for (const w of WANTED) {
      const o = known.objects.find((k) => k.obj_id === w.obj_id && k.idx === 0);
      if (!o) {
        missing.push(w);
      } else if (!o.component) {
        await rpc('BTHome.AddSensor', { config: { addr, obj_id: w.obj_id, idx: 0 } });
        console.log('  freigeschaltet: ' + w.name + ' (obj_id ' + w.obj_id + ')');
      }
    }
    if (missing.length === 0) {
      console.log('Fertig. Alle drei Werte stehen bereit.');
      return;
    }
    console.log(
      '  fehlt noch: ' + missing.map((m) => m.name).join(', ') +
      ' -- warte auf das naechste Funkpaket (' + attempt + '/10)'
    );
    await new Promise((r) => setTimeout(r, 15000));
  }
  console.log('Nicht alles gefunden. Knopf am Sensor druecken und noch einmal aufrufen.');
}

async function unpair(addr) {
  const dev = (await devices()).find((d) => d.addr === addr);
  if (!dev) {
    console.log(addr + ' ist hier nicht gekoppelt.');
    return;
  }
  await rpc('BTHome.DeleteDevice', { id: dev.id });
  console.log('Abgemeldet: ' + addr + ' (war bthomedevice:' + dev.id + ')');
}

const addr = arg('--addr');
const drop = arg('--unpair');

const job = drop ? unpair(drop) : addr ? pair(addr, arg('--name')) : list();
job.catch((e) => {
  console.error('Fehlgeschlagen: ' + e.message);
  process.exit(1);
});

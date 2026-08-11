// discover.js -- welche BLU-Sensoren ein Stecker gerade hoert
//
//   node tools/discover.js 192.168.178.25            einmal suchen
//   node tools/discover.js 192.168.178.25 --watch    weitersuchen, bis Strg-C
//
// Ein neuer BLU H&T hat seine MAC-Adresse nirgends gross aufgedruckt, und die
// Shelly-App kommt hier nicht in Frage. Gekoppelt wird per RPC, und dafuer
// muss die Adresse her: dieses Werkzeug laesst einen Stecker in der Naehe
// horchen und schreibt auf, was er hoert.
//
// BTHome.StartDeviceDiscovery meldet sein Ergebnis nicht als Antwort, sondern
// als Ereignis. Ueber HTTP ist das nicht zu sehen, deshalb die Websocket --
// Node bringt sie seit v21 selbst mit, es ist nichts zu installieren.
//
// Der Sensor muss dabei senden. Frisch mit Batterie versehen tut er das von
// allein; sonst weckt ein kurzer Druck auf seinen Knopf ihn auf.

const host = process.argv[2];
const watch = process.argv.includes('--watch');

if (!host) {
  console.error('Aufruf: node tools/discover.js <plug-ip> [--watch]');
  process.exit(2);
}

const seen = new Map();

const ws = new WebSocket('ws://' + host + '/rpc');
let id = 1;

function call(method, params) {
  ws.send(JSON.stringify({ id: id++, src: 'discover', method, params }));
}

ws.addEventListener('open', () => {
  console.log('Verbunden mit ' + host + ', suche ...');
  call('BTHome.StartDeviceDiscovery', {});
});

ws.addEventListener('message', (ev) => {
  let msg;
  try {
    msg = JSON.parse(ev.data);
  } catch {
    return;
  }

  if (msg.error) console.error('Fehler: ' + JSON.stringify(msg.error));

  // Ereignisse kommen einzeln oder gebuendelt, je nach Firmware unter
  // NotifyEvent oder NotifyStatus. Interessant ist alles, in dem eine
  // Adresse steckt.
  const events = (msg.params && msg.params.events) || [];
  for (const e of events) report(e);

  // Manche Staende melden das Ergebnis nicht als Liste, sondern jedes Geraet
  // fuer sich. Beides landet hier.
  if (msg.params && msg.params.addr) report(msg.params);
});

function report(e) {
  const list = e.devices || (e.addr ? [e] : []);
  for (const d of list) {
    const addr = d.addr;
    if (!addr) continue;
    const line =
      addr +
      '  RSSI ' + String(d.rssi === undefined ? '?' : d.rssi).padStart(4) +
      (d.model ? '  ' + d.model : '') +
      (d.name ? '  "' + d.name + '"' : '') +
      (d.encryption || d.key ? '  verschluesselt' : '');
    if (seen.get(addr) !== line) {
      seen.set(addr, line);
      console.log(line);
    }
  }
  if (e.event) {
    const known = ['discovery_done', 'discovery_started'];
    if (!known.includes(e.event) && !e.devices && !e.addr) {
      console.log('(' + e.event + ') ' + JSON.stringify(e).slice(0, 300));
    }
  }
  if (e.event === 'discovery_done') {
    if (watch) {
      call('BTHome.StartDeviceDiscovery', {});
    } else {
      if (seen.size === 0) console.log('Nichts gehoert.');
      done = true;
      ws.close();
    }
  }
}

let done = false;

ws.addEventListener('error', (e) => {
  if (done) return; // ein Fehler nach dem eigenen Schliessen ist keiner
  console.error('Websocket-Fehler: ' + (e.message || e.type));
  process.exit(1);
});

// Falls die Firmware gar kein Ergebnis meldet, nicht ewig warten.
setTimeout(() => {
  if (!watch) {
    if (seen.size === 0) console.log('Nichts gehoert.');
    process.exit(0);
  }
}, 45000).unref?.();

// blu-ht-kvs.js -- Shelly Plug M Gen3 (FW 2.x), mJS
//
// Legt Temperatur, Luftfeuchte und Batteriestand eines gekoppelten Shelly
// BLU H&T als EINEN KVS-Eintrag ab und veroeffentlicht Temperatur und Feuchte
// auf openSenseMap.
//
// Aufbau des Eintrags -- jeder Messwert bekommt eine eigene Ebene, der
// Zeitstempel gilt fuer den ganzen Datensatz:
//
//   blu/sensor -> {
//     "temperature": { "value": 23.2, "unit": "Grad C" },
//     "humidity":    { "value": 71,   "unit": "%" },
//     "battery":     { "value": 100,  "unit": "%" },
//     "ts": 1785827699
//   }
//
// ts ist der Zeitpunkt, zu dem zuletzt irgendetwas hereinkam. Er stammt aus
// last_updated_ts der Sensorkomponenten und laeuft bei jedem empfangenen
// BLE-Paket weiter, auch wenn die Werte gleich bleiben. Bleibt er stehen,
// sendet der Sensor nicht mehr.
//
// Geschrieben wird ereignisgesteuert, sobald sich ein Wert aendert. Weil der
// Shelly bei unveraenderten Werten kein Ereignis meldet (am Geraet verifiziert),
// sieht das Script zusaetzlich alle CFG.poll_ms selbst nach und schreibt
// spaetestens alle CFG.refresh_s -- dann wird der Zeitstempel aufgefrischt und
// erneut nach openSenseMap gepusht.
//
// Auslesen von aussen:
//   curl -s "http://<plug-ip>/rpc/KVS.Get?key=blu/sensor"

let CFG = {
  key: 'blu/sensor', // ein einziger KVS-Key fuer den ganzen Sensor
  refresh_s: 1800,   // spaetestens so oft schreiben und publizieren
  poll_ms: 60000,    // wie oft dafuer lokal nachgesehen wird
  debounce_ms: 500,  // Sammelfrist, damit ein Paket nur einen Schreibvorgang ausloest
  log: true,
};

// ACHTUNG: Der Access-Token steht hier im Klartext und ist ueber
// Script.GetCode fuer jeden im WLAN lesbar, solange der Plug kein Passwort hat.
let OSM = {
  enable: true,
  url: '{{OSM_URL}}',
  token: '{{OSM_TOKEN}}',
  ssl_ca: 'ca.pem', // eingebautes CA-Bundle des Geraets
  timeout_s: 15,
  // Was hier nicht steht, wird nicht veroeffentlicht -- die Box hat nur
  // Temperatur und Feuchte.
  sensors: [
    { name: 'temperature', id: '{{OSM_TEMPERATURE}}' },
    { name: 'humidity', id: '{{OSM_HUMIDITY}}' },
  ],
};

// BTHome-Objekt-IDs, wie sie dieser Sensor sendet (per BTHome.GetObjectInfos
// am Geraet verifiziert). obj_id 30 (light, binary_sensor) lassen wir bewusst
// weg -- der Sensor funkt dort nur hell/dunkel, keinen Lux-Wert.
let WANTED = [
  { obj_id: 69, name: 'temperature', unit: 'Grad C' },
  { obj_id: 46, name: 'humidity', unit: '%' },
  { obj_id: 1, name: 'battery', unit: '%' },
];

let SENSOR_PREFIX = 'bthomesensor:';

// ------------------------------------------------------- Viertelstunden-Archiv
//
// Der Sensor misst weiter, wenn openSenseMap nicht antwortet -- am 10.08.2026
// war der Dienst einen halben Abend lang weg, und alles aus dieser Zeit war
// verloren. Also schreibt das Script selbst mit, und zwar so, dass ein
// spaeteres Nachreichen moeglich bleibt.
//
// Ein Satz je voller Viertelstunde nach Uhr, drei Zeichen lang:
//
//   10 Bit Temperatur in Zehntelgrad, Nullpunkt -50 C, 1023 = unbekannt
//    8 Bit Feuchte in halben Prozent,               255 = unbekannt
//
// Kein Zeitstempel im Satz. Das Raster ist fest, also sagt die Stelle in der
// Seite, welche Viertelstunde gemeint ist -- und eine Luecke kostet denselben
// Platz wie ein Messwert, dafuer bleibt alles ausgerichtet.
//
// Elf Seiten zu je 336 Saetzen, reihum beschrieben: 3,5 Tage je Seite, gut
// 38 Tage insgesamt. Die zwoelfte Speicherstelle haelt die Verwaltung.
//
// Ein Code-Update loescht das alles nicht: Script.storage haengt an der
// Script-ID, nicht am Code. Am 10.08.2026 auf sechs Steckern nachgeprueft --
// nach dem Austausch stand die Tagesreihe des Energie-Journals unveraendert da.
let ARC = {
  slots: ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k'],
  meta: 'm',
  per_page: 336,
  step_s: 900,
  version: 1,
};

let A64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';

// Laufende Verwaltung: welche Seite gerade beschrieben wird, wo sie anfaengt,
// wieviel drinsteht, und die Summen der laufenden Viertelstunde.
let ST = {
  page: 0,     // Index in ARC.slots
  start: 0,    // Viertelstundennummer des ersten Satzes dieser Seite
  count: 0,    // Saetze in dieser Seite
  quarter: 0,  // Viertelstunde, fuer die gerade gesammelt wird
  sumT: 0, nT: 0,
  sumH: 0, nH: 0,
  ready: false,
};

// Zur Laufzeit gefuellt: je ein Eintrag { ckey, name, unit, last }
let MAP = [];

let lastWriteAt = 0; // Systemzeit des letzten Schreibvorgangs

// ---------------------------------------------------------------- Hilfsmittel

function sysTime() {
  let st = Shelly.getComponentStatus('sys');
  if (st === null || typeof st.unixtime !== 'number') return 0;
  return st.unixtime;
}

function wantedFor(objId) {
  for (let i = 0; i < WANTED.length; i++) {
    if (WANTED[i].obj_id === objId) return WANTED[i];
  }
  return null;
}

function entryByName(name) {
  for (let i = 0; i < MAP.length; i++) {
    if (MAP[i].name === name) return MAP[i];
  }
  return null;
}

// ----------------------------------------------------------- openSenseMap

let osmPending = null;
let osmBusy = false;

function osmPublish() {
  if (!OSM.enable) return;

  let batch = [];
  for (let i = 0; i < OSM.sensors.length; i++) {
    let entry = entryByName(OSM.sensors[i].name);
    if (entry === null || entry.last === null) continue;
    batch.push({ sensor: OSM.sensors[i].id, value: entry.last });
  }
  if (batch.length === 0) return;

  // Laeuft noch ein Request, ersetzt der neue Stand den wartenden -- alte
  // Messwerte nachzureichen bringt niemandem etwas.
  if (osmBusy) {
    osmPending = batch;
    return;
  }
  osmSend(batch);
}

function osmSend(batch) {
  osmBusy = true;
  Shelly.call(
    'HTTP.Request',
    {
      method: 'POST',
      url: OSM.url,
      headers: { 'Content-Type': 'application/json', Authorization: OSM.token },
      body: JSON.stringify(batch),
      ssl_ca: OSM.ssl_ca,
      timeout: OSM.timeout_s,
    },
    function (res, ec, em) {
      osmBusy = false;
      if (ec !== 0) {
        print('openSenseMap: Aufruf fehlgeschlagen: ' + em);
      } else if (res.code < 200 || res.code > 299) {
        print('openSenseMap: HTTP ' + JSON.stringify(res.code) + ' ' + res.body);
      } else if (CFG.log) {
        print('openSenseMap: ' + JSON.stringify(batch.length) + ' Wert(e) gesendet');
      }
      if (osmPending !== null) {
        let next = osmPending;
        osmPending = null;
        osmSend(next);
      }
    }
  );
}

// -------------------------------------------------------------------- KVS

function payload(ts) {
  let out = {};
  for (let i = 0; i < MAP.length; i++) {
    if (MAP[i].last === null) continue;
    out[MAP[i].name] = { value: MAP[i].last, unit: MAP[i].unit };
  }
  out.ts = ts;
  return out;
}

function writeKvs(value) {
  Shelly.call('KVS.Set', { key: CFG.key, value: value }, function (res, ec, em) {
    if (ec !== 0) {
      print('KVS.Set ' + CFG.key + ' fehlgeschlagen: ' + em);
    } else if (CFG.log) {
      print('KVS ' + CFG.key + ' = ' + JSON.stringify(value));
    }
  });
}

// ------------------------------------------------------------ Archiv-Ablage

function arcEncode(tc, hc) {
  let v = tc * 256 + hc;
  return A64.slice(Math.floor(v / 4096), Math.floor(v / 4096) + 1) +
    A64.slice(Math.floor(v / 64) % 64, Math.floor(v / 64) % 64 + 1) +
    A64.slice(v % 64, v % 64 + 1);
}

function arcTempCode(t) {
  if (t === null) return 1023;
  let c = Math.floor((t + 50) * 10 + 0.5);
  if (c < 0) c = 0;
  if (c > 1022) c = 1022;
  return c;
}

function arcHumCode(h) {
  if (h === null) return 255;
  let c = Math.floor(h * 2 + 0.5);
  if (c < 0) c = 0;
  if (c > 200) c = 200;
  return c;
}

// "1|<seite>|<start>|<anzahl>"
function arcSaveMeta() {
  Script.storage.setItem(
    ARC.meta,
    JSON.stringify(ARC.version) + '|' + JSON.stringify(ST.page) + '|' +
      JSON.stringify(ST.start) + '|' + JSON.stringify(ST.count)
  );
}

function arcLoadMeta() {
  let raw = Script.storage.getItem(ARC.meta);
  if (raw === null || raw === undefined) return false;
  let f = raw.split('|');
  if (f.length !== 4 || JSON.parse(f[0]) !== ARC.version) return false;
  ST.page = JSON.parse(f[1]);
  ST.start = JSON.parse(f[2]);
  ST.count = JSON.parse(f[3]);
  if (ST.page < 0 || ST.page >= ARC.slots.length) return false;
  return true;
}

// Haengt einen Satz an, wechselt die Seite wenn sie voll ist. Die aelteste
// Seite wird ueberschrieben -- das ist der Ringpuffer.
function arcAppend(text, quarter) {
  if (ST.count >= ARC.per_page) {
    ST.page = (ST.page + 1) % ARC.slots.length;
    ST.count = 0;
    ST.start = quarter;
    Script.storage.setItem(ARC.slots[ST.page], '');
  }
  if (ST.count === 0) ST.start = quarter;
  let key = ARC.slots[ST.page];
  let old = Script.storage.getItem(key);
  if (old === null || old === undefined) old = '';
  Script.storage.setItem(key, old + text);
  ST.count = ST.count + 1;
  arcSaveMeta();
}

// Schreibt alles bis ausschliesslich der laufenden Viertelstunde weg. Was
// uebersprungen wurde -- Neustart, Stromausfall, Sensor weg -- wird als
// unbekannt eingetragen, damit die Stellen im Raster stimmen bleiben.
function arcCloseUpTo(quarter) {
  if (ST.quarter === 0) return;
  let guard = 0;
  while (ST.quarter < quarter && guard < ARC.per_page) {
    let tc = 1023;
    let hc = 255;
    if (guard === 0 && ST.nT > 0) tc = arcTempCode(ST.sumT / ST.nT);
    if (guard === 0 && ST.nH > 0) hc = arcHumCode(ST.sumH / ST.nH);
    arcAppend(arcEncode(tc, hc), ST.quarter);
    ST.quarter = ST.quarter + 1;
    guard = guard + 1;
  }
  // Bei einem sehr langen Ausfall wird nicht die halbe Historie mit Luecken
  // vollgeschrieben -- dann faengt das Raster einfach neu an.
  if (ST.quarter < quarter) {
    ST.quarter = quarter;
    ST.count = ARC.per_page; // erzwingt eine frische Seite beim naechsten Satz
  }
  ST.sumT = 0; ST.nT = 0;
  ST.sumH = 0; ST.nH = 0;
}

// Sammelt den aktuellen Stand ein und schliesst faellige Viertelstunden ab.
function arcSample(now, t, h) {
  if (now <= 0) return;
  let q = Math.floor(now / ARC.step_s);
  if (!ST.ready) {
    if (arcLoadMeta()) {
      // Nach einem Neustart geht es dort weiter, wo die Seite aufhoert -- und
      // nicht bei der jetzigen Viertelstunde. Sonst rutscht das ganze Raster:
      // die Viertelstunde, in der der Strom ausfiel, war noch nicht
      // geschrieben, und der naechste Satz landet auf ihrer Stelle und traegt
      // damit eine falsche Uhrzeit. Was dazwischen fehlt, fuellt arcCloseUpTo
      // gleich als unbekannt auf.
      ST.quarter = ST.start + ST.count;
    } else {
      ST.page = 0; ST.start = q; ST.count = 0;
      Script.storage.setItem(ARC.slots[0], '');
      arcSaveMeta();
      ST.quarter = q;
    }
    ST.ready = true;
  }
  if (q > ST.quarter) arcCloseUpTo(q);
  if (ST.quarter === 0) ST.quarter = q;
  if (t !== null) { ST.sumT = ST.sumT + t; ST.nT = ST.nT + 1; }
  if (h !== null) { ST.sumH = ST.sumH + h; ST.nH = ST.nH + 1; }
}

// -------------------------------------------------------- Wertverarbeitung

// Liest alle beobachteten Komponenten lokal aus (kein Netzwerk, kein BLE) und
// entscheidet, ob ein Schreibvorgang faellig ist. Beide Ausloeser -- Ereignis
// und Timer -- laufen hier zusammen, damit es nur eine Wahrheit gibt.
function update() {
  let changed = false;
  let newest = 0;

  for (let i = 0; i < MAP.length; i++) {
    let st = Shelly.getComponentStatus(MAP[i].ckey);
    if (st === null) continue;
    if (typeof st.last_updated_ts === 'number' && st.last_updated_ts > newest) {
      newest = st.last_updated_ts;
    }
    if (typeof st.value !== 'undefined' && st.value !== null && st.value !== MAP[i].last) {
      MAP[i].last = st.value;
      changed = true;
    }
  }
  if (newest === 0) newest = sysTime();

  let now = sysTime();

  // Das Archiv bekommt jeden Durchlauf mit, auch den, der sonst nichts tut.
  // Genau davon lebt der Mittelwert einer Viertelstunde.
  let te = entryByName('temperature');
  let he = entryByName('humidity');
  arcSample(
    now,
    te === null || te.last === undefined ? null : te.last,
    he === null || he.last === undefined ? null : he.last
  );

  let due = now - lastWriteAt >= CFG.refresh_s;
  if (!changed && !due && lastWriteAt !== 0) return;

  lastWriteAt = now;
  writeKvs(payload(newest));
  osmPublish();
}

// Mehrere Werte aus demselben BLE-Paket treffen als getrennte Ereignisse ein.
// Die kurze Sammelfrist fasst sie zu einem Schreibvorgang zusammen.
let pendingTimer = null;

function scheduleUpdate() {
  if (pendingTimer !== null) return;
  pendingTimer = Timer.set(CFG.debounce_ms, false, function () {
    pendingTimer = null;
    update();
  });
}

// ------------------------------------------------------------ Initialisierung

function buildMap(offset) {
  Shelly.call('Shelly.GetComponents', { dynamic_only: true, offset: offset }, function (res, ec, em) {
    if (ec !== 0) {
      print('Shelly.GetComponents fehlgeschlagen: ' + em);
      return;
    }

    let comps = res.components;
    for (let i = 0; i < comps.length; i++) {
      let c = comps[i];
      if (c.key.slice(0, SENSOR_PREFIX.length) !== SENSOR_PREFIX) continue;

      let w = wantedFor(c.config.obj_id);
      if (w === null) continue;

      MAP.push({ ckey: c.key, name: w.name, unit: w.unit, last: null });
    }

    if (offset + comps.length < res.total) {
      buildMap(offset + comps.length);
      return;
    }

    if (MAP.length === 0) {
      print('Kein passender BTHome-Sensor gefunden. Ist der BLU H&T gekoppelt?');
      return;
    }

    // MAP in der Reihenfolge von WANTED sortieren, damit der KVS-Eintrag
    // unabhaengig von den Komponenten-IDs immer gleich aufgebaut ist.
    let ordered = [];
    for (let i = 0; i < WANTED.length; i++) {
      let e = entryByName(WANTED[i].name);
      if (e !== null) ordered.push(e);
    }
    MAP = ordered;

    print('Ueberwache ' + JSON.stringify(MAP.length) + ' Sensorwerte.');
    update();
    Timer.set(CFG.poll_ms, true, update);
  });
}

// ---------------------------------------------------------------- Auslesen
//
//   curl "http://<plug-ip>/script/<id>/quarters"
//   curl "http://<plug-ip>/script/<id>/quarters?from=1963760&count=96"
//
// Ohne from kommt die Uebersicht: welche Viertelstunden ueberhaupt dastehen.
// Mit from ein Stueck der Reihe, hoechstens ein Tag auf einmal -- mehr baut der
// Stecker nicht zusammen, ohne sich am eigenen Speicher zu verschlucken.
function arcOldest() {
  // Die Seite nach der laufenden ist die aelteste, sofern schon einmal
  // umgelaufen wurde; sonst ist es die erste.
  let n = ARC.slots.length;
  for (let i = 1; i <= n; i++) {
    let key = ARC.slots[(ST.page + i) % n];
    let text = Script.storage.getItem(key);
    if (text !== null && text !== undefined && text.length >= 3) {
      // Der Anfang dieser Seite laesst sich nicht speichern, ohne die
      // Verwaltung aufzublaehen -- er ergibt sich aus dem Abstand zur
      // laufenden Seite, die vollen Seiten dazwischen mitgezaehlt.
      let ahead = (ST.page - ((ST.page + i) % n) + n) % n;
      return ST.start - ahead * ARC.per_page;
    }
  }
  return ST.start;
}

function arcRead(from, count) {
  let t = [];
  let h = [];
  let n = ARC.slots.length;
  for (let i = 0; i < count; i++) {
    let q = from + i;
    // In welcher Seite steht diese Viertelstunde? Rueckwaerts von der
    // laufenden gerechnet.
    let back = ST.start - q;
    let page = ST.page;
    let pos = q - ST.start;
    if (back > 0) {
      let steps = Math.floor((back + ARC.per_page - 1) / ARC.per_page);
      page = (ST.page - steps + n * 2) % n;
      pos = q - (ST.start - steps * ARC.per_page);
    }
    let text = Script.storage.getItem(ARC.slots[page]);
    if (text === null || text === undefined || pos < 0 || (pos + 1) * 3 > text.length) {
      t.push(null); h.push(null);
      continue;
    }
    let a = A64.indexOf(text.slice(pos * 3, pos * 3 + 1));
    let b = A64.indexOf(text.slice(pos * 3 + 1, pos * 3 + 2));
    let c = A64.indexOf(text.slice(pos * 3 + 2, pos * 3 + 3));
    let v = a * 4096 + b * 64 + c;
    let tc = Math.floor(v / 256);
    let hc = v % 256;
    t.push(tc === 1023 ? null : (tc / 10) - 50);
    h.push(hc === 255 ? null : hc / 2);
  }
  return { t: t, h: h };
}

HTTPServer.registerEndpoint('quarters', function (req, res) {
  let from = 0;
  let count = 0;
  let q = req.query === undefined || req.query === null ? '' : req.query;
  let parts = q.split('&');
  for (let i = 0; i < parts.length; i++) {
    let kv = parts[i].split('=');
    if (kv.length !== 2) continue;
    if (kv[0] === 'from') from = JSON.parse(kv[1]);
    if (kv[0] === 'count') count = JSON.parse(kv[1]);
  }
  let head = '{"api":1,"step_s":' + JSON.stringify(ARC.step_s) +
    ',"oldest":' + JSON.stringify(arcOldest()) +
    ',"next":' + JSON.stringify(ST.start + ST.count) +
    ',"page":' + JSON.stringify(ST.page) +
    ',"count":' + JSON.stringify(ST.count);
  if (from <= 0) {
    res.body = head + '}';
  } else {
    if (count <= 0 || count > 96) count = 96;
    let got = arcRead(from, count);
    res.body = head + ',"from":' + JSON.stringify(from) +
      ',"t":' + JSON.stringify(got.t) + ',"h":' + JSON.stringify(got.h) + '}';
  }
  res.code = 200;
  res.headers = { 'Content-Type': 'application/json' };
  res.send();
});

let LAST_EV = null; // nur zur Diagnose via Script.Eval

Shelly.addStatusHandler(function (ev) {
  if (ev.component.slice(0, SENSOR_PREFIX.length) !== SENSOR_PREFIX) return;
  LAST_EV = ev;
  scheduleUpdate();
});

buildMap(0);

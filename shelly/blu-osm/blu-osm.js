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
//     "temperature": { "value": 23.2, "unit": "Â°C" },
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
  { obj_id: 69, name: 'temperature', unit: 'Â°C' },
  { obj_id: 46, name: 'humidity', unit: '%' },
  { obj_id: 1, name: 'battery', unit: '%' },
];

let SENSOR_PREFIX = 'bthomesensor:';

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

let LAST_EV = null; // nur zur Diagnose via Script.Eval

Shelly.addStatusHandler(function (ev) {
  if (ev.component.slice(0, SENSOR_PREFIX.length) !== SENSOR_PREFIX) return;
  LAST_EV = ev;
  scheduleUpdate();
});

buildMap(0);

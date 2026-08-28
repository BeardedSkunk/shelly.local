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
// Temperatur und Feuchte lassen sich um einen festen Betrag korrigieren, falls
// der Sensor gegen eine Referenz danebenliegt. Der Betrag steht in je einer
// virtuellen Zahl, die das Script selbst anlegt und die in der Weboberflaeche
// des Steckers auftaucht -- siehe OFFSETS weiter unten.
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
//
// offset nennt die virtuelle Zahl, deren Wert vor dem Veroeffentlichen
// aufgeschlagen wird. Die Batterie bekommt keine -- an einem Ladestand ist
// nichts zu justieren.
let WANTED = [
  { obj_id: 69, name: 'temperature', unit: 'Grad C', offset: 'Temperatur-Offset' },
  { obj_id: 46, name: 'humidity', unit: '%', offset: 'Feuchte-Offset' },
  { obj_id: 1, name: 'battery', unit: '%', offset: null },
];

let SENSOR_PREFIX = 'bthomesensor:';
let NUMBER_PREFIX = 'number:';

// ------------------------------------------------------------------- Abgleich
//
// Zwei BLU H&T am selben Ort sind sich nicht einig -- am 12.08.2026 lagen zwei
// Geraete nebeneinander im Garten stundenlang um mehrere Zehntel auseinander.
// Welches naeher an der Wahrheit liegt, sagt kein Geraet von selbst; das
// entscheidet ein Vergleich mit anderen Thermometern.
//
// Beim Modell ohne Anzeige (SBHT-203C) ist im Sensor kein Platz dafuer: seine
// Firmware kennt kein Offset-Feld, auch v1.2.12 nicht. Das mit Anzeige hat
// eines, aber wer die Korrektur dort versteckt, findet sie in zwei Jahren
// nicht wieder. Also korrigiert das Script, und zwar an genau einer Stelle --
// in update(), bevor der Wert sowohl in die openSenseMap-Meldung als auch ins
// Archiv geht. Ein Wert, eine Wahrheit.
//
// Verstellbar ist es ohne Code-Aenderung: das Script legt beim ersten Start je
// eine virtuelle Zahl an, die in der Weboberflaeche des Steckers unter den
// Komponenten auftaucht. Voreinstellung 0 -- ohne Zutun aendert sich nichts.
//
//   curl -s "http://<plug-ip>/rpc/Number.Set?id=<id>&value=-0.8"
//
// Was schon im Archiv steht, bleibt unkorrigiert: dort liegen fertige Saetze,
// keine Rohwerte. Eine Aenderung wirkt also ab jetzt und nicht rueckwirkend.
let OFFSETS = [
  { name: 'Temperatur-Offset', unit: 'K', min: -10, max: 10 },
  { name: 'Feuchte-Offset', unit: '%', min: -20, max: 20 },
];

// ------------------------------------------------------- Viertelstunden-Archiv
//
// Der Sensor misst weiter, wenn openSenseMap nicht antwortet -- am 10.08.2026
// war der Dienst einen halben Abend lang weg, und alles aus dieser Zeit war
// verloren. Also schreibt das Script selbst mit, und zwar so, dass ein
// spaeteres Nachreichen moeglich bleibt.
//
// Ein Satz je vollen fuenf Minuten nach Uhr -- zur vollen Stunde, fuenf nach,
// zehn nach und so weiter -- drei Zeichen lang:
//
//   10 Bit Temperatur in Zehntelgrad, Nullpunkt -50 C, 1023 = unbekannt
//    8 Bit Feuchte in halben Prozent,               255 = unbekannt
//
// Kein Zeitstempel im Satz. Das Raster ist fest, also sagt die Stelle in der
// Seite, welche Viertelstunde gemeint ist -- und eine Luecke kostet denselben
// Platz wie ein Messwert, dafuer bleibt alles ausgerichtet.
//
// Elf Seiten zu je 336 Saetzen, reihum beschrieben: 28 Stunden je Seite, knapp
// 13 Tage insgesamt. Die zwoelfte Speicherstelle haelt die Verwaltung.
//
// Gehalten werden zwischen 11,7 und 12,8 Tagen: die aelteste Seite wird geleert,
// wenn sie wieder an der Reihe ist, es sind also nie alle elf gleichzeitig voll.
//
// Gemessen an sechs Tagen echter Daten aendert sich die Temperatur alle zwei
// Minuten, die Feuchte fast ebenso oft. Eine Ablage nur bei Aenderung waere
// deshalb teurer als dieses feste Raster und nicht billiger -- 687 Saetze am Tag
// statt 288, jeder mit Zeitstempel. Beim festen Raster ist die Stelle in der
// Seite die Uhrzeit, und das kostet nichts.
//
// Geschrieben wird nicht bei jedem Satz. Die volle Seite jedes Mal neu in den
// Flash zu legen waere alle fuenf Minuten ein Kilobyte; gesammelt wird deshalb
// im RAM und nur halbstuendlich abgelegt. Ein Stromausfall kostet damit die
// letzte halbe Stunde -- der Preis dafuer, dass der Flash das jahrelang
// mitmacht.
//
// Ein Code-Update loescht das alles nicht: Script.storage haengt an der
// Script-ID, nicht am Code. Am 10.08.2026 auf sechs Steckern nachgeprueft --
// nach dem Austausch stand die Tagesreihe des Energie-Journals unveraendert da.
let ARC = {
  slots: ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k'],
  meta: 'm',
  per_page: 336,
  step_s: 300,
  flush_every: 6,   // Saetze im RAM, bevor der Flash sie zu sehen bekommt
  send_slots: 20,   // Zeitscheiben je Nachtrag-Anfrage
  give_up: 5,       // abgelehnte Anlaeufe, bis ein Stueck uebersprungen wird
  version: 2,
};

let A64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';

// Fuer arcNum. Eine Kette und ein indexOf, weil das in mJS und in node dasselbe
// heisst -- siehe dort.
let DIG = '0123456789';

// ------------------------------------------------------ Kurznamen fuer Ketten
//
// Gestrippte Bytes kosten zweimal: Flash (20480 je Slot) und Bytecode im
// gemeinsamen Skript-Heap, dessen Dauerbestand ungefaehr mit der Codegroesse
// waechst. (Die Quelle selbst haelt der Lader NICHT im Heap -- das 18,5-KB-
// power-journal startet mit 10,5 KB Spitze, gemessen 29.08.2026.) Der
// Minifizierer kuerzt jeden Namen der obersten Ebene auf ein, zwei Buchstaben,
// an Eigenschaftsnamen wie JSON.stringify darf er aber nicht ruehren. Der
// Umweg ueber eine eigene Funktion macht sie kuerzbar: aus 34x JSON.stringify(
// wird 34x j( -- zusammen rund 0,8 KB weniger Code. Der Preis ist ein
// Funktionsaufruf mehr in der Tiefe; die engste Stelle (Timer -> update ->
// arcSample -> arcCloseUpTo -> arcAppend -> arcFlush -> arcSaveMeta -> jstr)
// bleibt mit 8 von ~15 Rahmen unter dem Stack-Limit von mJS.
function jstr(v) { return JSON.stringify(v); }
function flo(v) { return Math.floor(v); }
function stoGet(k) { return Script.storage.getItem(k); }
function stoSet(k, v) { Script.storage.setItem(k, v); }
function cstat(k) { return Shelly.getComponentStatus(k); }

// Laufende Verwaltung: welche Seite gerade beschrieben wird, wo sie anfaengt,
// wieviel drinsteht, und der zuletzt gesehene Stand des Sensors.
//
// Gespeichert wird der Messwert, der zur vollen Viertelstunde galt -- nicht ein
// Mittel darueber. Der Sensor sendet bei Aenderung, ein Wert gilt also bis zum
// naechsten Paket, und dieselbe Lesart hat openSenseMap und hat die App. Ein
// Mittelwert waere eine andere Groesse: er glaettet den Sprung, den der Sensor
// gerade gemeldet hat, und keine der beiden anderen Seiten wuerde ihn so lesen.
let ST = {
  page: 0,     // Index in ARC.slots
  start: 0,    // Viertelstundennummer des ersten Satzes dieser Seite
  count: 0,    // Saetze dieser Seite, die schon im Flash stehen
  buf: '',     // fertige Saetze, die noch im RAM warten
  quarter: 0,  // Zeitscheibe, die gerade laeuft
  lastT: null, // zuletzt gesehener Stand ...
  lastH: null,
  lastAt: 0,   // ... und wann der Sensor ihn gemeldet hat
  sent: 0,     // erste Zeitscheibe, die openSenseMap noch nicht hat
  ready: false,
};

// Zur Laufzeit gefuellt: je ein Eintrag { ckey, name, unit, last, okey }.
// okey ist die virtuelle Zahl mit dem Offset oder null, wenn es fuer diesen
// Wert keine gibt.
let MAP = [];

// Waehrend des Komponenten-Durchlaufs gesammelt: die virtuellen Zahlen, die es
// auf diesem Stecker schon gibt, als { name, ckey }.
let FOUND = [];

let lastWriteAt = 0; // Systemzeit des letzten Schreibvorgangs

// ---------------------------------------------------------------- Hilfsmittel

function sysTime() {
  let st = cstat('sys');
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

function foundOffset(name) {
  for (let i = 0; i < FOUND.length; i++) {
    if (FOUND[i].name === name) return FOUND[i].ckey;
  }
  return null;
}

// Der Offset wird bei jedem Durchlauf frisch gelesen und nicht gemerkt. Das
// kostet nichts -- eine lokale Komponente, kein Funk, kein Netz -- und eine
// Aenderung in der Oberflaeche wirkt dadurch beim naechsten Poll, ohne dass
// jemand das Script neu starten muss.
function offsetOf(entry) {
  if (entry.okey === null) return 0;
  let st = cstat(entry.okey);
  if (st === null || typeof st.value !== 'number') return 0;
  return st.value;
}

// Ohne Runden schleppt jeder Offset seine Fliesskomma-Reste mit: 11.9 + -0.8
// ergibt 11.100000000000001, und genau so stuende es in der Meldung an
// openSenseMap. Ein Zehntel ist ohnehin die Aufloesung des Sensors.
//
// Math.round und Math.floor gibt es in mJS (am 12.08.2026 auf dem Geraet
// geprueft), den Bit-Operator |0 nicht -- der beendet das Script.
function round1(v) {
  return Math.round(v * 10) / 10;
}

// ----------------------------------------------------------- openSenseMap

let osmPending = null;
let osmBusy = false;

// Live-Push und Nachtrag schicken denselben POST an dieselbe Adresse und
// unterscheiden sich nur in Rumpf und Inhaltstyp. Einmal gebaut, zweimal
// benutzt -- der Parameterblock ist gestrippt gut 100 Byte schwer.
function osmPost(ctype, body, cb) {
  Shelly.call(
    'HTTP.Request',
    {
      method: 'POST',
      url: OSM.url,
      headers: { 'Content-Type': ctype, Authorization: OSM.token },
      body: body,
      ssl_ca: OSM.ssl_ca,
      timeout: OSM.timeout_s,
    },
    cb
  );
}

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
  osmPost('application/json', jstr(batch), function (res, ec, em) {
      osmBusy = false;
      if (ec !== 0) {
        print('openSenseMap: Aufruf fehlgeschlagen: ' + em);
      } else if (res.code < 200 || res.code > 299) {
        print('openSenseMap: HTTP ' + jstr(res.code) + ' ' + res.body);
      } else if (CFG.log) {
        print('openSenseMap: ' + jstr(batch.length) + ' Wert(e) gesendet');
      }
      if (osmPending !== null) {
        let next = osmPending;
        osmPending = null;
        osmSend(next);
      }
    });
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
      print('KVS ' + CFG.key + ' = ' + jstr(value));
    }
  });
}

// ------------------------------------------------------------ Archiv-Ablage

function arcEncode(tc, hc) {
  let v = tc * 256 + hc;
  let a = flo(v / 4096);
  let b = flo(v / 64) % 64;
  return A64.slice(a, a + 1) + A64.slice(b, b + 1) +
    A64.slice(v % 64, v % 64 + 1);
}

function arcTempCode(t) {
  if (t === null) return 1023;
  let c = flo((t + 50) * 10 + 0.5);
  if (c < 0) c = 0;
  if (c > 1022) c = 1022;
  return c;
}

function arcHumCode(h) {
  if (h === null) return 255;
  let c = flo(h * 2 + 0.5);
  if (c < 0) c = 0;
  if (c > 200) c = 200;
  return c;
}

// Eine nicht-negative ganze Zahl aus einem Feld, oder -1, wenn da etwas
// anderes steht.
//
// Ausdruecklich NICHT JSON.parse. Das faellt bei kaputter Eingabe nicht auf
// eine Nase, die man abfangen koennte -- in mJS toetet es das ganze Skript,
// unabfangbar. Wo die Eingabe von aussen kommt oder einen Absturz ueberlebt
// hat, ist das keine Fehlerbehandlung, sondern ein Selbstmordknopf.
//
// Am 28.08.2026 ist genau das passiert: der Speicher ging aus, waehrend der
// Merker geschrieben wurde, und der halbe Satz machte das Skript beim naechsten
// Start unstartbar -- ein halber Tag Messwerte weg, und aus der Ferne war nicht
// einmal zu sehen, was drinsteht, weil Script.storage ueber RPC nicht lesbar
// ist. Ziffern selbst zu pruefen sind zwoelf Zeilen und koennen nichts anrichten.
//
// Gelesen wird zeichenweise ueber indexOf in eine Ziffernkette -- dieselbe
// Machart wie beim Auspacken des Archivs weiter unten, und aus demselben Grund:
// sie bedeutet in mJS und in node dasselbe. `t.at(i)` tut das NICHT. In mJS
// kommt der Bytewert heraus, in node das Zeichen, und `zeichen - 48` ist dort
// NaN. NaN ist weder kleiner null noch groesser neun, rutscht also durch jede
// Bereichspruefung und wird stillschweigend zur Seitennummer. Die Testsuite hat
// genau das abgefangen, bevor es auf die Dose ging.
function arcNum(t) {
  if (t === null || t === undefined) return -1;
  if (t.length < 1 || t.length > 12) return -1;
  let n = 0;
  for (let i = 0; i < t.length; i++) {
    let d = DIG.indexOf(t.slice(i, i + 1));
    if (d < 0) return -1;
    n = n * 10 + d;
  }
  return n;
}

// "<version>|<seite>|<start>|<anzahl>|<gesendet>|z"
//
// gesendet ist die erste Zeitscheibe, die openSenseMap noch nicht hat. Sie
// steht hier und nicht im RAM, weil sie einen Neustart ueberleben muss: sonst
// wuerde nach jedem Stromausfall alles noch einmal geschickt, oder gar nichts.
//
// Das 'z' am Ende ist der Nachweis, dass der Satz vollstaendig ist. Ein
// Schreiben, das mittendrin abbricht, hinterlaesst sonst eine Zeile, die sich
// anstandslos lesen laesst und falsch ist -- lauter Ziffern, nur zu wenige, und
// dann wird der Nachtrag an der falschen Stelle fortgesetzt. Ein Zeichen, das
// nur ganz am Ende steht, macht den Unterschied zwischen "abgerissen" und
// "fertig" sichtbar.
function arcSaveMeta() {
  stoSet(
    ARC.meta,
    jstr(ARC.version) + '|' + jstr(ST.page) + '|' +
      jstr(ST.start) + '|' + jstr(ST.count) + '|' +
      jstr(ST.sent) + '|z'
  );
}

// Faellt der Satz durch, faengt das Archiv eine frische Seite an. Das kostet
// eine Seite Verlauf und nicht das Skript, und das ist der ganze Punkt.
//
// ST wird erst beschrieben, wenn alles geprueft ist. Feldweise zuzuweisen und
// spaeter abzubrechen liesse einen halb geaenderten Zustand stehen, auf dem der
// Rest des Skripts dann weiterrechnet.
function arcLoadMeta() {
  let raw = stoGet(ARC.meta);
  if (raw === null || raw === undefined) return false;
  let f = raw.split('|');
  if (f.length < 6 || f[5] !== 'z') return false;
  if (arcNum(f[0]) !== ARC.version) return false;
  let page = arcNum(f[1]);
  let start = arcNum(f[2]);
  let count = arcNum(f[3]);
  let sent = arcNum(f[4]);
  if (page < 0 || start < 0 || count < 0 || sent < 0) return false;
  if (page >= ARC.slots.length) return false;
  ST.page = page;
  ST.start = start;
  ST.count = count;
  ST.sent = sent;
  return true;
}

// Wieviel in dieser Seite steht, den wartenden Puffer eingerechnet.
function arcFilled() {
  return ST.count + flo(ST.buf.length / 3);
}

// Legt ab, was im RAM wartet. Der einzige Punkt, an dem der Flash beschrieben
// wird -- und deshalb der einzige, der zaehlt, wenn es um Verschleiss geht.
function arcFlush() {
  if (ST.buf.length === 0) return;
  let key = ARC.slots[ST.page];
  let old = stoGet(key);
  if (old === null || old === undefined) old = '';
  stoSet(key, old + ST.buf);
  ST.count = ST.count + flo(ST.buf.length / 3);
  ST.buf = '';
  arcSaveMeta();
}

// Haengt einen Satz an, wechselt die Seite wenn sie voll ist. Die aelteste
// Seite wird ueberschrieben -- das ist der Ringpuffer.
function arcAppend(text, quarter) {
  if (arcFilled() >= ARC.per_page) {
    // Was noch wartet, gehoert in die alte Seite und muss vor dem Wechsel
    // hinein -- danach zeigt ST.page woandershin.
    arcFlush();
    ST.page = (ST.page + 1) % ARC.slots.length;
    ST.count = 0;
    ST.start = quarter;
    stoSet(ARC.slots[ST.page], '');
    arcSaveMeta();
    oldestKnown = null;
  }
  if (arcFilled() === 0) { ST.start = quarter; oldestKnown = null; }
  ST.buf = ST.buf + text;
  if (flo(ST.buf.length / 3) >= ARC.flush_every) arcFlush();
}

// Schreibt alles bis ausschliesslich der laufenden Viertelstunde weg. Was
// uebersprungen wurde -- Neustart, Stromausfall, Sensor weg -- wird als
// unbekannt eingetragen, damit die Stellen im Raster stimmen bleiben.
//
// Geschrieben wird der Stand, der zum Ende der jeweiligen Viertelstunde galt,
// und nur wenn der Sensor sich waehrend dieser Viertelstunde ueberhaupt
// gemeldet hat. Ein Wert von vor drei Stunden ist nicht der Stand um Viertel
// nach -- er ist der letzte, den jemand kennt, und das ist etwas anderes.
function arcCloseUpTo(quarter) {
  if (ST.quarter === 0) return;
  let guard = 0;
  while (ST.quarter < quarter && guard < ARC.per_page) {
    let tc = 1023;
    let hc = 255;
    let fresh = ST.lastAt >= ST.quarter * ARC.step_s &&
      ST.lastAt < (ST.quarter + 1) * ARC.step_s;
    if (fresh && ST.lastT !== null) tc = arcTempCode(ST.lastT);
    if (fresh && ST.lastH !== null) hc = arcHumCode(ST.lastH);
    arcAppend(arcEncode(tc, hc), ST.quarter);
    ST.quarter = ST.quarter + 1;
    guard = guard + 1;
  }
  // Bei einem sehr langen Ausfall wird nicht die halbe Historie mit Luecken
  // vollgeschrieben -- dann faengt das Raster einfach neu an.
  if (ST.quarter < quarter) {
    ST.quarter = quarter;
    arcFlush();
    ST.count = ARC.per_page; // erzwingt eine frische Seite beim naechsten Satz
  }
}

// Schliesst faellige Viertelstunden ab und merkt sich danach den neuen Stand.
//
// Die Reihenfolge ist der Punkt: abgeschlossen wird mit dem Stand von vorhin,
// also mit dem, der vor dem Ueberschreiten der vollen Viertelstunde galt. Erst
// danach wird der neue eingetragen. Andersherum wuerde ein Paket, das eine
// Sekunde nach Viertel nach eintrifft, als der Stand von Viertel nach abgelegt.
function arcSample(now, t, h, at) {
  if (now <= 0) return;
  let q = flo(now / ARC.step_s);
  if (!ST.ready) {
    if (arcLoadMeta()) {
      // Nach einem Neustart geht es dort weiter, wo die Seite aufhoert -- und
      // nicht bei der jetzigen Viertelstunde. Sonst rutscht das ganze Raster:
      // die Viertelstunde, in der der Strom ausfiel, war noch nicht
      // geschrieben, und der naechste Satz landet auf ihrer Stelle und traegt
      // damit eine falsche Uhrzeit. Was dazwischen fehlt, fuellt arcCloseUpTo
      // gleich als unbekannt auf.
      ST.quarter = ST.start + ST.count;
      ST.buf = '';
    } else {
      ST.page = 0; ST.start = q; ST.count = 0; ST.sent = q;
      stoSet(ARC.slots[0], '');
      arcSaveMeta();
      ST.quarter = q;
      oldestKnown = null;
    }
    ST.ready = true;
  }
  if (q > ST.quarter) arcCloseUpTo(q);
  if (ST.quarter === 0) ST.quarter = q;
  if (t !== null) ST.lastT = t;
  if (h !== null) ST.lastH = h;
  // Wann der Sensor zuletzt gesprochen hat, nicht wann nachgesehen wurde. Beim
  // Nachsehen steht der alte Wert ja weiterhin in der Komponente.
  if (at > ST.lastAt) ST.lastAt = at;
}

// -------------------------------------------------------- Wertverarbeitung

// Liest alle beobachteten Komponenten lokal aus (kein Netzwerk, kein BLE) und
// entscheidet, ob ein Schreibvorgang faellig ist. Beide Ausloeser -- Ereignis
// und Timer -- laufen hier zusammen, damit es nur eine Wahrheit gibt.
function update() {
  let changed = false;
  let newest = 0;

  for (let i = 0; i < MAP.length; i++) {
    let st = cstat(MAP[i].ckey);
    if (st === null) continue;
    if (typeof st.last_updated_ts === 'number' && st.last_updated_ts > newest) {
      newest = st.last_updated_ts;
    }
    if (typeof st.value !== 'undefined' && st.value !== null) {
      // Hier und nur hier wird korrigiert. Alles weiter unten -- KVS, Archiv,
      // openSenseMap -- sieht ausschliesslich den fertigen Wert.
      let v = st.value;
      let off = offsetOf(MAP[i]);
      if (off !== 0) v = round1(v + off);

      if (v !== MAP[i].last) {
        MAP[i].last = v;
        changed = true;
      }
    }
  }
  if (newest === 0) newest = sysTime();

  let now = sysTime();

  // Das Archiv bekommt jeden Durchlauf mit, auch den, der sonst nichts tut --
  // sonst wuerde eine Viertelstundengrenze erst beim naechsten Paket bemerkt.
  let te = entryByName('temperature');
  let he = entryByName('humidity');
  arcSample(
    now,
    te === null || te.last === undefined ? null : te.last,
    he === null || he.last === undefined ? null : he.last,
    newest
  );

  // Nachreichen, was openSenseMap noch fehlt. Laeuft neben dem Live-Push und
  // stoert ihn nicht: es schickt nur, wenn gerade kein anderer Request laeuft.
  bfSend();

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

      // Die virtuellen Zahlen laufen im selben Durchlauf mit -- sie sind
      // ebenfalls dynamische Komponenten und stehen in derselben Liste.
      if (c.key.slice(0, NUMBER_PREFIX.length) === NUMBER_PREFIX) {
        FOUND.push({ name: c.config.name, ckey: c.key });
        continue;
      }

      if (c.key.slice(0, SENSOR_PREFIX.length) !== SENSOR_PREFIX) continue;

      let w = wantedFor(c.config.obj_id);
      if (w === null) continue;

      MAP.push({ ckey: c.key, name: w.name, unit: w.unit, last: null, okey: null });
    }

    if (offset + comps.length < res.total) {
      buildMap(offset + comps.length);
      return;
    }

    if (MAP.length === 0) {
      print('Kein passender BTHome-Sensor gefunden. Ist der BLU H&T gekoppelt?');
      return;
    }

    ensureOffsets(0);
  });
}

// Legt an, was noch fehlt, und zwar einzeln nacheinander -- jeder Aufruf muss
// seine Antwort abwarten, weil erst sie die Komponenten-ID nennt. Beim zweiten
// Start ist alles vorhanden und die Kette laeuft ohne einen einzigen Aufruf
// durch. Gefunden wird ueber den Namen: Komponenten-IDs vergibt der Stecker,
// der Name steht hier im Code.
function ensureOffsets(i) {
  if (i >= OFFSETS.length) {
    finishInit();
    return;
  }

  let o = OFFSETS[i];
  if (foundOffset(o.name) !== null) {
    ensureOffsets(i + 1);
    return;
  }

  Shelly.call(
    'Virtual.Add',
    {
      type: 'number',
      config: {
        name: o.name,
        min: o.min,
        max: o.max,
        default_value: 0,
        persisted: true,
        meta: { ui: { view: 'field', unit: o.unit, step: 0.1 } },
      },
    },
    function (res, ec, em) {
      if (ec !== 0) {
        // Kein Grund aufzugeben: ohne die Komponente bleibt der Offset bei 0,
        // und das Script tut, was es vorher auch getan hat.
        print('Konnte ' + o.name + ' nicht anlegen: ' + em);
      } else {
        FOUND.push({ name: o.name, ckey: NUMBER_PREFIX + jstr(res.id) });
        print(o.name + ' angelegt als ' + NUMBER_PREFIX + jstr(res.id));
      }
      ensureOffsets(i + 1);
    }
  );
}

function finishInit() {
  // MAP in der Reihenfolge von WANTED sortieren, damit der KVS-Eintrag
  // unabhaengig von den Komponenten-IDs immer gleich aufgebaut ist. Dabei
  // bekommt jeder Wert seine virtuelle Zahl zugewiesen.
  let ordered = [];
  for (let i = 0; i < WANTED.length; i++) {
    let e = entryByName(WANTED[i].name);
    if (e === null) continue;
    if (WANTED[i].offset !== null) e.okey = foundOffset(WANTED[i].offset);
    ordered.push(e);
  }
  MAP = ordered;

  print('Ueberwache ' + jstr(MAP.length) + ' Sensorwerte.');
  update();
  Timer.set(CFG.poll_ms, true, update);
}

// ------------------------------------------------------------- Nachreichen
//
// Was waehrend eines Ausfalls aufgezeichnet wurde, geht hinterher hinaus. Die
// API nimmt CSV mit eigenem Zeitstempel je Zeile und bis zu 2500 Werten am
// Stueck; hier sind es 20 Zeitscheiben, also hoechstens 40 Zeilen und knapp
// zwei Kilobyte -- der Stecker hat keine 8 KB frei, und ein Rumpf, den er nicht
// bauen kann, waere ein Ausfall im Ausfall.
//
// Der Zeiger auf das erste noch nicht gesendete Feld steht in der Verwaltung
// und wird erst nach einer bestaetigten Antwort weitergesetzt. Ein Fehlschlag
// aendert nichts, der naechste Durchlauf versucht dieselbe Stelle noch einmal.
//
// Zwei Sorten Fehlschlag, und sie sind nicht gleich viel wert. Ein Aufruf, der
// gar nicht ankommt, ist genau der Ausfall, gegen den das Nachreichen gebaut
// ist -- er darf beliebig oft wiederkommen und zaehlt nichts. Eine Antwort
// dagegen, die das Stueck ablehnt, wird es beim naechsten Mal genauso ablehnen:
// nach ARC.give_up Anlaeufen geht der Zeiger darueber hinweg. Sonst haelt eine
// einzige unverdauliche Scheibe den ganzen Nachtrag fuer immer an, und alles
// dahinter verfaellt still, waehrend der Ringpuffer weiterlaeuft.
//
// Was die API geantwortet hat, bleibt in bfNote stehen und ist ueber den
// quarters-Endpunkt abzuholen. Das kostet ein paar Byte und hat eine Nacht
// Ratens gekostet, weil es fehlte: der Code allein sagt nicht, welche Zeile
// nicht gefiel.

let bfBusy = false;
let bfFails = 0;
let bfNote = 'noch nichts versucht';

// Der allererste Durchlauf nach dem Start wird ausgelassen. Parse,
// Komponentenlauf und erster Komplettdurchlauf liegen in einem einzigen
// GC-Fenster -- der Nachtrag mit seinem Seiten-Lesen obendrauf machte daraus
// eine Startspitze von 15 KB. Eine Minute spaeter ist der Startschub
// eingesammelt, und der naechste Poll holt den Nachtrag ohnehin.
let bfWarm = false;

// Ein Zeilenumbruch als Zeichen statt als Escape: der Minifier arbeitet
// zeilenweise, und ein Escape im Quelltext hat ihn schon einmal gestolpert.
let NL = String.fromCharCode(10);

function two(n) {
  return n < 10 ? '0' + jstr(n) : jstr(n);
}

// Unixsekunde als RFC 3339. mJS kennt kein Date, also von Hand -- die
// Kalenderrechnung nach Hinnant, die ohne Sonderfaelle fuer Schaltjahre
// auskommt, weil sie das Jahr im Maerz beginnen laesst.
function isoTime(t) {
  let days = flo(t / 86400);
  let secs = t - days * 86400;
  let z = days + 719468;
  let era = flo(z / 146097);
  let doe = z - era * 146097;
  let yoe = flo(
    (doe - flo(doe / 1460) + flo(doe / 36524) - flo(doe / 146096)) / 365
  );
  let y = yoe + era * 400;
  let doy = doe - (365 * yoe + flo(yoe / 4) - flo(yoe / 100));
  let mp = flo((5 * doy + 2) / 153);
  let d = doy - flo((153 * mp + 2) / 5) + 1;
  let m = mp < 10 ? mp + 3 : mp - 9;
  if (m <= 2) y = y + 1;
  return jstr(y) + '-' + two(m) + '-' + two(d) + 'T' +
    two(flo(secs / 3600)) + ':' + two(flo(secs / 60) % 60) + ':' +
    two(secs % 60) + 'Z';
}

function bfSensorId(name) {
  for (let i = 0; i < OSM.sensors.length; i++) {
    if (OSM.sensors[i].name === name) return OSM.sensors[i].id;
  }
  return null;
}

// Baut den Rumpf fuer die naechsten Felder und schickt ihn. Unbekannte Felder
// werden uebersprungen, zaehlen aber als erledigt -- eine Luecke bleibt eine
// Luecke, und sie noch einmal anzusehen wuerde den Zeiger nie weiterbringen.
function bfSend() {
  if (!bfWarm) { bfWarm = true; bfNote = 'wartet den Start ab'; return; }
  if (!OSM.enable) { bfNote = 'abgeschaltet'; return; }
  if (bfBusy) { bfNote = 'wartet auf die letzte Antwort'; return; }
  if (osmBusy) { bfNote = 'der Live-Push ist dran'; return; }
  let last = ST.start + arcFilled();       // erste Scheibe, die es noch nicht gibt
  if (ST.sent >= last) { bfNote = 'nichts offen'; return; }
  if (ST.sent < arcOldest()) ST.sent = arcOldest();

  let count = last - ST.sent;
  if (count > ARC.send_slots) count = ARC.send_slots;
  let got = arcRead(ST.sent, count);
  let tid = bfSensorId('temperature');
  let hid = bfSensorId('humidity');

  let body = '';
  let lines = 0;
  for (let i = 0; i < count; i++) {
    let stamp = isoTime((ST.sent + i) * ARC.step_s);
    if (got.t[i] !== null && tid !== null) {
      body = body + tid + ',' + jstr(got.t[i]) + ',' + stamp + NL;
      lines = lines + 1;
    }
    if (got.h[i] !== null && hid !== null) {
      body = body + hid + ',' + jstr(got.h[i]) + ',' + stamp + NL;
      lines = lines + 1;
    }
  }

  // Nur Luecken in diesem Stueck: nichts zu senden, aber erledigt.
  if (lines === 0) {
    ST.sent = ST.sent + count;
    bfNote = 'nur Luecken, ' + jstr(count) + ' uebersprungen';
    arcSaveMeta();
    return;
  }

  bfBusy = true;
  let advance = count;
  let first = body.slice(0, body.indexOf(NL));
  osmPost('text/csv', body, function (res, ec, em) {
      bfBusy = false;
      if (ec !== 0) {
        bfNote = 'Aufruf fehlgeschlagen: ' + em;
        print('Nachtrag: ' + bfNote);
        return;
      }
      if (res.code < 200 || res.code > 299) {
        // 400 und 422 sind die beiden Antworten, mit denen die API sagt, dass
        // sie diesen Rumpf nicht lesen kann. Die wiederholen sich zeichengleich.
        // Alles andere -- 5xx, 429, ein abgelaufener Token -- geht vorueber
        // oder gehoert von Hand behoben, und da waere Wegwerfen das Falsche.
        let hopeless = res.code === 400 || res.code === 422;
        bfFails = hopeless ? bfFails + 1 : 0;
        bfNote = 'HTTP ' + jstr(res.code) + ' ' +
          (res.body ? res.body.slice(0, 120) : '') +
          ' [ab ' + jstr(ST.sent) + ', erste Zeile: ' + first + ']';
        print('Nachtrag: ' + bfNote);
        if (hopeless && bfFails >= ARC.give_up) {
          ST.sent = ST.sent + advance;
          bfFails = 0;
          arcSaveMeta();
          print('Nachtrag: ' + jstr(advance) + ' Felder aufgegeben.');
        }
        return;
      }
      bfFails = 0;
      bfNote = jstr(lines) + ' Werte bestaetigt';
      ST.sent = ST.sent + advance;
      arcSaveMeta();
      if (CFG.log) {
        print('Nachtrag: ' + jstr(lines) + ' Werte bis ' +
          jstr(ST.sent) + ' bestaetigt');
      }
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
// Das Ergebnis aendert sich nur, wenn eine Seite geleert wird oder das Raster
// neu ansetzt -- alle 28 Stunden also. Gefragt wird aber im Minutentakt:
// bfSend bei jedem Durchlauf mit offenem Nachtrag, der Endpunkt bei jeder
// Abfrage. Jeder dieser Durchlaeufe las bis zu elf Seiten zu je einem Kilobyte
// aus dem Storage -- elf Kilobyte Zwischenmuell fuer eine Zahl, die seit
// Stunden feststeht. Am 28.08. war genau das der Minutentakt neben den
// Spitzen von power-journal, als der Speicher ausging: openSenseMap weg,
// Nachtrag offen, elf Seiten je Minute. Deshalb wird sie gemerkt und nur
// verworfen, wenn sich am Seitenbestand wirklich etwas aendert.
let oldestKnown = null;

function arcOldest() {
  if (oldestKnown !== null) return oldestKnown;
  // Die Seite nach der laufenden ist die aelteste, sofern schon einmal
  // umgelaufen wurde; sonst ist es die erste.
  let n = ARC.slots.length;
  for (let i = 1; i <= n; i++) {
    let key = ARC.slots[(ST.page + i) % n];
    let text = stoGet(key);
    if (text !== null && text !== undefined && text.length >= 3) {
      // Der Anfang dieser Seite laesst sich nicht speichern, ohne die
      // Verwaltung aufzublaehen -- er ergibt sich aus dem Abstand zur
      // laufenden Seite, die vollen Seiten dazwischen mitgezaehlt.
      let ahead = (ST.page - ((ST.page + i) % n) + n) % n;
      oldestKnown = ST.start - ahead * ARC.per_page;
      return oldestKnown;
    }
  }
  oldestKnown = ST.start;
  return oldestKnown;
}

function arcRead(from, count) {
  let t = [];
  let h = [];
  let n = ARC.slots.length;
  let text = '';
  let have = -1; // Seite, die text gerade haelt
  for (let i = 0; i < count; i++) {
    let q = from + i;
    // In welcher Seite steht diese Viertelstunde? Rueckwaerts von der
    // laufenden gerechnet.
    let back = ST.start - q;
    let page = ST.page;
    let pos = q - ST.start;
    if (back > 0) {
      let steps = flo((back + ARC.per_page - 1) / ARC.per_page);
      page = (ST.page - steps + n * 2) % n;
      pos = q - (ST.start - steps * ARC.per_page);
    }
    if (page !== have) {
      // Eine Abfrage laeuft fast immer ueber ein und dieselbe Seite. Sie fuer
      // jede Viertelstunde neu aus dem Storage zu holen hiess: 96 Leseaufrufe
      // zu je einem Kilobyte fuer eine Tagesabfrage der App. Einmal je
      // Seitenwechsel reicht.
      text = stoGet(ARC.slots[page]);
      if (text === null || text === undefined) text = '';
      // Die laufende Seite hat einen Schwanz im RAM, der noch nicht im Flash
      // steht. Wer ihn beim Lesen auslaesst, sieht die letzte halbe Stunde
      // nicht.
      if (page === ST.page) text = text + ST.buf;
      have = page;
    }
    if (pos < 0 || (pos + 1) * 3 > text.length) {
      t.push(null); h.push(null);
      continue;
    }
    let a = A64.indexOf(text.slice(pos * 3, pos * 3 + 1));
    let b = A64.indexOf(text.slice(pos * 3 + 1, pos * 3 + 2));
    let c = A64.indexOf(text.slice(pos * 3 + 2, pos * 3 + 3));
    let v = a * 4096 + b * 64 + c;
    let tc = flo(v / 256);
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
    // arcNum und nicht JSON.parse: was hier ankommt, hat irgendwer in eine
    // Adresszeile geschrieben. Mit JSON.parse war ein "?from=x" von irgendwo im
    // Netz ein Ausschalter fuer dieses Skript -- unabfangbar, ohne Meldung, und
    // wieder hochzubekommen war es nur ueber einen Deploy. Unsinn wird jetzt zu
    // -1 und damit zur Vorgabe.
    if (kv[0] === 'from') from = arcNum(kv[1]);
    if (kv[0] === 'count') count = arcNum(kv[1]);
  }
  if (from < 0) from = 0;
  if (count < 0) count = 0;
  // code is the version of this file, written out as a plain number so it
  // survives the squeeze into the app's asset as readable text. The app finds
  // what it ships by looking for this literal in its own copy and what a plug
  // runs by reading it back from here, so it can say which of the two is the
  // newer instead of only whether they differ. Bumped by hand when a change is
  // worth pushing.
  let head = '{"api":1,"code":1,"step_s":' + jstr(ARC.step_s) +
    ',"oldest":' + jstr(arcOldest()) +
    ',"next":' + jstr(ST.start + arcFilled()) +
    ',"page":' + jstr(ST.page) +
    ',"count":' + jstr(arcFilled()) +
    ',"sent":' + jstr(ST.sent) +
    ',"note":' + jstr(bfNote);
  if (from <= 0) {
    res.body = head + '}';
  } else {
    if (count <= 0 || count > 96) count = 96;
    let got = arcRead(from, count);
    res.body = head + ',"from":' + jstr(from) +
      ',"t":' + jstr(got.t) + ',"h":' + jstr(got.h) + '}';
  }
  res.code = 200;
  res.headers = { 'Content-Type': 'application/json' };
  res.send();
});

Shelly.addStatusHandler(function (ev) {
  if (ev.component.slice(0, SENSOR_PREFIX.length) !== SENSOR_PREFIX) return;
  scheduleUpdate();
});

buildMap(0);

// Der Griff, an dem die Testsuite das Skript anfasst.
//
// Der Name steht in KEEP von strip.js und ueberlebt das Straffen; die
// Schluessel sind in Anfuehrungszeichen, weil der Minifizierer Zeichenketten in
// Ruhe laesst, jeden Namen der obersten Ebene aber auf ein oder zwei Buchstaben
// zusammenzieht. Beides zusammen ist der Grund, dass `BLU_STRIPPED=1` die
// gleichen Tests gegen genau die Fassung laufen lassen kann, die auf die Dose
// geht -- und nicht nur gegen die kommentierte daneben.
function selftest() {
  return { 'update': update, 'ST': ST, 'ARC': ARC, 'arcNum': arcNum };
}

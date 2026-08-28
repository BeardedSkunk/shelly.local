// Puts blu-osm.js onto a plug over local RPC.
//
//   node tools/upload.js 192.168.178.24              upload and start
//   node tools/upload.js 192.168.178.24 --no-start   upload only
//   node tools/upload.js 192.168.178.24 --status     show what is there
//
// The source is checked in as a template -- {{OSM_URL}}, {{OSM_TOKEN}},
// {{OSM_TEMPERATURE}}, {{OSM_HUMIDITY}} -- because a token belongs to one box
// and one person, and a copy of it in here would be a copy of it in the
// repository's history for good.
//
// So the values are read back off the plug that is already running a filled-in
// copy, and never travel through a file. That is not a trick: the plug is where
// they legitimately live, an upgrade is by far the commonest reason to run this,
// and it means the tool needs no secret of its own. A plug with nothing on it
// yet has to be told once:
//
//   node tools/upload.js <host> --url ... --token ... --temperature ... --humidity ...
//
// The code goes up in chunks because a single RPC body cannot carry the whole
// file. Script.PutCode appends, so the first chunk replaces and the rest add to
// it; the upload is read back and compared afterwards.

'use strict';

const fs = require('fs');
const path = require('path');
const http = require('http');
const { strip } = require('../../power-journal/tools/strip');

const SOURCE = path.join(__dirname, '..', 'blu-osm.js');
const SCRIPT_NAME = 'blu-osm';
const CHUNK = 1024;
const MAX_SCRIPT_BYTES = 20480;

// The template hole, and the pattern that finds the same value in a script the
// plug already runs. The stripped code renames every top-level name, so the
// object is not called OSM up there any more -- but string literals are left
// alone, and these four are recognisable on their own.
const SECRETS = [
  { hole: '{{OSM_URL}}', flag: '--url', find: /url:\s*'([^']*)'/ },
  { hole: '{{OSM_TOKEN}}', flag: '--token', find: /token:\s*'([^']*)'/ },
  {
    hole: '{{OSM_TEMPERATURE}}',
    flag: '--temperature',
    find: /name:\s*'temperature'\s*,\s*id:\s*'([^']*)'/,
  },
  {
    hole: '{{OSM_HUMIDITY}}',
    flag: '--humidity',
    find: /name:\s*'humidity'\s*,\s*id:\s*'([^']*)'/,
  },
];

const host = process.argv[2];
const flags = process.argv.slice(3);
const has = (flag) => flags.indexOf(flag) >= 0;
const valueOf = (flag) => {
  const at = flags.indexOf(flag);
  return at >= 0 ? flags[at + 1] : undefined;
};

if (!host) {
  console.error('usage: node tools/upload.js <host> [--no-start|--status]');
  process.exit(2);
}

function request(options, body) {
  return new Promise((resolve, reject) => {
    const req = http.request(options, (response) => {
      let text = '';
      response.on('data', (chunk) => { text += chunk; });
      response.on('end', () => resolve(text));
    });
    req.on('error', reject);
    if (body !== undefined) req.write(body);
    req.end();
  });
}

async function rpc(method, params) {
  const body = JSON.stringify({ id: 1, method, params });
  const text = await request({
    host,
    port: 80,
    path: '/rpc',
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) },
  }, body);
  let answer;
  try {
    answer = JSON.parse(text);
  } catch (err) {
    throw new Error(method + ' answered with something that is not JSON: ' + text.slice(0, 200));
  }
  if (answer.error) throw new Error(method + ': ' + JSON.stringify(answer.error));
  return answer.result;
}

async function findScript(name) {
  const list = await rpc('Script.List', {});
  return list.scripts.find((s) => s.name === name);
}

async function getCode(id) {
  let code = '';
  for (;;) {
    const part = await rpc('Script.GetCode', { id, offset: code.length, len: 2000 });
    code += part.data;
    if (part.left === 0) break;
  }
  return code;
}

async function putCode(id, code) {
  for (let at = 0; at < code.length; at += CHUNK) {
    await rpc('Script.PutCode', { id, code: code.slice(at, at + CHUNK), append: at > 0 });
  }
}

// From the flags if given, otherwise off the plug's own copy.
//
// Read before anything is written, so a plug whose values cannot be recovered
// is left exactly as it was rather than half replaced.
function secretsFrom(existingCode) {
  const found = {};
  for (const s of SECRETS) {
    const given = valueOf(s.flag);
    if (given !== undefined) { found[s.hole] = given; continue; }
    const match = existingCode === null ? null : existingCode.match(s.find);
    if (!match) {
      throw new Error(
        'no value for ' + s.hole + ': the plug has no copy to read it from, ' +
        'so pass ' + s.flag + ' <value>'
      );
    }
    found[s.hole] = match[1];
  }
  return found;
}

async function main() {
  const existing = await findScript(SCRIPT_NAME);

  if (has('--status')) {
    if (!existing) { console.log('no ' + SCRIPT_NAME + ' on ' + host); return; }
    const status = await rpc('Script.GetStatus', { id: existing.id });
    const code = await getCode(existing.id);
    console.log(SCRIPT_NAME + ' is script ' + existing.id +
      ', ' + code.length + ' of ' + MAX_SCRIPT_BYTES + ' bytes');
    console.log(JSON.stringify(status));
    return;
  }

  const before = existing ? await getCode(existing.id) : null;
  const secrets = secretsFrom(before);

  let code = strip(fs.readFileSync(SOURCE, 'utf8'));
  for (const hole of Object.keys(secrets)) {
    if (code.indexOf(hole) < 0) throw new Error('the source has no ' + hole + ' to fill');
    code = code.split(hole).join(secrets[hole]);
  }
  if (code.length > MAX_SCRIPT_BYTES) {
    throw new Error('stripped to ' + code.length + ' bytes, over the ' + MAX_SCRIPT_BYTES + ' limit');
  }

  const id = existing ? existing.id : (await rpc('Script.Create', { name: SCRIPT_NAME })).id;
  if (existing) await rpc('Script.Stop', { id }).catch(() => {});
  await putCode(id, code);

  const back = await getCode(id);
  if (back !== code) {
    throw new Error('what came back is not what went up: ' + back.length + ' of ' + code.length + ' bytes');
  }
  console.log('script ' + id + ': ' + code.length + ' of ' + MAX_SCRIPT_BYTES + ' bytes, verified');

  await rpc('Script.SetConfig', { id, config: { enable: true } });
  if (has('--no-start')) { console.log('left stopped'); return; }

  await rpc('Script.Start', { id });
  await new Promise((done) => setTimeout(done, 4000));
  const status = await rpc('Script.GetStatus', { id });
  console.log(JSON.stringify(status));
  if (!status.running) process.exitCode = 1;
}

main().catch((err) => {
  console.error(String(err.message || err));
  process.exit(1);
});

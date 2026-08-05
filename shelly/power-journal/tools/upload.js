// Puts power-journal.js onto a plug over local RPC.
//
//   node tools/upload.js 192.168.178.23              upload and start
//   node tools/upload.js 192.168.178.23 --no-start   upload only
//   node tools/upload.js 192.168.178.23 --status     show what is there
//   node tools/upload.js 192.168.178.23 --attic      dump the overflow archive
//   node tools/upload.js 192.168.178.23 --remove     stop and delete the journal
//   node tools/upload.js 192.168.178.23 --remove --with-attic   and its history
//
// Two scripts go onto the device. The journal itself, which runs, and an attic
// that never does: day pages pushed out of the twelve storage slots are
// appended to the attic's source as comments, which is the only writable space
// left on the plug. So the attic is where the oldest history lives, and
// --remove deliberately leaves it alone unless asked twice.
//
// The code goes up in chunks because a single RPC body cannot carry the whole
// file. Script.PutCode appends, so the first chunk replaces and the rest add
// to it; the upload is verified by reading the code back afterwards.

'use strict';

const fs = require('fs');
const path = require('path');
const http = require('http');
const { strip } = require('./strip');

const SOURCE = path.join(__dirname, '..', 'power-journal.js');
const SCRIPT_NAME = 'power-journal';
const ATTIC_NAME = 'pj-attic';
const ATTIC_HEADER = '// power-journal attic. This script never runs.\n' +
  '// Each comment below is one day page pushed out of the device storage,\n' +
  '// in the same encoding the HTTP endpoint serves.\n';
const CHUNK = 1024;
const MAX_SCRIPT_BYTES = 20480;

const host = process.argv[2];
const flags = process.argv.slice(3);
const has = (flag) => flags.indexOf(flag) >= 0;

if (!host) {
  console.error('usage: node tools/upload.js <host> [--no-start|--status|--attic|--remove [--with-attic]]');
  process.exit(2);
}

function request(options, body) {
  return new Promise((resolve, reject) => {
    const req = http.request(options, (response) => {
      let text = '';
      response.on('data', (chunk) => { text += chunk; });
      response.on('end', () => resolve(text));
    });
    req.on('timeout', () => req.destroy(new Error('timed out')));
    req.on('error', reject);
    req.end(body);
  });
}

async function rpc(method, params) {
  const body = JSON.stringify({ id: 1, method, params: params || {} });
  const text = await request({
    host, port: 80, path: '/rpc', method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) },
    timeout: 15000,
  }, body);
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch (error) {
    throw new Error(method + ': ' + text);
  }
  if (parsed.error) throw new Error(method + ': ' + JSON.stringify(parsed.error));
  return parsed.result;
}

async function findScript(name) {
  const list = await rpc('Script.List');
  return list.scripts.find((s) => s.name === name) || null;
}

async function putCode(id, code) {
  for (let at = 0; at < code.length; at += CHUNK) {
    await rpc('Script.PutCode', { id, code: code.slice(at, at + CHUNK), append: at > 0 });
  }
}

// Created, never started, left disabled. The journal finds it by name.
async function ensureAttic() {
  const existing = await findScript(ATTIC_NAME);
  if (existing) {
    const code = await rpc('Script.GetCode', { id: existing.id });
    console.log('attic is script ' + existing.id + ', holding ' + code.data.length +
      ' of ' + MAX_SCRIPT_BYTES + ' bytes');
    return existing.id;
  }
  const created = await rpc('Script.Create', { name: ATTIC_NAME });
  await putCode(created.id, ATTIC_HEADER);
  await rpc('Script.SetConfig', { id: created.id, config: { enable: false } });
  console.log('created the attic as script ' + created.id + ', disabled');
  return created.id;
}

async function status() {
  const info = await rpc('Shelly.GetDeviceInfo');
  console.log(info.model + ' ' + info.id + ', firmware ' + info.ver);

  const script = await findScript(SCRIPT_NAME);
  if (!script) {
    console.log('no ' + SCRIPT_NAME + ' on this device');
    return;
  }
  const state = await rpc('Script.GetStatus', { id: script.id });
  console.log('script ' + script.id + ' "' + script.name + '"' +
    ', running=' + state.running +
    (state.mem_used === undefined ? '' : ', mem_used=' + state.mem_used) +
    (state.mem_peak === undefined ? '' : ', peak=' + state.mem_peak) +
    (state.mem_free === undefined ? '' : ', free=' + state.mem_free) +
    (state.errors && state.errors.length ? ', errors=' + state.errors.join(',') : ''));
  if (state.error_msg) console.log(state.error_msg);

  const attic = await findScript(ATTIC_NAME);
  console.log(attic ? 'attic present as script ' + attic.id : 'no attic -- day pages would be dropped');

  if (!state.running) return;
  let index;
  try {
    index = JSON.parse(await request({
      host, port: 80, path: '/script/' + script.id + '/journal', method: 'GET', timeout: 15000,
    }));
  } catch (error) {
    console.log('the journal endpoint did not answer: ' + error.message);
    return;
  }

  const names = ['native', 'quarter hour', 'hour', 'day'];
  console.log('generation ' + index.generation + ', utc offset ' + index.utc_offset +
    ', attic ' + index.attic_bytes + ' bytes');
  index.tiers.forEach((tier, i) => {
    console.log('  ' + (names[i] || i).padEnd(13) +
      ' pages ' + (tier.pages.length ? tier.pages.join(',') : '-').padEnd(8) +
      ' pending ' + (tier.pending ? tier.pending[1] + 's/' + tier.pending[2] + 'mWh' : '-').padEnd(18) +
      ' open ' + (tier.open_bucket === null ? '-' : tier.open_bucket + ' (' + tier.open_mwh + ' mWh)'));
  });
  console.log('reaches to ' + index.archive_end +
    (index.archive_end ? ' (' + new Date(index.archive_end * 1000).toISOString() + ')' : ''));
  console.log('current ' + JSON.stringify(index.current));
}

async function dumpAttic() {
  const attic = await findScript(ATTIC_NAME);
  if (!attic) { console.log('no attic on this device'); return; }
  const code = await rpc('Script.GetCode', { id: attic.id });
  const pages = code.data.split('\n').filter((line) => /^\/\/[0-9]/.test(line));
  console.log(code.data.length + ' bytes, ' + pages.length + ' day page(s)');
  for (const line of pages) console.log(line.slice(2));
}

async function remove() {
  const script = await findScript(SCRIPT_NAME);
  if (script) {
    await rpc('Script.Stop', { id: script.id }).catch(() => {});
    await rpc('Script.Delete', { id: script.id });
    console.log('removed script ' + script.id + ' and its storage');
  } else {
    console.log('no journal to remove');
  }
  const attic = await findScript(ATTIC_NAME);
  if (!attic) return;
  if (!has('--with-attic')) {
    console.log('the attic is left in place; pass --with-attic to delete the history too');
    return;
  }
  await rpc('Script.Delete', { id: attic.id });
  console.log('removed the attic as well');
}

async function upload() {
  const source = fs.readFileSync(SOURCE, 'utf8');
  const code = has('--keep-comments') ? source : strip(source);
  const bytes = Buffer.byteLength(code);
  console.log('source ' + Buffer.byteLength(source) + ' bytes, uploading ' + bytes +
    ' of ' + MAX_SCRIPT_BYTES + ' allowed' +
    ' (' + Math.round((bytes / MAX_SCRIPT_BYTES) * 100) + '%)');
  if (bytes > MAX_SCRIPT_BYTES) throw new Error('the script is too large for the device');

  await ensureAttic();

  let script = await findScript(SCRIPT_NAME);
  if (!script) {
    const created = await rpc('Script.Create', { name: SCRIPT_NAME });
    script = { id: created.id };
    console.log('created script ' + script.id);
  } else {
    await rpc('Script.Stop', { id: script.id }).catch(() => {});
    console.log('replacing script ' + script.id);
  }

  await putCode(script.id, code);

  const readBack = await rpc('Script.GetCode', { id: script.id });
  if (readBack.data !== code) throw new Error('the code on the device does not match the file');
  console.log('uploaded and verified');

  // Survives a reboot on its own.
  await rpc('Script.SetConfig', { id: script.id, config: { enable: true } });

  if (!has('--no-start')) {
    await rpc('Script.Start', { id: script.id });
    console.log('started');
  }
}

async function main() {
  if (has('--status')) return status();
  if (has('--attic')) return dumpAttic();
  if (has('--remove')) return remove();
  await upload();
  await new Promise((resolve) => setTimeout(resolve, 2000));
  return status();
}

main().catch((error) => {
  console.error(String(error.message || error));
  process.exit(1);
});

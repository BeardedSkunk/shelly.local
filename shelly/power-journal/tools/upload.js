// Puts power-journal.js onto a plug over local RPC.
//
//   node tools/upload.js 192.168.178.21              upload and start
//   node tools/upload.js 192.168.178.21 --no-start   upload only
//   node tools/upload.js 192.168.178.21 --status     show what is there
//   node tools/upload.js 192.168.178.21 --remove     stop and delete it
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
const CHUNK = 1024;
const MAX_SCRIPT_BYTES = 20480;

const host = process.argv[2];
const flags = process.argv.slice(3);

if (!host) {
  console.error('usage: node tools/upload.js <host> [--no-start|--status|--remove]');
  process.exit(2);
}

function rpc(method, params) {
  const body = JSON.stringify({ id: 1, method, params: params || {} });
  const options = {
    host, port: 80, path: '/rpc', method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) },
    timeout: 15000,
  };
  return new Promise((resolve, reject) => {
    const request = http.request(options, (response) => {
      let text = '';
      response.on('data', (chunk) => { text += chunk; });
      response.on('end', () => {
        let parsed;
        try {
          parsed = JSON.parse(text);
        } catch (error) {
          reject(new Error(method + ': ' + text));
          return;
        }
        if (parsed.error) {
          reject(new Error(method + ': ' + JSON.stringify(parsed.error)));
          return;
        }
        resolve(parsed.result);
      });
    });
    request.on('timeout', () => request.destroy(new Error(method + ': timed out')));
    request.on('error', reject);
    request.end(body);
  });
}

async function findScript() {
  const list = await rpc('Script.List');
  return list.scripts.find((s) => s.name === SCRIPT_NAME) || null;
}

async function status() {
  const info = await rpc('Shelly.GetDeviceInfo');
  console.log(info.model + ' ' + info.id + ', firmware ' + info.ver);
  const script = await findScript();
  if (!script) {
    console.log('no ' + SCRIPT_NAME + ' on this device');
    return;
  }
  const state = await rpc('Script.GetStatus', { id: script.id });
  console.log('script ' + script.id + ' "' + script.name + '"' +
    ', running=' + state.running +
    (state.mem_free === undefined ? '' : ', mem_free=' + state.mem_free) +
    (state.errors && state.errors.length ? ', errors=' + state.errors.join(',') : ''));
  if (state.error_msg) console.log(state.error_msg);
  const sys = await rpc('Sys.GetStatus');
  console.log('kvs_rev=' + sys.kvs_rev + ', fs_free=' + sys.fs_free);
  try {
    const entry = await rpc('KVS.Get', { key: 'pj/current' });
    console.log('pj/current = ' + JSON.stringify(entry.value));
  } catch (error) {
    console.log('pj/current is not set yet');
  }
}

async function remove() {
  const script = await findScript();
  if (!script) { console.log('nothing to remove'); return; }
  await rpc('Script.Stop', { id: script.id }).catch(() => {});
  await rpc('Script.Delete', { id: script.id });
  console.log('removed script ' + script.id);
}

async function upload() {
  const source = fs.readFileSync(SOURCE, 'utf8');
  const code = flags.indexOf('--keep-comments') >= 0 ? source : strip(source);
  const bytes = Buffer.byteLength(code);
  console.log('source ' + Buffer.byteLength(source) + ' bytes, uploading ' + bytes +
    ' of ' + MAX_SCRIPT_BYTES + ' allowed' +
    ' (' + Math.round((bytes / MAX_SCRIPT_BYTES) * 100) + '%)');
  if (bytes > MAX_SCRIPT_BYTES) throw new Error('the script is too large for the device');

  let script = await findScript();
  if (!script) {
    const created = await rpc('Script.Create', { name: SCRIPT_NAME });
    script = { id: created.id };
    console.log('created script ' + script.id);
  } else {
    await rpc('Script.Stop', { id: script.id }).catch(() => {});
    console.log('replacing script ' + script.id);
  }

  for (let at = 0; at < code.length; at += CHUNK) {
    await rpc('Script.PutCode', {
      id: script.id,
      code: code.slice(at, at + CHUNK),
      append: at > 0,
    });
  }

  const readBack = await rpc('Script.GetCode', { id: script.id });
  if (readBack.data !== code) throw new Error('the code on the device does not match the file');
  console.log('uploaded and verified');

  // Survives a reboot on its own.
  await rpc('Script.SetConfig', { id: script.id, config: { enable: true } });

  if (flags.indexOf('--no-start') < 0) {
    await rpc('Script.Start', { id: script.id });
    console.log('started');
  }
}

async function main() {
  if (flags.indexOf('--status') >= 0) return status();
  if (flags.indexOf('--remove') >= 0) return remove();
  await upload();
  return status();
}

main().catch((error) => {
  console.error(String(error.message || error));
  process.exit(1);
});

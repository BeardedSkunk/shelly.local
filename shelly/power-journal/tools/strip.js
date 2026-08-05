// Shrinks the source for upload.
//
// A script may be 20480 bytes on the device and every byte of the file counts,
// comments and indentation included -- the plug stores it exactly as given.
// The commented source is more than twice the limit, so the repository keeps
// the explanation and the device gets the code.
//
// Three passes, in this order:
//
//   1. split each line into code and string literals, dropping comments
//   2. rename every top-level declaration to one or two characters
//   3. remove any space that is not holding two tokens apart
//
// It works line by line and never joins two lines. That is deliberate: line
// breaks are what automatic semicolon insertion relies on, so keeping them
// means nothing here can change what the code means by accident.
//
// This is not a general JavaScript minifier and does not try to be. It relies
// on the source using no regular expression literals, no multi-line strings,
// and no top-level name that is also used as a property -- all three are
// checked rather than assumed, and a violation throws instead of producing
// something subtly wrong. What it emits is what the acceptance tests run under
// PJ_STRIPPED=1, so a mistake in here fails the suite rather than reaching the
// plug.

'use strict';

// Words after which a space may be hiding an operand rather than an operator:
// "return -1" must not become "return-1".
const KEYWORDS = new Set([
  'return', 'typeof', 'case', 'new', 'delete', 'void', 'in', 'of', 'instanceof',
  'let', 'var', 'const', 'else', 'do', 'throw', 'yield', 'await',
]);

// main() is the entry point and selftest() is called by a line the harness
// appends after the source, so neither may lose its name.
const KEEP = new Set(['main', 'selftest']);

const identish = (c) => c !== undefined && /[A-Za-z0-9_$]/.test(c);

// Splits a line into alternating code and string pieces, so every later pass
// can ignore string contents entirely. Returns null if a quote is still open
// at the end of the line, which would mean an assumption here is wrong.
function pieces(line) {
  const out = [];
  let code = '';
  let i = 0;
  while (i < line.length) {
    const c = line[i];
    if (c === '"' || c === "'") {
      const quote = c;
      let text = c;
      i++;
      let closed = false;
      while (i < line.length) {
        if (line[i] === '\\') { text += line.slice(i, i + 2); i += 2; continue; }
        text += line[i];
        i++;
        if (line[i - 1] === quote) { closed = true; break; }
      }
      if (!closed) return null;
      out.push({ code, text });
      code = '';
      continue;
    }
    if (c === '/' && line[i + 1] === '/') break;
    code += c;
    i++;
  }
  out.push({ code, text: '' });
  return out;
}

// Every name declared at the top level: function declarations and let/const
// bindings that start a line. Those are the only ones that can be renamed
// without following scopes around.
function topLevelNames(source) {
  const names = [];
  for (const line of source.split('\n')) {
    let match = /^function\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*\(/.exec(line);
    if (match === null) match = /^(?:let|const|var)\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*=/.exec(line);
    if (match !== null && !KEEP.has(match[1])) names.push(match[1]);
  }
  return names;
}

// Short names, skipping anything that could collide with a keyword or with a
// name the source uses but does not declare.
function shortNames(count, taken) {
  const alphabet = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ';
  const out = [];
  for (let width = 1; out.length < count; width++) {
    const total = Math.pow(alphabet.length, width);
    for (let n = 0; n < total && out.length < count; n++) {
      let name = '';
      let rest = n;
      for (let k = 0; k < width; k++) { name = alphabet[rest % alphabet.length] + name; rest = Math.floor(rest / alphabet.length); }
      if (KEYWORDS.has(name) || taken.has(name)) continue;
      out.push(name);
    }
  }
  return out;
}

function buildRenames(source, codeOnly) {
  const names = topLevelNames(source);
  // A top-level name that is also used as a property would be renamed in one
  // place and not the other. Refuse rather than guess.
  for (const name of names) {
    if (new RegExp('\\.\\s*' + name + '\\b').test(codeOnly)) {
      throw new Error('top-level name "' + name + '" is also used as a property');
    }
  }
  const used = new Set(codeOnly.match(/[A-Za-z_$][A-Za-z0-9_$]*/g) || []);
  for (const name of names) used.delete(name);
  const shorts = shortNames(names.length, used);
  const map = new Map();
  names.forEach((name, i) => map.set(name, shorts[i]));
  return map;
}

function rename(code, map) {
  return code.replace(/[A-Za-z_$][A-Za-z0-9_$]*/g, (word, at, whole) => {
    // Leave property names alone: only "a.b" reaches here with a dot before it.
    if (at > 0 && whole[at - 1] === '.') return word;
    return map.has(word) ? map.get(word) : word;
  });
}

function squeeze(code, previousChar) {
  let out = '';
  let i = 0;
  while (i < code.length) {
    if (code[i] !== ' ' && code[i] !== '\t') { out += code[i]; i++; continue; }
    let j = i;
    while (j < code.length && (code[j] === ' ' || code[j] === '\t')) j++;
    const before = out.length > 0 ? out[out.length - 1] : previousChar;
    const word = /[A-Za-z0-9_$]+$/.exec(out);
    if (needsSpace(before, code[j], word === null ? '' : word[0])) out += ' ';
    i = j;
  }
  return out;
}

function needsSpace(before, after, word) {
  if (before === undefined || after === undefined) return false;
  if (identish(before) && identish(after)) return true;
  if (KEYWORDS.has(word) && (after === '-' || after === '+')) return true;
  if ((before === '+' || before === '-') && (after === '+' || after === '-')) return true;
  return false;
}

function strip(source, options) {
  if (/\\\s*$/m.test(source)) throw new Error('a line ends in a backslash; strip cannot join lines');

  const lines = source.split('\n').map((line) => {
    const parts = pieces(line);
    if (parts === null) throw new Error('unterminated string literal: ' + line.trim());
    return parts;
  });

  let map = null;
  if (!(options && options.keepNames)) {
    const codeOnly = lines.map((parts) => parts.map((p) => p.code).join(' ')).join('\n');
    map = buildRenames(source, codeOnly);
  }

  const kept = [];
  for (const parts of lines) {
    let line = '';
    for (const part of parts) {
      const code = map === null ? part.code : rename(part.code, map);
      line += squeeze(code, line.length > 0 ? line[line.length - 1] : undefined);
      line += part.text;
    }
    line = line.trim();
    // Blank lines have no meaning left once the comments are gone.
    if (line !== '') kept.push(line);
  }

  return kept.join('\n') + '\n';
}

module.exports = { strip };

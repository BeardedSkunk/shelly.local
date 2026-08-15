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

// Branches only the tests ever enter. The plug has 20480 bytes and every one of
// them is better spent on the journal; the suite still exercises these paths
// against the commented source, where they are untouched. A stripped build
// simply has no test mode, which is what CFG.test_mode being gone says.
const DEV_FLAGS = new Set(['CFG.test_mode']);

function dropDevCode(source) {
  const lines = source.split('\n');
  const kept = [];
  for (let i = 0; i < lines.length; i++) {
    const open = /^(\s*)if \((CFG\.[A-Za-z0-9_$]+)\) \{\s*$/.exec(lines[i]);
    if (open !== null && DEV_FLAGS.has(open[2])) {
      // Trailing whitespace included: the source may arrive with CRLF endings,
      // and a comparison that missed the close would swallow the rest of the file.
      const close = open[1] + '}';
      i++;
      while (i < lines.length && lines[i].replace(/\s+$/, '') !== close) i++;
      continue;
    }
    kept.push(lines[i]);
  }
  let out = kept.join('\n');
  // [^\n]* rather than .*, because a dot stops at the carriage return of a CRLF
  // source and the entry would survive -- leaving the flag defined but every
  // branch behind it gone, which is worse than either.
  out = out.replace(/^[ \t]*test_mode:[^\n]*\n/m, '');
  // Whatever still mentions the flag can only be a test that is now decided.
  return out.replace(/CFG\.test_mode/g, 'false');
}

// Names that live inside one top-level function: its parameters, whatever
// let/const/var introduces in its body, and the parameters of the callbacks
// nested in it. Each function is renamed on its own, so the short names are
// handed out again in every one of them -- which is where the saving is, since
// the bodies are where the long names are.
//
// A name is left alone when it is also an object key or a property anywhere in
// the function: renaming would rewrite the key and not the access, or the other
// way about. It is left alone too when the top level already declares it, since
// then it is a deliberate shadow and both halves have to keep their own meaning.
function localRenames(lines, topMap) {
  const maps = new Array(lines.length).fill(null);
  const codeOf = (i) => lines[i].map((p) => p.code).join(' ');
  const collect = (re, text, into) => {
    let m;
    while ((m = re.exec(text)) !== null) into.add(m[1]);
  };

  for (let start = 0; start < lines.length; start++) {
    const head = /^function\s+[A-Za-z_$][A-Za-z0-9_$]*\s*\(([^)]*)\)\s*\{/.exec(codeOf(start));
    if (head === null) continue;
    // The brace that closes the function sits in the first column; a nested one
    // is always indented. Trimming before the comparison would end the span at
    // the first inner block and leave the rest of the body renamed by nobody.
    let end = start + 1;
    while (end < lines.length && !/^\}/.test(codeOf(end))) end++;
    if (end >= lines.length) continue;

    let body = '';
    for (let i = start; i <= end; i++) body += codeOf(i) + '\n';

    const declared = new Set();
    for (const p of head[1].split(',')) if (p.trim() !== '') declared.add(p.trim());
    collect(/\b(?:let|const|var)\s+([A-Za-z_$][A-Za-z0-9_$]*)/g, body, declared);
    let m;
    const inner = /function\s*\(([^)]*)\)/g;
    while ((m = inner.exec(body)) !== null) {
      for (const p of m[1].split(',')) if (p.trim() !== '') declared.add(p.trim());
    }

    const unsafe = new Set();
    collect(/([A-Za-z_$][A-Za-z0-9_$]*)\s*:/g, body, unsafe);
    collect(/\.\s*([A-Za-z_$][A-Za-z0-9_$]*)/g, body, unsafe);

    const names = [...declared].filter(
      (n) => n.length > 1 && !unsafe.has(n) && !topMap.has(n) && !KEEP.has(n)
    );
    if (names.length === 0) continue;

    // Everything else the body says has to go on meaning what it means --
    // including the short names the top level is about to become.
    const seen = new Set(body.match(/[A-Za-z_$][A-Za-z0-9_$]*/g) || []);
    const used = new Set(seen);
    for (const n of names) used.delete(n);
    for (const [long, short] of topMap) if (seen.has(long)) used.add(short);

    const shorts = shortNames(names.length, used);
    const map = new Map();
    names.forEach((n, i) => map.set(n, shorts[i]));
    for (let i = start; i <= end; i++) maps[i] = map;
  }
  return maps;
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
  if (!(options && options.keepNames)) source = dropDevCode(source);

  const lines = source.split('\n').map((line) => {
    const parts = pieces(line);
    if (parts === null) throw new Error('unterminated string literal: ' + line.trim());
    return parts;
  });

  let map = null;
  let locals = null;
  if (!(options && options.keepNames)) {
    const codeOnly = lines.map((parts) => parts.map((p) => p.code).join(' ')).join('\n');
    map = buildRenames(source, codeOnly);
    locals = localRenames(lines, map);
  }

  const kept = [];
  for (let index = 0; index < lines.length; index++) {
    const parts = lines[index];
    // Inside a function both maps apply. They never disagree: a local that the
    // top level also declares was refused a short name above.
    const active = map === null ? null
      : (locals[index] === null ? map : new Map([...map, ...locals[index]]));
    let line = '';
    for (const part of parts) {
      const code = active === null ? part.code : rename(part.code, active);
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

// Removes the comments before upload.
//
// A script may be 20480 bytes on the device, and comments count towards that
// -- the file is stored on the plug exactly as written. The commented source
// is well past the limit, so the repository keeps the explanation and the
// device gets the code.
//
// Only whole lines whose first non-blank character is // are dropped, never a
// trailing comment on a line of code. That keeps this from having to
// understand string literals: a // inside a string is always preceded by
// something on the same line, so it is never at the start.

'use strict';

function strip(source) {
  const kept = [];
  let blanks = 0;
  for (const line of source.split('\n')) {
    const trimmed = line.replace(/\s+$/, '');
    if (/^\s*\/\//.test(trimmed)) continue;
    if (trimmed === '') {
      // Collapse the gaps the removed comments leave behind.
      if (++blanks > 1) continue;
    } else {
      blanks = 0;
    }
    kept.push(trimmed);
  }
  while (kept.length > 0 && kept[0] === '') kept.shift();
  return kept.join('\n') + '\n';
}

module.exports = { strip };

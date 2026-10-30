'use strict';
// Offline regression checks for the standalone playstyle builder in index.html.
// Run: node tools/playstyle-builder/verify.js
// Exit code 0 = every check passed; 1 = at least one failure (printed).
const fs = require('fs');
const vm = require('vm');
const path = require('path');
const crypto = require('crypto');
const assert = require('assert');

const ROOT = __dirname;
const HTML = path.join(ROOT, 'index.html');
const EXAMPLE = path.join(ROOT, 'example-export.pyla');

// Accepted generator output digests (sha256). A change here means the generator output
// changed and must be re-reviewed, not silently accepted.
const GOLDEN = {
  'balanced/stay': 'f43cee0befb2a4169193b58636e9a77611177f3b959078b756a07cf8bd797ec1',
  'balanced/follow': '18e0cb03fa8e4a0188bace3683e319804008861eb6a0fdbf9fbcb8c18c8744b8',
  'balanced/explore': 'baf70485a8c856d2e20af829b690fb168313d03985353ec4b867b6025b72054b',
  'kiter/stay': 'cdff8bde248fde1f9fbb4481e4db1a639cc1fe2e6f0fc4aa2d02e22bdab5fde4',
  'kiter/follow': '0e30ba15686a264291ea148e580f1a26fbf2bd4366dcdfef22f3928fb16db2ee',
  'kiter/explore': '231293bc49b37ebb6be56ba03f2f5284be3e1da126dd31a34f2f14167d06e027',
  'rusher/stay': 'd5c4e0171b56facb87742c10249e6bae24a00c6a7457ff36807911c8843c5fef',
  'rusher/follow': 'b91b7462782b2f2345c60eb11d511ee7d6f6d17866351602a5f62b12530ad567',
  'rusher/explore': 'a967463ae2def3abd64b16cd864ceabb58695b5e0709be22cc698211de132a70',
  'team/stay': '26623184c61186a88ca896fda1782c054c7fb94b9aeb286fb529cb8cd881f512',
  'team/follow': '34a23afc3502b47d1bb8c8eb3937505876658c2b9d5f77d96b4b68a2c244f9de',
  'team/explore': 'fb7e40d0d70ef2eba63bfc9a46a12a0ba6c47e4a93b3db36df75078b207a8ff4',
  'guard/stay': '6840bec8beeb5f507b5007f8193c60faadecb30b41445469d5fd6ca34ffc847c',
  'guard/follow': '4effa7c6e546ef1c6a0a07514fafed1197c46c4f3c91f5280fb6a66c985de7e7',
  'guard/explore': '61e1039a32a1e4b9a46cf314f1bbc778bbaf9204976d432ced3553c0dbf39fab',
};

const checks = [];
function check(name, fn) {
  try {
    fn();
    checks.push({ name, ok: true });
  } catch (e) {
    checks.push({ name, ok: false, error: e.message });
  }
}

const sha256 = (b) => crypto.createHash('sha256').update(b).digest('hex');
const html = fs.readFileSync(HTML, 'utf8');
const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map((m) => m[1]);

function loadGenerator() {
  const ctx = { module: { exports: {} } };
  vm.createContext(ctx);
  vm.runInContext(scripts[0], ctx);
  return ctx.module.exports;
}

const generator = scripts.length ? (() => { try { return loadGenerator(); } catch { return null; } })() : null;

check('single self-contained file, no external references', () => {
  assert(!/<script[^>]+\bsrc=/i.test(html), 'external script tag found');
  assert(!/<link[^>]+href=/i.test(html), 'external stylesheet/link found');
  assert(!/\b(https?:)?\/\/[a-z0-9.-]+\.[a-z]{2,}\//i.test(html.replace(/<svg[\s\S]*?<\/svg>/g, '')), 'absolute URL found');
  assert(html.includes("connect-src 'none'"), 'CSP must forbid network connections');
});

check('all inline scripts compile', () => {
  assert.strictEqual(scripts.length, 3, `expected 3 inline scripts, found ${scripts.length}`);
  for (const s of scripts) new vm.Script(s);
});

check('element ids are unique', () => {
  const ids = [...html.matchAll(/\bid="([^"]+)"/g)].map((m) => m[1]);
  assert.strictEqual(new Set(ids).size, ids.length, 'duplicate id found');
});

check('five presets are wired to the generator', () => {
  assert(generator, 'generator did not load');
  assert.deepStrictEqual(Object.keys(generator.PRESETS), ['balanced', 'kiter', 'rusher', 'team', 'guard']);
  const wired = [...html.matchAll(/data-preset="([a-z]+)"/g)].map((m) => m[1]);
  assert.deepStrictEqual(wired, Object.keys(generator.PRESETS), 'preset buttons do not match generator presets');
});

check('generator output matches accepted generation', () => {
  assert(generator, 'generator did not load');
  for (const [name, preset] of Object.entries(generator.PRESETS)) {
    for (const idle of ['stay', 'follow', 'explore']) {
      const key = `${name}/${idle}`;
      const output = generator.generate({ ...preset, name: preset.label, author: '', idle });
      assert.strictEqual(sha256(output), GOLDEN[key], `${key} output changed`);
    }
  }
});

check('export format stays .pyla compatible', () => {
  assert(generator, 'generator did not load');
  const output = generator.generate({ ...generator.PRESETS.balanced, name: 'Format check', author: '' });
  const lines = output.split('\n');
  const header = JSON.parse(lines[0]);
  assert.strictEqual(typeof header.name, 'string');
  assert.strictEqual(header.name, 'Format check');
  assert(Array.isArray(header.brawlers) && header.brawlers[0] === 'all');
  assert(lines[0].split('{').length === 2, 'metadata header must be a single line');
  assert(lines.slice(1).join('\n').trim().length > 0, 'body must not be empty');
  assert(!output.includes('\r'), 'export must use LF line endings');
  assert(!output.includes('\u0000'), 'export must not contain NUL bytes');
});

check('checked-in example export is reproducible from the generator', () => {
  assert(generator, 'generator did not load');
  const expected = generator.generate({
    ...generator.PRESETS.balanced,
    name: 'My Android Playstyle',
    author: '',
  });
  const actual = fs.readFileSync(EXAMPLE, 'utf8');
  assert.strictEqual(sha256(actual), sha256(expected), 'example-export.pyla no longer matches generated output');
});

check('invalid settings are rejected', () => {
  assert(generator, 'generator did not load');
  const base = { ...generator.PRESETS.balanced, name: 'Invalid', author: '' };
  const invalid = [
    { range: NaN }, { range: 111 }, { band: -1 }, { period: Infinity },
    { leash: 9 }, { idle: 'attack()' }, { name: '' }, { name: 'x\ny' },
  ];
  for (const patch of invalid) {
    assert.throws(() => generator.generate({ ...base, ...patch }), `accepted invalid config ${JSON.stringify(patch)}`);
  }
});

const failed = checks.filter((c) => !c.ok);
const report = {
  status: failed.length ? 'FAIL' : 'PASS',
  file: 'tools/playstyle-builder/index.html',
  sha256: sha256(html),
  checks: checks.length,
  failed: failed.length,
  details: checks,
};
console.log(JSON.stringify(report, null, 2));
process.exit(failed.length ? 1 : 0);

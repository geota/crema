#!/usr/bin/env node
// Fix security advisories in TRANSITIVE web dependencies with the smallest
// lockfile change that clears them. Dependabot's security updates fail on this
// pnpm 11 repo (`security_update_not_possible`); this automates what PRs #122
// and #133 did by hand. Run by .github/workflows/security-lockfile.yml.
//
//   cd web && node ../scripts/fix-transitive-advisories.mjs > summary.md
//
// What it does, in order:
//   1. `pnpm audit --json` over ALL deps (dev/build included).
//   2. For each vulnerable package line (`brace-expansion@2`, or `cookie@0.6`
//      for 0.x), picks the lowest patched release on that line that is older
//      than pnpm's minimumReleaseAge (CI's `--frozen-lockfile` rejects younger
//      ones). None old enough yet: skipped as "patched version too new, will
//      retry" (the next daily run picks it up).
//   3. Lockfile-only fix: adds a temporary scoped override per line
//      (`brace-expansion@2: '^2.1.7'`), `pnpm install --lockfile-only`, restores
//      pnpm-workspace.yaml and installs again. pnpm keeps the patched version
//      wherever the parents' ranges allow it, so only those packages move.
//   4. Re-audits. Anything still vulnerable has a parent that pins it: it gets a
//      persistent, commented override in pnpm-workspace.yaml (a package that
//      already has an override has that override bumped instead).
//   5. `pnpm install --frozen-lockfile` must pass, as in CI (all installs here
//      use --ignore-scripts: no dependency code runs).
//
// Prints a markdown summary on stdout (pnpm's own output goes to stderr).
// Exit: 0 = changes made, 2 = nothing to do, 1 = error (files restored).
//
// Why not `pnpm audit --fix`? pnpm 11's `--fix override` writes the same kind
// of scoped overrides, but leaves them in for good and adds every patched
// version to minimumReleaseAgeExclude (bypassing the release-age policy);
// `--fix update` also re-resolves unrelated packages (rollup, browserslist…).

import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';

const WS = 'pnpm-workspace.yaml';
const LOCK = 'pnpm-lock.yaml';
const SEVERITIES = ['critical', 'high', 'moderate', 'low', 'info'];
const DEFAULT_MIN_RELEASE_AGE = 24 * 60; // minutes; pnpm 11's default

// ── Minimal semver: advisory ranges are comparator sets joined by `||` ──────
function parse(v) {
  const m = /^v?(\d+)\.(\d+)\.(\d+)(?:-([\w.-]+))?(?:\+.*)?$/.exec(v.trim());
  if (!m) throw new Error(`unsupported version "${v}"`);
  return { n: [+m[1], +m[2], +m[3]], pre: m[4] ?? '' };
}
function compare(a, b) {
  const x = parse(a);
  const y = parse(b);
  for (let i = 0; i < 3; i++) if (x.n[i] !== y.n[i]) return x.n[i] - y.n[i];
  if (x.pre === y.pre) return 0;
  if (!x.pre || !y.pre) return x.pre ? -1 : 1;
  return x.pre < y.pre ? -1 : 1;
}
const OPS = { '<': (c) => c < 0, '<=': (c) => c <= 0, '>': (c) => c > 0, '>=': (c) => c >= 0, '=': (c) => c === 0 };
function satisfies(version, range) {
  const normalized = range.replace(/([<>=]+)\s+/g, '$1').replaceAll(',', ' ');
  return normalized.split('||').some((set) =>
    set.trim().split(/\s+/).every((c) => {
      if (c === '' || c === '*') return true;
      const [, op = '=', v] = /^(<=|>=|<|>|=)?(.+)$/.exec(c);
      return OPS[op](compare(version, v));
    }),
  );
}
// The caret line a version lives on: "2" for 2.1.0, "0.6" for 0.6.3.
const lineOf = (v) => {
  const [major, minor] = parse(v).n;
  return major > 0 ? `${major}` : `0.${minor}`;
};

// ── pnpm ─────────────────────────────────────────────────────────────────
function pnpm(args, capture = false) {
  const r = spawnSync('pnpm', args, {
    encoding: 'utf8',
    env: { ...process.env, CI: 'true' },
    stdio: ['ignore', capture ? 'pipe' : 2, 2],
    maxBuffer: 256 * 1024 * 1024,
  });
  if (r.error) throw r.error;
  return r;
}
// --ignore-scripts: no dependency code runs (resolution, the lockfile and the
// release-age check don't need it).
function install(...flags) {
  if (pnpm(['install', '--ignore-scripts', ...flags]).status !== 0) throw new Error(`pnpm install ${flags.join(' ')} failed`);
}
// One entry per vulnerable (advisory, installed version).
function audit() {
  const r = pnpm(['audit', '--json'], true);
  if (!r.stdout.trim()) throw new Error(`pnpm audit failed (exit ${r.status})`);
  const json = JSON.parse(r.stdout);
  if (json.error) throw new Error(`pnpm audit: ${json.error.summary ?? JSON.stringify(json.error)}`);
  if (r.status !== 0 && !Object.keys(json.advisories ?? {}).length) throw new Error(`pnpm audit failed (exit ${r.status})`);
  return Object.values(json.advisories ?? {}).flatMap((a) =>
    a.findings.map((f) => ({ ...a, name: a.module_name, version: f.version, paths: f.paths })),
  );
}
const times = new Map();
function publishTimes(name) {
  if (!times.has(name)) {
    const r = pnpm(['view', name, 'time', '--json'], true);
    if (r.status !== 0) throw new Error(`pnpm view ${name} time failed`);
    const { created, modified, ...byVersion } = JSON.parse(r.stdout);
    times.set(name, byVersion);
  }
  return times.get(name);
}

// ── pnpm-workspace.yaml / pnpm-lock.yaml (line-based, to keep comments) ─────
const quoteKey = (k) => (k.startsWith('@') ? `'${k}'` : k);
const escapeRe = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
function overrideBlock(text) {
  const lines = text.split('\n');
  const start = lines.findIndex((l) => /^overrides:\s*(#.*)?$/.test(l));
  let end = start + 1;
  if (start >= 0) while (end < lines.length && /^\s+\S/.test(lines[end])) end++;
  return { lines, start, end };
}
// { key, range, comment } → "  key: 'range' # comment", replacing an existing key.
function findOverride(text, key) {
  const { lines, start, end } = overrideBlock(text);
  if (start < 0) return null;
  const re = new RegExp(`^\\s+(['"]?)${escapeRe(key)}\\1:\\s*'?([^'#\\s]+)'?\\s*(?:#\\s*(.*))?$`);
  for (let i = start + 1; i < end; i++) {
    const m = re.exec(lines[i]);
    if (m) return { index: i, range: m[2], comment: m[3] ?? '' };
  }
  return null;
}
function setOverrides(text, entries) {
  let out = text;
  for (const { key, range, comment } of entries) {
    const line = `  ${quoteKey(key)}: '${range}'${comment ? ` # ${comment}` : ''}`;
    const found = findOverride(out, key);
    const { lines, start, end } = overrideBlock(out);
    if (found) lines[found.index] = line;
    else if (start >= 0) lines.splice(end, 0, line);
    else lines.splice(lines.at(-1) === '' ? -1 : lines.length, 0, 'overrides:', line);
    out = lines.join('\n');
  }
  return out;
}
// Versions of `name` in the lockfile's packages section.
function lockedVersions(lock, name) {
  const re = new RegExp(`^  '?${escapeRe(name)}@(\\d[^:'(]*)`, 'gm');
  return [...new Set([...lock.matchAll(re)].map((m) => m[1]))].sort(compare);
}

// ── Main ─────────────────────────────────────────────────────────────────
const original = { ws: readFileSync(WS, 'utf8'), lock: readFileSync(LOCK, 'utf8') };
const restore = () => {
  writeFileSync(WS, original.ws);
  writeFileSync(LOCK, original.lock);
};

function main() {
  const ageMatch = /^minimumReleaseAge:\s*(\d+)/m.exec(original.ws);
  const minAge = ageMatch ? Number(ageMatch[1]) : DEFAULT_MIN_RELEASE_AGE;
  const cutoff = Date.now() - minAge * 60_000;

  // 1. Audit, grouped per package line.
  const findings = audit();
  if (findings.length === 0) {
    console.log('No advisories in the web dependencies. Nothing to do.');
    return 2;
  }
  const groups = new Map();
  for (const f of findings) {
    const id = `${f.name}@${lineOf(f.version)}`;
    if (!groups.has(id)) groups.set(id, { id, name: f.name, line: lineOf(f.version), versions: new Set(), advisories: new Map() });
    groups.get(id).versions.add(f.version);
    groups.get(id).advisories.set(f.github_advisory_id ?? f.id, f);
  }

  // 2. Lowest patched, old-enough release per line.
  for (const g of groups.values()) {
    const advisories = [...g.advisories.values()];
    const newest = [...g.versions].sort(compare).at(-1);
    try {
      const patched = Object.entries(publishTimes(g.name)).filter(
        ([v, t]) =>
          !parse(v).pre && lineOf(v) === g.line && compare(v, newest) > 0 && t &&
          !advisories.some((a) => satisfies(v, a.vulnerable_versions)),
      );
      const mature = patched.filter(([, t]) => Date.parse(t) <= cutoff).map(([v]) => v).sort(compare);
      if (mature.length) g.floor = mature[0];
      else g.skip = patched.length ? 'patched version too new, will retry' : `no patched release on the ${g.line}.x line (needs a parent upgrade)`;
    } catch (err) {
      g.skip = `could not plan: ${err.message}`;
    }
    g.direct = advisories.some((a) => a.paths.some((p) => p.split('>').length === 2));
  }
  const todo = [...groups.values()].filter((g) => g.floor);

  if (todo.length) {
    // 3. Lockfile-only pass: temporary scoped overrides, then restore. Lines whose
    // package already has an override in pnpm-workspace.yaml skip straight to 4.
    const existingKey = (g) => [g.name, g.id].find((k) => findOverride(original.ws, k));
    const temp = todo.filter((g) => !existingKey(g));
    if (temp.length) {
      writeFileSync(WS, setOverrides(original.ws, temp.map((g) => ({ key: g.id, range: `^${g.floor}` }))));
      install('--lockfile-only');
      writeFileSync(WS, original.ws);
      install('--lockfile-only');
    }
    for (const g of todo) g.how = 'lockfile only';

    // 4. Still vulnerable: a parent pins it, so the override has to stay.
    const still = Map.groupBy(audit(), (f) => `${f.name}@${lineOf(f.version)}`);
    const persistent = [];
    for (const g of todo) {
      if (!still.has(g.id)) continue;
      if (g.direct && !existingKey(g)) {
        g.how = undefined;
        g.skip = 'direct dependency: bump its range in package.json';
        continue;
      }
      const otherLines = lockedVersions(readFileSync(LOCK, 'utf8'), g.name).some((v) => lineOf(v) !== g.line);
      const key = existingKey(g) ?? (otherLines ? g.id : g.name);
      const parents = [...new Set(still.get(g.id).flatMap((f) => f.paths.map((p) => p.split('>').at(-2))))];
      const n = g.advisories.size;
      const prefix = `<${g.floor} ${n === 1 ? 'advisory' : 'advisories'}`;
      const old = findOverride(original.ws, key)?.comment ?? '';
      const comment = /^<\S+ advisor(y|ies)/.test(old)
        ? old.replace(/^<\S+ advisor(y|ies)/, prefix)
        : `${prefix}, via ${parents.join(', ')}`;
      persistent.push({ key, range: `^${g.floor}`, comment });
      g.how = `override \`${key}: '^${g.floor}'\``;
    }
    if (persistent.length) {
      writeFileSync(WS, setOverrides(readFileSync(WS, 'utf8'), persistent));
      install('--lockfile-only');
    }
  }

  // 5. Final state: what is still open, and CI's install must pass.
  const remaining = new Set(audit().map((f) => `${f.name}@${lineOf(f.version)}`));
  const changed = readFileSync(LOCK, 'utf8') !== original.lock || readFileSync(WS, 'utf8') !== original.ws;
  if (changed) install('--frozen-lockfile');

  const lock = readFileSync(LOCK, 'utf8');
  const rows = [];
  for (const g of groups.values()) {
    const before = [...g.versions].sort(compare).join(', ');
    const after = lockedVersions(lock, g.name).filter((v) => lineOf(v) === g.line).join(', ');
    let how = g.skip ? `skipped: ${g.skip}` : g.how;
    if (!g.skip && remaining.has(g.id)) how = `**still vulnerable** after ${g.how}`;
    for (const a of g.advisories.values()) {
      rows.push({
        sev: a.severity,
        cells: [
          `[${a.github_advisory_id ?? a.id}](${a.url}) ${a.title.replaceAll('|', '\\|')}`,
          `\`${g.name}\``,
          a.severity,
          g.skip ? before : `${before} → ${after || 'removed'}`,
          how,
        ],
      });
    }
  }
  rows.sort((a, b) => SEVERITIES.indexOf(a.sev) - SEVERITIES.indexOf(b.sev) || a.cells[1].localeCompare(b.cells[1]));
  const skipped = [...groups.values()].filter((g) => g.skip);

  console.log('## Transitive web dependency advisories\n');
  console.log('| Advisory | Package | Severity | Version | Fix |');
  console.log('| --- | --- | --- | --- | --- |');
  for (const r of rows) console.log(`| ${r.cells.join(' | ')} |`);
  console.log(`\nRelease-age policy: ${minAge} min (pnpm minimumReleaseAge); newer patched versions are left for a later run.`);
  if (skipped.length) {
    console.log('\n### Skipped\n');
    for (const g of skipped) console.log(`- \`${g.id}\` (${[...g.versions].join(', ')}): ${g.skip}`);
  }
  console.log(changed ? '\n`pnpm install --frozen-lockfile` passes.' : '\nNo changes made.');
  return changed ? 0 : 2;
}

try {
  process.exitCode = main();
} catch (err) {
  restore();
  console.error(`fix-transitive-advisories: ${err.message} (pnpm-workspace.yaml and pnpm-lock.yaml restored)`);
  process.exitCode = 1;
}

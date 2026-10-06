/**
 * Target guard for the live verifier scripts.
 *
 * `smoke.mjs` and `verify-socket.mjs` both MUTATE the target: they register
 * users and create events. Pointing either at a shared development server
 * silently pollutes real data, so neither script is allowed to guess a target.
 *
 * Two independent guards:
 *   1. An explicit `VERIFY_BASE_URL` is REQUIRED. There is no
 *      `http://localhost:3000` default, because the conventional dev server is
 *      exactly the instance most likely to hold real data.
 *   2. The target is asked which database it serves (`GET /ping` -> databaseName)
 *      and that name must look like a scratch database.
 *
 * Escape hatch: `VERIFY_ALLOW_ANY_DB=1` skips guard 2 for environments where the
 * database name cannot be read (e.g. a proxy that strips the field).
 */

import { request } from 'node:http';
import { resolveDatabaseName } from '../../src/config/index.js';

/** Names that are never a valid verification target. */
const PROTECTED = new Set([
  'emh',
  'postgres',
  'template0',
  'template1',
  'postgis_topology'
]);

/** A database name is accepted only if it advertises itself as disposable. */
const SCRATCH_PATTERN = /(scratch|test|check|verify|verifier|tmp|temp|probe)/i;

/**
 * Classifies a database name reported by `GET /ping`.
 *
 * Pure and exported so the safety rule can be unit tested without a live server
 * - this is the decision that keeps a verifier away from real data.
 *
 * @param {string} name
 * @returns {'protected'|'scratch'|'unknown'}
 */
export function classifyDatabaseName(name) {
  if (typeof name !== 'string' || name.trim() === '') return 'unknown';
  const normalised = name.trim().toLowerCase();
  // Protected wins even if the name also looks disposable (`emh_test`), so a
  // renamed production database can never sneak through on its suffix.
  if (PROTECTED.has(normalised)) return 'protected';
  return SCRATCH_PATTERN.test(normalised) ? 'scratch' : 'unknown';
}

const USAGE = `This script mutates the target database, so it will not guess a URL.

Usage:
  VERIFY_BASE_URL=http://localhost:3100 npm run verify:rest
  VERIFY_BASE_URL=http://localhost:3100 npm run verify:socket

Boot a throwaway server on a scratch database first, for example:
  $env:EMBEDDED_PG_DATABASE='emh_scratch'
  $env:PORT=3100
  npm start

Optional:
  VERIFY_ALLOW_ANY_DB=1   skip the database-name guard (use only when the name
                          cannot be read from GET /ping)`;

/**
 * Fetches and parses `GET <base>/ping`.
 *
 * Uses a one-shot `http.request` with `agent: false` rather than global `fetch`:
 * the global fetch/undici dispatcher keeps the socket in a pool, and exiting
 * while that handle is closing trips a libuv assertion on Windows
 * ("Assertion failed: !(handle->flags & UV_HANDLE_CLOSING)"), turning a clean
 * refusal into a crash code. A dedicated non-pooled agent has nothing left open.
 *
 * @returns {Promise<object|null>}
 */
async function pingJson(base) {
  const url = new URL('/ping', base);
  return new Promise((resolve, reject) => {
    const req = request(
      {
        hostname: url.hostname,
        port: url.port || 80,
        path: url.pathname,
        method: 'GET',
        agent: false // no keep-alive: the socket closes with this response
      },
      (res) => {
        const chunks = [];
        res.on('data', (chunk) => chunks.push(chunk));
        res.on('error', reject);
        res.on('end', () => {
          try {
            resolve(JSON.parse(Buffer.concat(chunks).toString('utf8') || 'null'));
          } catch (err) {
            reject(new Error(`malformed /ping response: ${err.message}`));
          }
        });
      }
    );
    req.on('error', reject);
    req.setTimeout(5000, () => req.destroy(new Error('timed out')));
    req.end();
  });
}

/**
 * Resolves and validates the verification target.
 *
 * Prints the reason and returns `null` when the target is unusable, rather than
 * calling `process.exit()` from inside an async function - a hard exit while a
 * top-level await is still pending makes Node die with a Windows crash code
 * instead of the exit status the caller asked for. Callers do
 * `const BASE = await requireScratchTarget(); if (!BASE) process.exit(2);`
 *
 * @returns {Promise<string|null>} normalised base URL without a trailing slash,
 *   or null when the caller must abort
 */
export async function requireScratchTarget() {
  const raw = (process.env.VERIFY_BASE_URL || '').trim();
  if (!raw) {
    console.error(`[verify] VERIFY_BASE_URL is required.\n\n${USAGE}`);
    return null;
  }

  let base;
  try {
    base = new URL(raw).toString().replace(/\/$/, '');
  } catch {
    console.error(`[verify] VERIFY_BASE_URL is not a valid URL: ${raw}\n\n${USAGE}`);
    return null;
  }

  if (process.env.VERIFY_ALLOW_ANY_DB === '1') {
    console.warn(`[verify] VERIFY_ALLOW_ANY_DB=1 — database-name guard SKIPPED for ${base}`);
    return base;
  }

  let ping;
  try {
    ping = await pingJson(base);
  } catch (err) {
    console.error(`[verify] could not reach ${base}/ping: ${err.message}`);
    console.error(`[verify] is the server running? A verification target must be a scratch instance.\n\n${USAGE}`);
    return null;
  }

  const name = ping?.databaseName;
  const verdict = classifyDatabaseName(name);

  if (verdict === 'unknown' && !name) {
    console.error(
      `[verify] ${base} did not report a databaseName on /ping.\n` +
      '[verify] Refusing to run: the target cannot be proven disposable.\n' +
      '[verify] A server started before this check existed also lands here.\n' +
      '[verify] Set VERIFY_ALLOW_ANY_DB=1 to override.'
    );
    return null;
  }

  if (verdict === 'protected') {
    console.error(
      `[verify] REFUSING: ${base} is serving the protected database "${name}".\n` +
      '[verify] Start a server on a scratch database instead.'
    );
    return null;
  }

  if (verdict === 'unknown') {
    console.error(
      `[verify] REFUSING: database "${name}" does not look like a scratch database\n` +
      '[verify] (name must contain one of: scratch, test, check, verify, tmp, temp, probe).\n' +
      '[verify] Set VERIFY_ALLOW_ANY_DB=1 to override.'
    );
    return null;
  }

  const ours = name === resolveDatabaseName();
  console.log(`[verify] target ${base} -> scratch database "${name}"${ours ? ' (local cluster)' : ''}`);
  return base;
}
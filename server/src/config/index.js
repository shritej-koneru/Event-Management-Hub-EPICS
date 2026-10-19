import 'dotenv/config';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

/** Absolute path to the server/ root. */
export const ROOT = path.resolve(__dirname, '..', '..');

const env = process.env;

const isProd = env.NODE_ENV === 'production';
const isTest = env.NODE_ENV === 'test';

/**
 * Reads a required variable. Throws in every environment when missing so a
 * misconfigured deployment fails loudly instead of falling back to a
 * secret that is baked into the source.
 */
function requiredSecret(name) {
  const value = (env[name] || '').trim();
  if (value) return value;
  throw new Error(
    `Missing required environment variable ${name}. ` +
      `Copy .env.example to .env (npm run setup) and set it.`
  );
}

function bool(name, fallback) {
  const value = (env[name] || '').trim().toLowerCase();
  if (!value) return fallback;
  return value === 'true' || value === '1' || value === 'yes';
}

function int(name, fallback) {
  const value = Number.parseInt((env[name] || '').trim(), 10);
  return Number.isFinite(value) ? value : fallback;
}

const databaseUrlFromEnv = (env.DATABASE_URL || '').trim();

/**
 * Whether the known demo account should be created at seed time.
 *
 * The demo account uses a well-known password published in this repository, so
 * it must never exist in production unless an operator explicitly opts in.
 * In production the default is `false`; everywhere else it stays `true` so the
 * documented local/dev/test workflow keeps working unchanged.
 *
 * Exported as a pure function so the policy can be unit tested without
 * mutating `process.env`.
 */
export function resolveSeedDemoUser(envLike = {}, prod = false) {
  const raw = String(envLike.SEED_DEMO_USER ?? '').trim().toLowerCase();
  if (raw === 'true' || raw === '1' || raw === 'yes') return true;
  if (raw === 'false' || raw === '0' || raw === 'no') return false;
  return !prod;
}

export const config = {
  env: env.NODE_ENV || 'development',
  isProd,
  isTest,

  port: int('PORT', 3000),

  /** Base URL the Android client is expected to use. Informational. */
  apiBaseUrl: (env.API_BASE_URL || '').trim(),

  /** Bumped whenever contract/API-CONTRACT.md changes shape. */
  // 1.1.0 added GET /events/:id/details, GET /chat/announcements?eventId=, and
  // the optional announcement eventId field. Additive only — no existing
  // response shape changed.
  // 1.2.0 added GET /navigation/map and GET /navigation/venues/:venueId/map
  // (stored canvas layout + edges). Additive only — no existing shape changed.
  contractVersion: '1.2.0',

  jwt: {
    secret: isTest && !(env.JWT_SECRET || '').trim() ? 'test-only-secret' : requiredSecret('JWT_SECRET'),
    expiresIn: (env.JWT_EXPIRES_IN || '').trim() || '7d'
  },

  bcryptRounds: int('BCRYPT_ROUNDS', 10),

  databaseUrl: databaseUrlFromEnv,

  /**
   * When DATABASE_URL is empty we boot a bundled local PostgreSQL cluster so
   * the project runs with zero external services. Never used in production.
   */
  useEmbeddedPg: bool('USE_EMBEDDED_PG', !databaseUrlFromEnv) && !isProd,

  /**
   * Creates the shared demo user (and its bookmarks/search history) during
   * seeding. Off by default in production - see resolveSeedDemoUser().
   */
  seedDemoUser: resolveSeedDemoUser(env, isProd),

  /** Comma-separated CORS allowlist. Empty means "reflect the request origin". */
  corsOrigins: (env.CORS_ORIGINS || '')
    .split(',')
    .map((origin) => origin.trim())
    .filter(Boolean),

  embeddedPg: {
    port: int('EMBEDDED_PG_PORT', 5433),
    user: (env.EMBEDDED_PG_USER || '').trim() || 'emh',
    password: (env.EMBEDDED_PG_PASSWORD || '').trim() || 'emh_local_dev',
    database: (env.EMBEDDED_PG_DATABASE || '').trim() || 'emh',
    dir: ((env.EMBEDDED_PG_DIR || '').trim() || path.join(ROOT, 'data', 'pg'))
  },

  logLevel: (env.LOG_LEVEL || '').trim() || 'info'
};

/**
 * Effective connection string. Derived from the embedded cluster settings when
 * DATABASE_URL is not provided.
 */
export function resolveDatabaseUrl() {
  if (config.databaseUrl) return config.databaseUrl;
  const { user, password, port, database } = config.embeddedPg;
  return `postgres://${encodeURIComponent(user)}:${encodeURIComponent(password)}@127.0.0.1:${port}/${encodeURIComponent(database)}`;
}

/**
 * Name of the database this process serves, with credentials stripped.
 *
 * Lets the live verifier scripts confirm they were pointed at a scratch database
 * rather than a real one. Never expose this in production.
 *
 * @returns {string} e.g. "emh_scratch", or '' when it cannot be determined
 */
export function resolveDatabaseName() {
  try {
    // Take the path segment only; the query string and credentials are irrelevant.
    const { pathname } = new URL(resolveDatabaseUrl());
    return decodeURIComponent(pathname.replace(/^\//, ''));
  } catch {
    return '';
  }
}
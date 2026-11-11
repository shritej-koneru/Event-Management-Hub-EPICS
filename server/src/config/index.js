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

/**
 * Whether the website may create manager (organizer) accounts through POST /api/auth/register-manager.
 *
 * Anyone who can reach the server could use that endpoint to become a manager (post announcements, read every
 * help-desk ticket), so in production it is OFF unless an operator sets ALLOW_MANAGER_SIGNUP=true on purpose, and
 * should be switched off again once the managers exist. Everywhere else (local development, tests) it defaults to on.
 * The app's student sign-up (POST /api/auth/register) is unaffected and can never create a manager.
 *
 * Exported as a pure function so the policy can be unit tested without mutating `process.env`.
 */
export function resolveManagerSignup(envLike = {}, prod = false) {
  const raw = String(envLike.ALLOW_MANAGER_SIGNUP ?? '').trim().toLowerCase();
  if (raw === 'true' || raw === '1' || raw === 'yes') return true;
  if (raw === 'false' || raw === '0' || raw === 'no') return false;
  return !prod;
}

/** Defaults for the AI provider (NVIDIA's OpenAI-compatible chat-completions API). All overridable by env. */
export const AI_DEFAULTS = Object.freeze({
  baseUrl: 'https://integrate.api.nvidia.com/v1',
  chatModel: 'nvidia/nemotron-3.5-bolt-e4b',
  translateModel: 'nvidia/riva-translate-4b-instruct-v2',
  timeoutMs: 15_000,
  maxInputChars: 2_000,
  maxOutputTokens: 400,
  ratePerMinute: 20
});

/**
 * AI provider settings from the environment.
 *
 * The API key is read ONLY from NVIDIA_API_KEY (never from code or the client). With no key, or AI_ENABLED=false,
 * `enabled` is false and every AI endpoint answers 503 AI_DISABLED so the apps fall back to their non-AI behaviour.
 * The base URL must be https (http only for localhost, which the tests use); anything else falls back to the default.
 *
 * Exported as a pure function so the policy can be unit tested without mutating `process.env`.
 */
export function resolveAiConfig(envLike = {}) {
  const text = (name, fallback) => String(envLike[name] ?? '').trim() || fallback;
  const number = (name, fallback, min, max) => {
    const n = Number.parseInt(String(envLike[name] ?? '').trim(), 10);
    return Number.isFinite(n) && n >= min && n <= max ? n : fallback;
  };
  const apiKey = String(envLike.NVIDIA_API_KEY ?? '').trim();
  const switchedOff = ['false', '0', 'no'].includes(String(envLike.AI_ENABLED ?? '').trim().toLowerCase());

  let baseUrl = text('NVIDIA_BASE_URL', AI_DEFAULTS.baseUrl).replace(/\/+$/, '');
  try {
    const u = new URL(baseUrl);
    const local = ['localhost', '127.0.0.1'].includes(u.hostname);
    if (!(u.protocol === 'https:' || (u.protocol === 'http:' && local))) baseUrl = AI_DEFAULTS.baseUrl;
  } catch {
    baseUrl = AI_DEFAULTS.baseUrl;
  }

  return {
    apiKey,
    enabled: apiKey.length > 0 && !switchedOff,
    baseUrl,
    chatModel: text('NVIDIA_CHAT_MODEL', AI_DEFAULTS.chatModel),
    translateModel: text('NVIDIA_TRANSLATE_MODEL', AI_DEFAULTS.translateModel),
    timeoutMs: number('AI_TIMEOUT_MS', AI_DEFAULTS.timeoutMs, 1_000, 60_000),
    maxInputChars: number('AI_MAX_INPUT_CHARS', AI_DEFAULTS.maxInputChars, 100, 20_000),
    maxOutputTokens: number('AI_MAX_OUTPUT_TOKENS', AI_DEFAULTS.maxOutputTokens, 16, 4_000),
    ratePerMinute: number('AI_RATE_PER_MINUTE', AI_DEFAULTS.ratePerMinute, 1, 600)
  };
}

/**
 * What the server does to the database when it boots.
 *
 * Outside production nothing changes: migrations are applied and an empty database is seeded, so
 * `npm run dev` and the tests keep working with zero setup. In production a restart must NOT touch the
 * schema or insert data on its own, so both are off by default:
 *   - MIGRATE_ON_BOOT=true  opts in to applying pending migrations at boot (default: false in production).
 *     With it off the server still refuses to start if migrations are pending (see server.js), so a
 *     failed or forgotten `npm run db:migrate` is a failed deploy, not a half-migrated database.
 *   - Seeding an empty database at boot is development-only. SEED_ON_BOOT=true (see `seedOnBoot`)
 *     remains an explicit opt-in that forces the idempotent seed.
 *
 * Exported as a pure function so the policy can be unit tested without mutating `process.env`.
 *
 * @returns {{ migrate: boolean, seedIfEmpty: boolean }}
 */
export function resolveBootPlan(envLike = {}, prod = false) {
  const raw = String(envLike.MIGRATE_ON_BOOT ?? '').trim().toLowerCase();
  let migrate = !prod;
  if (raw === 'true' || raw === '1' || raw === 'yes') migrate = true;
  else if (raw === 'false' || raw === '0' || raw === 'no') migrate = false;
  return { migrate, seedIfEmpty: !prod };
}

/** Minimum JWT_SECRET length enforced in production. */
export const MIN_PROD_SECRET_LENGTH = 32;

function resolveJwtSecret() {
  if (isTest && !(env.JWT_SECRET || '').trim()) return 'test-only-secret';
  const secret = requiredSecret('JWT_SECRET');
  if (isProd && secret.length < MIN_PROD_SECRET_LENGTH) {
    throw new Error(
      `JWT_SECRET must be at least ${MIN_PROD_SECRET_LENGTH} characters when NODE_ENV=production.`
    );
  }
  return secret;
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
  // 1.3.0 added users.role (auth responses), role-gated POST /events,
  // PUT/DELETE /events/:id, chat auth/room delivery/clientId, and 401 for an
  // invalid bearer token on optional-auth routes.
  // 1.4.0 added the /api/manage/* event-manager API, the `map:updated` socket
  // event, announcements.sent_by (migration 007) and the new event
  // auto-announcement wording.
  // 1.5.0 added campus geo bounds + per-venue lat/lng/kind/description/geoPlaced
  // (migration 008), POST/PUT /manage/venues by lat/lng with connectToNearest,
  // repeatable manager announcements, and admin-only /manage/users.
  // 1.6.0 added GET /events/:id/announcements, the /support attendee help
  // desk, /manage/support/* and the user:/managers: socket rooms + support:*
  // events (migration 009).
  contractVersion: '1.6.0',

  jwt: {
    secret: resolveJwtSecret(),
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

  /** Lets the website create organizer accounts. Off by default in production - see resolveManagerSignup(). */
  managerSignupEnabled: resolveManagerSignup(env, isProd),

  /** AI provider settings. `ai.apiKey` must never be logged, returned or sent to a client. */
  ai: resolveAiConfig(env),

  /** Force the idempotent seed on every boot. Explicit opt-in only; never needed in normal operation. */
  seedOnBoot: bool('SEED_ON_BOOT', false),

  /** Boot-time database actions (migrate / seed-if-empty). Production defaults to neither; see resolveBootPlan. */
  boot: resolveBootPlan(env, isProd),

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
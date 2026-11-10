import fs from 'node:fs';
import path from 'node:path';
import express from 'express';
import cors from 'cors';
import helmet from 'helmet';
import { config, resolveDatabaseName, ROOT } from './config/index.js';
import apiRoutes from './routes/index.js';
import { notFoundHandler, errorHandler } from './middleware/errorHandler.js';
import { apiRateLimit } from './middleware/rateLimit.js';
import { venues as liveVenues, ensureRegistryLoaded } from './navigation/registry.js';
import { healthcheck } from './db/pool.js';

/**
 * Resolves the CORS allowlist.
 *
 * Production must set CORS_ORIGINS explicitly; reflecting arbitrary origins
 * while allowing credentials lets any site issue authenticated requests.
 * With no allowlist configured (development default) the request origin is
 * reflected, which is what the previous behaviour was.
 *
 * Native Android clients send no Origin header, so they are unaffected either
 * way.
 */
export function corsOriginDelegate() {
  const allowlist = config.corsOrigins;
  if (!allowlist.length) {
    if (config.isProd) return false; // no configured allowlist => no browser access
    return true;
  }
  return (origin, callback) => {
    if (!origin || allowlist.includes(origin)) return callback(null, true);
    return callback(new Error('Origin not allowed by CORS policy'));
  };
}

/** True when the TCP peer is the local machine. */
function isLoopback(req) {
  const addr = req.socket?.remoteAddress || '';
  return addr === '::1' || addr === '127.0.0.1' || addr === '::ffff:127.0.0.1';
}

/** Built web frontend (Vite output). Lives next to server/ in the repository; built at deploy time, not committed. */
export const FRONTEND_DIST = path.resolve(ROOT, '..', 'frontend', 'dist');

/** Paths owned by the API, health check and Socket.IO: never answered with the frontend page. */
const NON_SPA_PREFIX = /^\/(api|ping|socket\.io)(\/|$)/;

/**
 * Serves the built frontend from the same origin as the API.
 *
 * Does nothing when `dist/index.html` is absent (Android-only use, dev, tests), so the API behaves exactly as before.
 * Hashed assets are cached for a year; index.html is never cached so a redeploy is picked up. Browser routes
 * (`/events/123`) get index.html; /api, /ping and /socket.io never do, and a missing file (a path with an extension)
 * is a real 404 instead of an HTML page pretending to be a script.
 */
export function mountFrontend(app, dir = FRONTEND_DIST) {
  const indexFile = path.join(dir, 'index.html');
  if (!fs.existsSync(indexFile)) return false;

  app.use(
    express.static(dir, {
      index: false,
      setHeaders(res, filePath) {
        const hashed = filePath.startsWith(path.join(dir, 'assets') + path.sep);
        res.setHeader('Cache-Control', hashed ? 'public, max-age=31536000, immutable' : 'no-cache');
      }
    })
  );
  app.get(/.*/, (req, res, next) => {
    if (NON_SPA_PREFIX.test(req.path) || path.extname(req.path)) return next();
    if (!req.accepts('html')) return next();
    res.setHeader('Cache-Control', 'no-cache');
    return res.sendFile(indexFile);
  });
  return true;
}

/**
 * Builds the Express application.
 *
 * Kept separate from src/server.js so tests can mount it with supertest
 * without binding a TCP port.
 *
 * @param {{ broadcastEvent?: Function, frontendDir?: string }} [deps] broadcastEvent is injected after Socket.IO boots
 */
export function createApp(deps = {}) {
  const app = express();

  app.disable('x-powered-by');

  // Security headers. `crossOriginResourcePolicy: false` keeps the resource
  // policy from blocking the Android client and browser preflights.
  // The only CSP change from helmet's defaults: the web map draws OpenStreetMap tiles as images.
  app.use(
    helmet({
      crossOriginResourcePolicy: false,
      contentSecurityPolicy: {
        useDefaults: true,
        directives: { 'img-src': ["'self'", 'data:', 'https://*.tile.openstreetmap.org'] }
      }
    })
  );

  app.set('trust proxy', config.isProd ? 1 : false);
  app.use(cors({ origin: corsOriginDelegate(), credentials: true }));
  app.use(express.json({ limit: '256kb' }));

  // Broad backstop; credential endpoints add their own stricter limiter.
  app.use('/api', apiRateLimit);

  // Set by server.js once the Socket.IO instance exists.
  app.locals.broadcastEvent = deps.broadcastEvent ?? null;

  /**
   * GET /ping — liveness + contract version + the canonical venue catalogue.
   * Unauthenticated so the Android client and uptime checks can call it freely.
   * Deliberately outside /api so it escapes the API rate limiter.
   */
  app.get('/ping', async (req, res, next) => {
    try {
      res.json({
        status: 'ok',
        ok: true,
        service: 'emh-server',
        contractVersion: config.contractVersion,
        contract: 'contract/API-CONTRACT.md',
        database: await healthcheck() ? 'connected' : 'unavailable',
        // The database name is only disclosed to loopback callers outside
        // production (the live verifier scripts run on the same host and use it
        // to refuse real databases). LAN / emulator / internet clients never see it.
        ...(!config.isProd && isLoopback(req) ? { databaseName: resolveDatabaseName() } : {}),
        venues: liveVenues().map((venue) => ({ id: venue.id, name: venue.name }))
      });
    } catch (err) {
      next(err);
    }
  });

  // The DB is the venue/graph source of truth: load the cached registry once
  // before the first request (server.js also loads it at boot).
  const registryReady = async (req, res, next) => {
    try {
      await ensureRegistryLoaded();
    } catch {
      // Keep serving the cached (seed) registry; the next request retries.
    }
    next();
  };
  app.use('/api', registryReady, apiRoutes);

  // After every API route, before the JSON 404, so unmatched /api paths still get the API's 404.
  mountFrontend(app, deps.frontendDir);

  app.use(notFoundHandler);
  app.use(errorHandler);

  return app;
}

export default createApp;
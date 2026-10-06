import express from 'express';
import cors from 'cors';
import helmet from 'helmet';
import { config, resolveDatabaseName } from './config/index.js';
import apiRoutes from './routes/index.js';
import { notFoundHandler, errorHandler } from './middleware/errorHandler.js';
import { apiRateLimit } from './middleware/rateLimit.js';
import { VENUES } from './navigation/venues.js';
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
function corsOriginDelegate() {
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

/**
 * Builds the Express application.
 *
 * Kept separate from src/server.js so tests can mount it with supertest
 * without binding a TCP port.
 *
 * @param {{ broadcastEvent?: Function }} [deps] injected after Socket.IO boots
 */
export function createApp(deps = {}) {
  const app = express();

  app.disable('x-powered-by');

  // Security headers. `crossOriginResourcePolicy: false` keeps the resource
  // policy from blocking the Android client and browser preflights.
  app.use(helmet({ crossOriginResourcePolicy: false }));

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
        // Which database this instance is actually serving, so the live verifier
        // scripts can refuse to run against a real development database. Omitted
        // in production, where naming it would be needless disclosure.
        ...(config.isProd ? {} : { databaseName: resolveDatabaseName() }),
        venues: VENUES.map((venue) => ({ id: venue.id, name: venue.name }))
      });
    } catch (err) {
      next(err);
    }
  });

  app.use('/api', apiRoutes);

  app.use(notFoundHandler);
  app.use(errorHandler);

  return app;
}

export default createApp;
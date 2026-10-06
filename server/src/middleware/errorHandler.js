import { config } from '../config/index.js';
import { ApiError, toApiError } from '../utils/errors.js';

/** Terminal 404 for unmatched routes. */
export function notFoundHandler(req, res, next) {
  next(ApiError.notFound(`No route ${req.method} ${req.originalUrl}`, 'NOT_FOUND'));
}

/**
 * Centralized error handler. Every failure leaves the server as
 * `{ error, code }` per contract/API-CONTRACT.md §7.
 *
 * Stack traces and internal messages are logged but never serialized in
 * production responses.
 */
// eslint-disable-next-line no-unused-vars -- Express identifies error handlers by arity.
export function errorHandler(err, req, res, next) {
  const apiError = toApiError(err);

  if (apiError.status >= 500) {
    console.error(`[error] ${req.method} ${req.originalUrl}`, err);
  } else {
    console.warn(`[warn] ${req.method} ${req.originalUrl} -> ${apiError.status} ${apiError.code}: ${apiError.message}`);
  }

  const body = { error: publicMessage(apiError, err), code: apiError.code };
  if (apiError.details && !config.isProd) body.details = apiError.details;

  if (res.headersSent) return res.end();
  return res.status(apiError.status).json(body);
}

function publicMessage(apiError, original) {
  // Unknown 500s must not leak driver internals.
  if (apiError.status === 500 && !(original instanceof ApiError)) {
    return 'Internal server error';
  }
  return apiError.message;
}
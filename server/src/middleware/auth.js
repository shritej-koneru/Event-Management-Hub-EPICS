import { ApiError } from '../utils/errors.js';
import { bearerToken, verifyToken } from '../auth/jwt.js';

/**
 * Rejects the request with 401 unless a valid JWT is present.
 * On success `req.auth = { uid, email, name }`.
 */
export function requireAuth(req, res, next) {
  const token = bearerToken(req);
  if (!token) {
    return next(ApiError.unauthorized('Missing bearer token', 'AUTH_REQUIRED'));
  }
  try {
    req.auth = verifyToken(token);
    return next();
  } catch (err) {
    const message =
      err?.name === 'TokenExpiredError' ? 'Token expired' : 'Invalid token';
    return next(ApiError.unauthorized(message, 'INVALID_TOKEN'));
  }
}

/**
 * Attaches `req.auth` when a valid token is supplied and silently ignores
 * anything else. Used by optional-auth endpoints (GET /events,
 * POST /ai/search, GET /chat/announcements) so anonymous callers still work.
 */
export function optionalAuth(req, res, next) {
  const token = bearerToken(req);
  if (token) {
    try {
      req.auth = verifyToken(token);
    } catch {
      /* anonymous */
    }
  }
  return next();
}
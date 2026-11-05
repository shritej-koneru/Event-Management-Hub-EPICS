import { ApiError } from '../utils/errors.js';
import { bearerToken, verifyToken } from '../auth/jwt.js';
import { findUserById } from '../models/userModel.js';

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
 * Requires `req.auth` (run after requireAuth) to carry one of `roles`.
 * The role is read from the database, not the token, so a role change takes
 * effect immediately. 403 FORBIDDEN otherwise.
 */
export function requireRole(...roles) {
  return async (req, res, next) => {
    try {
      const user = req.auth ? await findUserById(req.auth.uid) : null;
      if (!user) return next(ApiError.unauthorized('User not found', 'INVALID_TOKEN'));
      req.user = user;
      if (!roles.includes(user.role)) {
        return next(ApiError.forbidden('Your account is not allowed to perform this action', 'FORBIDDEN'));
      }
      return next();
    } catch (err) {
      return next(err);
    }
  };
}

/**
 * Attaches `req.auth` when a valid token is supplied. No Authorization header
 * means anonymous; a header that IS present but invalid/expired is a 401, so a
 * client with a stale token learns to re-authenticate instead of silently
 * being downgraded.
 */
export function optionalAuth(req, res, next) {
  const token = bearerToken(req);
  if (token) {
    try {
      req.auth = verifyToken(token);
    } catch (err) {
      const message =
        err?.name === 'TokenExpiredError' ? 'Token expired' : 'Invalid token';
      return next(ApiError.unauthorized(message, 'INVALID_TOKEN'));
    }
  }
  return next();
}
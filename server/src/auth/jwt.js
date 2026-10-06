import jwt from 'jsonwebtoken';
import { config } from '../config/index.js';

/**
 * Signs a session token for a user row.
 * The payload carries only non-sensitive identity claims — the `uid` claim is
 * what every authenticated handler uses to resolve the current user, so no
 * user id is ever hard-coded in an API operation.
 */
export function signToken(user) {
  return jwt.sign(
    { uid: user.id, email: user.email, name: user.name },
    config.jwt.secret,
    { expiresIn: config.jwt.expiresIn }
  );
}

/** @returns the decoded payload, or throws on an invalid/expired token. */
export function verifyToken(token) {
  return jwt.verify(token, config.jwt.secret);
}

/** Extracts a bearer token from an Authorization header, if present. */
export function bearerToken(req) {
  const header = req.headers?.authorization || '';
  const match = /^Bearer\s+(.+)$/i.exec(header.trim());
  return match ? match[1].trim() : null;
}
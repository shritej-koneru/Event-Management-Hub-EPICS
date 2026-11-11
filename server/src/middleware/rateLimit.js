import rateLimit, { ipKeyGenerator } from 'express-rate-limit';
import { config } from '../config/index.js';

/**
 * Rate limiting.
 *
 * Guards the credential endpoints against password spraying and account
 * enumeration, with a broader backstop across the API. Limits are keyed by
 * client IP.
 *
 * `ipKeyGenerator` is used so IPv6 clients are bucketed by their /64 subnet
 * rather than their exact address — otherwise a single host with a routed
 * prefix could rotate addresses and bypass the limit entirely.
 */
export function keyGenerator(req) {
  return ipKeyGenerator(req.ip ?? req.socket?.remoteAddress ?? '');
}

/**
 * Builds a limiter.
 *
 * Exported as a factory so tests can mount a deliberately tight limiter
 * instead of exhausting the production budget.
 *
 * `skipSuccessfulRequests` is opt-in: only the auth limiter uses it, so a
 * legitimate user who logs in repeatedly is never locked out by their own
 * successes. The general API limiter counts every request.
 */
export function buildRateLimiter({
  windowMs,
  max,
  code = 'RATE_LIMITED',
  message = 'Too many requests',
  skipSuccessfulRequests = false,
  key = keyGenerator
}) {
  return rateLimit({
    windowMs,
    limit: max,
    keyGenerator: key,
    standardHeaders: 'draft-7',
    legacyHeaders: false,
    skipSuccessfulRequests,
    // Same envelope as the central error handler and contract §7:
    // `{ "error": "<message>", "code": "<CODE>" }`.
    handler: (req, res) => {
      res.status(429).json({ error: message, code });
    }
  });
}

/** POST /api/auth/register + /api/auth/login. */
export const authRateLimit = buildRateLimiter({
  windowMs: 15 * 60 * 1000,
  max: config.isTest ? 1_000 : 20,
  code: 'RATE_LIMITED',
  skipSuccessfulRequests: true,
  message: 'Too many authentication attempts. Try again later.'
});

/** Broad backstop for the rest of the API. */
export const apiRateLimit = buildRateLimiter({
  windowMs: 60 * 1000,
  max: config.isTest ? 100_000 : 300,
  code: 'RATE_LIMITED',
  message: 'Too many requests. Slow down and try again.'
});
/**
 * Limiter for the AI endpoints (each call costs provider quota): per signed-in user when there is one, otherwise per IP.
 * Exported as a factory so tests can mount a tight one.
 */
export function buildAiRateLimit(max) {
  return buildRateLimiter({
    windowMs: 60 * 1000,
    max,
    code: 'AI_RATE_LIMITED',
    message: 'Too many AI requests. Try again in a minute.',
    key: (req) => (req.auth?.uid ? `u:${req.auth.uid}` : `ip:${keyGenerator(req)}`)
  });
}

/** Default AI limiter: AI_RATE_PER_MINUTE per user/IP (effectively off under test). */
export const aiRateLimit = buildAiRateLimit(config.isTest ? 100_000 : config.ai.ratePerMinute);

/**
 * Dev-only defaults for the verify:* scripts. Import this FIRST (before any
 * module that reads src/config) so the scripts run without a hand-set secret.
 * Never applies in production.
 */
if (process.env.NODE_ENV !== 'production' && !(process.env.JWT_SECRET || '').trim()) {
  process.env.JWT_SECRET = 'dev-secret-at-least-32-characters-long';
}

import { Router } from 'express';
import { requireAuth } from '../middleware/auth.js';
import { validate } from '../middleware/validate.js';
import {
  eventIdRouteParamsSchema,
  interestsSchema,
  loginSchema,
  registerSchema
} from '../middleware/validators.js';
import { authRateLimit } from '../middleware/rateLimit.js';
import * as auth from '../controllers/authController.js';

const router = Router();

// Credential endpoints are rate limited to blunt password spraying.
router.post('/register', authRateLimit, validate({ body: registerSchema }), auth.register);
router.post('/login', authRateLimit, validate({ body: loginSchema }), auth.login);

// Website manager sign-up: creates an organizer, but only when the operator turned it on (see config).
router.get('/manager-signup', auth.managerSignupStatus);
router.post('/register-manager', authRateLimit, validate({ body: registerSchema }), auth.registerManager);

// GET /api/auth/me -> 200 (requires JWT)
router.get('/me', requireAuth, auth.me);

// PUT /api/auth/me/interests -> 200 (requires JWT)
router.put(
  '/me/interests',
  requireAuth,
  validate({ body: interestsSchema }),
  auth.updateInterestsHandler
);

/* Bookmarks ---------------------------------------------------------------- */

// GET /api/auth/me/bookmarks -> 200 { bookmarks } (requires JWT)
router.get('/me/bookmarks', requireAuth, auth.listBookmarksHandler);

// POST /api/auth/me/bookmarks/:eventId -> 200 { bookmarked, eventId, bookmarks }
router.post(
  '/me/bookmarks/:eventId',
  requireAuth,
  validate({ params: eventIdRouteParamsSchema }),
  auth.addBookmarkHandler
);

// DELETE /api/auth/me/bookmarks/:eventId -> 200 { bookmarked, eventId, bookmarks }
router.delete(
  '/me/bookmarks/:eventId',
  requireAuth,
  validate({ params: eventIdRouteParamsSchema }),
  auth.removeBookmarkHandler
);

export default router;
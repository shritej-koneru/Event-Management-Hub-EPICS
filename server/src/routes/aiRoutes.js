import { Router } from 'express';
import { optionalAuth, requireAuth, requireRole } from '../middleware/auth.js';
import { aiRateLimit } from '../middleware/rateLimit.js';
import { validate } from '../middleware/validate.js';
import {
  aiAssistantSchema,
  aiDraftSchema,
  aiRewriteSchema,
  aiScheduleSchema,
  aiSearchSchema,
  aiTranslateSchema,
  announcementIdParamSchema,
  eventIdParamSchema,
  supportTicketIdParamSchema
} from '../middleware/validators.js';
import * as ai from '../controllers/aiController.js';

const router = Router();

// GET /ai/status -> 200 (public): can the apps offer the AI helpers?
router.get('/status', ai.status);

// POST /ai/search -> 200 (optional auth: personalized when a JWT is present). Deterministic; no provider call.
router.post('/search', optionalAuth, validate({ body: aiSearchSchema }), ai.search);

// GET /ai/recommendations -> 200 (requires JWT, enforced in the controller). Deterministic; no provider call.
router.get('/recommendations', optionalAuth, ai.recommendations);

/*
 * Everything below can call the AI provider, so it needs a signed-in user and is rate limited per user. Each answers
 * 503 AI_DISABLED when AI is not configured, which the apps use to hide the matching buttons.
 */
router.post('/assistant', requireAuth, aiRateLimit, validate({ body: aiAssistantSchema }), ai.assistant);
router.post('/announcements/:id/summary', requireAuth, aiRateLimit, validate({ params: announcementIdParamSchema }), ai.announcementSummary);
router.post(
  '/announcements/:id/translate',
  requireAuth,
  aiRateLimit,
  validate({ params: announcementIdParamSchema, body: aiTranslateSchema }),
  ai.announcementTranslate
);
router.post('/support/rewrite', requireAuth, aiRateLimit, validate({ body: aiRewriteSchema }), ai.supportRewrite);
router.post('/events/:id/simplify', requireAuth, aiRateLimit, validate({ params: eventIdParamSchema }), ai.eventSimplify);
router.post('/schedule/conflicts', requireAuth, aiRateLimit, validate({ body: aiScheduleSchema }), ai.scheduleConflicts);

// Manager tools: organizers (for events they created) and admins only. Drafts are returned, never saved or published.
router.post(
  '/manage/tickets/:id/category',
  requireAuth,
  requireRole('organizer', 'admin'),
  aiRateLimit,
  validate({ params: supportTicketIdParamSchema }),
  ai.ticketCategory
);
router.post(
  '/manage/draft',
  requireAuth,
  requireRole('organizer', 'admin'),
  aiRateLimit,
  validate({ body: aiDraftSchema }),
  ai.organizerDraft
);

export default router;

import { asyncHandler } from '../utils/asyncHandler.js';
import { ApiError } from '../utils/errors.js';
import { searchEvents } from '../services/aiSearchService.js';
import { recommendFor } from '../services/recommendationService.js';
import { config } from '../config/index.js';
import { supportedLanguages } from '../ai/translate.js';
import { cleanInput } from '../ai/inputLimits.js';
import { askAssistant } from '../services/aiAssistantService.js';
import { checkSchedule } from '../services/aiScheduleService.js';
import { draftAnnouncement, draftEvent, suggestTicketCategory } from '../services/aiManagerService.js';
import { simplifyDescription, suggestRewrite, summarizeAnnouncement, translateAnnouncement } from '../services/aiTextService.js';

/**
 * POST /api/ai/search -> 200 { query, results }
 * Optional auth: re-ranks using the caller's interests/bookmarks/history.
 */
export const search = asyncHandler(async (req, res) => {
  const { query, limit, filters } = req.body;
  const result = await searchEvents({ query, limit, filters, auth: req.auth ?? null });
  res.json(result);
});

/**
 * GET /api/ai/status -> 200 { enabled, languages, maxInputChars }
 * Public. Tells the apps whether the AI helpers can be offered at all (false when no key is configured or AI is switched
 * off), which languages translation supports, and the input limit. Never returns the key, models or provider details.
 */
export const status = (req, res) => {
  res.json({
    enabled: config.ai.enabled,
    languages: supportedLanguages(),
    maxInputChars: config.ai.maxInputChars
  });
};

/** GET /api/ai/recommendations -> 200 { recommendations } (requires JWT) */
export const recommendations = asyncHandler(async (req, res) => {
  if (!req.auth) {
    throw ApiError.unauthorized(
      'Login to get personalized recommendations',
      'AUTH_REQUIRED'
    );
  }
  const recommendations = await recommendFor(req.auth);
  res.json({ recommendations });
});
/* ------------------------------ AI helpers ------------------------------ */

const limit = (cap) => Math.min(cap, config.ai.maxInputChars);

/** POST /api/ai/assistant -> 200 { available, answer, sources, ... }. Grounded in current data; see aiAssistantService. */
export const assistant = asyncHandler(async (req, res) => {
  const question = cleanInput(req.body.question, limit(300), 'question');
  res.json(await askAssistant({ question }));
});

/** POST /api/ai/announcements/:id/summary -> 200 { summary, original, link, aiGenerated } */
export const announcementSummary = asyncHandler(async (req, res) => {
  res.json(await summarizeAnnouncement(req.params.id));
});

/** POST /api/ai/announcements/:id/translate { language } -> 200 { original, translated, language } */
export const announcementTranslate = asyncHandler(async (req, res) => {
  res.json(await translateAnnouncement(req.params.id, req.body.language));
});

/** POST /api/ai/support/rewrite { text, kind } -> 200 { original, suggestion, requiresReview: true }. Never sends. */
export const supportRewrite = asyncHandler(async (req, res) => {
  const text = cleanInput(req.body.text, limit(1000), 'text');
  res.json(await suggestRewrite({ text, kind: req.body.kind }));
});

/** POST /api/ai/events/:id/simplify -> 200 { original, plain, accessibility } */
export const eventSimplify = asyncHandler(async (req, res) => {
  res.json(await simplifyDescription(req.params.id));
});

/** POST /api/ai/schedule/conflicts { eventIds? } -> 200 { conflicts, alternatives, basedOn, limitation } */
export const scheduleConflicts = asyncHandler(async (req, res) => {
  res.json(await checkSchedule({ eventIds: req.body.eventIds, auth: req.auth }));
});

/** POST /api/ai/manage/tickets/:id/category -> 200 { category, reason }. Managers of the ticket's event / admins only. */
export const ticketCategory = asyncHandler(async (req, res) => {
  res.json(await suggestTicketCategory(req.user, req.params.id));
});

/** POST /api/ai/manage/draft { kind, brief, eventId? } -> 200 { draft, status: "DRAFT_REQUIRES_REVIEW" }. Saves nothing. */
export const organizerDraft = asyncHandler(async (req, res) => {
  const brief = cleanInput(req.body.brief, limit(800), 'brief');
  if (req.body.kind === 'event') {
    return res.json(await draftEvent({ brief }));
  }
  return res.json(await draftAnnouncement({ brief, eventId: req.body.eventId ?? null }, req.user));
});

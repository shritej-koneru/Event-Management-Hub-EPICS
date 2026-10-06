import { asyncHandler } from '../utils/asyncHandler.js';
import { ApiError } from '../utils/errors.js';
import { searchEvents } from '../services/aiSearchService.js';
import { recommendFor } from '../services/recommendationService.js';

/**
 * POST /api/ai/search -> 200 { query, results }
 * Optional auth: re-ranks using the caller's interests/bookmarks/history.
 */
export const search = asyncHandler(async (req, res) => {
  const { query, limit } = req.body;
  const result = await searchEvents({ query, limit, auth: req.auth ?? null });
  res.json(result);
});

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
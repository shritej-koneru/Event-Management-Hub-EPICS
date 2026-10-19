import { asyncHandler } from '../utils/asyncHandler.js';
import { venueList } from '../navigation/graph.js';
import { findPath, campusMap, venueMap as venueMapNode } from '../services/navigationService.js';

/** GET /api/navigation/venues -> 200 { venues } */
export const venues = asyncHandler(async (req, res) => {
  res.json({ venues: venueList() });
});

/**
 * POST /api/navigation/path { from, to } -> 200 { nodes, steps, totalDistanceM }
 * `from`/`to` accept a graph node id or a canonical display name. Anything
 * else is a 400 UNKNOWN_VENUE — never a guessed or coerced route.
 */
export const path = asyncHandler(async (req, res) => {
  const { from, to } = req.body;
  res.json(findPath({ from, to }));
});

/**
 * GET /api/navigation/map -> 200 { venues, edges }
 * Whole-campus drawing data read from the seeded database.
 */
export const map = asyncHandler(async (req, res) => {
  res.json(await campusMap());
});

/**
 * GET /api/navigation/venues/:venueId/map -> 200 { venue, neighbors }
 * | 400 UNKNOWN_VENUE. `:venueId` accepts a node id or canonical display name.
 */
export const venueMap = asyncHandler(async (req, res) => {
  res.json(await venueMapNode(req.params.venueId));
});
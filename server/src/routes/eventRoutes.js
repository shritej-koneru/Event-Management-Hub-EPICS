import { Router } from 'express';
import { optionalAuth, requireAuth } from '../middleware/auth.js';
import { validate } from '../middleware/validate.js';
import { createEventSchema, eventIdSchema, eventListQuerySchema, eventRouteQuerySchema } from '../middleware/validators.js';
import * as events from '../controllers/eventController.js';

const router = Router();

/**
 * GET /api/events?category=&date=&venue=&q=&page=&pageSize= -> 200 (optional auth)
 * Unpaginated unless `page` is supplied, in which case a `pagination` block is
 * added. See contract/API-CONTRACT.md.
 */
router.get(
  '/',
  optionalAuth,
  validate({ query: eventListQuerySchema }),
  events.list
);

/**
 * GET /api/events/:id/route?from=entrance -> 200
 * Wayfinding for a single event: routes from `from` to the event's own venue.
 * Declared before `/:id` so the more specific path wins.
 */
router.get(
  '/:id/route',
  optionalAuth,
  validate({ params: eventIdSchema, query: eventRouteQuerySchema }),
  events.route
);

/**
 * GET /api/events/:id/details?from=entrance -> 200
 *
 * Aggregate Event Details payload (event, venue, statistics, charts,
 * announcements, navigation) built entirely from stored data. Declared before
 * `/:id` so the more specific path wins, and additive so GET /api/events/:id
 * keeps its exact existing shape.
 */
router.get(
  '/:id/details',
  optionalAuth,
  validate({ params: eventIdSchema, query: eventRouteQuerySchema }),
  events.details
);

// GET /api/events/:id -> 200 | 404
router.get('/:id', optionalAuth, validate({ params: eventIdSchema }), events.detail);

/**
 * POST /api/events -> 201
 *
 * Creating events mutates shared data, so it requires a JWT. This matches
 * contract/API-CONTRACT.md, which exempts only /ping, /auth/login,
 * /auth/register, GET /events, GET /events/:id and GET /navigation/venues.
 */
router.post('/', requireAuth, validate({ body: createEventSchema }), events.create);

export default router;
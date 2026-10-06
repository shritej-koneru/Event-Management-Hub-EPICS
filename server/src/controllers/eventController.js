import { asyncHandler } from '../utils/asyncHandler.js';
import { ApiError } from '../utils/errors.js';
import { createEvent, getEvent, getEventDetails, getEventRoute, getEvents } from '../services/eventService.js';
import { validatedQuery } from '../middleware/validate.js';

/**
 * GET /api/events?category=&date=&venue=&q=&page=&pageSize= -> 200
 *
 * `pagination` is additive: it only appears when the caller actually paginates,
 * so the existing Android `EventListResponse(events)` keeps working unchanged.
 */
export const list = asyncHandler(async (req, res) => {
  const filters = validatedQuery(req);
  const { events, total, pagination } = await getEvents(filters);

  if (pagination) {
    res.json({
      events,
      pagination: {
        page: pagination.page,
        pageSize: pagination.pageSize,
        total,
        totalPages: Math.ceil(total / pagination.pageSize) || 0,
        hasMore: pagination.page * pagination.pageSize < total
      }
    });
    return;
  }

  res.json({ events, total });
});

/** GET /api/events/:id -> 200 { event } | 404 EVENT_NOT_FOUND */
export const detail = asyncHandler(async (req, res) => {
  const event = await getEvent(req.params.id);
  if (!event) throw ApiError.notFound(`Event not found: ${req.params.id}`, 'EVENT_NOT_FOUND');
  res.json({ event });
});

/**
 * POST /api/events -> 201 { event }
 * Triggers the BitChat announcement relay via app.locals.broadcastEvent.
 */
export const create = asyncHandler(async (req, res) => {
  const event = await createEvent(req.body);
  await req.app.locals.broadcastEvent?.(event, { reason: 'created' });
  res.status(201).json({ event });
});

/**
 * GET /api/events/:id/route?from=entrance -> 200
 * @returns {{ eventId, venueId, venue, nodes, steps, totalDistanceM }}
 */
export const route = asyncHandler(async (req, res) => {
  const { from } = validatedQuery(req);
  res.json(await getEventRoute(req.params.id, { from }));
});

/**
 * GET /api/events/:id/details[?from=entrance] -> 200
 *
 * Aggregate payload for the Event Details screen:
 * { event, venue, statistics, charts, announcements, navigation }
 *
 * Deliberately additive. `GET /api/events/:id` above is untouched, so the
 * existing Android `EventResponse(event)` keeps working exactly as before.
 * `isBookmarked` reflects the caller and is always false when unauthenticated.
 */
export const details = asyncHandler(async (req, res) => {
  const { from = 'entrance' } = validatedQuery(req);
  res.json(await getEventDetails(req.params.id, { from, auth: req.auth ?? null }));
});
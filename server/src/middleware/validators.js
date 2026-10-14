import { z } from 'zod';
import { VENUE_IDS } from '../navigation/venues.js';

/* -------------------------------------------------------------------------- */
/* Auth                                                                       */
/* -------------------------------------------------------------------------- */

const email = z
  .string({ required_error: 'email is required' })
  .trim()
  .min(1, 'email is required')
  .email('must be a valid email address')
  .toLowerCase();

const interestList = z.array(z.string().trim().min(1)).max(20, 'at most 20 interests');

export const registerSchema = z.object({
  name: z.string().trim().min(1, 'name is required').max(120),
  email,
  password: z.string().min(6, 'password must be at least 6 characters').max(200),
  interests: interestList.optional()
});

export const loginSchema = z.object({
  email,
  password: z.string({ required_error: 'password is required' }).min(1, 'password is required')
});

export const interestsSchema = z.object({
  interests: interestList.default([])
});

/* -------------------------------------------------------------------------- */
/* Events                                                                     */
/* -------------------------------------------------------------------------- */

/** Event ids are `evt_*` tokens; anything else is rejected before the DB call. */
export const eventIdParamSchema = z.object({
  id: z
    .string()
    .trim()
    .min(1, 'event id is required')
    .max(64)
    .regex(/^[A-Za-z0-9_-]+$/, 'event id may only contain letters, numbers, _ and -')
});

export const eventIdSchema = eventIdParamSchema;

/** Bookmark routes use `:eventId` rather than `:id`. */
export const eventIdRouteParamsSchema = z.object({
  eventId: eventIdParamSchema.shape.id
});

/**
 * Event creation. `venue` must be a canonical display name — validated against
 * the navigation graph in the controller so an unmappable venue produces a
 * clear UNKNOWN_VENUE error instead of an unroutable event.
 */
export const createEventSchema = z.object({
  title: z.string().trim().min(1, 'title is required').max(200),
  category: z.string().trim().min(1, 'category is required').max(60),
  venue: z.string().trim().min(1, 'venue is required').max(80),
  date: z
    .string()
    .trim()
    .regex(/^\d{4}-\d{2}-\d{2}$/, 'date must be formatted YYYY-MM-DD'),
  time: z
    .string()
    .trim()
    .regex(/^([01]\d|2[0-3]):[0-5]\d$/, 'time must be formatted HH:MM (24h)')
    .default('09:00'),
  durationMin: z.coerce.number().int().positive().max(1440).default(60),
  capacity: z.coerce.number().int().min(0).max(100_000),
  registered: z.coerce.number().int().min(0).max(100_000).default(0),
  host: z.string().trim().max(120).default(''),
  description: z.string().trim().max(2000).default(''),
  tags: z.array(z.string().trim().min(1)).max(20).default([]),
  icon: z.string().trim().max(8).default('🎫')
});

/**
 * Event list query.
 *
 * Pagination is opt-in: send `page` (and optionally `pageSize`) to get a page
 * plus a `pagination` block. Omit it and the full filtered list is returned,
 * which keeps the existing Android client working without changes.
 *
 * `category` is matched case-insensitively and `venue` accepts either a
 * canonical id ("room-204") or display name ("Room 204").
 *
 * `.strict()` rejects unsupported query parameters with a 400 rather than
 * silently ignoring a typo such as `catgeory=Tech`.
 */
export const eventListQuerySchema = z
  .object({
    category: z.string().trim().max(60).optional(),
    date: z
      .string()
      .trim()
      .regex(/^\d{4}-\d{2}-\d{2}$/, 'date must be formatted YYYY-MM-DD')
      .optional(),
    venue: z.string().trim().min(1).max(80).optional(),
    q: z.string().trim().max(200).optional(),
    page: z.coerce.number().int().min(1).max(10_000).optional(),
    pageSize: z.coerce.number().int().min(1).max(100).optional()
  })
  .strict();

/* -------------------------------------------------------------------------- */
/* AI                                                                         */
/* -------------------------------------------------------------------------- */

export const aiSearchSchema = z.object({
  query: z
    .string({ required_error: 'query is required' })
    .trim()
    .min(1, 'query is required')
    .max(500),
  limit: z.coerce.number().int().min(1).max(50).default(10)
});

/**
 * GET /api/events/:id/route — wayfinding to an event's own venue.
 * `from` accepts a node id ("entrance") or a display name ("Entrance") and
 * defaults to the graph entrance.
 */
export const eventRouteQuerySchema = z
  .object({
    from: z.string().trim().min(1, 'from is required').max(80).default('entrance')
  })
  .strict();

/* -------------------------------------------------------------------------- */
/* Navigation                                                                 */
/* -------------------------------------------------------------------------- */

/**
 * Accepts a graph node id ("room-204") or a canonical display name ("Room 204").
 * Semantic resolution happens in the navigation service.
 */
const venueRef = z.string().trim().min(1, 'venue is required').max(80);

export const navigationPathSchema = z.object({
  from: venueRef,
  to: venueRef
});

/* -------------------------------------------------------------------------- */
/* Chat / announcements                                                        */
/* -------------------------------------------------------------------------- */

/**
 * GET /api/chat/announcements?eventId=
 *
 * Omitting `eventId` keeps the existing venue-wide feed. Supplying it restricts
 * the feed to announcements recorded for THAT event, which is what stops two
 * events sharing a venue from showing each other's messages.
 */
export const announcementQuerySchema = z
  .object({
    eventId: z
      .string()
      .trim()
      .max(64)
      .regex(/^[A-Za-z0-9_-]+$/, 'eventId contains unsupported characters')
      .optional()
  })
  .strict();

/**
 * GET /api/chat/messages?room=&before=&limit=
 *
 * `room` is a bare venue id or `announce:<venueId>`; both are accepted and
 * normalised in the controller. `before` is an ISO-8601 timestamp cursor.
 * `limit` caps the page (server maximum is enforced in the model).
 */
export const chatMessageQuerySchema = z
  .object({
    room: z.string().trim().min(1).max(80).optional(),
    before: z
      .string()
      .trim()
      .datetime({ offset: true })
      .optional(),
    limit: z.coerce.number().int().min(1).max(100).optional()
  })
  .strict();

export { VENUE_IDS };
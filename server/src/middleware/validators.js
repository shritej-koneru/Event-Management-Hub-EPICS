import { z } from 'zod';
import { VENUE_IDS } from '../navigation/venues.js';

/* -------------------------------------------------------------------------- */
/* Auth                                                                       */
/* -------------------------------------------------------------------------- */

const email = z
  .string({ error: 'email is required' })
  .trim()
  .min(1, 'email is required')
  .pipe(z.email('must be a valid email address'))
  .transform((v) => v.toLowerCase());

const interestList = z.array(z.string().trim().min(1)).max(20, 'at most 20 interests');

export const registerSchema = z.object({
  name: z.string().trim().min(1, 'name is required').max(120),
  email,
  password: z.string().min(6, 'password must be at least 6 characters').max(200),
  interests: interestList.optional()
});

export const loginSchema = z.object({
  email,
  password: z.string({ error: 'password is required' }).min(1, 'password is required')
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
/** Accessibility features an event can declare. A feature that is not sent stays UNKNOWN. */
export const ACCESSIBILITY_KEYS = ['stepFree', 'accessibleRestroom', 'signLanguage', 'captions', 'hearingLoop'];

export const accessibilitySchema = z.strictObject(
  Object.fromEntries(ACCESSIBILITY_KEYS.map((key) => [key, z.boolean().optional()]))
);

/** `null` = unknown. `free` carries no amount; `paid` needs a positive amount and a currency code. */
export const priceSchema = z
  .discriminatedUnion('type', [
    z.strictObject({ type: z.literal('free') }),
    z.strictObject({
      type: z.literal('paid'),
      amountCents: z.coerce.number().int().min(1).max(100_000_000),
      currency: z.string().trim().toUpperCase().regex(/^[A-Z]{3}$/, 'currency must be a 3-letter code')
    })
  ])
  .nullable();

export const createEventBase = z.object({
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
  icon: z.string().trim().max(8).default('🎫'),
  price: priceSchema.optional(),
  accessibility: accessibilitySchema.optional()
});

export const createEventSchema = createEventBase;

/** PUT /api/events/:id — any subset of the create fields (at least one). */
// zod 4 applies `.default()` even inside `.partial()`, which would silently
// overwrite untouched columns on PUT. Unwrap defaults so omitted fields stay omitted.
const updateShape = Object.fromEntries(
  Object.entries(createEventBase.shape).map(([key, schema]) => [
    key,
    schema instanceof z.ZodDefault ? schema.unwrap().optional() : schema.optional()
  ])
);

export const updateEventSchema = z
  .strictObject(updateShape)
  .refine((body) => Object.keys(body).length > 0, { message: 'at least one field is required' });

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

const isoDate = z.string().trim().regex(/^\d{4}-\d{2}-\d{2}$/, 'date must be formatted YYYY-MM-DD');

/** Filters the app's filter chips send with a search. Unknown keys are rejected, not ignored. */
export const searchFiltersSchema = z.strictObject({
  category: z.string().trim().min(1).max(60).optional(),
  dateFrom: isoDate.optional(),
  dateTo: isoDate.optional(),
  free: z.boolean().optional(),
  maxPriceCents: z.coerce.number().int().min(0).max(100_000_000).optional(),
  accessibility: z.array(z.enum(ACCESSIBILITY_KEYS)).max(ACCESSIBILITY_KEYS.length).optional()
});

export const aiSearchSchema = z.object({
  query: z
    .string({ error: 'query is required' })
    .trim()
    .min(1, 'query is required')
    .max(500),
  limit: z.coerce.number().int().min(1).max(50).default(10),
  filters: searchFiltersSchema.optional()
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

/**
 * GET /api/navigation/venues/:venueId/map — same ref rules as the path body;
 * a URL-encoded display name ("Room%20204") is decoded by Express first.
 */
export const navigationVenueMapParamsSchema = z.object({
  venueId: venueRef
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
/* -------------------------------------------------------------------------- */
/* Manager console (/api/manage/*)                                             */
/* -------------------------------------------------------------------------- */

export const manageScopeQuerySchema = z
  .object({ scope: z.enum(['mine', 'all']).default('mine') })
  .strict();

const venueSlug = z
  .string({ error: 'id is required' })
  .trim()
  .min(1, 'id is required')
  .max(40, 'id must be at most 40 characters')
  .regex(/^[a-z0-9]+(-[a-z0-9]+)*$/, 'id must be a lowercase slug (a-z, 0-9, single hyphens)')
  .refine((id) => id !== 'all', { message: '"all" is reserved' });

const venueName = z.string({ error: 'name is required' }).trim().min(1, 'name is required').max(60);
const fraction = (label) =>
  z
    .number({ error: `${label} must be a number between 0 and 1` })
    .min(0, `${label} must be between 0 and 1`)
    .max(1, `${label} must be between 0 and 1`);

const latitude = z
  .number({ error: 'lat must be a number between -90 and 90' })
  .min(-90, 'lat must be between -90 and 90')
  .max(90, 'lat must be between -90 and 90');
const longitude = z
  .number({ error: 'lng must be a number between -180 and 180' })
  .min(-180, 'lng must be between -180 and 180')
  .max(180, 'lng must be between -180 and 180');
const venueKind = z.string({ error: 'kind must be a string' }).trim().max(40, 'kind must be at most 40 characters');
const venueDescription = z
  .string({ error: 'description must be a string' })
  .trim()
  .max(500, 'description must be at most 500 characters');

/**
 * Either canvas position (x,y) or real-world position (lat,lng) - never both,
 * never half a pair. Bounds (OUTSIDE_CAMPUS) are checked in the service.
 */
const venuePositionRule = (b, { partialXY = false } = {}) => {
  const hasXY = b.x !== undefined || b.y !== undefined;
  const hasLL = b.lat !== undefined || b.lng !== undefined;
  if (hasXY && hasLL) return false;
  if (!partialXY && (b.x === undefined) !== (b.y === undefined)) return false;
  if ((b.lat === undefined) !== (b.lng === undefined)) return false;
  return true;
};
const POSITION_MESSAGE = 'provide x/y or both lat and lng (never a mix of the two pairs); create needs both x and y';

export const createVenueSchema = z
  .strictObject({
    id: venueSlug,
    name: venueName,
    x: fraction('x').optional(),
    y: fraction('y').optional(),
    lat: latitude.optional(),
    lng: longitude.optional(),
    kind: venueKind.optional(),
    description: venueDescription.optional(),
    connectToNearest: z.boolean({ error: 'connectToNearest must be a boolean' }).optional()
  })
  .refine(venuePositionRule, { message: POSITION_MESSAGE, path: ['x'] })
  .refine((b) => b.x !== undefined || b.lat !== undefined, {
    message: 'a position is required: x and y, or lat and lng',
    path: ['x']
  })
  .refine((b) => b.lat !== undefined || b.connectToNearest === undefined || b.connectToNearest === false, {
    message: 'connectToNearest needs lat and lng',
    path: ['connectToNearest']
  });

export const updateVenueSchema = z
  .strictObject({
    name: venueName.optional(),
    kind: venueKind.optional(),
    description: venueDescription.optional(),
    x: fraction('x').optional(),
    y: fraction('y').optional(),
    lat: latitude.optional(),
    lng: longitude.optional()
  })
  .refine((b) => venuePositionRule(b, { partialXY: true }), { message: POSITION_MESSAGE, path: ['x'] })
  .refine((b) => Object.keys(b).length > 0, {
    message: 'at least one of name, kind, description, x/y or lat/lng is required'
  });

/** :id on PUT/DELETE is loose on purpose so an unknown id is a 404, not a 400. */
export const manageVenueParamsSchema = z.object({
  id: z.string().trim().min(1).max(80)
});

const edgeEndpoint = (label) =>
  z.string({ error: `${label} is required` }).trim().min(1, `${label} is required`).max(80);

const distanceM = z
  .number({ error: 'distanceM must be an integer between 1 and 1000' })
  .int('distanceM must be an integer between 1 and 1000')
  .min(1, 'distanceM must be an integer between 1 and 1000')
  .max(1000, 'distanceM must be an integer between 1 and 1000');

export const edgeBodySchema = z
  .strictObject({ from: edgeEndpoint('from'), to: edgeEndpoint('to'), distanceM })
  .refine((b) => b.from !== b.to, { message: 'from and to must be different venues', path: ['to'] });

export const edgeQuerySchema = z
  .object({ from: edgeEndpoint('from'), to: edgeEndpoint('to') })
  .strict();

export const createAnnouncementSchema = z.strictObject({
  message: z.string({ error: 'message is required' }).trim().min(1, 'message is required').max(280),
  venueId: z.string().trim().min(1).max(80).optional(),
  eventId: eventIdParamSchema.shape.id.optional()
});

export const announcementIdParamSchema = z.object({
  id: z.coerce.number().int().positive().max(Number.MAX_SAFE_INTEGER)
});


/* -------------------------------------------------------------------------- */
/* Admin accounts (/api/manage/users)                                          */
/* -------------------------------------------------------------------------- */

const userRole = z.enum(['organizer', 'admin', 'student'], {
  error: 'role must be one of organizer, admin, student'
});

export const adminCreateUserSchema = z.strictObject({
  name: z.string({ error: 'name is required' }).trim().min(1, 'name is required').max(120),
  email,
  password: z
    .string({ error: 'password is required' })
    .min(8, 'password must be at least 8 characters')
    .max(200),
  role: userRole
});

export const adminUpdateUserSchema = z.strictObject({ role: userRole });

export const userIdParamSchema = z.object({ id: z.string().trim().min(1).max(80) });


/* -------------------------------------------------------------------------- */
/* Help / support desk (/api/support, /api/manage/support)                     */
/* -------------------------------------------------------------------------- */

const supportClientId = z
  .string({ error: 'clientId must be a string' })
  .trim()
  .min(1, 'clientId must not be empty')
  .max(64, 'clientId must be at most 64 characters')
  .optional();

export const supportTicketIdParamSchema = z.object({
  id: z.coerce.number().int().positive().max(Number.MAX_SAFE_INTEGER)
});

export const supportTicketQuerySchema = z.object({
  eventId: eventIdParamSchema.shape.id.optional()
});

export const createSupportTicketSchema = z.strictObject({
  eventId: eventIdParamSchema.shape.id,
  subject: z.string({ error: 'subject is required' }).trim().min(1, 'subject is required').max(120),
  message: z.string({ error: 'message is required' }).trim().min(1, 'message is required').max(1000),
  clientId: supportClientId
});

export const supportMessageSchema = z.strictObject({
  body: z.string({ error: 'body is required' }).trim().min(1, 'body is required').max(1000),
  clientId: supportClientId
});

export const managerSupportTicketsQuerySchema = z.object({
  eventId: eventIdParamSchema.shape.id,
  status: z.enum(['open', 'closed', 'all'], { error: 'status must be open, closed or all' }).default('all')
});

export const supportStatusSchema = z.strictObject({
  status: z.enum(['open', 'closed'], { error: 'status must be open or closed' })
});

/* -------------------------------------------------------------------------- */
/* AI helpers (all calls are made by the server; see src/ai/)                  */
/* -------------------------------------------------------------------------- */

export const aiAssistantSchema = z.object({
  question: z.string({ error: 'question is required' }).trim().min(1, 'question is required').max(300)
});

export const aiTranslateSchema = z.object({
  language: z.string({ error: 'language is required' }).trim().min(2).max(8)
});

export const aiRewriteSchema = z.object({
  text: z.string({ error: 'text is required' }).trim().min(1, 'text is required').max(1000),
  kind: z.enum(['subject', 'message']).default('message')
});

export const aiScheduleSchema = z.object({
  eventIds: z.array(eventIdParamSchema.shape.id).max(30).optional()
});

export const aiDraftSchema = z.object({
  kind: z.enum(['event', 'announcement']),
  brief: z.string({ error: 'brief is required' }).trim().min(1, 'brief is required').max(800),
  eventId: eventIdParamSchema.shape.id.optional()
});

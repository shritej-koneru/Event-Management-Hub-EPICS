import {
  listEvents,
  findEventById,
  insertEvent,
  findEventOwner,
  findDuplicateEvent,
  updateEventRow,
  deleteEventRow,
  countEvents,
  countEventsByCategory
} from '../models/eventModel.js';
import {
  listRecentAnnouncements,
  countAnnouncementsForEvent,
  announcementTrendForEvent
} from '../models/announcementModel.js';
import { countBookmarksForEvent, isEventBookmarked } from '../models/bookmarkModel.js';
import { resolveVenue, neighborsOf } from '../navigation/registry.js';
import { ApiError } from '../utils/errors.js';
import { newId } from '../utils/id.js';
import { pathToEventVenue } from './navigationService.js';

/** Maximum page size a caller may request. */
export const MAX_PAGE_SIZE = 100;

/**
 * Normalises the validated list query into SQL model parameters.
 * Pagination is opt-in: omitting `page`/`pageSize` returns the full filtered
 * set, which is what the existing Android client and internal callers expect.
 */
export function toListParams({ category, date, venue, q, page, pageSize }) {
  const filters = {};
  if (category) filters.category = category;
  if (date) filters.date = date;
  if (venue) filters.venue = venue;
  if (q) filters.q = q;

  if (page === undefined) return { filters, pagination: null };

  const limit = Math.min(pageSize ?? 20, MAX_PAGE_SIZE);
  filters.limit = limit;
  filters.offset = (page - 1) * limit;

  return { filters, pagination: { page, pageSize: limit, limit, offset: filters.offset } };
}

/**
 * Lists events with SQL-level filtering and optional pagination.
 *
 * @returns {Promise<{events: object[], total: number, pagination: ?object}>}
 */
export async function getEvents(query = {}) {
  const { filters, pagination } = toListParams(query);
  const { events, total } = await listEvents(filters);
  return { events, total, pagination };
}

export async function getEvent(id) {
  return findEventById(id);
}

/**
 * Creates an event, enforcing that its venue resolves to a canonical
 * navigation graph node. An unmappable venue is a 400 UNKNOWN_VENUE rather than
 * a silently stored event that navigation could never route to.
 */
export async function createEvent(payload, { createdBy = null } = {}) {
  assertNotPast(payload.date);
  const venueId = resolveVenue(payload.venue);
  if (!venueId) {
    throw ApiError.badRequest(
      `Unknown venue: "${payload.venue}". Use a canonical venue name from the navigation graph ` +
        '(e.g. "Room 204", "Auditorium", "Main Hall").',
      'UNKNOWN_VENUE'
    );
  }

  const registered = Math.min(payload.registered ?? 0, payload.capacity);
  if ((payload.registered ?? 0) > payload.capacity) {
    throw ApiError.badRequest('registered cannot exceed capacity', 'VALIDATION_ERROR');
  }

  if (await findDuplicateEvent({ title: payload.title, date: payload.date, venueId })) {
    throw ApiError.conflict('An event with this title, date and venue already exists', 'DUPLICATE_EVENT');
  }

  return insertEvent({
    id: newId('evt'),
    title: payload.title,
    category: payload.category,
    venueId,
    date: payload.date,
    time: payload.time,
    durationMin: payload.durationMin,
    capacity: payload.capacity,
    registered,
    host: payload.host ?? '',
    description: payload.description ?? '',
    tags: payload.tags ?? [],
    icon: payload.icon ?? '🎫',
    createdBy
  });
}

/** Today as YYYY-MM-DD (UTC), the same day basis used by event details. */
const todayIso = () => new Date().toISOString().slice(0, 10);

function assertNotPast(date) {
  if (date < todayIso()) {
    throw ApiError.badRequest('Event date cannot be in the past', 'PAST_DATE');
  }
}

/** Loads the event owner and enforces creator-or-admin. */
async function assertCanModify(id, user) {
  const { exists, createdBy } = await findEventOwner(id);
  if (!exists) throw ApiError.notFound(`Event not found: ${id}`, 'EVENT_NOT_FOUND');
  if (user.role !== 'admin' && createdBy !== user.id) {
    throw ApiError.forbidden('Only the event creator or an admin can modify this event', 'FORBIDDEN');
  }
}

/** PUT /api/events/:id — partial update restricted to the creator or an admin. */
export async function updateEvent(id, patch, user) {
  await assertCanModify(id, user);
  const current = await findEventById(id);

  const next = { ...patch };
  if (patch.date !== undefined && patch.date !== current.date) assertNotPast(patch.date);
  if (patch.venue !== undefined) {
    const venueId = resolveVenue(patch.venue);
    if (!venueId) {
      throw ApiError.badRequest(`Unknown venue: "${patch.venue}".`, 'UNKNOWN_VENUE');
    }
    next.venueId = venueId;
  }
  const capacity = next.capacity ?? current.capacity;
  const registered = next.registered ?? current.registered;
  if (registered > capacity) {
    throw ApiError.badRequest('registered cannot exceed capacity', 'VALIDATION_ERROR');
  }

  const dup = await findDuplicateEvent({
    title: next.title ?? current.title,
    date: next.date ?? current.date,
    venueId: next.venueId ?? current.venueId,
    excludeId: id
  });
  if (dup) {
    throw ApiError.conflict('An event with this title, date and venue already exists', 'DUPLICATE_EVENT');
  }

  return updateEventRow(id, next);
}

/** DELETE /api/events/:id — restricted to the creator or an admin. Returns the removed event. */
export async function removeEvent(id, user) {
  await assertCanModify(id, user);
  const event = await findEventById(id);
  await deleteEventRow(id);
  return event;
}

/**
 * GET /api/events/:id/route
 *
 * Wayfinding to a single event: routes from `from` (default: the graph
 * entrance) to the venue the event is held at. Returns the same node/step shape
 * as POST /api/navigation/path, so the client can render either identically.
 */
export async function getEventRoute(id, { from = 'entrance' } = {}) {
  const event = await findEventById(id);
  if (!event) throw ApiError.notFound(`Event not found: ${id}`, 'EVENT_NOT_FOUND');

  return { eventId: event.id, venueId: event.venueId, venue: event.venue, ...pathToEventVenue(from, event.venueId) };
}

/* -------------------------------------------------------------------------- */
/* Event Details                                                               */
/* -------------------------------------------------------------------------- */

/**
 * GET /api/events/:id/details
 *
 * One call that fills an Event Details screen: the event record, its venue,
 * real statistics, chart-ready series, this event's announcements and the route
 * to the venue. `GET /api/events/:id` is deliberately left untouched so the
 * existing Android `EventResponse(event)` keeps its exact shape.
 *
 * Every number below is derived from a stored column or a COUNT over real rows.
 * There is no synthetic attendance: `registered` is the event's own counter
 * column, and nothing here ever asserts that a particular user registered,
 * because registrations live on-device in the Android app.
 *
 * Charts are omitted rather than padded. A series needs at least two real data
 * points to be plottable, so a single-announcement event simply has no trend
 * chart instead of a flat fake one.
 */
export async function getEventDetails(id, { from = 'entrance', auth = null } = {}) {
  const event = await findEventById(id);
  if (!event) throw ApiError.notFound(`Event not found: ${id}`, 'EVENT_NOT_FOUND');

  const [
    announcementCount,
    bookmarkCount,
    categoryEventCount,
    totalEvents,
    trend,
    announcements,
    isBookmarked
  ] = await Promise.all([
    countAnnouncementsForEvent(id),
    countBookmarksForEvent(id),
    countEventsByCategory(event.category),
    countEvents(),
    announcementTrendForEvent(id),
    listRecentAnnouncements(undefined, { eventId: id }),
    auth ? isEventBookmarked(auth.uid, id) : Promise.resolve(false)
  ]);

  const seatsRemaining = Math.max(event.capacity - event.registered, 0);
  const occupancyPercent =
    event.capacity > 0 ? Math.round((event.registered / event.capacity) * 1000) / 10 : 0;

  // `date` is the canonical event day (there is no startDate/endDate concept).
  // Compare on the date string so the result does not shift with the clock time.
  const today = new Date().toISOString().slice(0, 10);
  const daysUntil = Math.round(
    (Date.parse(`${event.date}T00:00:00Z`) - Date.parse(`${today}T00:00:00Z`)) / 86_400_000
  );

  return {
    event,
    venue: {
      id: event.venueId,
      name: event.venue,
      // The event's venue IS a navigation graph node, so this is the id the
      // navigation API accepts for `to`.
      navigationNode: event.venueId,
      neighbors: neighborsOf(event.venueId)
    },
    statistics: {
      capacity: event.capacity,
      // Server-stored aggregate counter on the event row. NOT per-user, and
      // never a claim about the caller.
      registered: event.registered,
      seatsRemaining,
      occupancyPercent,
      durationMin: event.durationMin,
      announcementCount,
      // Aggregate: how many users saved this event. False for anonymous callers.
      bookmarkCount,
      isBookmarked,
      categoryEventCount,
      totalEvents,
      isPast: daysUntil < 0,
      daysUntilEvent: daysUntil
    },
    charts: buildEventCharts({
      capacity: event.capacity,
      registered: event.registered,
      seatsRemaining,
      category: event.category,
      categoryEventCount,
      totalEvents,
      trend
    }),
    announcements,
    navigation: { from, ...pathToEventVenue(from, event.venueId) }
  };
}

/**
 * Chart-ready series. Each entry is `{ id, title, type, unit, total, data }`
 * where `data` is `[{ label, value }]` — the shape a bar/donut/line renderer can
 * consume without further transformation. Series that would be misleading (a
 * zero-capacity event, a single trend point) are left out entirely.
 */
function buildEventCharts({
  capacity,
  registered,
  seatsRemaining,
  category,
  categoryEventCount,
  totalEvents,
  trend
}) {
  const charts = [];

  if (capacity > 0) {
    charts.push({
      id: 'occupancy',
      title: 'Capacity',
      type: 'donut',
      unit: 'people',
      total: capacity,
      data: [
        { label: 'Registered', value: registered },
        { label: 'Available', value: seatsRemaining }
      ]
    });
  }

  const otherEvents = Math.max(totalEvents - categoryEventCount, 0);
  if (totalEvents > 0) {
    charts.push({
      id: 'categoryMix',
      title: `${category} vs other categories`,
      type: 'bar',
      unit: 'events',
      total: totalEvents,
      data: [
        { label: category, value: categoryEventCount },
        { label: 'Other categories', value: otherEvents }
      ]
    });
  }

  // Needs at least two real points, otherwise there is no trend to draw.
  if (trend.length >= 2) {
    charts.push({
      id: 'announcementTrend',
      title: 'Announcements over time',
      type: 'line',
      unit: 'announcements',
      total: trend.reduce((sum, point) => sum + point.value, 0),
      data: trend
    });
  }

  return charts;
}
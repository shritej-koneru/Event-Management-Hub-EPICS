import {
  listManagedEvents,
  eventOverviewCounts,
  findEventById,
  findEventOwner
} from '../models/eventModel.js';
import {
  insertManagedAnnouncement,
  listManagedAnnouncements,
  findManagedAnnouncement,
  deleteAnnouncementRow,
  countAnnouncementsBy
} from '../models/announcementModel.js';
import {
  countVenues,
  countVenueEdges,
  findVenueByName,
  insertVenue,
  updateVenueRow,
  getVenueMapRow,
  countEventsAtVenue,
  deleteVenueRow,
  findEdgeRow,
  insertEdgeRow,
  updateEdgeRow,
  deleteEdgeRow
} from '../models/venueModel.js';
import { refreshRegistry, resolveVenue, GLOBAL_ANNOUNCE_ROOM } from '../navigation/registry.js';
import { validateGraph } from '../navigation/graph.js';
import { ApiError } from '../utils/errors.js';

const todayIso = () => new Date().toISOString().slice(0, 10);
const isAdmin = (user) => user.role === 'admin';

/* -------------------------------------------------------------------------- */
/* Overview + events                                                          */
/* -------------------------------------------------------------------------- */

/**
 * GET /manage/overview. Organizers see their own events/announcements; an
 * admin sees the whole system. Venue/edge counts are the shared campus map.
 */
export async function overview(user) {
  const owner = isAdmin(user) ? null : user.id;
  const counts = await eventOverviewCounts({ createdBy: owner, today: todayIso() });
  return {
    events: { total: counts.total, upcoming: counts.upcoming, past: counts.past },
    bookmarks: counts.bookmarks,
    announcements: await countAnnouncementsBy(owner),
    venues: await countVenues(),
    edges: await countVenueEdges()
  };
}

export async function managedEvents(user, scope) {
  if (scope === 'all' && !isAdmin(user)) {
    throw ApiError.forbidden('Only an admin can list all events', 'FORBIDDEN');
  }
  return listManagedEvents({ createdBy: scope === 'all' ? null : user.id });
}

/* -------------------------------------------------------------------------- */
/* Map writes. The DB is written first, then the in-memory registry rebuilt.  */
/* -------------------------------------------------------------------------- */

const toVenue = (row) => ({ id: row.id, name: row.name, x: Number(row.map_x), y: Number(row.map_y) });
const toEdge = (row) => ({ from: row.from_id, to: row.to_id, distanceM: row.distance_m });

async function afterMapWrite() {
  await refreshRegistry();
  validateGraph();
}

export async function createVenue({ id, name, x, y }) {
  if (await getVenueMapRow(id)) {
    throw ApiError.conflict(`Venue "${id}" already exists`, 'DUPLICATE_VENUE');
  }
  if (await findVenueByName(name)) {
    throw ApiError.conflict(`A venue named "${name}" already exists`, 'DUPLICATE_VENUE');
  }
  const row = await insertVenue({ id, name, x, y });
  await afterMapWrite();
  return toVenue(row);
}

export async function updateVenue(id, patch) {
  if (!(await getVenueMapRow(id))) {
    throw ApiError.notFound(`Unknown venue: ${id}`, 'UNKNOWN_VENUE');
  }
  if (patch.name !== undefined && (await findVenueByName(patch.name, id))) {
    throw ApiError.conflict(`A venue named "${patch.name}" already exists`, 'DUPLICATE_VENUE');
  }
  const row = await updateVenueRow(id, patch);
  await afterMapWrite();
  return toVenue(row);
}

export async function deleteVenue(id) {
  if (!(await getVenueMapRow(id))) {
    throw ApiError.notFound(`Unknown venue: ${id}`, 'UNKNOWN_VENUE');
  }
  const inUse = await countEventsAtVenue(id);
  if (inUse > 0) {
    throw ApiError.conflict(
      `Venue "${id}" is used by ${inUse} event(s); move or delete them first`,
      'VENUE_IN_USE'
    );
  }
  await deleteVenueRow(id);
  await afterMapWrite();
  return { deleted: true, id };
}

async function requireEndpoints(from, to) {
  for (const id of [from, to]) {
    if (!(await getVenueMapRow(id))) {
      throw ApiError.badRequest(`Unknown venue: ${id}`, 'UNKNOWN_VENUE');
    }
  }
}

export async function createEdge({ from, to, distanceM }) {
  await requireEndpoints(from, to);
  if (await findEdgeRow(from, to)) {
    throw ApiError.conflict(`Edge ${from} - ${to} already exists`, 'DUPLICATE_EDGE');
  }
  const row = await insertEdgeRow(from, to, distanceM);
  await afterMapWrite();
  return toEdge(row);
}

export async function updateEdge({ from, to, distanceM }) {
  const row = await updateEdgeRow(from, to, distanceM);
  if (!row) throw ApiError.notFound(`Edge ${from} - ${to} does not exist`, 'UNKNOWN_EDGE');
  await afterMapWrite();
  return toEdge(row);
}

export async function deleteEdge({ from, to }) {
  if (!(await deleteEdgeRow(from, to))) {
    throw ApiError.notFound(`Edge ${from} - ${to} does not exist`, 'UNKNOWN_EDGE');
  }
  await afterMapWrite();
  return { deleted: true };
}

/* -------------------------------------------------------------------------- */
/* Announcements                                                              */
/* -------------------------------------------------------------------------- */

export const ANNOUNCE_THROTTLE_MAX = 10;
export const ANNOUNCE_THROTTLE_WINDOW_MS = 60_000;
const hitsByUser = new Map();

/** Sliding-window limiter, per user. Returns true when allowed. */
export function allowAnnouncement(userId, now = Date.now()) {
  const hits = (hitsByUser.get(userId) ?? []).filter((t) => now - t < ANNOUNCE_THROTTLE_WINDOW_MS);
  if (hits.length >= ANNOUNCE_THROTTLE_MAX) {
    hitsByUser.set(userId, hits);
    return false;
  }
  hits.push(now);
  hitsByUser.set(userId, hits);
  return true;
}

/** Test hook. */
export function resetAnnouncementThrottle() {
  hitsByUser.clear();
}

/**
 * Creates + persists an announcement.
 * @returns {{ announcement, venueName: string|null, event: object|null }}
 */
export async function sendAnnouncement(user, { message, venueId, eventId }) {
  if (!allowAnnouncement(user.id)) {
    throw new ApiError(
      429,
      `At most ${ANNOUNCE_THROTTLE_MAX} announcements per minute. Try again shortly.`,
      'RATE_LIMITED'
    );
  }

  let room = GLOBAL_ANNOUNCE_ROOM;
  let venueName = null;
  let event = null;

  if (eventId) {
    event = await findEventById(eventId);
    if (!event) throw ApiError.notFound(`Event not found: ${eventId}`, 'EVENT_NOT_FOUND');
    const { createdBy } = await findEventOwner(eventId);
    if (!isAdmin(user) && createdBy !== user.id) {
      throw ApiError.forbidden('Only the event creator or an admin can announce for this event', 'FORBIDDEN');
    }
    if (venueId !== undefined && resolveVenue(venueId) !== event.venueId) {
      throw ApiError.badRequest('venueId does not match the event venue', 'VALIDATION_ERROR');
    }
    room = event.venueId;
    venueName = event.venue;
  } else if (venueId !== undefined) {
    const resolved = resolveVenue(venueId);
    if (!resolved) {
      throw ApiError.badRequest(`Unknown venue: ${venueId}`, 'UNKNOWN_VENUE');
    }
    room = resolved;
    venueName = (await getVenueMapRow(resolved))?.name ?? null;
  }

  try {
    const announcement = await insertManagedAnnouncement({
      room,
      message,
      eventId: event?.id ?? null,
      sentBy: user.id
    });
    return { announcement, venueName, event };
  } catch (err) {
    if (err?.code === '23505') {
      throw ApiError.conflict(
        'An identical announcement already exists for this target; change the text or delete the old one',
        'DUPLICATE_ANNOUNCEMENT'
      );
    }
    throw err;
  }
}

export async function managedAnnouncements(user, scope) {
  if (scope === 'all' && !isAdmin(user)) {
    throw ApiError.forbidden('Only an admin can list all announcements', 'FORBIDDEN');
  }
  return listManagedAnnouncements({ sentBy: scope === 'all' ? null : user.id });
}

export async function removeAnnouncement(user, id) {
  const existing = await findManagedAnnouncement(id);
  if (!existing) throw ApiError.notFound(`Announcement not found: ${id}`, 'ANNOUNCEMENT_NOT_FOUND');
  if (!isAdmin(user) && existing.sentBy !== user.id) {
    throw ApiError.forbidden('You can only delete announcements you sent', 'FORBIDDEN');
  }
  await deleteAnnouncementRow(id);
  return { deleted: true, id: existing.id };
}

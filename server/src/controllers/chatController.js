import { asyncHandler } from '../utils/asyncHandler.js';
import { listRecentAnnouncements } from '../models/announcementModel.js';
import { listRecentChatMessages } from '../models/chatMessageModel.js';
import { findEventById } from '../models/eventModel.js';
import { resolveVenue } from '../navigation/venues.js';
import { ApiError } from '../utils/errors.js';
import { roomForVenue, normalizeRoom } from '../socket/index.js';
import { validatedQuery } from '../middleware/validate.js';

/**
 * GET /api/chat/announcements[?eventId=] -> 200 { announcements }
 *
 * REST fallback for BitChat; rows are already de-duplicated by the DB's
 * UNIQUE(room, message) constraint.
 *
 * With `eventId` the feed is scoped to that EVENT rather than the venue. That
 * matters because two events can be held at the same venue: without this filter
 * "Music Night" would render the announcements of "Drama: The Glass Menagerie".
 * An unknown event id is a 404 rather than an empty list, so a client typo is
 * not silently indistinguishable from "this event has no announcements".
 */
export const announcements = asyncHandler(async (req, res) => {
  const { eventId } = validatedQuery(req);

  if (eventId) {
    const event = await findEventById(eventId);
    if (!event) throw ApiError.notFound(`Event not found: ${eventId}`, 'EVENT_NOT_FOUND');

    res.json({
      announcements: await listRecentAnnouncements(undefined, { eventId }),
      eventId
    });
    return;
  }

  res.json({ announcements: await listRecentAnnouncements() });
});

/**
 * POST /api/chat/join { venueId } -> 200 { ok, room }
 * REST mirror of the Socket.io `join` event, for clients that cannot hold a
 * socket connection. The venue must be a real graph node.
 */
export const join = asyncHandler(async (req, res) => {
  const venueId = resolveVenue(req.body?.venueId);
  if (!venueId) {
    throw ApiError.badRequest(`Unknown venue: ${req.body?.venueId}`, 'UNKNOWN_VENUE');
  }
  res.json({ ok: true, room: roomForVenue(venueId) });
});

/**
 * GET /api/chat/messages[?room=&before=&limit=] -> 200 { messages }
 *
 * Persisted BitChat history for reconnect / cold start. Messages are returned
 * in chronological (oldest-first) order. `room` scopes to one bare venue id;
 * omit it for the full thread the Android client renders. `before` is an ISO
 * timestamp cursor for "load more".
 *
 * An unknown `room` value is a 400 rather than a silent empty list, so a typo
 * is not indistinguishable from "this venue has no messages yet".
 */
export const messages = asyncHandler(async (req, res) => {
  const { room, before, limit } = validatedQuery(req);

  let venueId = null;
  if (room) {
    venueId = normalizeRoom(room);
    if (!venueId) {
      throw ApiError.badRequest(`Unknown venue: ${room}`, 'UNKNOWN_VENUE');
    }
  }

  res.json({
    messages: await listRecentChatMessages({ room: venueId, before: before || null, limit })
  });
});
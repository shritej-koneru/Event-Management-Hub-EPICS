import { Server as SocketIOServer } from 'socket.io';
import { verifyToken } from '../auth/jwt.js';
import { isValidVenueId } from '../navigation/venues.js';
import { listEvents } from '../models/eventModel.js';
import { recordAnnouncement, stripRoomPrefix } from '../models/announcementModel.js';

/**
 * BitChat announcement relay.
 *
 * Android integration (android/.../ui/screens/ChatScreen.kt):
 *   - connects to the DEFAULT namespace at path `/socket.io`
 *     (`IO.socket(uri, IO.Options().apply { path = "/socket.io" })`)
 *   - emits `join` with an array of BARE venue ids (no `announce:` prefix)
 *   - listens for `joined` and `event`
 *   - reads `title` and `venue` from the TOP LEVEL of the `event` payload
 *
 * The namespace `/announce` from contract/API-CONTRACT.md §6 is kept working,
 * so both `IO.socket(url)` and `IO.socket(url + "/announce")` receive updates.
 *
 * AUTHENTICATION
 * The Android client connects anonymously, so anonymous listeners are supported
 * and stay that way. What is enforced:
 *   - a token that IS supplied must be valid; an invalid/expired token is
 *     rejected with `connect_error` rather than silently downgrading to
 *     anonymous, so a client with a stale token learns to re-authenticate;
 *   - no token means anonymous, and `socket.data.userId` stays undefined.
 * There are no per-user socket actions today, so nothing else is gated.
 */

/** Socket.io room name for a venue node. */
export const roomForVenue = (venueId) => `announce:${venueId}`;

/** Accepts "room-204" or "announce:room-204" and returns a validated node id. */
export function normalizeRoom(value) {
  if (typeof value !== 'string') return null;
  const id = stripRoomPrefix(value.trim());
  return isValidVenueId(id) ? id : null;
}

/**
 * Resolves the handshake token.
 * @returns {{userId?: string}} `userId` present only for a valid token.
 * @throws when a token was supplied but could not be verified.
 */
export function resolveHandshakeAuth(auth = {}) {
  const token = auth?.token;
  if (token === undefined || token === null || token === '') return {};

  const decoded = verifyToken(token);
  return { userId: decoded.uid };
}

function registerNamespace(namespace) {
  namespace.use((socket, next) => {
    try {
      socket.data = { ...socket.data, ...resolveHandshakeAuth(socket.handshake.auth) };
      next();
    } catch {
      // A token was supplied and it is not usable.
      next(Object.assign(new Error('Invalid or expired token'), { data: { code: 'AUTH_INVALID_TOKEN' } }));
    }
  });

  namespace.on('connection', (socket) => {
    socket.on('join', (rooms, ack) => {
      const requested = Array.isArray(rooms) ? rooms : [rooms];
      const joined = [];
      for (const value of requested) {
        const venueId = normalizeRoom(value);
        if (!venueId) continue; // never create a room for a non-graph venue
        socket.join(roomForVenue(venueId));
        joined.push(roomForVenue(venueId));
      }
      socket.emit('joined', joined);
      if (typeof ack === 'function') ack({ ok: true, joined });
    });

    socket.on('leave', (rooms, ack) => {
      const requested = Array.isArray(rooms) ? rooms : [rooms];
      const left = [];
      for (const value of requested) {
        const venueId = normalizeRoom(value);
        if (!venueId) continue;
        socket.leave(roomForVenue(venueId));
        left.push(roomForVenue(venueId));
      }
      if (typeof ack === 'function') ack({ ok: true, left });
    });

    // Map sync: current events for one venue. Filtering happens in SQL.
    socket.on('events:for', async (venue, ack) => {
      const venueId = normalizeRoom(venue);
      try {
        const { events } = venueId ? await listEvents({ venue: venueId }) : { events: [] };
        if (typeof ack === 'function') ack({ ok: true, events });
      } catch (err) {
        // Never let an async handler rejection become an unhandled rejection:
        // the client is told via the acknowledgement instead.
        if (typeof ack === 'function') {
          ack({ ok: false, error: { code: 'INTERNAL_ERROR', message: 'Could not load events' } });
        }
      }
    });
  });
}

/**
 * Attaches Socket.IO to the HTTP server.
 * @returns {SocketIOServer}
 */
export function attachSocketServer(httpServer, { corsOrigin = true } = {}) {
  const io = new SocketIOServer(httpServer, {
    path: '/socket.io',
    cors: { origin: corsOrigin, credentials: true }
  });

  registerNamespace(io.of('/')); // Android BitChat client
  registerNamespace(io.of('/announce')); // contract §6 namespace

  return io;
}

/**
 * Relays an event change to the venue room.
 *
 * The announcement message embeds the event id so two different events at the
 * same venue produce two distinct messages. Without that, the
 * `(room, message)` uniqueness constraint silently collapsed every subsequent
 * event at a venue into the first one ever announced there.
 *
 * Duplicate protection remains two-layered:
 *  1. `recordAnnouncement` writes through `INSERT ... ON CONFLICT (room, message)
 *     DO NOTHING` against a UNIQUE constraint.
 *  2. `created === false` means the announcement already existed, so nothing is
 *     persisted.
 *
 * @returns {Promise<{ event, announcement, created, persisted }>}
 */
export async function broadcastEventUpdate(io, event, { reason = 'updated' } = {}) {
  const room = roomForVenue(event.venueId);
  const announcement = {
    title:
      reason === 'created'
        ? `New event at ${event.venue}`
        : `${event.title} updated at ${event.venue}`,
    venueId: event.venueId,
    eventId: event.id
  };

  const { created, announcement: persisted } = await recordAnnouncement(
    event.venueId,
    // Keep the id in the stored text so distinct events never collide.
    `${announcement.title} [${event.id}]`,
    // And store it as a real column so the announcement can be looked up by
    // EVENT, not merely by venue: two events can share a venue.
    event.id
  );

  if (io) {
    const payload = {
      event,
      announcement,
      // Flattened fields the Android ChatScreen reads off the top level.
      title: announcement.title,
      venue: event.venue,
      venueId: event.venueId,
      eventId: event.id
    };
    for (const namespace of ['/', '/announce']) {
      io.of(namespace).to(room).emit('event', payload);
    }
  }

  return { event, announcement, created, persisted };
}
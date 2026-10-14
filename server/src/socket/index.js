import { Server as SocketIOServer } from 'socket.io';
import { verifyToken } from '../auth/jwt.js';
import { isValidVenueId } from '../navigation/venues.js';
import { listEvents } from '../models/eventModel.js';
import { findUserById } from '../models/userModel.js';
import { recordAnnouncement, stripRoomPrefix } from '../models/announcementModel.js';
import { insertChatMessage } from '../models/chatMessageModel.js';

/**
 * BitChat: announcements relay + interactive chat.
 *
 * Android integration (android/.../ui/screens/ChatScreen.kt):
 *   - connects to the DEFAULT namespace at path `/socket.io`
 *     (`IO.socket(uri, IO.Options().apply { path = "/socket.io" })`)
 *   - emits `join` with an array of BARE venue ids (no `announce:` prefix)
 *   - listens for `joined` and `event`
 *   - reads `title` and `venue` from the TOP LEVEL of the `event` payload
 *   - emits `chat:message` ({ text }) to talk, and renders every `chat:message`
 *     the server broadcasts back to the namespace (including its own echo)
 *   - loads missed history with GET /api/chat/messages after a reconnect
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
 * Chat authorship follows the same rule: a valid token names the user, no token
 * means the message is attributed to `Guest`.
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

/** Display name of the built-in chat assistant that auto-replies to messages. */
export const ASSISTANT_NAME = 'Event Hub Assistant';

/**
 * Builds a short automatic reply for an incoming chat message. Rule-based and
 * dependency-free (may consult the events table for "what's on" questions).
 * @returns {Promise<string>}
 */
export async function buildAutoReply(text) {
  const t = String(text || '').toLowerCase();
  if (/\b(hi|hello|hey|yo|greetings)\b/.test(t)) {
    return "Hi! I'm the Event Hub assistant. Ask me about upcoming events or a venue \u2014 or type \"help\".";
  }
  if (t.includes('help')) {
    return 'I can tell you what events are coming up, or about venues like "Room 204". Try: "what events are on?"';
  }
  if (/\b(event|events|what'?s on|upcoming|happening|schedule)\b/.test(t)) {
    try {
      const { events } = await listEvents({});
      if (!events.length) return 'There are no events scheduled right now.';
      const top = events.slice(0, 3).map((e) => e.title).join(', ');
      return `There are ${events.length} events coming up. Next: ${top}.`;
    } catch {
      return 'I could not load events just now \u2014 please try again in a moment.';
    }
  }
  if (t.endsWith('?')) {
    return "Good question! I'm a simple demo assistant \u2014 the Events and Map tabs have the full details.";
  }
  return 'Got it \u2014 thanks for your message! Ask me about upcoming events or a venue anytime.';
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

    // Interactive chat: the server is the single writer. It validates the text,
    // badges it with the sender's identity, PERSISTS the row, and only then
    // broadcasts the immutable message to every connected client in this
    // namespace (sender included). A failed write is never broadcast — the
    // ack carries CHAT_PERSIST_FAILED so the UI can retry.
    socket.on('chat:message', async (payload = {}, ack) => {
      const text = typeof payload === 'object' && payload !== null
        ? String(payload.text ?? '').trim()
        : '';
      if (!text) {
        if (typeof ack === 'function') {
          ack({ ok: false, error: { code: 'BAD_CHAT_MESSAGE', message: 'Message text is required' } });
        }
        return;
      }
      if (text.length > 500) {
        if (typeof ack === 'function') {
          ack({ ok: false, error: { code: 'BAD_CHAT_MESSAGE', message: 'Message must be 500 characters or fewer' } });
        }
        return;
      }

      let author = 'Guest';
      if (socket.data.userId) {
        const user = await findUserById(socket.data.userId).catch(() => null);
        author = (user && (user.name || user.email)) || 'Guest';
      }

      const room = normalizeRoom(payload.room);

      let message;
      try {
        message = await insertChatMessage({
          author,
          text,
          userId: socket.data.userId || null,
          room,
          bot: false
        });
      } catch {
        if (typeof ack === 'function') {
          ack({
            ok: false,
            error: { code: 'CHAT_PERSIST_FAILED', message: 'Could not save the message. Please retry.' }
          });
        }
        return;
      }

      namespace.emit('chat:message', message);
      if (typeof ack === 'function') ack({ ok: true, message });

      // Built-in assistant: reply automatically to the sender shortly after the
      // message lands. The reply is persisted too so history survives reloads.
      try {
        const replyText = await buildAutoReply(text);
        setTimeout(async () => {
          if (typeof socket.connected === 'boolean' && !socket.connected) return;
          try {
            const botMessage = await insertChatMessage({
              author: ASSISTANT_NAME,
              text: replyText,
              userId: null,
              room,
              bot: true
            });
            socket.emit('chat:message', botMessage);
          } catch {
            // A failed auto-reply persist must never affect the user's message.
          }
        }, 500);
      } catch {
        // A failed auto-reply must never affect the user's own message.
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
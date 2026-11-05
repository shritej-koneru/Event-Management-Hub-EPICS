import { Server as SocketIOServer } from 'socket.io';
import { verifyToken } from '../auth/jwt.js';
import { isValidVenueId, GLOBAL_ANNOUNCE_ROOM } from '../navigation/registry.js';
import { listEvents } from '../models/eventModel.js';
import { findUserById } from '../models/userModel.js';
import { recordAnnouncement, stripRoomPrefix } from '../models/announcementModel.js';
import { insertChatMessage, insertChatMessageIdempotent } from '../models/chatMessageModel.js';
import { config } from '../config/index.js';

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
 * POSTING chat requires a valid token (ack/`chat:error` code AUTH_REQUIRED);
 * listening and joining stay anonymous. Posting is throttled per socket
 * (CHAT_THROTTLE_MAX messages / CHAT_THROTTLE_WINDOW_MS), excess is dropped with
 * a RATE_LIMITED error. Messages are delivered only to the message's room:
 * `announce:<room>` for a venue message (plus the sender), or the global
 * `chat:global` room (every connected socket auto-joins it) when no room is set.
 * An optional `clientId` makes a resend idempotent (acked, not re-broadcast).
 */

/** Room every socket joins so room-less (global) chat reaches all clients. */
export const GLOBAL_CHAT_ROOM = 'chat:global';
export const CHAT_THROTTLE_MAX = 5;
export const CHAT_THROTTLE_WINDOW_MS = 5000;

/** Socket.IO CORS origin: the same allowlist Express uses (config.corsOrigins). */
export function socketCorsOrigin() {
  if (config.corsOrigins.length) return config.corsOrigins;
  return config.isProd ? false : true;
}

/** Sliding-window limiter: returns true when the call is allowed. */
function allowChat(socket, now = Date.now()) {
  const hits = (socket.data.chatHits ??= []);
  while (hits.length && now - hits[0] >= CHAT_THROTTLE_WINDOW_MS) hits.shift();
  if (hits.length >= CHAT_THROTTLE_MAX) return false;
  hits.push(now);
  return true;
}


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
    } catch {
      // A token was supplied and it is not usable.
      return next(Object.assign(new Error('Invalid or expired token'), { data: { code: 'AUTH_INVALID_TOKEN' } }));
    }
    joinSupportRooms(socket).then(() => next(), () => next());
  });

  namespace.on('connection', (socket) => {
    socket.join(GLOBAL_CHAT_ROOM);

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
      const fail = (code, message) => {
        socket.emit('chat:error', { code, message });
        if (typeof ack === 'function') ack({ ok: false, error: { code, message } });
      };

      if (!socket.data.userId) {
        fail('AUTH_REQUIRED', 'Sign in to send chat messages');
        return;
      }
      if (!allowChat(socket)) {
        fail('RATE_LIMITED', 'You are sending messages too fast. Slow down.');
        return;
      }

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

      const rawClientId = payload?.clientId;
      if (rawClientId !== undefined && rawClientId !== null &&
          (typeof rawClientId !== 'string' || !rawClientId.trim() || rawClientId.length > 64)) {
        fail('BAD_CHAT_MESSAGE', 'clientId must be a non-empty string of at most 64 characters');
        return;
      }
      const clientId = typeof rawClientId === 'string' ? rawClientId.trim() : null;

      const user = await findUserById(socket.data.userId).catch(() => null);
      if (!user) {
        fail('AUTH_REQUIRED', 'Account no longer exists');
        return;
      }
      const author = user.name || user.email || 'Guest';

      const room = normalizeRoom(payload?.room);

      let message;
      try {
        const stored = await insertChatMessageIdempotent({
          author,
          text,
          userId: socket.data.userId,
          room,
          bot: false,
          clientId
        });
        message = stored.message;
        if (stored.duplicate) {
          // Resend of a message we already hold: ack it, do not re-broadcast.
          if (typeof ack === 'function') ack({ ok: true, message, duplicate: true });
          return;
        }
      } catch {
        if (typeof ack === 'function') {
          ack({
            ok: false,
            error: { code: 'CHAT_PERSIST_FAILED', message: 'Could not save the message. Please retry.' }
          });
        }
        return;
      }

      // Deliver only to the message's room (plus the sender's own socket).
      const target = room ? roomForVenue(room) : GLOBAL_CHAT_ROOM;
      namespace.to(target).to(socket.id).emit('chat:message', message);
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
export function attachSocketServer(httpServer, { corsOrigin = socketCorsOrigin() } = {}) {
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
        ? `New event: ${event.title} at ${event.venue}`
        : reason === 'deleted'
          ? `Cancelled: ${event.title}`
          : `Updated: ${event.title}`,
    venueId: event.venueId,
    eventId: event.id
  };

  // A deleted event no longer exists, so announcements.event_id (FK) could not
  // reference it: the notice is relayed live but not persisted.
  const { created, announcement: persisted } = reason === 'deleted'
    ? { created: false, announcement: null }
    : await recordAnnouncement(
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
      eventId: event.id,
      reason
    };
    for (const namespace of ['/', '/announce']) {
      io.of(namespace).to(room).emit('event', payload);
    }
  }

  return { event, announcement, created, persisted };
}

/** Namespaces every broadcast is mirrored to (Android uses '/', contract §6 uses '/announce'). */
const NAMESPACES = ['/', '/announce'];

/**
 * Relays a manager announcement live using the SAME `event` socket event and
 * top-level fields (`title`, `venue`) the Android ChatViewModel already reads.
 *
 *   venue/event announcement -> emitted to room `announce:<venueId>`
 *   global announcement      -> emitted to EVERY connected client of the
 *                               namespace (no room membership needed)
 *
 * @param {object} announcement  { id, room, message, at, eventId, sentBy }
 * @param {{venueName?: string|null, event?: object|null}} [extra]
 */
export function broadcastAnnouncement(io, announcement, { venueName = null, event = null } = {}) {
  if (!io) return null;
  const isGlobal = announcement.room === GLOBAL_ANNOUNCE_ROOM;
  const venueId = isGlobal ? null : announcement.room;
  const payload = {
    event,
    announcement: {
      ...announcement,
      title: announcement.message,
      venueId
    },
    title: announcement.message,
    venue: isGlobal ? 'All venues' : (venueName ?? announcement.room),
    venueId,
    eventId: announcement.eventId ?? null,
    reason: 'announcement',
    global: isGlobal
  };
  for (const namespace of NAMESPACES) {
    const target = io.of(namespace);
    if (isGlobal) target.emit('event', payload);
    else target.to(roomForVenue(venueId)).emit('event', payload);
  }
  return payload;
}

/** Tells every client (both namespaces) that the campus map changed. */
export function broadcastMapUpdated(io) {
  if (!io) return null;
  const payload = { at: new Date().toISOString() };
  for (const namespace of NAMESPACES) io.of(namespace).emit('map:updated', payload);
  return payload;
}

/* -------------------------------------------------------------------------- */
/* Help desk realtime (contract 1.6.0)                                         */
/* -------------------------------------------------------------------------- */

export const userRoom = (userId) => `user:${userId}`;
export const managersRoom = (userId) => `managers:${userId}`;
export const MANAGERS_ALL_ROOM = 'managers:all';

/**
 * Joins the per-user / per-manager rooms from the VERIFIED handshake identity.
 * The role is read from the database at connect time; the client can never
 * name these rooms (`join` only accepts venue ids). Anonymous sockets join none.
 */
export async function joinSupportRooms(socket) {
  const userId = socket.data?.userId;
  if (!userId) return;
  const user = await findUserById(userId);
  if (!user) return;
  socket.join(userRoom(user.id));
  if (user.role === 'organizer' || user.role === 'admin') socket.join(managersRoom(user.id));
  if (user.role === 'admin') socket.join(MANAGERS_ALL_ROOM);
}

function emitSupport(io, ticket, eventCreatedBy, name, payload) {
  if (!io) return;
  for (const ns of NAMESPACES) {
    let target = io.of(ns).to(userRoom(ticket.userId)).to(MANAGERS_ALL_ROOM);
    if (eventCreatedBy) target = target.to(managersRoom(eventCreatedBy));
    target.emit(name, payload);
  }
}

/** `support:message` { ticket, message } -> ticket owner, event creator, admins. */
export function broadcastSupportMessage(io, ticket, message, eventCreatedBy) {
  emitSupport(io, ticket, eventCreatedBy, 'support:message', { ticket, message });
}

/** `support:ticket` { ticket } (created / status changed). */
export function broadcastSupportTicket(io, ticket, eventCreatedBy) {
  emitSupport(io, ticket, eventCreatedBy, 'support:ticket', { ticket });
}

/** Wires every server -> socket broadcast hook onto `app.locals`. */
export function bindSocketBroadcasts(app, io) {
  app.locals.io = io;
  app.locals.broadcastEvent = (event, options) => broadcastEventUpdate(io, event, options);
  app.locals.broadcastAnnouncement = (announcement, extra) => broadcastAnnouncement(io, announcement, extra);
  app.locals.broadcastMapUpdated = () => broadcastMapUpdated(io);
  app.locals.broadcastSupportMessage = (ticket, message, creator) =>
    broadcastSupportMessage(io, ticket, message, creator);
  app.locals.broadcastSupportTicket = (ticket, creator) => broadcastSupportTicket(io, ticket, creator);
}

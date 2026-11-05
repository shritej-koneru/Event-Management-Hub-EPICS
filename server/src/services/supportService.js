import { ApiError } from '../utils/errors.js';
import { findEventById, findEventOwner } from '../models/eventModel.js';
import { isEventBookmarked } from '../models/bookmarkModel.js';
import { listAnnouncementsForEventFeed } from '../models/announcementModel.js';
import * as db from '../models/supportModel.js';

/**
 * Help / support desk. Attendees (any authenticated role, acting as a user)
 * open chats about events they are registered (bookmarked) for; the event
 * creator (organizer) or any admin reads and answers them.
 */

export const SUPPORT_THROTTLE_MAX = 20;
export const SUPPORT_THROTTLE_WINDOW_MS = 60_000;
const hitsByUser = new Map();

/** Sliding-window limiter, per user. Returns true when allowed. */
export function allowSupportMessage(userId, now = Date.now()) {
  const hits = (hitsByUser.get(userId) ?? []).filter((t) => now - t < SUPPORT_THROTTLE_WINDOW_MS);
  const allowed = hits.length < SUPPORT_THROTTLE_MAX;
  if (allowed) hits.push(now);
  hitsByUser.set(userId, hits);
  return allowed;
}

/** Test hook. */
export function resetSupportThrottle() {
  hitsByUser.clear();
}

function throttle(userId) {
  if (!allowSupportMessage(userId)) {
    throw new ApiError(
      429,
      `At most ${SUPPORT_THROTTLE_MAX} support messages per minute. Try again shortly.`,
      'RATE_LIMITED'
    );
  }
}

const isAdmin = (user) => user.role === 'admin';
const notFound = () => ApiError.notFound('Ticket not found', 'TICKET_NOT_FOUND');

/* ------------------------------ public feed ------------------------------ */

export async function eventAnnouncements(eventId) {
  const event = await findEventById(eventId);
  if (!event) throw ApiError.notFound(`Event not found: ${eventId}`, 'EVENT_NOT_FOUND');
  return listAnnouncementsForEventFeed(eventId);
}

/* ------------------------------- attendee -------------------------------- */

export function myTickets(userId, eventId) {
  return db.listTicketsForUser(userId, { eventId: eventId ?? null });
}

async function ownTicket(userId, ticketId) {
  const found = await db.findTicket(ticketId);
  // Another user's ticket is indistinguishable from a missing one.
  if (!found || found.ticket.userId !== userId) throw notFound();
  return found;
}

/** @returns {{ ticket, message, eventCreatedBy, duplicate }} */
export async function openTicket(userId, { eventId, subject, message, clientId }) {
  const event = await findEventById(eventId);
  if (!event) throw ApiError.notFound(`Event not found: ${eventId}`, 'EVENT_NOT_FOUND');
  if (!(await isEventBookmarked(userId, eventId))) {
    throw ApiError.forbidden('Register for this event to contact its organizers', 'NOT_REGISTERED');
  }
  if (clientId) {
    const existing = await db.findMessageByClientId(userId, clientId);
    if (existing) {
      const found = await db.findTicket(existing.ticketId);
      return { ticket: found.ticket, message: existing, eventCreatedBy: found.eventCreatedBy, duplicate: true };
    }
  }
  throttle(userId);
  const { ticketId, messageId } = await db.createTicketWithMessage({
    eventId,
    userId,
    subject,
    body: message,
    clientId: clientId ?? null
  });
  const found = await db.findTicket(ticketId);
  return {
    ticket: found.ticket,
    message: await db.findMessage(messageId),
    eventCreatedBy: found.eventCreatedBy,
    duplicate: false
  };
}

export async function myThread(userId, ticketId) {
  const { ticket } = await ownTicket(userId, ticketId);
  return { ticket, messages: await db.listMessages(ticketId) };
}

async function post(found, senderId, senderRole, { body, clientId }) {
  throttle(senderId);
  const { duplicate, message } = await db.appendMessage({
    ticketId: found.ticket.id,
    senderId,
    senderRole,
    body,
    clientId: clientId ?? null
  });
  if (duplicate && message.ticketId !== found.ticket.id) {
    throw ApiError.conflict('clientId was already used for another ticket', 'DUPLICATE_CLIENT_ID');
  }
  const fresh = await db.findTicket(found.ticket.id);
  return {
    ticket: fresh.ticket,
    message,
    eventCreatedBy: fresh.eventCreatedBy,
    duplicate,
    reopened: !duplicate && found.ticket.status === 'closed' && fresh.ticket.status === 'open'
  };
}

export async function userReply(userId, ticketId, input) {
  const found = await ownTicket(userId, ticketId);
  return post(found, userId, 'user', input);
}

/* -------------------------------- manager -------------------------------- */

async function assertOwnsEvent(user, eventId) {
  const { exists, createdBy } = await findEventOwner(eventId);
  if (!exists) throw ApiError.notFound(`Event not found: ${eventId}`, 'EVENT_NOT_FOUND');
  if (!isAdmin(user) && createdBy !== user.id) {
    throw ApiError.forbidden('You can only manage help chats for events you created', 'FORBIDDEN');
  }
}

async function managedTicket(user, ticketId) {
  const found = await db.findTicket(ticketId);
  // Tickets of events the organizer does not own are hidden (404), not leaked.
  if (!found || (!isAdmin(user) && found.eventCreatedBy !== user.id)) throw notFound();
  return found;
}

export function supportEvents(user) {
  return db.listSupportEvents({ createdBy: isAdmin(user) ? null : user.id });
}

export async function managerTickets(user, { eventId, status = 'all' }) {
  await assertOwnsEvent(user, eventId);
  return db.listTicketsForEvent(eventId, { status });
}

export async function managerThread(user, ticketId) {
  const { ticket } = await managedTicket(user, ticketId);
  return { ticket, messages: await db.listMessages(ticketId) };
}

export async function managerReply(user, ticketId, input) {
  const found = await managedTicket(user, ticketId);
  return post(found, user.id, 'manager', input);
}

export async function managerSetStatus(user, ticketId, status) {
  const found = await managedTicket(user, ticketId);
  const changed = found.ticket.status !== status;
  if (changed) await db.setTicketStatus(ticketId, status);
  const fresh = await db.findTicket(ticketId);
  return { ticket: fresh.ticket, eventCreatedBy: fresh.eventCreatedBy, changed };
}

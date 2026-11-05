import { asyncHandler } from '../utils/asyncHandler.js';
import { validatedQuery } from '../middleware/validate.js';
import * as support from '../services/supportService.js';

const emitMessage = (req, r) =>
  req.app.locals.broadcastSupportMessage?.(r.ticket, r.message, r.eventCreatedBy);
const emitTicket = (req, r) => req.app.locals.broadcastSupportTicket?.(r.ticket, r.eventCreatedBy);

/** GET /api/events/:id/announcements */
export const eventAnnouncements = asyncHandler(async (req, res) => {
  res.json({ announcements: await support.eventAnnouncements(req.params.id) });
});

/* ------------------------------- attendee -------------------------------- */

export const myTickets = asyncHandler(async (req, res) => {
  const { eventId } = validatedQuery(req);
  res.json({ tickets: await support.myTickets(req.auth.uid, eventId) });
});

export const createTicket = asyncHandler(async (req, res) => {
  const r = await support.openTicket(req.auth.uid, req.body);
  if (!r.duplicate) {
    emitTicket(req, r);
    emitMessage(req, r);
  }
  res.status(r.duplicate ? 200 : 201).json({ ticket: r.ticket, message: r.message });
});

export const myThread = asyncHandler(async (req, res) => {
  res.json(await support.myThread(req.auth.uid, req.params.id));
});

export const userReply = asyncHandler(async (req, res) => {
  const r = await support.userReply(req.auth.uid, req.params.id, req.body);
  if (!r.duplicate) {
    if (r.reopened) emitTicket(req, r);
    emitMessage(req, r);
  }
  res.status(r.duplicate ? 200 : 201).json({ message: r.message });
});

/* -------------------------------- manager -------------------------------- */

export const supportEvents = asyncHandler(async (req, res) => {
  res.json({ events: await support.supportEvents(req.user) });
});

export const managerTickets = asyncHandler(async (req, res) => {
  res.json({ tickets: await support.managerTickets(req.user, validatedQuery(req)) });
});

export const managerThread = asyncHandler(async (req, res) => {
  res.json(await support.managerThread(req.user, req.params.id));
});

export const managerReply = asyncHandler(async (req, res) => {
  const r = await support.managerReply(req.user, req.params.id, req.body);
  if (!r.duplicate) emitMessage(req, r);
  res.status(r.duplicate ? 200 : 201).json({ message: r.message });
});

export const managerSetStatus = asyncHandler(async (req, res) => {
  const r = await support.managerSetStatus(req.user, req.params.id, req.body.status);
  if (r.changed) emitTicket(req, r);
  res.json({ ticket: r.ticket });
});

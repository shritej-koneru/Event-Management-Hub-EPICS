import { asyncHandler } from '../utils/asyncHandler.js';
import { validatedQuery } from '../middleware/validate.js';
import * as manager from '../services/managerService.js';

/** Fire the live `map:updated` socket event after any map write. */
const mapChanged = (req) => req.app.locals.broadcastMapUpdated?.();

export const overview = asyncHandler(async (req, res) => {
  res.json(await manager.overview(req.user));
});

export const events = asyncHandler(async (req, res) => {
  const { scope } = validatedQuery(req);
  res.json({ events: await manager.managedEvents(req.user, scope) });
});

export const createVenue = asyncHandler(async (req, res) => {
  const venue = await manager.createVenue(req.body);
  mapChanged(req);
  res.status(201).json(venue);
});

export const updateVenue = asyncHandler(async (req, res) => {
  const venue = await manager.updateVenue(req.params.id, req.body);
  mapChanged(req);
  res.json(venue);
});

export const deleteVenue = asyncHandler(async (req, res) => {
  const result = await manager.deleteVenue(req.params.id);
  mapChanged(req);
  res.json(result);
});

export const createEdge = asyncHandler(async (req, res) => {
  const edge = await manager.createEdge(req.body);
  mapChanged(req);
  res.status(201).json(edge);
});

export const updateEdge = asyncHandler(async (req, res) => {
  const edge = await manager.updateEdge(req.body);
  mapChanged(req);
  res.json(edge);
});

export const deleteEdge = asyncHandler(async (req, res) => {
  const result = await manager.deleteEdge(validatedQuery(req));
  mapChanged(req);
  res.json(result);
});

export const createAnnouncement = asyncHandler(async (req, res) => {
  const { announcement, venueName, event } = await manager.sendAnnouncement(req.user, req.body);
  req.app.locals.broadcastAnnouncement?.(announcement, { venueName, event });
  res.status(201).json({ announcement });
});

export const listAnnouncements = asyncHandler(async (req, res) => {
  const { scope } = validatedQuery(req);
  res.json({ announcements: await manager.managedAnnouncements(req.user, scope) });
});

export const deleteAnnouncement = asyncHandler(async (req, res) => {
  res.json(await manager.removeAnnouncement(req.user, req.params.id));
});

export const listUsers = asyncHandler(async (req, res) => {
  res.json({ users: await manager.listAccounts() });
});

export const createUser = asyncHandler(async (req, res) => {
  res.status(201).json(await manager.createAccount(req.body));
});

export const updateUser = asyncHandler(async (req, res) => {
  res.json(await manager.changeAccountRole(req.user, req.params.id, req.body.role));
});

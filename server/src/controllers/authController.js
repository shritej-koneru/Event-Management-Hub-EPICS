import bcrypt from 'bcryptjs';
import { config } from '../config/index.js';
import { signToken } from '../auth/jwt.js';
import { newId } from '../utils/id.js';
import { ApiError } from '../utils/errors.js';
import { asyncHandler } from '../utils/asyncHandler.js';
import { findUserByEmail, findUserById, insertUser, toPublicUser, updateInterests } from '../models/userModel.js';
import { listBookmarkIds, addBookmark, removeBookmark } from '../models/bookmarkModel.js';
import { findEventById } from '../models/eventModel.js';

/** Rejects a bookmark for an event that does not exist instead of storing a dangling id. */
async function requireExistingEvent(eventId) {
  const event = await findEventById(eventId);
  if (!event) throw ApiError.notFound(`Event not found: ${eventId}`, 'EVENT_NOT_FOUND');
  return event;
}

/** POST /api/auth/register -> 201 { token, user } */
export const register = asyncHandler(async (req, res) => {
  const { name, email, password, interests = [] } = req.body;

  // Fast path. The unique index below is still the authority: two concurrent
  // registrations can both pass this check, and the loser must get a clean 409
  // rather than a raw 500 from the driver.
  if (await findUserByEmail(email)) {
    throw ApiError.conflict('Email already registered', 'EMAIL_TAKEN');
  }

  const passwordHash = await bcrypt.hash(password, config.bcryptRounds);

  let user;
  try {
    user = await insertUser({
      id: newId('usr'),
      name,
      email,
      passwordHash,
      interests
    });
  } catch (err) {
    if (err?.code === '23505') {
      throw ApiError.conflict('Email already registered', 'EMAIL_TAKEN');
    }
    throw err;
  }

  res.status(201).json({ token: signToken(user), user: toPublicUser(user) });
});

/** POST /api/auth/login -> 200 { token, user } */
export const login = asyncHandler(async (req, res) => {
  const { email, password } = req.body;

  const user = await findUserByEmail(email);
  // Same response for unknown email and wrong password — no user enumeration.
  if (!user || !(await bcrypt.compare(password, user.password_hash))) {
    throw ApiError.unauthorized('Invalid email or password', 'INVALID_CREDENTIALS');
  }

  res.json({ token: signToken(user), user: toPublicUser(user) });
});

/** GET /api/auth/me -> 200 { user } (requires JWT) */
export const me = asyncHandler(async (req, res) => {
  const user = await findUserById(req.auth.uid);
  if (!user) throw ApiError.notFound('User not found', 'USER_NOT_FOUND');

  const bookmarks = await listBookmarkIds(user.id);
  res.json({ user: toPublicUser(user, { bookmarks }) });
});

/** PUT /api/auth/me/interests -> 200 { user } (requires JWT) */
export const updateInterestsHandler = asyncHandler(async (req, res) => {
  const user = await updateInterests(req.auth.uid, req.body.interests);
  if (!user) throw ApiError.notFound('User not found', 'USER_NOT_FOUND');
  res.json({ user: toPublicUser(user) });
});

/**
 * POST /api/auth/me/bookmarks/:eventId -> 200 { bookmarked, eventId, bookmarks }
 *
 * Idempotent: bookmarking an already-bookmarked event succeeds and simply
 * reports the existing state, so a retried request never 500s on the
 * (user_id, event_id) primary key.
 */
export const addBookmarkHandler = asyncHandler(async (req, res) => {
  const { eventId } = req.params;
  await requireExistingEvent(eventId);

  await addBookmark(req.auth.uid, eventId);
  const bookmarks = await listBookmarkIds(req.auth.uid);

  res.json({ bookmarked: true, eventId, bookmarks });
});

/**
 * DELETE /api/auth/me/bookmarks/:eventId -> 200 { bookmarked, eventId, bookmarks }
 *
 * Idempotent for a real event: removing an absent bookmark still succeeds. An
 * event that does not exist at all is a 404, matching POST.
 */
export const removeBookmarkHandler = asyncHandler(async (req, res) => {
  const { eventId } = req.params;
  await requireExistingEvent(eventId);

  await removeBookmark(req.auth.uid, eventId);
  const bookmarks = await listBookmarkIds(req.auth.uid);

  res.json({ bookmarked: false, eventId, bookmarks });
});

/** GET /api/auth/me/bookmarks -> 200 { bookmarks } (requires JWT) */
export const listBookmarksHandler = asyncHandler(async (req, res) => {
  res.json({ bookmarks: await listBookmarkIds(req.auth.uid) });
});
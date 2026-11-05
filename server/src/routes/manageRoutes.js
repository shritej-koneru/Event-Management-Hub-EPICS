import { Router } from 'express';
import { requireAuth, requireRole } from '../middleware/auth.js';
import { validate } from '../middleware/validate.js';
import {
  manageScopeQuerySchema,
  createVenueSchema,
  updateVenueSchema,
  manageVenueParamsSchema,
  edgeBodySchema,
  edgeQuerySchema,
  createAnnouncementSchema,
  announcementIdParamSchema,
  adminCreateUserSchema,
  adminUpdateUserSchema,
  userIdParamSchema
} from '../middleware/validators.js';
import * as manage from '../controllers/manageController.js';

/**
 * Event-manager console API. Every route needs a JWT and an organizer/admin
 * role (students get 403 FORBIDDEN). Mounted at /api/manage.
 */
const router = Router();
router.use(requireAuth, requireRole('organizer', 'admin'));

router.get('/overview', manage.overview);
router.get('/events', validate({ query: manageScopeQuerySchema }), manage.events);

// Map: venues (nodes). Shared campus map - organizer + admin, no ownership.
router.post('/venues', validate({ body: createVenueSchema }), manage.createVenue);
router.put('/venues/:id', validate({ params: manageVenueParamsSchema, body: updateVenueSchema }), manage.updateVenue);
router.delete('/venues/:id', validate({ params: manageVenueParamsSchema }), manage.deleteVenue);

// Map: edges (walkable paths), order-insensitive endpoints.
router.post('/edges', validate({ body: edgeBodySchema }), manage.createEdge);
router.put('/edges', validate({ body: edgeBodySchema }), manage.updateEdge);
router.delete('/edges', validate({ query: edgeQuerySchema }), manage.deleteEdge);

// Announcements.
router.get('/announcements', validate({ query: manageScopeQuerySchema }), manage.listAnnouncements);
router.post('/announcements', validate({ body: createAnnouncementSchema }), manage.createAnnouncement);
router.delete(
  '/announcements/:id',
  validate({ params: announcementIdParamSchema }),
  manage.deleteAnnouncement
);

// Accounts: admin only (organizers get 403 FORBIDDEN).
router.get('/users', requireRole('admin'), manage.listUsers);
router.post('/users', requireRole('admin'), validate({ body: adminCreateUserSchema }), manage.createUser);
router.put(
  '/users/:id',
  requireRole('admin'),
  validate({ params: userIdParamSchema, body: adminUpdateUserSchema }),
  manage.updateUser
);

export default router;

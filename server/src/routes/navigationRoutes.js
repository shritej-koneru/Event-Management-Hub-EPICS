import { Router } from 'express';
import { validate } from '../middleware/validate.js';
import { navigationPathSchema, navigationVenueMapParamsSchema } from '../middleware/validators.js';
import * as navigation from '../controllers/navigationController.js';

const router = Router();

// GET /navigation/venues -> 200
router.get('/venues', navigation.venues);

// GET /navigation/map -> 200 { venues, edges } (stored canvas layout + graph)
router.get('/map', navigation.map);

// GET /navigation/venues/:venueId/map -> 200 { venue, neighbors } | 400 UNKNOWN_VENUE
router.get(
  '/venues/:venueId/map',
  validate({ params: navigationVenueMapParamsSchema }),
  navigation.venueMap
);

// POST /navigation/path { from, to } -> 200 | 400 UNKNOWN_VENUE
router.post('/path', validate({ body: navigationPathSchema }), navigation.path);

export default router;
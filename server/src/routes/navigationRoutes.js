import { Router } from 'express';
import { validate } from '../middleware/validate.js';
import { navigationPathSchema } from '../middleware/validators.js';
import * as navigation from '../controllers/navigationController.js';

const router = Router();

// GET /navigation/venues -> 200
router.get('/venues', navigation.venues);

// POST /navigation/path { from, to } -> 200 | 400 UNKNOWN_VENUE
router.post('/path', validate({ body: navigationPathSchema }), navigation.path);

export default router;
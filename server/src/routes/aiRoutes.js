import { Router } from 'express';
import { optionalAuth } from '../middleware/auth.js';
import { validate } from '../middleware/validate.js';
import { aiSearchSchema } from '../middleware/validators.js';
import * as ai from '../controllers/aiController.js';

const router = Router();

// POST /ai/search -> 200 (optional auth: personalized when a JWT is present)
router.post('/search', optionalAuth, validate({ body: aiSearchSchema }), ai.search);

// GET /ai/recommendations -> 200 (requires JWT, enforced in the controller)
router.get('/recommendations', optionalAuth, ai.recommendations);

export default router;
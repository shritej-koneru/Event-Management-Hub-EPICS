import { Router } from 'express';
import authRoutes from './authRoutes.js';
import eventRoutes from './eventRoutes.js';
import aiRoutes from './aiRoutes.js';
import navigationRoutes from './navigationRoutes.js';
import chatRoutes from './chatRoutes.js';

/**
 * All versioned API routes.
 *
 * The Android base URL is `http://10.0.2.2:3000/` and Retrofit paths already
 * carry the `api/` prefix (see android/.../data/api/HubApi.kt), so everything
 * business-related hangs off `/api`.
 */
const router = Router();

router.use('/auth', authRoutes);
router.use('/events', eventRoutes);
router.use('/ai', aiRoutes);
router.use('/navigation', navigationRoutes);
router.use('/chat', chatRoutes);

export default router;
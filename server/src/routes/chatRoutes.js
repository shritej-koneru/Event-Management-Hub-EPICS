import { Router } from 'express';
import { optionalAuth } from '../middleware/auth.js';
import { validate } from '../middleware/validate.js';
import { announcementQuerySchema, chatMessageQuerySchema } from '../middleware/validators.js';
import * as chat from '../controllers/chatController.js';

const router = Router();

// GET /chat/announcements[?eventId=] -> 200 { announcements }
router.get('/announcements', optionalAuth, validate({ query: announcementQuerySchema }), chat.announcements);

// GET /chat/messages[?room=&before=&limit=] -> 200 { messages }
router.get('/messages', optionalAuth, validate({ query: chatMessageQuerySchema }), chat.messages);

// POST /chat/join { venueId } -> 200
router.post('/join', chat.join);

export default router;
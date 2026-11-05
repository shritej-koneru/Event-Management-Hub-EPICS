import { Router } from 'express';
import { requireAuth } from '../middleware/auth.js';
import { validate } from '../middleware/validate.js';
import {
  supportTicketIdParamSchema,
  supportTicketQuerySchema,
  createSupportTicketSchema,
  supportMessageSchema
} from '../middleware/validators.js';
import * as support from '../controllers/supportController.js';

/**
 * Attendee help desk. Mounted at /api/support. Any authenticated user may use
 * it (a user only ever sees their own tickets).
 */
const router = Router();
router.use(requireAuth);

router.get('/tickets', validate({ query: supportTicketQuerySchema }), support.myTickets);
router.post('/tickets', validate({ body: createSupportTicketSchema }), support.createTicket);
router.get('/tickets/:id/messages', validate({ params: supportTicketIdParamSchema }), support.myThread);
router.post(
  '/tickets/:id/messages',
  validate({ params: supportTicketIdParamSchema, body: supportMessageSchema }),
  support.userReply
);

export default router;

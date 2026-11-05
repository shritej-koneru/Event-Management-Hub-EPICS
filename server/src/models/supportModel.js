import { getPool } from '../db/pool.js';

const iso = (v) => (v instanceof Date ? v.toISOString() : v);

const TICKET_SELECT = `
  SELECT t.id, t.event_id, e.title AS event_title, e.created_by AS event_created_by,
         t.user_id, u.name AS user_name, t.subject, t.status,
         t.created_at, t.updated_at, t.last_message_at,
         lm.body AS last_body, lm.sender_role AS last_role, lm.created_at AS last_at,
         (SELECT COUNT(*)::int FROM support_messages c WHERE c.ticket_id = t.id) AS message_count
    FROM support_tickets t
    JOIN events e ON e.id = t.event_id
    JOIN users u ON u.id = t.user_id
    LEFT JOIN LATERAL (
      SELECT body, sender_role, created_at FROM support_messages m
       WHERE m.ticket_id = t.id ORDER BY m.id DESC LIMIT 1
    ) lm ON true`;

function toTicket(row) {
  return {
    id: row.id,
    eventId: row.event_id,
    eventTitle: row.event_title,
    userId: row.user_id,
    userName: row.user_name,
    subject: row.subject,
    status: row.status,
    createdAt: iso(row.created_at),
    updatedAt: iso(row.updated_at),
    lastMessageAt: iso(row.last_message_at),
    lastMessage:
      row.last_body == null
        ? null
        : { body: row.last_body, senderRole: row.last_role, createdAt: iso(row.last_at) },
    messageCount: row.message_count
  };
}

function toMessage(row) {
  return {
    id: row.id,
    ticketId: row.ticket_id,
    senderId: row.sender_id ?? null,
    senderName: row.sender_name ?? null,
    senderRole: row.sender_role,
    body: row.body,
    createdAt: iso(row.created_at),
    ...(row.client_id ? { clientId: row.client_id } : {})
  };
}

const MESSAGE_SELECT = `
  SELECT m.id, m.ticket_id, m.sender_id, u.name AS sender_name, m.sender_role,
         m.body, m.client_id, m.created_at
    FROM support_messages m
    LEFT JOIN users u ON u.id = m.sender_id`;

/** Ticket plus the event creator id (needed for ownership + realtime routing). */
export async function findTicket(id) {
  const { rows } = await getPool().query(`${TICKET_SELECT} WHERE t.id = $1`, [id]);
  return rows[0] ? { ticket: toTicket(rows[0]), eventCreatedBy: rows[0].event_created_by ?? null } : null;
}

export async function listTicketsForUser(userId, { eventId = null } = {}) {
  const { rows } = await getPool().query(
    `${TICKET_SELECT}
      WHERE t.user_id = $1 AND ($2::text IS NULL OR t.event_id = $2)
      ORDER BY t.last_message_at DESC, t.id DESC`,
    [userId, eventId]
  );
  return rows.map(toTicket);
}

export async function listTicketsForEvent(eventId, { status = 'all' } = {}) {
  const { rows } = await getPool().query(
    `${TICKET_SELECT}
      WHERE t.event_id = $1 AND ($2::text = 'all' OR t.status = $2)
      ORDER BY t.last_message_at DESC, t.id DESC`,
    [eventId, status]
  );
  return rows.map(toTicket);
}

/** Creates a ticket and its first message atomically. */
export async function createTicketWithMessage({ eventId, userId, subject, body, clientId = null }) {
  const client = await getPool().connect();
  try {
    await client.query('BEGIN');
    const t = await client.query(
      'INSERT INTO support_tickets (event_id, user_id, subject) VALUES ($1, $2, $3) RETURNING id',
      [eventId, userId, subject]
    );
    const ticketId = t.rows[0].id;
    const m = await client.query(
      `INSERT INTO support_messages (ticket_id, sender_id, sender_role, body, client_id)
       VALUES ($1, $2, 'user', $3, $4) RETURNING id`,
      [ticketId, userId, body, clientId]
    );
    await client.query('COMMIT');
    return { ticketId, messageId: m.rows[0].id };
  } catch (err) {
    await client.query('ROLLBACK').catch(() => {});
    throw err;
  } finally {
    client.release();
  }
}

export async function findMessage(id) {
  const { rows } = await getPool().query(`${MESSAGE_SELECT} WHERE m.id = $1`, [id]);
  return rows[0] ? toMessage(rows[0]) : null;
}

export async function findMessageByClientId(senderId, clientId) {
  const { rows } = await getPool().query(
    `${MESSAGE_SELECT} WHERE m.sender_id = $1 AND m.client_id = $2`,
    [senderId, clientId]
  );
  return rows[0] ? toMessage(rows[0]) : null;
}

export async function listMessages(ticketId) {
  const { rows } = await getPool().query(`${MESSAGE_SELECT} WHERE m.ticket_id = $1 ORDER BY m.id ASC`, [ticketId]);
  return rows.map(toMessage);
}

/**
 * Appends a message and bumps the ticket. A user message reopens a closed
 * ticket. Idempotent on (senderId, clientId): returns { duplicate: true }.
 */
export async function appendMessage({ ticketId, senderId, senderRole, body, clientId = null }) {
  const pool = getPool();
  const { rows, rowCount } = await pool.query(
    `INSERT INTO support_messages (ticket_id, sender_id, sender_role, body, client_id)
     VALUES ($1, $2, $3, $4, $5)
     ON CONFLICT (sender_id, client_id) WHERE client_id IS NOT NULL DO NOTHING
     RETURNING id`,
    [ticketId, senderId, senderRole, body, clientId]
  );
  if (rowCount === 0) {
    return { duplicate: true, message: await findMessageByClientId(senderId, clientId) };
  }
  await pool.query(
    `UPDATE support_tickets
        SET last_message_at = now(), updated_at = now(),
            status = CASE WHEN $2 = 'user' THEN 'open' ELSE status END
      WHERE id = $1`,
    [ticketId, senderRole]
  );
  return { duplicate: false, message: await findMessage(rows[0].id) };
}

export async function setTicketStatus(ticketId, status) {
  await getPool().query('UPDATE support_tickets SET status = $2, updated_at = now() WHERE id = $1', [
    ticketId,
    status
  ]);
}

/** Events in scope (creator-restricted, or all) with chat counters. */
export async function listSupportEvents({ createdBy = null } = {}) {
  const { rows } = await getPool().query(
    `SELECT e.id, e.title, e.date, v.name AS venue,
            COUNT(t.id) FILTER (WHERE t.status = 'open')::int AS open_count,
            COUNT(t.id)::int AS total_count,
            MAX(t.last_message_at) AS last_message_at
       FROM events e
       JOIN venues v ON v.id = e.venue_id
       LEFT JOIN support_tickets t ON t.event_id = e.id
      WHERE ($1::text IS NULL OR e.created_by = $1)
      GROUP BY e.id, v.name
      ORDER BY MAX(t.last_message_at) DESC NULLS LAST, e.date ASC, e.id ASC`,
    [createdBy]
  );
  return rows.map((r) => ({
    id: r.id,
    title: r.title,
    date: r.date instanceof Date ? r.date.toISOString().slice(0, 10) : r.date,
    venue: r.venue,
    openCount: r.open_count,
    totalCount: r.total_count,
    lastMessageAt: iso(r.last_message_at) ?? null
  }));
}

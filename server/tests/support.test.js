import http from 'node:http';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { io as ioClient } from 'socket.io-client';
import { as, api, testContext, unique } from './helpers/testContext.js';
import { query } from '../src/db/pool.js';
import { insertUser, setUserRole } from '../src/models/userModel.js';
import { signToken } from '../src/auth/jwt.js';
import { newId } from '../src/utils/id.js';
import { attachSocketServer, bindSocketBroadcasts } from '../src/socket/index.js';
import { resetSupportThrottle, SUPPORT_THROTTLE_MAX } from '../src/services/supportService.js';
import { resetAnnouncementThrottle } from '../src/services/managerService.js';

/**
 * Help desk + event announcements (contract 1.6.0): ownership, registration
 * gate, own-ticket-only, idempotency, reopen, throttle and room-scoped
 * socket delivery.
 */

const created = { users: [], events: [] };
const openSockets = [];
let server;
let io;
let baseUrl;
let orgA;
let orgB;
let admin;
let userA;
let userB;
let eventA; // created by orgA
let eventB; // created by orgB

async function makeUser(role) {
  const id = newId('usr');
  await insertUser({ id, name: `Sup ${role}`, email: `${unique('sup')}@x.io`, passwordHash: 'x' });
  if (role !== 'student') await setUserRole(id, role);
  created.users.push(id);
  return { id, role, token: signToken({ id, email: 'x@x.io', name: 'x' }) };
}

async function makeEvent(owner, title) {
  const res = await as(owner.token).post('/api/events').send({
    title,
    category: 'Tech',
    venue: 'Room 101',
    date: '2027-09-01',
    time: '10:00',
    capacity: 10
  });
  expect(res.status).toBe(201);
  created.events.push(res.body.event.id);
  return res.body.event.id;
}

const register = (user, eventId) =>
  query('INSERT INTO bookmarks (user_id, event_id) VALUES ($1, $2) ON CONFLICT DO NOTHING', [user.id, eventId]);

function connect(token) {
  const socket = ioClient(baseUrl, {
    path: '/socket.io',
    transports: ['websocket'],
    auth: token ? { token } : {}
  });
  openSockets.push(socket);
  return new Promise((resolve, reject) => {
    socket.once('connect', () => resolve(socket));
    socket.once('connect_error', reject);
  });
}

const waitFor = (socket, event, timeout = 4000) =>
  new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`timed out waiting for "${event}"`)), timeout);
    socket.once(event, (payload) => {
      clearTimeout(timer);
      resolve(payload);
    });
  });

const noEvent = (socket, event, ms = 500) =>
  new Promise((resolve, reject) => {
    const handler = () => reject(new Error(`unexpected "${event}"`));
    socket.once(event, handler);
    setTimeout(() => {
      socket.off(event, handler);
      resolve();
    }, ms);
  });

async function openTicket(user, eventId, extra = {}) {
  const res = await as(user.token)
    .post('/api/support/tickets')
    .send({ eventId, subject: 'Where is it?', message: 'I cannot find the hall', ...extra });
  expect(res.status).toBe(201);
  return res.body;
}

beforeAll(async () => {
  const { app } = testContext();
  orgA = await makeUser('organizer');
  orgB = await makeUser('organizer');
  admin = await makeUser('admin');
  userA = await makeUser('student');
  userB = await makeUser('student');
  eventA = await makeEvent(orgA, unique('SupA'));
  eventB = await makeEvent(orgB, unique('SupB'));
  await register(userA, eventA);
  await register(userA, eventB);
  await register(userB, eventB);

  server = http.createServer(app);
  io = attachSocketServer(server);
  bindSocketBroadcasts(app, io);
  await new Promise((resolve) => server.listen(0, resolve));
  baseUrl = `http://127.0.0.1:${server.address().port}`;
});

beforeEach(() => {
  resetSupportThrottle();
  resetAnnouncementThrottle();
});

afterAll(async () => {
  for (const socket of openSockets) socket.close();
  await new Promise((resolve) => io.close(resolve));
  await new Promise((resolve) => server.close(resolve));
  await query('DELETE FROM announcements WHERE sent_by = ANY($1::text[])', [created.users]);
  await query('DELETE FROM events WHERE id = ANY($1::text[])', [created.events]);
  await query('DELETE FROM users WHERE id = ANY($1::text[])', [created.users]);
});

describe('GET /api/events/:id/announcements', () => {
  it('returns this event plus global announcements, never another event', async () => {
    const a = await as(orgA.token).post('/api/manage/announcements').send({ message: 'Hall A news', eventId: eventA });
    const b = await as(orgB.token).post('/api/manage/announcements').send({ message: 'Hall B news', eventId: eventB });
    const g = await as(admin.token).post('/api/manage/announcements').send({ message: 'Campus closed Monday' });
    expect([a.status, b.status, g.status]).toEqual([201, 201, 201]);

    const res = await api().get(`/api/events/${eventA}/announcements`); // public
    expect(res.status).toBe(200);
    const ids = res.body.announcements.map((x) => x.id);
    expect(ids).toContain(a.body.announcement.id);
    expect(ids).toContain(g.body.announcement.id);
    expect(ids).not.toContain(b.body.announcement.id);

    const mine = res.body.announcements.find((x) => x.id === a.body.announcement.id);
    expect(mine).toMatchObject({ eventId: eventA, global: false, message: 'Hall A news' });
    const glob = res.body.announcements.find((x) => x.id === g.body.announcement.id);
    expect(glob).toMatchObject({ room: 'all', global: true, eventId: null });
    expect(Object.keys(mine).sort()).toEqual(['at', 'eventId', 'global', 'id', 'message', 'room', 'sentBy']);
    // newest first
    expect(ids).toEqual([...ids].sort((x, y) => y - x));
  });

  it('404 EVENT_NOT_FOUND for an unknown event', async () => {
    const res = await api().get('/api/events/evt_nope/announcements');
    expect(res.status).toBe(404);
    expect(res.body.code).toBe('EVENT_NOT_FOUND');
  });
});

describe('attendee support API', () => {
  it('requires a token', async () => {
    expect((await api().get('/api/support/tickets')).status).toBe(401);
    expect((await api().post('/api/support/tickets').send({})).status).toBe(401);
  });

  it('creates a ticket + first message (201) with the documented shapes', async () => {
    const { ticket, message } = await openTicket(userA, eventA, { clientId: unique('c') });
    expect(ticket).toMatchObject({
      eventId: eventA,
      userId: userA.id,
      userName: 'Sup student',
      subject: 'Where is it?',
      status: 'open',
      messageCount: 1,
      lastMessage: { body: 'I cannot find the hall', senderRole: 'user' }
    });
    expect(Object.keys(ticket).sort()).toEqual([
      'createdAt', 'eventId', 'eventTitle', 'id', 'lastMessage', 'lastMessageAt', 'messageCount',
      'status', 'subject', 'updatedAt', 'userId', 'userName'
    ]);
    expect(message).toMatchObject({
      ticketId: ticket.id, senderId: userA.id, senderRole: 'user', body: 'I cannot find the hall'
    });
    expect(message.clientId).toBeTruthy();
    expect(typeof message.senderName).toBe('string');
  });

  it('403 NOT_REGISTERED when the event is not bookmarked; 404 for an unknown event', async () => {
    const res = await as(userB.token)
      .post('/api/support/tickets')
      .send({ eventId: eventA, subject: 's', message: 'm' });
    expect(res.status).toBe(403);
    expect(res.body.code).toBe('NOT_REGISTERED');

    const none = await as(userB.token)
      .post('/api/support/tickets')
      .send({ eventId: 'evt_nope', subject: 's', message: 'm' });
    expect(none.status).toBe(404);
    expect(none.body.code).toBe('EVENT_NOT_FOUND');
  });

  it('validates input (400 VALIDATION_ERROR)', async () => {
    const bad = [
      { eventId: eventA, subject: '', message: 'm' },
      { eventId: eventA, subject: 'x'.repeat(121), message: 'm' },
      { eventId: eventA, subject: 's', message: '' },
      { eventId: eventA, subject: 's', message: 'x'.repeat(1001) },
      { subject: 's', message: 'm' }
    ];
    for (const body of bad) {
      const res = await as(userA.token).post('/api/support/tickets').send(body);
      expect(res.status).toBe(400);
      expect(res.body.code).toBe('VALIDATION_ERROR');
    }
  });

  it('lists only my tickets, newest activity first, with an eventId filter', async () => {
    const t1 = (await openTicket(userA, eventA)).ticket;
    const t2 = (await openTicket(userA, eventB)).ticket;
    const other = (await openTicket(userB, eventB)).ticket;
    await as(userA.token).post(`/api/support/tickets/${t1.id}/messages`).send({ body: 'bump' });

    const all = await as(userA.token).get('/api/support/tickets');
    expect(all.status).toBe(200);
    const ids = all.body.tickets.map((t) => t.id);
    expect(ids).not.toContain(other.id);
    expect(ids.indexOf(t1.id)).toBeLessThan(ids.indexOf(t2.id));
    expect(all.body.tickets.every((t) => t.userId === userA.id)).toBe(true);

    const filtered = await as(userA.token).get(`/api/support/tickets?eventId=${eventB}`);
    expect(filtered.body.tickets.every((t) => t.eventId === eventB)).toBe(true);
    expect(filtered.body.tickets.map((t) => t.id)).toContain(t2.id);
    expect(filtered.body.tickets.map((t) => t.id)).not.toContain(t1.id);
  });

  it('own ticket only: user B gets 404 on user A ticket (read and write)', async () => {
    const { ticket } = await openTicket(userA, eventB);
    const read = await as(userB.token).get(`/api/support/tickets/${ticket.id}/messages`);
    expect(read.status).toBe(404);
    expect(read.body.code).toBe('TICKET_NOT_FOUND');
    const write = await as(userB.token).post(`/api/support/tickets/${ticket.id}/messages`).send({ body: 'hi' });
    expect(write.status).toBe(404);
    const missing = await as(userA.token).get('/api/support/tickets/99999999/messages');
    expect(missing.status).toBe(404);
  });

  it('thread is oldest-first; replies are idempotent on clientId', async () => {
    const { ticket } = await openTicket(userA, eventA);
    const clientId = unique('cid');
    const first = await as(userA.token)
      .post(`/api/support/tickets/${ticket.id}/messages`)
      .send({ body: 'second', clientId });
    expect(first.status).toBe(201);
    const again = await as(userA.token)
      .post(`/api/support/tickets/${ticket.id}/messages`)
      .send({ body: 'second', clientId });
    expect(again.status).toBe(200);
    expect(again.body.message.id).toBe(first.body.message.id);

    const thread = await as(userA.token).get(`/api/support/tickets/${ticket.id}/messages`);
    expect(thread.status).toBe(200);
    expect(thread.body.ticket.id).toBe(ticket.id);
    expect(thread.body.messages.map((m) => m.body)).toEqual(['I cannot find the hall', 'second']);
    expect(thread.body.ticket.messageCount).toBe(2);
  });

  it('create is idempotent on clientId (200, same ticket)', async () => {
    const clientId = unique('cid');
    const first = await openTicket(userA, eventA, { clientId });
    const again = await as(userA.token)
      .post('/api/support/tickets')
      .send({ eventId: eventA, subject: 'Where is it?', message: 'I cannot find the hall', clientId });
    expect(again.status).toBe(200);
    expect(again.body.ticket.id).toBe(first.ticket.id);
    expect(again.body.message.id).toBe(first.message.id);
  });

  it('a closed ticket is reopened by a new user message', async () => {
    const { ticket } = await openTicket(userA, eventA);
    const closed = await as(orgA.token).put(`/api/manage/support/tickets/${ticket.id}`).send({ status: 'closed' });
    expect(closed.body.ticket.status).toBe('closed');
    await as(userA.token).post(`/api/support/tickets/${ticket.id}/messages`).send({ body: 'still need help' });
    const thread = await as(userA.token).get(`/api/support/tickets/${ticket.id}/messages`);
    expect(thread.body.ticket.status).toBe('open');
  });

  it('throttles to 20 messages / minute / user with 429 RATE_LIMITED', async () => {
    const { ticket } = await openTicket(userB, eventB); // 1 used
    for (let i = 1; i < SUPPORT_THROTTLE_MAX; i += 1) {
      const ok = await as(userB.token).post(`/api/support/tickets/${ticket.id}/messages`).send({ body: `m${i}` });
      expect(ok.status).toBe(201);
    }
    const blocked = await as(userB.token).post(`/api/support/tickets/${ticket.id}/messages`).send({ body: 'too many' });
    expect(blocked.status).toBe(429);
    expect(blocked.body.code).toBe('RATE_LIMITED');
  });
});

describe('manager help-desk API', () => {
  let tA; // userA -> eventA (orgA)
  let tB; // userB -> eventB (orgB)

  beforeAll(async () => {
    resetSupportThrottle();
    tA = (await openTicket(userA, eventA)).ticket;
    tB = (await openTicket(userB, eventB)).ticket;
  });

  it('401 without token, 403 FORBIDDEN for a student', async () => {
    const urls = ['/api/manage/support/events', `/api/manage/support/tickets?eventId=${eventA}`];
    for (const url of urls) {
      expect((await api().get(url)).status).toBe(401);
      const res = await as(userA.token).get(url);
      expect(res.status).toBe(403);
      expect(res.body.code).toBe('FORBIDDEN');
    }
  });

  it('events: organizer sees only own events (incl. 0-chat ones); admin sees all', async () => {
    const empty = await makeEvent(orgA, unique('SupEmpty'));
    const res = await as(orgA.token).get('/api/manage/support/events');
    expect(res.status).toBe(200);
    const ids = res.body.events.map((e) => e.id);
    expect(ids).toContain(eventA);
    expect(ids).toContain(empty);
    expect(ids).not.toContain(eventB);
    const row = res.body.events.find((e) => e.id === eventA);
    expect(Object.keys(row).sort()).toEqual(
      ['date', 'id', 'lastMessageAt', 'openCount', 'title', 'totalCount', 'venue']
    );
    expect(row.openCount).toBeGreaterThanOrEqual(1);
    expect(res.body.events.find((e) => e.id === empty)).toMatchObject({
      openCount: 0, totalCount: 0, lastMessageAt: null
    });
    // events with chats come before those without
    expect(ids.indexOf(eventA)).toBeLessThan(ids.indexOf(empty));

    const adminRes = await as(admin.token).get('/api/manage/support/events');
    const adminIds = adminRes.body.events.map((e) => e.id);
    expect(adminIds).toEqual(expect.arrayContaining([eventA, eventB, empty]));
  });

  it('tickets: ownership enforced (organizer A cannot see organizer B event), admin can', async () => {
    const own = await as(orgA.token).get(`/api/manage/support/tickets?eventId=${eventA}`);
    expect(own.status).toBe(200);
    expect(own.body.tickets.map((t) => t.id)).toContain(tA.id);
    expect(own.body.tickets.every((t) => t.eventId === eventA)).toBe(true);

    const foreign = await as(orgA.token).get(`/api/manage/support/tickets?eventId=${eventB}`);
    expect(foreign.status).toBe(403);
    expect(foreign.body.code).toBe('FORBIDDEN');

    const adminRes = await as(admin.token).get(`/api/manage/support/tickets?eventId=${eventB}`);
    expect(adminRes.status).toBe(200);
    expect(adminRes.body.tickets.map((t) => t.id)).toContain(tB.id);

    expect((await as(orgA.token).get('/api/manage/support/tickets')).status).toBe(400);
    expect((await as(admin.token).get('/api/manage/support/tickets?eventId=evt_nope')).status).toBe(404);
    expect(
      (await as(orgA.token).get(`/api/manage/support/tickets?eventId=${eventA}&status=bogus`)).status
    ).toBe(400);
  });

  it('thread/reply/status: organizer A cannot touch organizer B ticket (404), admin can', async () => {
    expect((await as(orgA.token).get(`/api/manage/support/tickets/${tB.id}/messages`)).status).toBe(404);
    const reply = await as(orgA.token).post(`/api/manage/support/tickets/${tB.id}/messages`).send({ body: 'nope' });
    expect(reply.status).toBe(404);
    expect(reply.body.code).toBe('TICKET_NOT_FOUND');
    expect(
      (await as(orgA.token).put(`/api/manage/support/tickets/${tB.id}`).send({ status: 'closed' })).status
    ).toBe(404);

    const adminThread = await as(admin.token).get(`/api/manage/support/tickets/${tB.id}/messages`);
    expect(adminThread.status).toBe(200);
    expect(adminThread.body.ticket.id).toBe(tB.id);
    const adminReply = await as(admin.token)
      .post(`/api/manage/support/tickets/${tB.id}/messages`)
      .send({ body: 'admin here' });
    expect(adminReply.status).toBe(201);
    expect(adminReply.body.message.senderRole).toBe('manager');
  });

  it('manager reply is visible to the attendee; status change round trip; clientId idempotent', async () => {
    const clientId = unique('mc');
    const reply = await as(orgA.token)
      .post(`/api/manage/support/tickets/${tA.id}/messages`)
      .send({ body: 'Second floor, east wing', clientId });
    expect(reply.status).toBe(201);
    expect(reply.body.message).toMatchObject({
      senderRole: 'manager', senderId: orgA.id, body: 'Second floor, east wing', ticketId: tA.id
    });
    const again = await as(orgA.token)
      .post(`/api/manage/support/tickets/${tA.id}/messages`)
      .send({ body: 'Second floor, east wing', clientId });
    expect(again.status).toBe(200);
    expect(again.body.message.id).toBe(reply.body.message.id);

    const userView = await as(userA.token).get(`/api/support/tickets/${tA.id}/messages`);
    expect(userView.body.messages.at(-1)).toMatchObject({ senderRole: 'manager', body: 'Second floor, east wing' });

    // a manager reply does NOT reopen a closed ticket
    const closed = await as(orgA.token).put(`/api/manage/support/tickets/${tA.id}`).send({ status: 'closed' });
    expect(closed.status).toBe(200);
    expect(closed.body.ticket.status).toBe('closed');
    await as(orgA.token).post(`/api/manage/support/tickets/${tA.id}/messages`).send({ body: 'bye' });
    const closedList = await as(orgA.token).get(`/api/manage/support/tickets?eventId=${eventA}&status=closed`);
    expect(closedList.body.tickets.map((t) => t.id)).toContain(tA.id);
    const openList = await as(orgA.token).get(`/api/manage/support/tickets?eventId=${eventA}&status=open`);
    expect(openList.body.tickets.map((t) => t.id)).not.toContain(tA.id);

    const reopened = await as(orgA.token).put(`/api/manage/support/tickets/${tA.id}`).send({ status: 'open' });
    expect(reopened.body.ticket.status).toBe('open');

    const bad = await as(orgA.token).put(`/api/manage/support/tickets/${tA.id}`).send({ status: 'weird' });
    expect(bad.status).toBe(400);
  });
});

describe('support realtime', () => {
  it('delivers support:message only to the ticket owner, the owning manager and admins', async () => {
    resetSupportThrottle();
    const [sUserA, sUserB, sOrgA, sOrgB, sAdmin, sAnon] = await Promise.all([
      connect(userA.token),
      connect(userB.token),
      connect(orgA.token),
      connect(orgB.token),
      connect(admin.token),
      connect()
    ]);

    // user -> manager direction: userA writes on eventA
    const pOwner = waitFor(sUserA, 'support:message');
    const pMgr = waitFor(sOrgA, 'support:message');
    const pAdmin = waitFor(sAdmin, 'support:message');
    const quiet = [
      noEvent(sUserB, 'support:message'),
      noEvent(sOrgB, 'support:message'),
      noEvent(sAnon, 'support:message')
    ];
    const { ticket } = await openTicket(userA, eventA);
    const [owner, mgr, adm] = await Promise.all([pOwner, pMgr, pAdmin]);
    for (const payload of [owner, mgr, adm]) {
      expect(payload.ticket.id).toBe(ticket.id);
      expect(payload.message).toMatchObject({ ticketId: ticket.id, senderRole: 'user' });
    }
    await Promise.all(quiet);

    // manager -> user direction: orgA replies, only userA's socket gets it among users
    const rOwner = waitFor(sUserA, 'support:message');
    const rAdmin = waitFor(sAdmin, 'support:message');
    const rQuiet = [noEvent(sUserB, 'support:message'), noEvent(sOrgB, 'support:message')];
    await as(orgA.token).post(`/api/manage/support/tickets/${ticket.id}/messages`).send({ body: 'on it' });
    expect((await rOwner).message).toMatchObject({ senderRole: 'manager', body: 'on it' });
    await rAdmin;
    await Promise.all(rQuiet);

    // status change -> support:ticket to the same audience only
    const tOwner = waitFor(sUserA, 'support:ticket');
    const tMgr = waitFor(sOrgA, 'support:ticket');
    const tQuiet = [noEvent(sOrgB, 'support:ticket'), noEvent(sUserB, 'support:ticket')];
    await as(orgA.token).put(`/api/manage/support/tickets/${ticket.id}`).send({ status: 'closed' });
    expect((await tOwner).ticket.status).toBe('closed');
    await tMgr;
    await Promise.all(tQuiet);
  });

  it('a client cannot join user/manager rooms by asking', async () => {
    resetSupportThrottle();
    const sUserB = await connect(userB.token);
    const sOrgB = await connect(orgB.token);
    const rogue = await connect(userB.token);
    rogue.emit('join', [`user:${userA.id}`, 'managers:all', `managers:${orgA.id}`]);
    await new Promise((r) => setTimeout(r, 150));
    const quiet = [noEvent(rogue, 'support:message'), noEvent(sUserB, 'support:message'), noEvent(sOrgB, 'support:message')];
    await openTicket(userA, eventA);
    await Promise.all(quiet);
  });

  it('organizer B (not the creator) does not receive; creator of the second event does', async () => {
    resetSupportThrottle();
    const sOrgA = await connect(orgA.token);
    const sOrgB = await connect(orgB.token);
    const pB = waitFor(sOrgB, 'support:message');
    const quiet = noEvent(sOrgA, 'support:message');
    await openTicket(userB, eventB);
    await pB;
    await quiet;
  });

  it('an invalid token is still rejected at the handshake', async () => {
    await expect(connect('not-a-jwt')).rejects.toBeTruthy();
  });
});

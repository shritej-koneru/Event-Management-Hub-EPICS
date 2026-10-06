/**
 * Live verification of the Socket.IO relay and duplicate-announcement guard.
 * Mirrors what the Android ChatScreen does: default namespace, bare venue ids.
 *
 *   VERIFY_BASE_URL=http://localhost:3100 npm run verify:socket
 *
 * MUTATES the target: it registers a verifier account and creates events. There
 * is deliberately no default URL and no positional argument, so the script can
 * never be pointed at the shared development server by accident - see
 * scripts/lib/requireScratchTarget.mjs for the guard and the escape hatch.
 */
import { io } from 'socket.io-client';
import { requireScratchTarget } from './lib/requireScratchTarget.mjs';

const base = await requireScratchTarget();
if (!base) process.exit(2);
const results = [];
let failures = 0;

function check(name, condition, extra) {
  const ok = Boolean(condition);
  if (!ok) failures += 1;
  results.push(`${ok ? 'PASS' : 'FAIL'}  ${name}${!ok && extra !== undefined ? ` -> ${JSON.stringify(extra)}` : ''}`);
}

const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const announcements = async () => (await fetch(`${base}/api/chat/announcements`)).json();

/** `POST /api/events` requires a JWT, so mint a throwaway verifier account. */
async function verifierToken() {
  const email = `socket-verify-${Date.now().toString(36)}@uni.edu`;
  const res = await fetch(`${base}/api/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: 'Socket Verifier', email, password: 'secret123' })
  });
  const body = await res.json();
  if (!body.token) throw new Error(`could not mint verifier token: ${JSON.stringify(body)}`);
  return body.token;
}

const createEvent = async (token, title, venue) =>
  (
    await fetch(`${base}/api/events`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({
        title,
        category: 'Tech',
        venue,
        date: '2027-05-01',
        time: '11:00',
        capacity: 30,
        host: 'Socket Verifier'
      })
    })
  ).json();

// Android connects to the DEFAULT namespace with a bare venue-id join list.
const socket = io(base, { path: '/socket.io', transports: ['websocket'] });

const joinedRooms = [];
const received = [];

socket.on('joined', (rooms) => joinedRooms.push(...rooms));
socket.on('event', (payload) => received.push(payload));

await new Promise((resolve, reject) => {
  socket.on('connect', resolve);
  socket.on('connect_error', reject);
  setTimeout(() => reject(new Error('socket connect timeout')), 8000);
});
check('socket connected (default namespace)', socket.connected, { id: socket.id });

// android/.../ChatScreen.kt allVenueRooms() -> 14 bare venue ids
const allVenues = [
  'entrance', 'lobby', 'registration', 'auditorium', 'main-hall',
  'room-101', 'room-102', 'room-204', 'room-205', 'lab-a', 'lab-b',
  'cafeteria', 'library', 'tech-lounge'
];
socket.emit('join', allVenues);
await wait(400);
check('joined 14 announce:<venue> rooms', joinedRooms.length === 14, { got: joinedRooms.length });
check('room names are prefixed announce:', joinedRooms[0] === 'announce:entrance', { got: joinedRooms[0] });

// A non-graph venue must never create a room.
socket.emit('join', ['not-a-venue']);
await wait(250);
check('non-graph venue rejected from join', joinedRooms.length === 14, { got: joinedRooms.length });

// The stored message embeds the event id, so two DIFFERENT events at one venue
// must both be persisted. Previously the (room, message) uniqueness constraint
// collapsed every event after the first one at a venue into that first row.
const before = (await announcements()).announcements;
const beforeCount = before.length;
const token = await verifierToken();

const anon = await fetch(`${base}/api/events`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ title: 'Anon', category: 'Tech', venue: 'Room 102', date: '2027-05-01', capacity: 5 })
});
check('anonymous event creation rejected -> 401', anon.status === 401);

const first = await createEvent(token, 'Announce Guard Probe A', 'Room 102');
await wait(400);
const mid = (await announcements()).announcements;
check('first relay persisted an announcement', mid.length === beforeCount + 1, {
  before: beforeCount, after: mid.length, event: first.event?.id
});

const second = await createEvent(token, 'Announce Guard Probe B', 'Room 102');
await wait(400);
const after = (await announcements()).announcements;
check('second, DIFFERENT event at same venue is also persisted', after.length === mid.length + 1, {
  before: mid.length, after: after.length, event: second.event?.id
});
check('no duplicate (room,message) rows in DB',
  new Set(after.map((a) => `${a.room}|${a.message}`)).size === after.length);

// Each stored row must be traceable to its own event id. The announcement text
// is "New event at <venue> [<id>]", so match on the id we were handed back.
const probeIds = [first.event?.id, second.event?.id].filter(Boolean);
const probeRows = after.filter((a) => a.room === 'room-102' && probeIds.some((id) => a.message.endsWith(`[${id}]`)));
check('one stored row per probe event', probeRows.length === probeIds.length, {
  expected: probeIds.length, rows: probeRows.map((r) => r.message)
});
check('stored message embeds its event id',
  probeRows.every((a) => /\[[a-z0-9_-]+\]$/i.test(a.message)), probeRows.map((a) => a.message));
check('stored messages are distinct per event',
  new Set(probeRows.map((a) => a.message)).size === probeRows.length);

// The Android client reads `title` and `venue` off the TOP LEVEL of the payload.
const payload = received.at(-1);
check('client received event broadcast', Boolean(payload), { received: received.length });
check('broadcast exposes top-level title (Android reads it)', typeof payload?.title === 'string' && payload.title.length > 0, { title: payload?.title });
check('broadcast exposes top-level venue (Android reads it)', typeof payload?.venue === 'string' && payload.venue.length > 0, { venue: payload?.venue });
check('broadcast exposes top-level venueId', typeof payload?.venueId === 'string' && payload.venueId.length > 0, { venueId: payload?.venueId });
check('broadcast keeps contract keys event + announcement',
  Boolean(payload?.event?.id && payload?.announcement?.venueId), {
    eventId: payload?.event?.id, announcementEventId: payload?.announcement?.eventId
  });
check('announcement carries the event id', payload?.announcement?.eventId === payload?.event?.id);

// The contract's /announce namespace must keep working too.
const contractSocket = io(`${base}/announce`, { path: '/socket.io', transports: ['websocket'] });
await new Promise((resolve, reject) => {
  contractSocket.on('connect', resolve);
  contractSocket.on('connect_error', reject);
  setTimeout(() => reject(new Error('/announce connect timeout')), 8000);
});
const contractRooms = [];
contractSocket.on('joined', (rooms) => contractRooms.push(...rooms));
contractSocket.emit('join', ['auditorium']);
await wait(300);
check('/announce namespace still supported', contractRooms.includes('announce:auditorium'), { rooms: contractRooms });
contractSocket.close();

// Socket auth: anonymous is allowed (Android), a bad token is rejected.
const badTokenSocket = io(base, {
  path: '/socket.io',
  transports: ['websocket'],
  auth: { token: 'not-a-real-jwt' },
  reconnection: false
});
let connectError = null;
await new Promise((resolve) => {
  badTokenSocket.on('connect', resolve);
  badTokenSocket.on('connect_error', (err) => { connectError = err; resolve(); });
  setTimeout(resolve, 8000);
});
check('invalid socket token rejected', !badTokenSocket.connected, { message: connectError?.message });
check('rejection carries AUTH_INVALID_TOKEN', connectError?.data?.code === 'AUTH_INVALID_TOKEN', {
  data: connectError?.data
});
badTokenSocket.close();

const goodTokenSocket = io(base, {
  path: '/socket.io',
  transports: ['websocket'],
  auth: { token },
  reconnection: false
});
await new Promise((resolve, reject) => {
  goodTokenSocket.on('connect', resolve);
  goodTokenSocket.on('connect_error', reject);
  setTimeout(() => reject(new Error('valid-token socket connect timeout')), 8000);
});
check('valid socket token connects', goodTokenSocket.connected);
goodTokenSocket.close();

socket.close();

console.log(results.join('\n'));
console.log(`\n${results.length - failures} passed, ${failures} failed`);
process.exit(failures > 0 ? 1 : 0);
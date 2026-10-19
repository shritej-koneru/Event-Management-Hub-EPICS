import http from 'node:http';
import { config } from './config/index.js';
import { createApp } from './app.js';
import { ensureDatabase, releaseDatabase } from './db/bootstrap.js';
import { migrate } from './db/migrate.js';
import { seed } from './db/seeds/index.js';
import { validateGraph } from './navigation/graph.js';
import { attachSocketServer, broadcastEventUpdate } from './socket/index.js';
import { closePool } from './db/pool.js';

/**
 * Server entry point.
 *
 * Boot order: database -> migrations -> seed -> navigation graph check ->
 * Express + Socket.IO. The database is the single source of truth, so nothing
 * starts until it is reachable and migrated.
 */

async function start() {
  console.log('\n  Event Management Hub — central server');

  const databaseUrl = await ensureDatabase();
  console.log(`  database: ${config.useEmbeddedPg ? 'local PostgreSQL' : 'DATABASE_URL'} (${maskUrl(databaseUrl)})`);

  const { applied, total } = await migrate();
  console.log(`  migrations: ${applied} applied / ${total} total`);

  await seed();

  const graph = validateGraph();
  console.log(`  venues: ${graph.venueCount} graph nodes, all reachable`);

  const app = createApp();
  const server = http.createServer(app);
  const io = attachSocketServer(server);

  app.locals.broadcastEvent = (event, options) => broadcastEventUpdate(io, event, options);

  await new Promise((resolve) => server.listen(config.port, resolve));

  const port = server.address().port;
  const base = config.apiBaseUrl || `http://localhost:${port}`;
  console.log(`\n  listening on http://localhost:${port}`);
  console.log(`  contract version ${config.contractVersion} — see contract/API-CONTRACT.md`);
  console.log(`  Android emulator base URL: ${base}`);
  console.log(`  Android emulator API base: ${base}/api\n`);
  printRoutes(port);

  return { app, server, io };
}

function printRoutes(port) {
  const rows = [
    ['GET', '/ping', '-'],
    ['POST', '/api/auth/register', '- rate limited'],
    ['POST', '/api/auth/login', '- rate limited'],
    ['GET', '/api/auth/me', 'Bearer'],
    ['PUT', '/api/auth/me/interests', 'Bearer'],
    ['GET', '/api/auth/me/bookmarks', 'Bearer'],
    ['POST', '/api/auth/me/bookmarks/:eventId', 'Bearer'],
    ['DELETE', '/api/auth/me/bookmarks/:eventId', 'Bearer'],
    ['GET', '/api/events', 'optional + filter/paginate'],
    ['POST', '/api/events', 'Bearer'],
['GET', '/api/events/:id', 'optional'],
  ['GET', '/api/events/:id/details', 'optional'],
  ['GET', '/api/events/:id/route', 'optional'],
    ['POST', '/api/ai/search', 'optional'],
    ['GET', '/api/ai/recommendations', 'Bearer'],
    ['GET', '/api/navigation/venues', '-'],
    ['GET', '/api/navigation/map', '-'],
    ['GET', '/api/navigation/venues/:venueId/map', '-'],
    ['POST', '/api/navigation/path', '-'],
    ['GET', '/api/chat/announcements', 'optional'],
    ['GET', '/api/chat/messages', 'optional'],
    ['POST', '/api/chat/join', '-']
  ];
  const width = Math.max(...rows.map((r) => r[1].length));
  for (const [method, route, auth] of rows) {
    console.log(`    ${method.padEnd(5)} ${route.padEnd(width)}  ${auth}`);
  }
  console.log(`    ${'WS'.padEnd(5)} ${`/socket.io (ns / and /announce)`.padEnd(width)}  optional / token optional\n`);
}

function maskUrl(url) {
  return url.replace(/\/\/([^:]+):[^@]+@/, '//$1:***@');
}

let handles = null;

async function shutdown(signal, code = 0) {
  console.log(`\n  ${signal} received — shutting down`);
  try {
    if (handles) {
      await new Promise((resolve) => handles.io.close(resolve));
      await new Promise((resolve) => handles.server.close(resolve));
    }
    await releaseDatabase();
    await closePool();
  } catch (err) {
    console.error('  shutdown error:', err.message);
    process.exitCode = 1;
  }
  process.exit(code);
}

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => shutdown(signal));
}

handles = await start();
export default handles;
# Setup and Testing

## Prerequisites

- Node.js **≥ 20.19** (`node -v`)
- No Docker, no system PostgreSQL, no admin rights for local dev
- For the Android app: Android Studio / SDK, emulator API 26+ (project targets 36)

## Backend from scratch

```bash
cd "server"          # this repository root
npm install
npm run setup        # creates .env with JWT_SECRET
npm start            # migrate + seed + listen on :3000
```

Verify:

```bash
curl http://localhost:3000/ping
```

Stop with Ctrl+C. The embedded cluster lives in `data/pg` (gitignored).

### Environment

Copy names from `.env.example` if needed. Never commit `.env`.
Key variables: `PORT`, `DATABASE_URL`, `JWT_SECRET`, `EMBEDDED_PG_PORT`.

## Automated tests (backend)

```bash
npm test                 # full Vitest suite (embedded emh_test on :5433)
npx vitest run tests/chat.test.js
npm run verify:migrations
npm run verify           # test + verify:migrations
```

**Important:** stop `npm start` before `npm test` — both want embedded PG port 5433.

Live verifiers (`verify:rest`, `verify:socket`, `verify:events`) require an
explicit scratch `VERIFY_BASE_URL` and refuse to run against `emh` / `emh_test`.

## Android app

1. Start the backend (`npm start`).
2. Emulator: either use `10.0.2.2:3000` (default fallback in the app) **or**
   `adb reverse tcp:3000 tcp:3000` if `API_BASE_URL` is `http://127.0.0.1:3000/`.
3. Open `../Epics/android` in Android Studio → Run `app`.
4. Demo login: `alice@uni.edu` / `secret123`.

### Android unit tests

```powershell
cd ..\Epics\android
.\gradlew.bat :app:testDebugUnitTest
```

## Manual test scripts

### Auth + events

```bash
TOKEN=$(curl -s -X POST http://localhost:3000/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"alice@uni.edu","password":"secret123"}' | node -e "let d='';process.stdin.on('data',c=>d+=c);process.stdin.on('end',()=>console.log(JSON.parse(d).token))")

curl -s http://localhost:3000/api/events -H "Authorization: Bearer $TOKEN"
```

### Chat persistence

1. Connect a Socket.IO client (or use the app Chat tab).
2. Emit `chat:message` `{ "text": "hello history" }` → ack `ok: true`.
3. `curl http://localhost:3000/api/chat/messages?limit=5` → row present.
4. Restart the backend → same curl still returns the row (DB persistence).
5. Navigate Home → Chat in the app → messages still visible (ViewModel).

## Emulator networking tips

| Setup | App base URL | Notes |
| --- | --- | --- |
| Emulator + `10.0.2.2` | `http://10.0.2.2:3000/` | Default fallback in ApiClient |
| Emulator + reverse | `http://127.0.0.1:3000/` | `adb reverse tcp:3000 tcp:3000` |
| Physical phone | `http://<lan-ip>:3000/` | Same Wi‑Fi; adjust API_BASE_URL |

If Chat shows Offline: confirm reverse (or 10.0.2.2), backend `/ping`, and
logcat tag `BitChatSocket`.

## Troubleshooting

| Error | Fix |
| --- | --- |
| `EADDRINUSE :3000` | Kill old node / change PORT |
| `EADDRINUSE :5433` | Stop other embedded PG (often a leftover server or test) |
| Vitest “databaseUrl” missing | globalSetup failed — read PG logs above the error |
| Android 401 on API | Token expired → logout/login |
| Chat `xhr poll error` | Reverse missing or backend down |
| Migration assert “four” | Old `verify-migrations` vs new 004 — pull latest script |

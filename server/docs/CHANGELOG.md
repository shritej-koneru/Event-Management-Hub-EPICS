# Changelog

Derived from **actual git history** in this repository plus the working tree at
the time of handover. Nothing here is invented.

## Unreleased (working tree, not yet committed at handover time)

### Added

- Interactive chat persistence:
  - Migration `004_chat_messages.sql` (`chat_messages` table + indexes)
  - `src/models/chatMessageModel.js` (`insertChatMessage`, `listRecentChatMessages`)
  - Socket `chat:message` now **inserts before broadcast**; nack `CHAT_PERSIST_FAILED` on write failure
  - Assistant auto-reply rows are persisted (`bot: true`)
  - REST `GET /api/chat/messages` with `room` / `before` / `limit`
  - `tests/chat.test.js` (16 tests)
  - `dropAll` + `verify-migrations` updated for the fourth migration
- Assistant auto-reply (`buildAutoReply`, `ASSISTANT_NAME`) on `chat:message`
- Interactive `chat:message` handler itself (was announcements-only before this working tree)

### Fixed

- Deterministic search: prefix matching with `MIN_PREFIX_LEN = 3` so queries
  like `"android dev work"` match `"workshop"` (`src/ai/scoring.js`, `tests/ai.test.js`)

### Changed

- `.gitignore` tweaks; README wording

## Committed history

| Commit | Summary |
| --- | --- |
| `9ea8a8a` | test: cover the single-string join form the Android client uses |
| `6803581` | test: add probe-event cleanup script and correct test count |
| `981c665` | fix: require an explicit scratch target for live verifiers |
| `dc091bd` | test: add scratch-database migration rebuild verifier |
| `e119c58` | test: add malformed-JSON error mapping regression suite |
| `36bf74b` | Initial commit: Person 2 Event Management Hub backend |

## Verification results (this handover)

| Check | Result |
| --- | --- |
| `npm test` | **285 passed** (12 files), 0 failed |
| `npm run verify:migrations` | **ALL CHECKS PASSED** (4 migrations, `chat_messages` present) |
| `npm run db:schema` | Regenerated `src/db/schema/schema.sql` |

Android companion repo (separate): `ChatHistoryMergeTest` 8 tests + `OfflineEngineTest` 25 tests, all passing after the ViewModel history fix.

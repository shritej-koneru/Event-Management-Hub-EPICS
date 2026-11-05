-- ---------------------------------------------------------------------------
-- 006_roles_and_ownership.sql  (additive)
--   users.role              : 'student' (default) | 'organizer' | 'admin'
--   events.created_by       : owner of an event (NULL for seeded events)
--   chat_messages.client_id : client-generated idempotency key for resends
-- ---------------------------------------------------------------------------
ALTER TABLE users ADD COLUMN IF NOT EXISTS role text NOT NULL DEFAULT 'student';

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'users_role_check') THEN
    ALTER TABLE users ADD CONSTRAINT users_role_check
      CHECK (role IN ('student', 'organizer', 'admin'));
  END IF;
END $$;

ALTER TABLE events
  ADD COLUMN IF NOT EXISTS created_by text REFERENCES users (id) ON UPDATE CASCADE ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_events_created_by ON events (created_by);

ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS client_id text;
CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_messages_user_client
  ON chat_messages (user_id, client_id) WHERE client_id IS NOT NULL;

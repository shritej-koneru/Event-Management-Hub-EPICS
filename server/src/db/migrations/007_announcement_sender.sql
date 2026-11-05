-- ---------------------------------------------------------------------------
-- 007_announcement_sender.sql  (additive)
--   announcements.sent_by : user who sent a manual (organizer/admin)
--                           announcement. NULL for automatic event relays and
--                           seed rows; set to NULL if the user is deleted.
--   announcements.room    : now NULLABLE. NULL = campus-wide (global)
--                           announcement, served as room "all". The venue FK
--                           (announcements_room_fkey) still guards every
--                           non-NULL room; NULLs also bypass the UNIQUE
--                           (room, message) duplicate guard, which is intended
--                           (the guard exists for the idempotent event relay).
-- ---------------------------------------------------------------------------
ALTER TABLE announcements ALTER COLUMN room DROP NOT NULL;

ALTER TABLE announcements
  ADD COLUMN IF NOT EXISTS sent_by text REFERENCES users (id) ON UPDATE CASCADE ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_announcements_sent_by
  ON announcements (sent_by, id DESC)
  WHERE sent_by IS NOT NULL;

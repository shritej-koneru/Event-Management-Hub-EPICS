-- =============================================================================
-- GENERATED FILE — do not edit by hand.
-- Refresh with `npm run db:schema`.
-- Authoritative definitions live in src/db/migrations/*.sql
-- =============================================================================

-- ---- venues ------------------------------------------------------
  id              text               NOT NULL
  name            text               NOT NULL
  map_x           double precision   NOT NULL DEFAULT 0.5
  map_y           double precision   NOT NULL DEFAULT 0.5
  -- PRIMARY KEY (id)
  -- UNIQUE (name)
  -- INDEX venues_name_key
  -- INDEX venues_pkey

-- ---- events ------------------------------------------------------
  id              text               NOT NULL
  title           text               NOT NULL
  category        text               NOT NULL
  venue_id        text               NOT NULL
  date            date               NOT NULL
  time            text               NOT NULL
  duration_min    integer            NOT NULL DEFAULT 60
  capacity        integer            NOT NULL
  registered      integer            NOT NULL DEFAULT 0
  host            text               NOT NULL DEFAULT ''::text
  description     text               NOT NULL DEFAULT ''::text
  tags            jsonb              NOT NULL DEFAULT '[]'::jsonb
  icon            text               NOT NULL DEFAULT '🎫'::text
  created_at      timestamp with time zone NOT NULL DEFAULT now()
  -- PRIMARY KEY (id)
  -- FOREIGN KEY (venue_id) REFERENCES venues(id) ON UPDATE CASCADE
  -- INDEX events_pkey
  -- INDEX idx_events_category
  -- INDEX idx_events_date
  -- INDEX idx_events_venue

-- ---- users -------------------------------------------------------
  id              text               NOT NULL
  name            text               NOT NULL
  email           text               NOT NULL
  password_hash   text               NOT NULL
  interests       jsonb              NOT NULL DEFAULT '[]'::jsonb
  created_at      timestamp with time zone NOT NULL DEFAULT now()
  -- PRIMARY KEY (id)
  -- UNIQUE (email)
  -- INDEX users_email_key
  -- INDEX users_pkey

-- ---- bookmarks ---------------------------------------------------
  user_id         text               NOT NULL
  event_id        text               NOT NULL
  created_at      timestamp with time zone NOT NULL DEFAULT now()
  -- PRIMARY KEY (user_id, event_id)
  -- FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE
  -- FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
  -- INDEX bookmarks_pkey
  -- INDEX idx_bookmarks_event
  -- INDEX idx_bookmarks_user

-- ---- search_history ----------------------------------------------
  id              bigint             NOT NULL
  user_id         text               NOT NULL
  query           text               NOT NULL
  at              timestamp with time zone NOT NULL DEFAULT now()
  -- PRIMARY KEY (id)
  -- FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
  -- INDEX idx_search_history_user
  -- INDEX idx_search_history_user_query
  -- INDEX search_history_pkey

-- ---- announcements -----------------------------------------------
  id              bigint             NOT NULL
  room            text               NOT NULL
  message         text               NOT NULL
  at              timestamp with time zone NOT NULL DEFAULT now()
  event_id        text              
  -- PRIMARY KEY (id)
  -- UNIQUE (room, message)
  -- FOREIGN KEY (event_id) REFERENCES events(id) ON UPDATE CASCADE ON DELETE CASCADE
  -- FOREIGN KEY (room) REFERENCES venues(id) ON UPDATE CASCADE ON DELETE CASCADE
  -- INDEX announcements_pkey
  -- INDEX announcements_room_message_key
  -- INDEX idx_announcements_event
  -- INDEX idx_announcements_room

-- ---- venue_edges -------------------------------------------------
  from_id         text               NOT NULL
  to_id           text               NOT NULL
  distance_m      integer            NOT NULL
  -- PRIMARY KEY (from_id, to_id)
  -- FOREIGN KEY (from_id) REFERENCES venues(id) ON UPDATE CASCADE ON DELETE CASCADE
  -- FOREIGN KEY (to_id) REFERENCES venues(id) ON UPDATE CASCADE ON DELETE CASCADE
  -- INDEX venue_edges_pkey

-- ---- migrations --------------------------------------------------
  id              text               NOT NULL
  applied_at      timestamp with time zone NOT NULL DEFAULT now()
  -- PRIMARY KEY (id)
  -- INDEX migrations_pkey

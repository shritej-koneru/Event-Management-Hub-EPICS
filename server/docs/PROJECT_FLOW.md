# Project Flow Diagrams

Mermaid diagrams for flows **implemented in this codebase**. Incomplete or
external pieces are labelled.

## 1. Overall system architecture

```mermaid
flowchart LR
  subgraph Client["Android app (separate repo)"]
    UI[Compose UI]
    Repo[HubRepository]
    VM[ChatViewModel]
    Cache[OfflineStore JSON]
  end
  subgraph Server["This backend"]
    Express[Express /api]
    SIO[Socket.IO]
    Models[Models / SQL]
  end
  PG[(PostgreSQL)]
  UI --> Repo
  UI --> VM
  Repo --> Cache
  Repo --> Express
  VM --> SIO
  VM --> Repo
  Express --> Models
  SIO --> Models
  Models --> PG
```

## 2. API request / response flow

```mermaid
sequenceDiagram
  participant C as Android
  participant R as Route
  participant V as validate Zod
  participant Ctrl as Controller
  participant M as Model/SQL
  C->>R: GET /api/events?category=Tech
  R->>V: parse query
  V-->>C: 400 VALIDATION_ERROR (if bad)
  R->>Ctrl: ok
  Ctrl->>M: SELECT events
  M-->>Ctrl: rows
  Ctrl-->>C: 200 { events, pagination? }
```

## 3. Database interaction (boot)

```mermaid
flowchart TD
  A[node src/server.js] --> B[ensureDatabase]
  B --> C[migrate pending SQL]
  C --> D[seed idempotent]
  D --> E[validateGraph]
  E --> F[listen HTTP + Socket.IO]
```

## 4. Authentication flow

```mermaid
sequenceDiagram
  participant A as App
  participant API as /api/auth
  participant DB
  A->>API: POST /login {email,password}
  API->>DB: lookup user, bcrypt compare
  DB-->>API: user row
  API-->>A: { token, user }
  A->>API: GET /api/auth/me Authorization Bearer
  API->>API: verifyJwt
  API-->>A: 200 profile or 401
```

Socket path: optional `auth.token` → `resolveHandshakeAuth` → invalid ⇒
`connect_error AUTH_INVALID_TOKEN`.

## 5. Event discovery and registration

```mermaid
flowchart LR
  Q[Query filters or AI search] --> S[Score / SQL filter]
  S --> L[Event list]
  L --> D[Event details aggregate]
  D --> B[Bookmarks REST]
  Note1["Registration is a capacity counter on events.registered via POST /events — no separate ticket table"]:::note
  classDef note fill:#fff7e6,stroke:#d48806
```

## 6. Real-time announcements and chat

```mermaid
sequenceDiagram
  participant C1 as Client A
  participant S as Socket server
  participant DB
  participant C2 as Client B
  C1->>S: connect (optional token)
  C1->>S: join announce:room-204
  C2->>S: join announce:room-204
  Note over S: POST /api/events creates event
  S->>DB: recordAnnouncement INSERT ON CONFLICT
  DB-->>S: created=true
  S->>C1: event { title, venue, eventId }
  S->>C2: event { title, venue, eventId }
  C1->>S: chat:message { text }
  S->>DB: INSERT chat_messages
  DB-->>S: row with id
  S->>C1: chat:message (broadcast)
  S->>C2: chat:message (broadcast)
  S-->>C1: ack { ok, message }
  C1->>S: (later) GET /api/chat/messages via REST
```

## 7. Application startup and database initialization

```mermaid
stateDiagram-v2
  [*] --> Starting
  Starting --> EnsuringDB
  EnsuringDB --> Migrating
  Migrating --> Seeding
  Seeding --> Listening
  Listening --> [*]
  Migrating --> Failed: SQL error
  Failed --> [*]
```

---

Diagrams cover implemented paths only. Push notifications, payments, and
multi-instance Socket.IO are **not** in this system.

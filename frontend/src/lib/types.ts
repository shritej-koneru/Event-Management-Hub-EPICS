/**
 * All API shapes for the manager console live here. The /api/manage/* shapes follow
 * server contract 1.4.0 (manager-api-spec); if the server deviates, fix it in this file.
 */
export type Role = 'student' | 'organizer' | 'admin'

export interface User {
  id: string
  name: string
  email: string
  role: Role
  interests?: string[]
  bookmarks?: string[]
}

export interface EventItem {
  id: string
  title: string
  category: string
  venue: string
  venueId: string
  date: string
  time: string
  durationMin: number
  capacity: number
  registered: number
  host: string
  description: string
  tags: string[]
  icon: string
}

/** GET /api/manage/events item: normal event fields + createdBy, bookmarkCount. */
export interface ManagedEvent extends EventItem {
  createdBy?: string | null
  bookmarkCount: number
}

/** GET /api/manage/overview */
export interface Overview {
  events: { total: number; upcoming: number; past: number }
  bookmarks: number
  announcements: number
  venues: number
  edges: number
}

/** Used for socket room joins. */
export interface Venue {
  id: string
  name: string
}

/** GET /api/navigation/map */
export interface MapVenue {
  id: string
  name: string
  x: number
  y: number
  neighbors?: string[]
  /** Contract 1.5.0 (additive; absent on older servers). */
  lat?: number
  lng?: number
  kind?: string
  description?: string
  /** true = placed on the real map by a manager; false = lat/lng derived from x/y. */
  geoPlaced?: boolean
}

/** Campus geo bounds served by the server (contract 1.5.0). */
export interface CampusBounds {
  north: number
  south: number
  west: number
  east: number
  center: { lat: number; lng: number }
}

export interface MapEdge {
  from: string
  to: string
  distanceM: number
}

export interface CampusMap {
  venues: MapVenue[]
  edges: MapEdge[]
  bounds?: CampusBounds
}

/** GET /api/manage/users item (admin only). */
export interface ManagedUser {
  id: string
  name: string
  email: string
  role: Role
  createdAt: string
}

/** POST /api/manage/announcements -> { announcement } ; GET list items. */
export interface ManagedAnnouncement {
  id: number | string
  room: string
  message: string
  at: string
  eventId?: string | null
  sentBy?: string | null
}

/** Payload of the Socket.IO `event` broadcast (events and manager announcements). */
export interface EventBroadcast {
  title?: string
  venue?: string
  venueId?: string
  eventId?: string
  reason?: string
  announcement?: { title?: string; message?: string }
}

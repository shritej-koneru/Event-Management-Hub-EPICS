export type Role = 'student' | 'organizer' | 'admin'

export interface User {
  id: string
  name: string
  email: string
  role: Role
  interests: string[]
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

export interface ScoredEvent {
  event: EventItem
  score: number
  reason: string
}

export interface ChatMessage {
  id: number
  author: string
  text: string
  room: string | null
  bot: boolean
  ts: string
}

export interface Announcement {
  id: number
  room: string
  message: string
  at: string
  eventId?: string
}

export interface Venue {
  id: string
  name: string
  neighbors?: string[]
}

/** Payload of the Socket.IO `event` broadcast. */
export interface EventBroadcast {
  event: EventItem
  title: string
  venue: string
  venueId: string
  eventId: string
  reason: 'created' | 'updated' | 'deleted'
}

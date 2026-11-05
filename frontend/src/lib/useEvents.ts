import { useEffect, useState } from 'react'

import { useAuth } from '@/auth/useAuth'
import { api, errorMessage } from '@/lib/api'
import type { EventItem, ScoredEvent } from '@/lib/types'
import { useSocket } from '@/realtime/context'

interface Result {
  key: string
  events: EventItem[]
  error: string | null
}

export interface EventsState {
  events: EventItem[]
  loading: boolean
  error: string | null
  reload: () => void
}

/**
 * Event list, optionally narrowed by a search query (POST /api/ai/search).
 * Re-fetches silently whenever the server broadcasts an `event` change over
 * Socket.IO, so a web- or Android-created event shows up live.
 */
export function useEvents(query: string): EventsState {
  const { socket } = useSocket()
  const { token } = useAuth()
  const q = query.trim()
  const key = `${q}|${token ?? ''}`
  const [tick, setTick] = useState(0)
  const [result, setResult] = useState<Result | null>(null)

  useEffect(() => {
    if (!socket) return
    const onEvent = () => setTick((t) => t + 1)
    socket.on('event', onEvent)
    return () => {
      socket.off('event', onEvent)
    }
  }, [socket])

  useEffect(() => {
    const ctrl = new AbortController()
    const timer = setTimeout(
      () => {
        const request = q
          ? api<{ results: ScoredEvent[] }>('/ai/search', {
              method: 'POST',
              body: { query: q, limit: 50 },
              signal: ctrl.signal,
            }).then((r) => r.results.map((s) => s.event))
          : api<{ events: EventItem[] }>('/events', { signal: ctrl.signal }).then((r) => r.events)
        request
          .then((events) => setResult({ key, events, error: null }))
          .catch((err: unknown) => {
            if (ctrl.signal.aborted) return
            setResult({ key, events: [], error: errorMessage(err) })
          })
      },
      q && tick === 0 ? 300 : 0,
    )
    return () => {
      clearTimeout(timer)
      ctrl.abort()
    }
  }, [q, key, tick])

  const current = result && result.key === key ? result : null
  return {
    events: current?.events ?? [],
    loading: current === null,
    error: current?.error ?? null,
    reload: () => setTick((t) => t + 1),
  }
}

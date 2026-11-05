import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'

import { useAuth } from '@/auth/useAuth'
import { api, errorMessage } from '@/lib/api'
import type { ScoredEvent } from '@/lib/types'

import EventCard from './EventCard'
import { Empty, ErrorNote, Loading } from './Feedback'

/** Personalised picks from GET /api/ai/recommendations (requires login). */
export default function Recommendations() {
  const { token, user } = useAuth()
  const [state, setState] = useState<{
    token: string
    items: ScoredEvent[]
    error: string | null
  } | null>(null)

  useEffect(() => {
    if (!token) return
    let cancelled = false
    api<{ recommendations: ScoredEvent[] }>('/ai/recommendations')
      .then((r) => !cancelled && setState({ token, items: r.recommendations, error: null }))
      .catch((err: unknown) => !cancelled && setState({ token, items: [], error: errorMessage(err) }))
    return () => {
      cancelled = true
    }
  }, [token])

  if (!user) {
    return (
      <p className="text-sm text-white/80">
        <Link to="/login" className="font-bold underline">
          Sign in
        </Link>{' '}
        to see events recommended for you.
      </p>
    )
  }

  const current = state && state.token === token ? state : null
  return (
    <section aria-label="Recommended for you" className="flex flex-col gap-3.5">
      <h2 className="text-[19px] font-bold text-white">Recommended for you</h2>
      {!current && <Loading label="Loading recommendations..." />}
      {current?.error && <ErrorNote>{current.error}</ErrorNote>}
      {current && !current.error && current.items.length === 0 && (
        <Empty>No recommendations yet. Add interests in your profile or bookmark events.</Empty>
      )}
      {current?.items.map((r) => (
        <EventCard key={r.event.id} event={r.event} hint={r.reason} />
      ))}
    </section>
  )
}

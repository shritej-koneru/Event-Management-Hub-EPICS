import { useEffect, useState } from 'react'

import { api } from './api'
import type { CampusMap } from './types'

let cache: Record<string, string> | null = null

/** venue id -> display name, fetched once per session from /navigation/map (empty until known). */
export function useVenueNames(): Record<string, string> {
  const [names, setNames] = useState<Record<string, string>>(cache ?? {})
  useEffect(() => {
    if (cache) return
    const ac = new AbortController()
    api<CampusMap>('/navigation/map', { signal: ac.signal })
      .then((m) => {
        cache = Object.fromEntries(m.venues.map((v) => [v.id, v.name]))
        setNames(cache)
      })
      .catch(() => undefined)
    return () => ac.abort()
  }, [])
  return names
}

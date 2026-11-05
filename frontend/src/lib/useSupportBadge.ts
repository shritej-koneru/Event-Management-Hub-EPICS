import { useEffect, useState } from 'react'

import { useAuth } from '@/auth/useAuth'
import { useSocket } from '@/realtime/context'

import { api } from './api'
import { totalOpen } from './support'
import type { SupportEventSummary } from './types'

/** Open help-desk chats across the manager's events (0 until known). Refreshes on socket support events. */
export function useSupportBadge(): number {
  const { user, isManager } = useAuth()
  const { socket } = useSocket()
  const [count, setCount] = useState(0)
  const [tick, setTick] = useState(0)

  useEffect(() => {
    if (!socket) return
    const bump = () => setTick((t) => t + 1)
    socket.on('support:message', bump)
    socket.on('support:ticket', bump)
    return () => {
      socket.off('support:message', bump)
      socket.off('support:ticket', bump)
    }
  }, [socket])

  useEffect(() => {
    if (!user || !isManager) return
    const ac = new AbortController()
    api<{ events: SupportEventSummary[] }>('/manage/support/events', { signal: ac.signal })
      .then((r) => setCount(totalOpen(r.events)))
      .catch(() => undefined)
    return () => ac.abort()
  }, [user, isManager, tick])

  return count
}

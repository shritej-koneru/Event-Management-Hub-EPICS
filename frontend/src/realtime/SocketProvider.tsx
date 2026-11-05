import { useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { io } from 'socket.io-client'
import type { Socket } from 'socket.io-client'

import { useAuth } from '@/auth/useAuth'
import { api } from '@/lib/api'
import type { Venue } from '@/lib/types'

import { SocketContext } from './context'
import type { SocketState, SocketStatus } from './context'

/**
 * One Socket.IO connection (default namespace, path /socket.io, same origin via
 * the Vite proxy). Rebuilt whenever the auth token changes so the handshake
 * `auth.token` is always current. On every (re)connect the socket joins every
 * venue room so it receives the per-venue `event` broadcasts.
 */
export default function SocketProvider({ children }: { children: ReactNode }) {
  const { token, loading, logout } = useAuth()
  const [status, setStatus] = useState<SocketStatus>('connecting')

  // Created (not connected) per token; connection is managed by the effect below.
  const socket = useMemo<Socket | null>(
    () => (loading ? null : io({ path: '/socket.io', auth: token ? { token } : {}, autoConnect: false })),
    [token, loading],
  )

  useEffect(() => {
    if (!socket) return
    let venueIds: string[] = []
    let cancelled = false

    const join = () => {
      if (venueIds.length) socket.emit('join', venueIds)
    }
    const onConnect = () => {
      setStatus('connected')
      join()
    }
    const onDisconnect = () => setStatus('disconnected')
    const onConnectError = (err: Error & { data?: { code?: string } }) => {
      setStatus('disconnected')
      // A stale token is rejected outright by the server: drop the session.
      if (err.data?.code === 'AUTH_INVALID_TOKEN') logout()
    }

    socket.on('connect', onConnect)
    socket.on('disconnect', onDisconnect)
    socket.on('connect_error', onConnectError)
    socket.connect()

    api<{ venues: Venue[] }>('/navigation/venues')
      .then((res) => {
        if (cancelled) return
        venueIds = res.venues.map((v) => v.id)
        if (socket.connected) join()
      })
      .catch(() => undefined)

    return () => {
      cancelled = true
      socket.off('connect', onConnect)
      socket.off('disconnect', onDisconnect)
      socket.off('connect_error', onConnectError)
      socket.disconnect()
      setStatus('connecting')
    }
  }, [socket, logout])

  const value = useMemo<SocketState>(() => ({ socket, status }), [socket, status])
  return <SocketContext.Provider value={value}>{children}</SocketContext.Provider>
}

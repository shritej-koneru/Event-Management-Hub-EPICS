import { createContext, useContext } from 'react'
import type { Socket } from 'socket.io-client'

export type SocketStatus = 'connecting' | 'connected' | 'disconnected'

export interface SocketState {
  socket: Socket | null
  status: SocketStatus
}

export const SocketContext = createContext<SocketState>({ socket: null, status: 'disconnected' })

export function useSocket(): SocketState {
  return useContext(SocketContext)
}

import type { ReactNode } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'

import { useAuth } from '@/auth/useAuth'

import AppLayout from '@/components/layout/AppLayout'
import AccountsPage from '@/pages/AccountsPage'
import AnnouncementsPage from '@/pages/AnnouncementsPage'
import DashboardPage from '@/pages/DashboardPage'
import EventsPage from '@/pages/EventsPage'
import HelpDeskPage from '@/pages/HelpDeskPage'
import LoginPage from '@/pages/LoginPage'
import MapPage from '@/pages/MapPage'

function AdminOnly({ children }: { children: ReactNode }) {
  const { user } = useAuth()
  return user?.role === 'admin' ? children : <Navigate to="/" replace />
}

export default function App() {
  return (
    <Routes>
      <Route path="login" element={<LoginPage />} />
      <Route element={<AppLayout />}>
        <Route index element={<DashboardPage />} />
        <Route path="events" element={<EventsPage />} />
        <Route path="map" element={<MapPage />} />
        <Route path="announcements" element={<AnnouncementsPage />} />
        <Route path="help-desk" element={<HelpDeskPage />} />
        <Route path="accounts" element={<AdminOnly><AccountsPage /></AdminOnly>} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  )
}

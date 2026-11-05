import { Navigate, Route, Routes } from 'react-router-dom'

import AppLayout from '@/components/layout/AppLayout'
import AnnouncementsPage from '@/pages/AnnouncementsPage'
import DashboardPage from '@/pages/DashboardPage'
import EventsPage from '@/pages/EventsPage'
import LoginPage from '@/pages/LoginPage'
import MapPage from '@/pages/MapPage'

export default function App() {
  return (
    <Routes>
      <Route path="login" element={<LoginPage />} />
      <Route element={<AppLayout />}>
        <Route index element={<DashboardPage />} />
        <Route path="events" element={<EventsPage />} />
        <Route path="map" element={<MapPage />} />
        <Route path="announcements" element={<AnnouncementsPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  )
}

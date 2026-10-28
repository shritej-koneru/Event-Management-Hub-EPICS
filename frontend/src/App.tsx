import { Navigate, Route, Routes } from 'react-router-dom'

import AppLayout from '@/components/layout/AppLayout'
import CategoryPage from '@/pages/CategoryPage'
import DashboardPage from '@/pages/DashboardPage'
import EventDetailPage from '@/pages/EventDetailPage'
import EventsPage from '@/pages/EventsPage'
import HomePage from '@/pages/HomePage'
import MyEventsPage from '@/pages/MyEventsPage'
import ProfilePage from '@/pages/ProfilePage'
import RegistrationPage from '@/pages/RegistrationPage'

export default function App() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<HomePage />} />
        <Route path="events" element={<EventsPage />} />
        <Route path="events/:eventId" element={<EventDetailPage />} />
        <Route
          path="events/:eventId/category/:categoryId"
          element={<CategoryPage />}
        />
        <Route
          path="events/:eventId/register"
          element={<RegistrationPage />}
        />
        <Route path="my-events" element={<MyEventsPage />} />
        <Route path="profile" element={<ProfilePage />} />
        <Route path="dashboard" element={<DashboardPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  )
}

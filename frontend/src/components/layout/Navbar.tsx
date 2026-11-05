import { Bell, CalendarDays } from 'lucide-react'
import { Link } from 'react-router-dom'

import { useAuth } from '@/auth/useAuth'

export default function Navbar() {
  const { user } = useAuth()
  const initials = user?.name
    .split(/\s+/)
    .map((p) => p[0])
    .join('')
    .slice(0, 2)
    .toUpperCase()

  return (
    <header className="fixed inset-x-0 top-0 z-40 flex h-16 items-center justify-between border-b border-gray-200 bg-white/90 px-4 backdrop-blur sm:px-6 lg:px-8">
      <Link to="/" className="flex items-center gap-2">
        <span className="flex h-9 w-9 items-center justify-center rounded-lg bg-indigo-600 text-white">
          <CalendarDays className="h-5 w-5" aria-hidden />
        </span>
        <span className="text-lg font-semibold text-gray-900">EventHub</span>
      </Link>

      <div className="flex items-center gap-2">
        <button
          type="button"
          aria-label="Notifications"
          className="relative flex h-9 w-9 items-center justify-center rounded-lg text-gray-500 transition hover:bg-gray-100 hover:text-gray-700"
        >
          <Bell className="h-5 w-5" aria-hidden />
          <span className="absolute right-2 top-2 h-2 w-2 rounded-full bg-indigo-600" />
        </button>
        {user ? (
          <Link
            to="/profile"
            aria-label={`Profile: ${user.name}`}
            className="flex h-9 w-9 items-center justify-center rounded-full bg-gray-200 text-sm font-medium text-gray-600"
          >
            {initials}
          </Link>
        ) : (
          <Link
            to="/login"
            className="rounded-lg bg-indigo-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-indigo-500"
          >
            Sign in
          </Link>
        )}
      </div>
    </header>
  )
}

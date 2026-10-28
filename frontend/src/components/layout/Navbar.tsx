import { Bell, CalendarDays } from 'lucide-react'
import { Link } from 'react-router-dom'

export default function Navbar() {
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
        <span
          aria-hidden
          className="flex h-9 w-9 items-center justify-center rounded-full bg-gray-200 text-sm font-medium text-gray-600"
        >
          AK
        </span>
      </div>
    </header>
  )
}

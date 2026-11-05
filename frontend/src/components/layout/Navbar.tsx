import { useEffect, useRef, useState } from 'react'
import { CalendarDays, LogOut } from 'lucide-react'
import { Link } from 'react-router-dom'

import { useAuth } from '@/auth/useAuth'

/** Top bar with the account menu (name, email, role badge, sign out). */
export default function Navbar() {
  const { user, logout } = useAuth()
  const [open, setOpen] = useState(false)
  const wrapRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

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
        <span className="text-lg font-semibold text-gray-900">EventHub Manager</span>
      </Link>

      {user && (
        <div ref={wrapRef} className="relative">
          <button
            type="button"
            aria-label={`Account menu for ${user.name}`}
            aria-haspopup="true"
            aria-expanded={open}
            onClick={() => setOpen((o) => !o)}
            className="flex h-9 w-9 cursor-pointer items-center justify-center rounded-full bg-gray-200 text-sm font-medium text-gray-600 hover:bg-gray-300"
          >
            {initials}
          </button>
          {open && (
            <div
              role="dialog"
              aria-label="Account"
              className="absolute right-0 top-11 w-64 rounded-xl border border-gray-200 bg-white p-4 shadow-xl"
            >
              <p className="truncate text-sm font-semibold text-gray-900">{user.name}</p>
              <p className="truncate text-xs text-gray-500">{user.email}</p>
              <span className="mt-2 inline-block rounded-full bg-indigo-50 px-2.5 py-0.5 text-xs font-semibold capitalize text-indigo-700">
                {user.role}
              </span>
              <button
                type="button"
                onClick={logout}
                className="mt-3 flex h-9 w-full cursor-pointer items-center justify-center gap-2 rounded-lg border border-gray-200 text-sm font-medium text-gray-700 hover:bg-gray-100"
              >
                <LogOut className="h-4 w-4" aria-hidden />
                Sign out
              </button>
            </div>
          )}
        </div>
      )}
    </header>
  )
}

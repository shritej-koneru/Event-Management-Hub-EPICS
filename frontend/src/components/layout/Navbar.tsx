import { useEffect, useRef, useState } from 'react'
import { LifeBuoy, LogOut } from 'lucide-react'
import { Link } from 'react-router-dom'

import { useAuth } from '@/auth/useAuth'
import { buttonSecondary, chipClass, overlayClass } from '@/components/styles'

/** 56px top bar: wordmark, help-desk open count, account menu (name, email, role, sign out). */
export default function Navbar({ openChats }: { openChats: number }) {
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
    <header className="fixed inset-x-0 top-0 z-40 flex h-14 items-center justify-between border-b border-line bg-surface px-4 lg:px-6">
      <Link to="/" className="flex h-11 items-center gap-2.5 rounded-control text-ink hover:text-ink">
        <span aria-hidden className="h-4 w-[3px] bg-accent" />
        <span className="text-heading font-semibold">EventHub</span>
      </Link>

      {user && (
        <div className="flex items-center gap-2">
          <Link
            to="/help-desk"
            className="flex h-9 items-center gap-1.5 rounded-control px-2.5 text-small font-medium text-ink-2 hover:bg-surface-sunken hover:text-ink pointer-coarse:h-11"
          >
            <LifeBuoy className="h-[18px] w-[18px]" strokeWidth={1.75} aria-hidden />
            <span className="max-sm:sr-only">Help desk</span>
            <span className={chipClass(openChats > 0 ? 'warning' : 'neutral')}>
              {openChats} open
              <span className="sr-only"> chats</span>
            </span>
          </Link>

          <div ref={wrapRef} className="relative">
            <button
              type="button"
              aria-label={`Account menu for ${user.name}`}
              aria-haspopup="true"
              aria-expanded={open}
              onClick={() => setOpen((o) => !o)}
              className="flex h-9 w-9 items-center justify-center rounded-full bg-surface-sunken text-small font-semibold text-ink-2 transition-colors duration-150 ease-out hover:bg-line pointer-coarse:h-11 pointer-coarse:w-11"
            >
              {initials}
            </button>
            {open && (
              <div role="dialog" aria-label="Account" className={`${overlayClass} absolute right-0 top-11 w-64 p-4`}>
                <p className="truncate text-body font-medium text-ink">{user.name}</p>
                <p className="truncate text-small text-ink-2">{user.email}</p>
                <span className={`${chipClass('accent')} mt-2 capitalize`}>{user.role}</span>
                <button type="button" onClick={logout} className={`${buttonSecondary} mt-3 w-full`}>
                  <LogOut className="h-4 w-4" strokeWidth={1.75} aria-hidden />
                  Sign out
                </button>
              </div>
            )}
          </div>
        </div>
      )}
    </header>
  )
}

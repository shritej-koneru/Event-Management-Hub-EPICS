import { NavLink } from 'react-router-dom'

import { useAuth } from '@/auth/useAuth'

import { navItemsFor } from './navItems'

/** Mobile bottom bar (below 1024px): 56px, hairline on top, active = accent text + 2px top bar. */
export default function BottomNav({ openChats }: { openChats: number }) {
  const { user } = useAuth()
  return (
    <nav
      aria-label="Main"
      className="fixed inset-x-0 bottom-0 z-40 h-14 border-t border-line bg-surface pb-[env(safe-area-inset-bottom)] box-content lg:hidden"
    >
      <div className="flex h-14 items-stretch">
        {navItemsFor(user?.role).map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            end={item.to === '/'}
            className={({ isActive }) =>
              `relative flex min-w-0 flex-1 flex-col items-center justify-center gap-0.5 text-caption font-medium transition-colors duration-150 ease-out ${
                isActive
                  ? 'text-accent before:absolute before:inset-x-0 before:top-0 before:h-0.5 before:bg-accent'
                  : 'text-ink-2 hover:text-ink'
              }`
            }
          >
            <span className="relative">
              <item.icon className="h-[18px] w-[18px]" strokeWidth={1.75} aria-hidden />
              {item.badge && openChats > 0 && (
                <span
                  className="absolute -right-3 -top-2 inline-flex h-4 min-w-4 items-center justify-center rounded-control bg-accent px-1 text-overline font-semibold text-on-accent"
                  aria-label={`${openChats} open chats`}
                >
                  {openChats}
                </span>
              )}
            </span>
            <span aria-hidden className="max-w-full truncate px-0.5">
              {item.short}
            </span>
            <span className="sr-only">{item.label}</span>
          </NavLink>
        ))}
      </div>
    </nav>
  )
}

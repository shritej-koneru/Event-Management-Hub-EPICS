import { NavLink } from 'react-router-dom'

import { useAuth } from '@/auth/useAuth'
import { badgeClass, overlineClass } from '@/components/styles'

import { navItemsFor } from './navItems'

export default function Sidebar({ openChats }: { openChats: number }) {
  const { user } = useAuth()
  return (
    <aside className="fixed bottom-0 left-0 top-14 z-30 hidden w-[232px] flex-col overflow-y-auto border-r border-line bg-paper px-3 py-5 lg:flex">
      <p className={`${overlineClass} px-3`}>Manage</p>
      <nav aria-label="Main" className="mt-2 flex flex-col gap-0.5">
        {navItemsFor(user?.role).map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            end={item.to === '/'}
            className={({ isActive }) =>
              `relative flex h-9 items-center gap-3 rounded-control px-3 text-body font-medium transition-colors duration-150 ease-out ${
                isActive
                  ? 'bg-accent-soft text-accent before:absolute before:inset-y-0 before:left-0 before:w-0.5 before:rounded-full before:bg-accent'
                  : 'text-ink-2 hover:bg-surface-sunken hover:text-ink'
              }`
            }
          >
            <item.icon className="h-[18px] w-[18px] shrink-0" strokeWidth={1.75} aria-hidden />
            {item.label}
            {item.badge && openChats > 0 && (
              <span className={`${badgeClass} ml-auto`} aria-label={`${openChats} open chats`}>
                {openChats}
              </span>
            )}
          </NavLink>
        ))}
      </nav>
    </aside>
  )
}

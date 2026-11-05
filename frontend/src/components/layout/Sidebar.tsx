import { NavLink } from 'react-router-dom'

import { useAuth } from '@/auth/useAuth'

import { useSupportBadge } from '@/lib/useSupportBadge'

import { navItemsFor } from './navItems'

export default function Sidebar() {
  const { user } = useAuth()
  const openChats = useSupportBadge()
  return (
    <aside className="fixed bottom-0 left-0 top-16 z-30 hidden w-64 flex-col overflow-y-auto border-r border-gray-200 bg-white px-3 py-6 lg:flex">
      <p className="px-3 text-xs font-semibold uppercase tracking-wider text-gray-400">
        Navigation
      </p>
      <nav className="mt-3 space-y-1">
        {navItemsFor(user?.role).map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            end={item.to === '/'}
            className={({ isActive }) =>
              `flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition ${
                isActive
                  ? 'bg-indigo-50 text-indigo-700'
                  : 'text-gray-600 hover:bg-gray-100 hover:text-gray-900'
              }`
            }
          >
            <item.icon className="h-5 w-5 shrink-0" aria-hidden />
            {item.label}
            {item.badge && openChats > 0 && (
              <span className="ml-auto rounded-full bg-[#6145ef] px-2 py-0.5 text-[11px] font-bold text-white" aria-label={`${openChats} open chats`}>
                {openChats}
              </span>
            )}
          </NavLink>
        ))}
      </nav>
    </aside>
  )
}

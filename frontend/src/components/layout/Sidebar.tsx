import { NavLink } from 'react-router-dom'

import { NAV_ITEMS } from './navItems'

export default function Sidebar() {
  return (
    <aside className="fixed bottom-0 left-0 top-16 z-30 hidden w-64 flex-col overflow-y-auto border-r border-gray-200 bg-white px-3 py-6 lg:flex">
      <p className="px-3 text-xs font-semibold uppercase tracking-wider text-gray-400">
        Navigation
      </p>
      <nav className="mt-3 space-y-1">
        {NAV_ITEMS.map((item) => (
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
          </NavLink>
        ))}
      </nav>
    </aside>
  )
}

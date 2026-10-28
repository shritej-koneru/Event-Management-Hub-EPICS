import { NavLink } from 'react-router-dom'

import { NAV_ITEMS } from './navItems'

export default function BottomNav() {
  return (
    <nav className="fixed bottom-3 left-1/2 z-40 h-[72px] w-[calc(100%-24px)] max-w-[456px] -translate-x-1/2 rounded-[23px] bg-[rgba(255,255,255,0.97)] px-1.5 pb-[env(safe-area-inset-bottom)] shadow-[0_12px_35px_rgba(20,0,80,0.3)] lg:hidden">
      <div className="flex h-full items-center justify-around">
        {NAV_ITEMS.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            end={item.to === '/'}
            className={({ isActive }) =>
              `flex w-[78px] flex-col items-center justify-center gap-1 rounded-[18px] py-2 text-[9px] font-bold transition-all ${
                isActive
                  ? 'bg-gradient-to-br from-[#6746f5] to-[#8b4ef5] text-white shadow-[0_7px_17px_rgba(95,60,240,0.3)]'
                  : 'text-[#9995aa] hover:bg-[#f0ecff] hover:text-[#6246ea]'
              }`
            }
          >
            <item.icon className="h-[21px] w-[21px]" aria-hidden />
            {item.label}
          </NavLink>
        ))}
      </div>
    </nav>
  )
}

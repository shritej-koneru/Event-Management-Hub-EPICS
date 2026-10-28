import {
  CalendarDays,
  CalendarHeart,
  Home,
  LayoutDashboard,
  User,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'

export interface NavItem {
  to: string
  label: string
  icon: LucideIcon
}

export const NAV_ITEMS: NavItem[] = [
  { to: '/', label: 'Home', icon: Home },
  { to: '/events', label: 'Events', icon: CalendarDays },
  { to: '/my-events', label: 'My Events', icon: CalendarHeart },
  { to: '/profile', label: 'Profile', icon: User },
  { to: '/dashboard', label: 'Dashboard', icon: LayoutDashboard },
]

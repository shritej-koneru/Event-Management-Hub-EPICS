import { Navigate, Outlet } from 'react-router-dom'

import { NOT_MANAGER_MESSAGE } from '@/auth/context'
import { useAuth } from '@/auth/useAuth'
import { Loading } from '@/components/Feedback'
import { buttonSecondary } from '@/components/styles'
import { useSupportBadge } from '@/lib/useSupportBadge'

import BottomNav from './BottomNav'
import Navbar from './Navbar'
import Sidebar from './Sidebar'

/** Console shell. Only organizer/admin sessions get past this point. */
export default function AppLayout() {
  const { user, loading, isManager, logout } = useAuth()
  const openChats = useSupportBadge()

  if (loading) {
    return (
      <div className="min-h-screen bg-paper">
        <Loading label="Checking your session..." />
      </div>
    )
  }
  if (!user) return <Navigate to="/login" replace />
  if (!isManager) {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center gap-4 bg-paper px-6 text-center">
        <p role="alert" className="max-w-md text-heading font-semibold text-ink">
          {NOT_MANAGER_MESSAGE}
        </p>
        <button type="button" onClick={logout} className={buttonSecondary}>
          Sign out
        </button>
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-paper text-ink">
      <Navbar openChats={openChats} />
      <Sidebar openChats={openChats} />
      <main className="pt-14 max-lg:pb-14 lg:pl-[232px]">
        <Outlet />
      </main>
      <BottomNav openChats={openChats} />
    </div>
  )
}

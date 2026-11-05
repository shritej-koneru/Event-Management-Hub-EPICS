import { Navigate, useNavigate } from 'react-router-dom'

import { useAuth } from '@/auth/useAuth'
import AuthForm from '@/components/AuthForm'

export default function LoginPage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  if (user) return <Navigate to="/" replace />

  return (
    <div className="px-[18px] pb-[110px] pt-[25px]">
      <h1 className="mb-4 text-[25px] font-bold text-white">Welcome to EventHub</h1>
      <AuthForm onDone={() => navigate('/', { replace: true })} />
    </div>
  )
}

import { useState } from 'react'
import type { FormEvent } from 'react'
import { UserPlus } from 'lucide-react'

import { useAuth } from '@/auth/useAuth'
import { Empty, ErrorNote, Loading, SuccessNote } from '@/components/Feedback'
import PageContainer from '@/components/layout/PageContainer'
import {
  buttonPrimary,
  chipClass,
  inputClass,
  labelClass,
  noticeClass,
  panelClass,
  panelHeader,
  panelTitle,
  tableClass,
  tdClass,
  theadClass,
  thClass,
  trClass,
} from '@/components/styles'
import { api, ApiError } from '@/lib/api'
import { formatDate } from '@/lib/format'
import { apiErrorMessage } from '@/lib/manage'
import type { ManagedUser, Role } from '@/lib/types'
import { useFetch } from '@/lib/useFetch'

const ROLES: Role[] = ['organizer', 'admin', 'student']

function accountsError(err: unknown): string {
  if (err instanceof ApiError && err.status === 403) return 'Only admins can manage accounts.'
  return apiErrorMessage(err)
}

export default function AccountsPage() {
  const { user: me } = useAuth()
  const users = useFetch((signal) => api<{ users: ManagedUser[] }>('/manage/users', { signal }))
  const [form, setForm] = useState({ name: '', email: '', password: '', role: 'organizer' as Role })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [rowBusy, setRowBusy] = useState<string | null>(null)
  const [rowError, setRowError] = useState<string | null>(null)

  async function create(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setNotice(null)
    if (!form.name.trim()) return setError('Name is required.')
    if (!/^\S+@\S+\.\S+$/.test(form.email.trim())) return setError('Enter a valid email.')
    if (form.password.length < 8) return setError('Password must be at least 8 characters.')
    setBusy(true)
    try {
      await api('/manage/users', {
        method: 'POST',
        body: { name: form.name.trim(), email: form.email.trim(), password: form.password, role: form.role },
      })
      setNotice(`Created account for ${form.name.trim()}.`)
      setForm({ name: '', email: '', password: '', role: form.role })
      users.reload()
    } catch (err) {
      setError(accountsError(err))
    } finally {
      setBusy(false)
    }
  }

  async function changeRole(u: ManagedUser, role: Role) {
    if (role === u.role) return
    setRowBusy(u.id)
    setRowError(null)
    setNotice(null)
    try {
      await api(`/manage/users/${encodeURIComponent(u.id)}`, { method: 'PUT', body: { role } })
      setNotice(`${u.name} is now ${role}.`)
      users.reload()
    } catch (err) {
      setRowError(accountsError(err))
    } finally {
      setRowBusy(null)
    }
  }

  const list = users.data?.users ?? []

  return (
    <PageContainer title="Accounts" description="Create accounts and change roles (admins only)">
      {notice && <SuccessNote>{notice}</SuccessNote>}

      <section aria-labelledby="create-account" className={panelClass}>
        <div className={panelHeader}>
          <h2 id="create-account" className={panelTitle}>
            Create account
          </h2>
        </div>
        <form onSubmit={create} aria-label="Create account" className="grid grid-cols-1 gap-4 p-4 md:grid-cols-2">
          <label className={labelClass}>
            Name
            <input
              required
              className={inputClass}
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
            />
          </label>
          <label className={labelClass}>
            Email
            <input
              required
              type="email"
              autoComplete="off"
              className={inputClass}
              value={form.email}
              onChange={(e) => setForm({ ...form, email: e.target.value })}
            />
          </label>
          <label className={labelClass}>
            Password (min 8 characters)
            <input
              required
              type="password"
              minLength={8}
              autoComplete="new-password"
              className={inputClass}
              value={form.password}
              onChange={(e) => setForm({ ...form, password: e.target.value })}
            />
          </label>
          <label className={labelClass}>
            Role
            <select
              className={`${inputClass} capitalize`}
              value={form.role}
              onChange={(e) => setForm({ ...form, role: e.target.value as Role })}
            >
              {ROLES.map((r) => (
                <option key={r} value={r}>
                  {r}
                </option>
              ))}
            </select>
          </label>
          {error && (
            <p role="alert" className={`${noticeClass('danger')} md:col-span-2`}>
              {error}
            </p>
          )}
          <div className="md:col-span-2">
            <button type="submit" disabled={busy} className={buttonPrimary}>
              <UserPlus className="h-4 w-4" strokeWidth={1.75} aria-hidden />
              Create account
            </button>
          </div>
        </form>
      </section>

      <section aria-labelledby="user-list" className={`${panelClass} mt-6`}>
        <div className={panelHeader}>
          <h2 id="user-list" className={panelTitle}>
            Users ({list.length})
          </h2>
        </div>
        {rowError && (
          <div className="p-4 pb-0">
            <p role="alert" className={noticeClass('danger')}>
              {rowError}
            </p>
          </div>
        )}
        {users.error && (
          <div className="p-4 pb-0">
            <ErrorNote>{users.error}</ErrorNote>
          </div>
        )}
        {users.loading && <Loading />}
        {!users.loading && !users.error && list.length === 0 && <Empty>No accounts.</Empty>}
        {list.length > 0 && (
          <div className="relative overflow-x-auto">
            <table className={`${tableClass} min-w-[560px]`}>
              <caption className="sr-only">Users</caption>
              <thead className={theadClass}>
                <tr>
                  <th scope="col" className={thClass}>Name</th>
                  <th scope="col" className={thClass}>Email</th>
                  <th scope="col" className={thClass}>Role</th>
                  <th scope="col" className={thClass}>Created</th>
                </tr>
              </thead>
              <tbody>
                {list.map((u) => (
                  <tr key={u.id} className={trClass}>
                    <td className={`${tdClass} font-medium`}>
                      {u.name}
                      {u.id === me?.id && <span className={`${chipClass('accent')} ml-2`}>You</span>}
                    </td>
                    <td className={`${tdClass} break-all text-ink-2`}>{u.email}</td>
                    <td className={tdClass}>
                      <select
                        aria-label={`Role for ${u.name}`}
                        className={`${inputClass} w-auto capitalize`}
                        value={u.role}
                        disabled={rowBusy === u.id}
                        onChange={(e) => void changeRole(u, e.target.value as Role)}
                      >
                        {ROLES.map((r) => (
                          <option key={r} value={r}>
                            {r}
                          </option>
                        ))}
                      </select>
                    </td>
                    <td className={`${tdClass} whitespace-nowrap text-ink-2`}>
                      {formatDate(u.createdAt.slice(0, 10))}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </PageContainer>
  )
}

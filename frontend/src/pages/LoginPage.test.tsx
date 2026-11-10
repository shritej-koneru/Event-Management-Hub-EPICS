import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { AuthContext } from '@/auth/context'
import type { AuthState } from '@/auth/context'

import LoginPage from './LoginPage'

// Runs the real LoginPage against a stubbed fetch and a fake auth context: checks that manager sign-up is only
// offered when the server says it is enabled, and that the form sends exactly one sign-up request.

function fakeAuth(over: Partial<AuthState> = {}): AuthState {
  return {
    token: null,
    user: null,
    loading: false,
    isManager: false,
    login: vi.fn(async () => {}),
    registerManager: vi.fn(async () => {}),
    logout: vi.fn(),
    ...over,
  }
}

function mockSignupStatus(enabled: boolean) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) =>
      url.includes('/auth/manager-signup')
        ? new Response(JSON.stringify({ enabled }), { status: 200 })
        : new Response('{}', { status: 404 }),
    ),
  )
}

function renderPage(auth: AuthState) {
  return render(
    <MemoryRouter>
      <AuthContext.Provider value={auth}>
        <LoginPage />
      </AuthContext.Provider>
    </MemoryRouter>,
  )
}

describe('LoginPage manager sign-up', () => {
  beforeEach(() => vi.unstubAllGlobals())
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  it('shows only sign-in when the server has manager sign-up switched off', async () => {
    mockSignupStatus(false)
    renderPage(fakeAuth())

    await waitFor(() => expect(fetch).toHaveBeenCalled())
    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeTruthy()
    expect(screen.queryByText('Create a manager account')).toBeNull()
    expect(screen.getByText(/Accounts are created by an administrator/)).toBeTruthy()
  })

  it('offers "Create a manager account" when enabled and sends exactly one sign-up', async () => {
    mockSignupStatus(true)
    const auth = fakeAuth()
    renderPage(auth)

    fireEvent.click(await screen.findByText('Create a manager account'))
    expect(screen.getByRole('heading', { name: 'Create manager account' })).toBeTruthy()

    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'UITEST Manager' } })
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: ' uitest-mgr@example.test ' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'test-pass-123' } })
    const form = screen.getByRole('form', { name: 'Create manager account' })
    fireEvent.submit(form)
    fireEvent.submit(form) // a double submit while the first is in flight must not send twice

    await waitFor(() => expect(auth.registerManager).toHaveBeenCalled())
    expect(auth.registerManager).toHaveBeenCalledTimes(1)
    expect(auth.registerManager).toHaveBeenCalledWith('UITEST Manager', 'uitest-mgr@example.test', 'test-pass-123')
    expect(auth.login).not.toHaveBeenCalled()
  })

  it('can go back to sign-in, which uses login and not sign-up', async () => {
    mockSignupStatus(true)
    const auth = fakeAuth()
    renderPage(auth)

    fireEvent.click(await screen.findByText('Create a manager account'))
    fireEvent.click(screen.getByText('Back to sign in'))
    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeTruthy()

    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'mgr@example.test' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'x' } })
    fireEvent.submit(screen.getByRole('form', { name: 'Sign in' }))

    await waitFor(() => expect(auth.login).toHaveBeenCalledWith('mgr@example.test', 'x'))
    expect(auth.registerManager).not.toHaveBeenCalled()
  })

  it('shows the server refusal when sign-up is turned off after the page loaded', async () => {
    mockSignupStatus(true)
    const auth = fakeAuth({
      registerManager: vi.fn(async () => {
        throw new Error('Manager sign-up is turned off on this server')
      }),
    })
    renderPage(auth)

    fireEvent.click(await screen.findByText('Create a manager account'))
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'N' } })
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'a@b.test' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'secret1' } })
    fireEvent.submit(screen.getByRole('form', { name: 'Create manager account' }))

    expect((await screen.findByRole('alert')).textContent).toContain('turned off')
  })
})

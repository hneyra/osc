import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { AppShell } from '@/shell/AppShell'
import type { ObjectDefinition } from '@/types/metadata'
import * as hooks from '@/api/hooks'

vi.mock('@/api/hooks')

const mockObjects: ObjectDefinition[] = [
  { id: 'o1', tenantId: 't1', apiName: 'Account', label: 'Account', labelPlural: 'Accounts',
    isCustom: false, createdAt: '', updatedAt: '' },
  { id: 'o2', tenantId: 't1', apiName: 'Contact', label: 'Contact', labelPlural: 'Contacts',
    isCustom: false, createdAt: '', updatedAt: '' },
]

function makeQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } })
}

function renderShell(children?: React.ReactNode) {
  const qc = makeQueryClient()
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <AppShell>{children ?? <div>Main content</div>}</AppShell>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('AppShell', () => {
  beforeEach(() => {
    vi.mocked(hooks.useObjectDefinitions).mockReturnValue({
      data: mockObjects,
      isLoading: false,
      isError: false,
    } as ReturnType<typeof hooks.useObjectDefinitions>)
  })

  it('renders header with app name', () => {
    renderShell()
    expect(screen.getByRole('banner')).toBeInTheDocument()
    expect(screen.getByText(/OSC/i)).toBeInTheDocument()
  })

  it('renders sidebar navigation with object links', async () => {
    renderShell()
    await waitFor(() => {
      expect(screen.getByRole('navigation')).toBeInTheDocument()
    })
    expect(screen.getByText('Accounts')).toBeInTheDocument()
    expect(screen.getByText('Contacts')).toBeInTheDocument()
  })

  it('renders main content area', () => {
    renderShell(<p>Page content here</p>)
    expect(screen.getByText('Page content here')).toBeInTheDocument()
  })

  it('shows loading state while objects are being fetched', () => {
    vi.mocked(hooks.useObjectDefinitions).mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
    } as ReturnType<typeof hooks.useObjectDefinitions>)

    renderShell()
    expect(screen.getByRole('status')).toBeInTheDocument()
  })

  it('renders search input in header', () => {
    renderShell()
    expect(screen.getByRole('searchbox')).toBeInTheDocument()
  })

  it('filters navigation by search query', async () => {
    renderShell()
    const search = screen.getByRole('searchbox')
    await userEvent.type(search, 'Account')
    await waitFor(() => {
      expect(screen.getByText('Accounts')).toBeInTheDocument()
      expect(screen.queryByText('Contacts')).not.toBeInTheDocument()
    })
  })
})

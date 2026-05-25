import React, { useState } from 'react'
import { NavLink } from 'react-router-dom'
import { useObjectDefinitions } from '@/api/hooks'
import { Spinner } from '@/components/feedback'
import { SearchInput } from '@/components/navigation'

interface AppShellProps {
  children?: React.ReactNode
}

export function AppShell({ children }: AppShellProps) {
  const { data: objects, isLoading } = useObjectDefinitions()
  const [searchQuery, setSearchQuery] = useState('')

  const filteredObjects = (objects ?? []).filter((obj) =>
    searchQuery === '' ||
    obj.labelPlural.toLowerCase().includes(searchQuery.toLowerCase()) ||
    obj.label.toLowerCase().includes(searchQuery.toLowerCase()) ||
    obj.apiName.toLowerCase().includes(searchQuery.toLowerCase())
  )

  return (
    <div className="osc-shell">
      <header className="osc-shell__header" role="banner">
        <div className="osc-shell__logo">
          <NavLink to="/" className="osc-shell__brand">OSC</NavLink>
        </div>
        <div className="osc-shell__search">
          <SearchInput
            placeholder="Search objects…"
            value={searchQuery}
            onSearch={setSearchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
          />
        </div>
        <div className="osc-shell__user">
          <button type="button" className="osc-shell__avatar" aria-label="User menu">
            <span aria-hidden="true">👤</span>
          </button>
        </div>
      </header>

      <div className="osc-shell__body">
        <nav className="osc-shell__sidebar" aria-label="Main navigation">
          <NavLink to="/" className={({ isActive }) => `osc-nav-item ${isActive ? 'osc-nav-item--active' : ''}`} end>
            Home
          </NavLink>

          {isLoading && (
            <div className="osc-shell__nav-loading">
              <Spinner size="sm" label="Loading objects…" />
            </div>
          )}

          {filteredObjects.map((obj) => (
            <NavLink
              key={obj.id}
              to={`/objects/${obj.apiName}`}
              className={({ isActive }) => `osc-nav-item ${isActive ? 'osc-nav-item--active' : ''}`}
            >
              {obj.labelPlural}
            </NavLink>
          ))}
        </nav>

        <main className="osc-shell__main" role="main">
          {children}
        </main>
      </div>
    </div>
  )
}

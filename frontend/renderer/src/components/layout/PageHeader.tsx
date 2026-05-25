import React from 'react'

interface PageHeaderProps {
  title: string
  subtitle?: string
  actions?: React.ReactNode
  breadcrumbs?: React.ReactNode
}

export function PageHeader({ title, subtitle, actions, breadcrumbs }: PageHeaderProps) {
  return (
    <header className="osc-page-header">
      {breadcrumbs && <div className="osc-page-header__breadcrumbs">{breadcrumbs}</div>}
      <div className="osc-page-header__row">
        <div className="osc-page-header__text">
          <h1 className="osc-page-header__title">{title}</h1>
          {subtitle && <p className="osc-page-header__subtitle">{subtitle}</p>}
        </div>
        {actions && <div className="osc-page-header__actions">{actions}</div>}
      </div>
    </header>
  )
}

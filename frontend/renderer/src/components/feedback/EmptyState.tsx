import React from 'react'

interface EmptyStateProps {
  title: string
  description?: string
  action?: React.ReactNode
}

export function EmptyState({ title, description, action }: EmptyStateProps) {
  return (
    <div className="osc-empty-state" role="status">
      <p className="osc-empty-state__title">{title}</p>
      {description && <p className="osc-empty-state__description">{description}</p>}
      {action && <div className="osc-empty-state__action">{action}</div>}
    </div>
  )
}

import React from 'react'

type AlertVariant = 'info' | 'success' | 'warning' | 'error'

interface AlertProps {
  variant?: AlertVariant
  title?: string
  children: React.ReactNode
  onDismiss?: () => void
  className?: string
}

export function Alert({ variant = 'info', title, children, onDismiss, className = '' }: AlertProps) {
  return (
    <div role="alert" className={`osc-alert osc-alert--${variant} ${className}`}>
      <div className="osc-alert__content">
        {title && <strong className="osc-alert__title">{title}</strong>}
        <div className="osc-alert__body">{children}</div>
      </div>
      {onDismiss && (
        <button type="button" className="osc-alert__dismiss" onClick={onDismiss} aria-label="Dismiss">
          ×
        </button>
      )}
    </div>
  )
}

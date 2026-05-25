import React from 'react'

interface CardProps extends React.HTMLAttributes<HTMLDivElement> {
  children: React.ReactNode
}

export function Card({ children, className = '', ...props }: CardProps) {
  return (
    <div className={`osc-card ${className}`} {...props}>
      {children}
    </div>
  )
}

interface CardHeaderProps {
  title: string
  subtitle?: string
  actions?: React.ReactNode
}

export function CardHeader({ title, subtitle, actions }: CardHeaderProps) {
  return (
    <div className="osc-card__header">
      <div className="osc-card__header-text">
        <h2 className="osc-card__title">{title}</h2>
        {subtitle && <p className="osc-card__subtitle">{subtitle}</p>}
      </div>
      {actions && <div className="osc-card__actions">{actions}</div>}
    </div>
  )
}

export function CardBody({ children, className = '' }: { children: React.ReactNode; className?: string }) {
  return <div className={`osc-card__body ${className}`}>{children}</div>
}

export function CardFooter({ children, className = '' }: { children: React.ReactNode; className?: string }) {
  return <div className={`osc-card__footer ${className}`}>{children}</div>
}

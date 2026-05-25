import React from 'react'

interface SectionProps {
  title?: string
  children: React.ReactNode
  className?: string
}

export function Section({ title, children, className = '' }: SectionProps) {
  return (
    <section className={`osc-section ${className}`}>
      {title && <h3 className="osc-section__title">{title}</h3>}
      <div className="osc-section__body">{children}</div>
    </section>
  )
}

import React from 'react'

interface GridProps extends React.HTMLAttributes<HTMLDivElement> {
  columns?: 1 | 2 | 3 | 4
  gap?: 'sm' | 'md' | 'lg'
  children: React.ReactNode
}

export function Grid({ columns = 2, gap = 'md', children, className = '', ...props }: GridProps) {
  return (
    <div
      className={`osc-grid osc-grid--cols-${columns} osc-grid--gap-${gap} ${className}`}
      {...props}
    >
      {children}
    </div>
  )
}

interface GridItemProps extends React.HTMLAttributes<HTMLDivElement> {
  span?: 1 | 2
  children: React.ReactNode
}

export function GridItem({ span = 1, children, className = '', ...props }: GridItemProps) {
  return (
    <div className={`osc-grid__item osc-grid__item--span-${span} ${className}`} {...props}>
      {children}
    </div>
  )
}

import React from 'react'
import { NavLink } from 'react-router-dom'

interface NavItemProps {
  to: string
  label: string
  icon?: React.ReactNode
}

export function NavItem({ to, label, icon }: NavItemProps) {
  return (
    <NavLink
      to={to}
      className={({ isActive }) => `osc-nav-item ${isActive ? 'osc-nav-item--active' : ''}`}
      aria-current={undefined}
    >
      {icon && <span className="osc-nav-item__icon" aria-hidden="true">{icon}</span>}
      <span className="osc-nav-item__label">{label}</span>
    </NavLink>
  )
}

import { Link } from 'react-router-dom'

export interface BreadcrumbItem {
  label: string
  to?: string
}

interface BreadcrumbProps {
  items: BreadcrumbItem[]
}

export function Breadcrumb({ items }: BreadcrumbProps) {
  return (
    <nav aria-label="Breadcrumb">
      <ol className="osc-breadcrumb">
        {items.map((item, i) => {
          const isLast = i === items.length - 1
          return (
            <li key={i} className="osc-breadcrumb__item">
              {!isLast && item.to ? (
                <Link to={item.to} className="osc-breadcrumb__link">
                  {item.label}
                </Link>
              ) : (
                <span className="osc-breadcrumb__current" aria-current={isLast ? 'page' : undefined}>
                  {item.label}
                </span>
              )}
              {!isLast && <span className="osc-breadcrumb__separator" aria-hidden="true">/</span>}
            </li>
          )
        })}
      </ol>
    </nav>
  )
}

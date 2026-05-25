interface DividerProps {
  label?: string
  className?: string
}

export function Divider({ label, className = '' }: DividerProps) {
  if (label) {
    return (
      <div className={`osc-divider osc-divider--labeled ${className}`} role="separator">
        <span className="osc-divider__label">{label}</span>
      </div>
    )
  }
  return <hr className={`osc-divider ${className}`} />
}

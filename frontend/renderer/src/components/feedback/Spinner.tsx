type SpinnerSize = 'sm' | 'md' | 'lg'

interface SpinnerProps {
  size?: SpinnerSize
  label?: string
}

export function Spinner({ size = 'md', label = 'Loading…' }: SpinnerProps) {
  return (
    <div className={`osc-spinner osc-spinner--${size}`} role="status" aria-label={label}>
      <span className="osc-spinner__ring" />
      <span className="osc-visually-hidden">{label}</span>
    </div>
  )
}

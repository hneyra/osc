import React from 'react'

interface CheckboxProps extends Omit<React.InputHTMLAttributes<HTMLInputElement>, 'type'> {
  label: string
  id: string
  error?: string
}

export function Checkbox({ label, id, error, className = '', ...props }: CheckboxProps) {
  return (
    <div className={`osc-field osc-field--checkbox ${className}`}>
      <input
        id={id}
        type="checkbox"
        className="osc-checkbox"
        aria-invalid={error ? 'true' : undefined}
        aria-describedby={error ? `${id}-error` : undefined}
        {...props}
      />
      <label htmlFor={id} className="osc-label osc-label--checkbox">
        {label}
      </label>
      {error && (
        <span id={`${id}-error`} className="osc-error-msg" role="alert">
          {error}
        </span>
      )}
    </div>
  )
}

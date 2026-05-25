import React from 'react'

interface NumberInputProps extends Omit<React.InputHTMLAttributes<HTMLInputElement>, 'type'> {
  label: string
  id: string
  error?: string
}

export function NumberInput({ label, id, error, required, className = '', ...props }: NumberInputProps) {
  return (
    <div className={`osc-field ${className}`}>
      <label htmlFor={id} className="osc-label">
        {label}
        {required && <span className="osc-required" aria-hidden="true">*</span>}
      </label>
      <input
        id={id}
        type="number"
        className={`osc-input osc-input--number ${error ? 'osc-input--error' : ''}`}
        aria-invalid={error ? 'true' : undefined}
        aria-describedby={error ? `${id}-error` : undefined}
        required={required}
        {...props}
      />
      {error && (
        <span id={`${id}-error`} className="osc-error-msg" role="alert">
          {error}
        </span>
      )}
    </div>
  )
}

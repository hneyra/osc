import React from 'react'

export interface SelectOption {
  value: string
  label: string
}

interface SelectProps extends Omit<React.SelectHTMLAttributes<HTMLSelectElement>, 'children'> {
  label: string
  id: string
  options: SelectOption[]
  placeholder?: string
  error?: string
}

export function Select({ label, id, options, placeholder = 'Select…', error, required, className = '', ...props }: SelectProps) {
  return (
    <div className={`osc-field ${className}`}>
      <label htmlFor={id} className="osc-label">
        {label}
        {required && <span className="osc-required" aria-hidden="true">*</span>}
      </label>
      <select
        id={id}
        className={`osc-select ${error ? 'osc-select--error' : ''}`}
        aria-invalid={error ? 'true' : undefined}
        aria-describedby={error ? `${id}-error` : undefined}
        required={required}
        {...props}
      >
        <option value="">{placeholder}</option>
        {options.map((opt) => (
          <option key={opt.value} value={opt.value}>
            {opt.label}
          </option>
        ))}
      </select>
      {error && (
        <span id={`${id}-error`} className="osc-error-msg" role="alert">
          {error}
        </span>
      )}
    </div>
  )
}

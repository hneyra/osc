import React from 'react'
import type { SelectOption } from './Select'

interface MultiSelectProps {
  label: string
  id: string
  options: SelectOption[]
  value?: string[]
  onChange?: (selected: string[]) => void
  error?: string
  required?: boolean
  disabled?: boolean
  className?: string
}

export function MultiSelect({ label, id, options, value = [], onChange, error, required, disabled, className = '' }: MultiSelectProps) {
  const handleChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const selected = Array.from(e.target.selectedOptions, (o) => o.value)
    onChange?.(selected)
  }

  return (
    <div className={`osc-field ${className}`}>
      <label htmlFor={id} className="osc-label">
        {label}
        {required && <span className="osc-required" aria-hidden="true">*</span>}
      </label>
      <select
        id={id}
        multiple
        value={value}
        onChange={handleChange}
        disabled={disabled}
        aria-invalid={error ? 'true' : undefined}
        aria-describedby={error ? `${id}-error` : undefined}
        className={`osc-select osc-select--multi ${error ? 'osc-select--error' : ''}`}
      >
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

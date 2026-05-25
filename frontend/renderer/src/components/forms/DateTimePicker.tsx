import React from 'react'

interface DateTimePickerProps extends Omit<React.InputHTMLAttributes<HTMLInputElement>, 'type'> {
  label: string
  id: string
  error?: string
}

export function DateTimePicker({ label, id, error, required, className = '', ...props }: DateTimePickerProps) {
  return (
    <div className={`osc-field ${className}`}>
      <label htmlFor={id} className="osc-label">
        {label}
        {required && <span className="osc-required" aria-hidden="true">*</span>}
      </label>
      <input
        id={id}
        type="datetime-local"
        className={`osc-input ${error ? 'osc-input--error' : ''}`}
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

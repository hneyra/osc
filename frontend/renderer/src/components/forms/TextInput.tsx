import React from 'react'

interface TextInputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  label: string
  id: string
  error?: string
}

export function TextInput({ label, id, error, required, className = '', ...props }: TextInputProps) {
  return (
    <div className={`osc-field ${className}`}>
      <label htmlFor={id} className="osc-label">
        {label}
        {required && <span className="osc-required" aria-hidden="true">*</span>}
      </label>
      <input
        id={id}
        type="text"
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

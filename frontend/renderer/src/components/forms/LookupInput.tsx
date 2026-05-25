import React, { useState } from 'react'

interface LookupOption {
  id: string
  label: string
}

interface LookupInputProps {
  label: string
  id: string
  value?: string
  displayValue?: string
  onSearch: (query: string) => Promise<LookupOption[]>
  onSelect: (option: LookupOption) => void
  onClear?: () => void
  error?: string
  required?: boolean
  disabled?: boolean
  placeholder?: string
  className?: string
}

export function LookupInput({
  label, id, value, displayValue, onSearch, onSelect, onClear,
  error, required, disabled, placeholder = 'Search…', className = '',
}: LookupInputProps) {
  const [query, setQuery] = useState(displayValue ?? '')
  const [options, setOptions] = useState<LookupOption[]>([])
  const [open, setOpen] = useState(false)

  const handleInput = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const q = e.target.value
    setQuery(q)
    if (q.length >= 2) {
      const results = await onSearch(q)
      setOptions(results)
      setOpen(true)
    } else {
      setOpen(false)
    }
  }

  const handleSelect = (opt: LookupOption) => {
    setQuery(opt.label)
    setOpen(false)
    onSelect(opt)
  }

  const handleClear = () => {
    setQuery('')
    setOptions([])
    onClear?.()
  }

  return (
    <div className={`osc-field osc-field--lookup ${className}`} role="combobox" aria-expanded={open} aria-haspopup="listbox">
      <label htmlFor={id} className="osc-label">
        {label}
        {required && <span className="osc-required" aria-hidden="true">*</span>}
      </label>
      <div className="osc-lookup-wrapper">
        <input
          id={id}
          type="text"
          value={query}
          onChange={handleInput}
          placeholder={placeholder}
          disabled={disabled}
          aria-invalid={error ? 'true' : undefined}
          aria-autocomplete="list"
          aria-controls={`${id}-listbox`}
          className={`osc-input ${error ? 'osc-input--error' : ''}`}
        />
        {value && (
          <button type="button" className="osc-lookup-clear" onClick={handleClear} aria-label="Clear selection">
            ×
          </button>
        )}
      </div>
      {open && options.length > 0 && (
        <ul id={`${id}-listbox`} role="listbox" className="osc-lookup-dropdown">
          {options.map((opt) => (
            <li key={opt.id} role="option" aria-selected={opt.id === value} onClick={() => handleSelect(opt)} className="osc-lookup-option">
              {opt.label}
            </li>
          ))}
        </ul>
      )}
      {error && (
        <span id={`${id}-error`} className="osc-error-msg" role="alert">
          {error}
        </span>
      )}
    </div>
  )
}

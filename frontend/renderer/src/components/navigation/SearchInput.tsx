import React from 'react'

interface SearchInputProps extends Omit<React.InputHTMLAttributes<HTMLInputElement>, 'type'> {
  onSearch?: (query: string) => void
}

export function SearchInput({ onSearch, onChange, value, className = '', ...props }: SearchInputProps) {
  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    onChange?.(e)
    onSearch?.(e.target.value)
  }

  return (
    <div className={`osc-search ${className}`}>
      <span className="osc-search__icon" aria-hidden="true">⌕</span>
      <input
        type="search"
        role="searchbox"
        className="osc-search__input"
        value={value}
        onChange={handleChange}
        {...props}
      />
    </div>
  )
}

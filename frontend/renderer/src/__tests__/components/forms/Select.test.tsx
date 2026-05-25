import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Select } from '@/components/forms/Select'

const options = [
  { value: 'tech', label: 'Technology' },
  { value: 'finance', label: 'Finance' },
]

describe('Select', () => {
  it('renders with label', () => {
    render(<Select label="Industry" id="industry" options={options} />)
    expect(screen.getByLabelText('Industry')).toBeInTheDocument()
  })

  it('renders all options', () => {
    render(<Select label="Industry" id="industry" options={options} />)
    expect(screen.getByRole('combobox')).toBeInTheDocument()
    expect(screen.getAllByRole('option')).toHaveLength(options.length + 1) // +1 placeholder
  })

  it('calls onChange on selection', async () => {
    const onChange = vi.fn()
    render(<Select label="Industry" id="industry" options={options} onChange={onChange} />)
    await userEvent.selectOptions(screen.getByRole('combobox'), 'tech')
    expect(onChange).toHaveBeenCalled()
  })

  it('shows error message', () => {
    render(<Select label="Industry" id="industry" options={options} error="Select a value" />)
    expect(screen.getByText('Select a value')).toBeInTheDocument()
  })

  it('disables when disabled', () => {
    render(<Select label="Industry" id="industry" options={options} disabled />)
    expect(screen.getByRole('combobox')).toBeDisabled()
  })
})

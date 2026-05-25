import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { TextInput } from '@/components/forms/TextInput'

describe('TextInput', () => {
  it('renders with label', () => {
    render(<TextInput label="Name" id="name" />)
    expect(screen.getByLabelText('Name')).toBeInTheDocument()
  })

  it('shows required indicator when required', () => {
    render(<TextInput label="Name" id="name" required />)
    expect(screen.getByText('*')).toBeInTheDocument()
  })

  it('calls onChange with new value', async () => {
    const onChange = vi.fn()
    render(<TextInput label="Name" id="name" onChange={onChange} />)
    await userEvent.type(screen.getByLabelText('Name'), 'ACME')
    expect(onChange).toHaveBeenCalled()
  })

  it('shows error message', () => {
    render(<TextInput label="Name" id="name" error="Required field" />)
    expect(screen.getByText('Required field')).toBeInTheDocument()
    expect(screen.getByRole('textbox')).toHaveAttribute('aria-invalid', 'true')
  })

  it('disables input when disabled', () => {
    render(<TextInput label="Name" id="name" disabled />)
    expect(screen.getByRole('textbox')).toBeDisabled()
  })

  it('shows placeholder', () => {
    render(<TextInput label="Name" id="name" placeholder="Enter name" />)
    expect(screen.getByPlaceholderText('Enter name')).toBeInTheDocument()
  })
})

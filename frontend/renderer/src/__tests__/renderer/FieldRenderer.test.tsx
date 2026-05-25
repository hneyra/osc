import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { FieldRenderer } from '@/renderer/FieldRenderer'
import type { FieldDefinition } from '@/types/metadata'

const field = (overrides: Partial<FieldDefinition>): FieldDefinition => ({
  id: '1', tenantId: 't1', objectId: 'o1', apiName: 'name', label: 'Name',
  fieldType: 'TEXT', storageKind: 'COLUMN', storageKey: 'name',
  isRequired: false, isCustom: false, config: null, createdAt: '', updatedAt: '',
  ...overrides,
})

describe('FieldRenderer — view mode', () => {
  it('renders TEXT as plain text', () => {
    render(<FieldRenderer field={field({ fieldType: 'TEXT', label: 'Name' })} value="ACME" mode="view" />, { wrapper: MemoryRouter })
    expect(screen.getByText('ACME')).toBeInTheDocument()
    expect(screen.getByText('Name')).toBeInTheDocument()
  })

  it('renders NUMBER as formatted number', () => {
    render(<FieldRenderer field={field({ fieldType: 'NUMBER', label: 'Amount' })} value={1234.5} mode="view" />, { wrapper: MemoryRouter })
    expect(screen.getByText(/1[,.]?234/)).toBeInTheDocument()
  })

  it('renders BOOLEAN true as Yes', () => {
    render(<FieldRenderer field={field({ fieldType: 'BOOLEAN', label: 'Active' })} value={true} mode="view" />, { wrapper: MemoryRouter })
    expect(screen.getByText('Yes')).toBeInTheDocument()
  })

  it('renders BOOLEAN false as No', () => {
    render(<FieldRenderer field={field({ fieldType: 'BOOLEAN', label: 'Active' })} value={false} mode="view" />, { wrapper: MemoryRouter })
    expect(screen.getByText('No')).toBeInTheDocument()
  })

  it('renders null value as em-dash', () => {
    render(<FieldRenderer field={field({ fieldType: 'TEXT', label: 'Name' })} value={null} mode="view" />, { wrapper: MemoryRouter })
    expect(screen.getByText('—')).toBeInTheDocument()
  })

  it('renders EMAIL as link', () => {
    render(<FieldRenderer field={field({ fieldType: 'EMAIL', label: 'Email' })} value="test@example.com" mode="view" />, { wrapper: MemoryRouter })
    expect(screen.getByRole('link')).toHaveAttribute('href', 'mailto:test@example.com')
  })

  it('renders URL as link', () => {
    render(<FieldRenderer field={field({ fieldType: 'URL', label: 'Website' })} value="https://example.com" mode="view" />, { wrapper: MemoryRouter })
    expect(screen.getByRole('link')).toHaveAttribute('href', 'https://example.com')
  })

  it('renders PICKLIST value as text', () => {
    render(<FieldRenderer field={field({ fieldType: 'PICKLIST', label: 'Industry', config: { options: ['Tech', 'Finance'] } })} value="Tech" mode="view" />, { wrapper: MemoryRouter })
    expect(screen.getByText('Tech')).toBeInTheDocument()
  })
})

describe('FieldRenderer — edit mode', () => {
  it('renders TEXT as TextInput', () => {
    render(<FieldRenderer field={field({ fieldType: 'TEXT', label: 'Name' })} value="ACME" mode="edit" />, { wrapper: MemoryRouter })
    expect(screen.getByLabelText('Name')).toBeInTheDocument()
    expect(screen.getByRole('textbox')).toHaveValue('ACME')
  })

  it('renders NUMBER as number input', () => {
    render(<FieldRenderer field={field({ fieldType: 'NUMBER', label: 'Amount' })} value={42} mode="edit" />, { wrapper: MemoryRouter })
    expect(screen.getByLabelText('Amount')).toBeInTheDocument()
    expect(screen.getByRole('spinbutton')).toHaveValue(42)
  })

  it('renders BOOLEAN as checkbox', () => {
    render(<FieldRenderer field={field({ fieldType: 'BOOLEAN', label: 'Active' })} value={true} mode="edit" />, { wrapper: MemoryRouter })
    expect(screen.getByRole('checkbox')).toBeChecked()
  })

  it('renders PICKLIST as Select', () => {
    render(
      <FieldRenderer
        field={field({ fieldType: 'PICKLIST', label: 'Industry', config: { options: ['Tech', 'Finance'] } })}
        value="Tech" mode="edit"
      />,
      { wrapper: MemoryRouter }
    )
    expect(screen.getByRole('combobox')).toBeInTheDocument()
  })

  it('renders DATE as date input', () => {
    render(<FieldRenderer field={field({ fieldType: 'DATE', label: 'Start Date' })} value="2024-01-01" mode="edit" />, { wrapper: MemoryRouter })
    expect(screen.getByLabelText('Start Date')).toHaveAttribute('type', 'date')
  })

  it('calls onChange when value changes', async () => {
    const onChange = vi.fn()
    render(<FieldRenderer field={field({ fieldType: 'BOOLEAN', label: 'Active' })} value={false} mode="edit" onChange={onChange} />, { wrapper: MemoryRouter })
    await screen.getByRole('checkbox').click()
    expect(onChange).toHaveBeenCalledWith(true)
  })

  it('passes error to input component', () => {
    render(<FieldRenderer field={field({ fieldType: 'TEXT', label: 'Name' })} value="" mode="edit" error="Required" />, { wrapper: MemoryRouter })
    expect(screen.getByText('Required')).toBeInTheDocument()
  })

  it('marks required field with * indicator', () => {
    render(<FieldRenderer field={field({ fieldType: 'TEXT', label: 'Name', isRequired: true })} value="" mode="edit" />, { wrapper: MemoryRouter })
    expect(screen.getByText('*')).toBeInTheDocument()
  })
})

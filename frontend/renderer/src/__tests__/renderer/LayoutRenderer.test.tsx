import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { LayoutRenderer } from '@/renderer/LayoutRenderer'
import type { FieldDefinition, LayoutDefinition } from '@/types/metadata'

const nameField: FieldDefinition = {
  id: 'f1', tenantId: 't1', objectId: 'o1', apiName: 'name', label: 'Name',
  fieldType: 'TEXT', storageKind: 'COLUMN', storageKey: 'name',
  isRequired: true, isCustom: false, config: null, createdAt: '', updatedAt: '',
}
const industryField: FieldDefinition = {
  id: 'f2', tenantId: 't1', objectId: 'o1', apiName: 'industry__c', label: 'Industry',
  fieldType: 'PICKLIST', storageKind: 'JSONB', storageKey: 'industry__c',
  isRequired: false, isCustom: true, config: { options: ['Tech', 'Finance'] }, createdAt: '', updatedAt: '',
}

const layout: LayoutDefinition = {
  sections: [
    {
      label: 'Basic Info',
      columns: 2,
      fields: [
        { fieldApiName: 'name', required: true },
        { fieldApiName: 'industry__c' },
      ],
    },
  ],
}

const fields = [nameField, industryField]
const record: Record<string, unknown> = { name: 'ACME', industry__c: 'Tech' }

describe('LayoutRenderer — view mode', () => {
  it('renders section title', () => {
    render(
      <LayoutRenderer layout={layout} fields={fields} record={record} mode="view" />,
      { wrapper: MemoryRouter }
    )
    expect(screen.getByText('Basic Info')).toBeInTheDocument()
  })

  it('renders field labels and values', () => {
    render(
      <LayoutRenderer layout={layout} fields={fields} record={record} mode="view" />,
      { wrapper: MemoryRouter }
    )
    expect(screen.getByText('Name')).toBeInTheDocument()
    expect(screen.getByText('ACME')).toBeInTheDocument()
    expect(screen.getByText('Industry')).toBeInTheDocument()
    expect(screen.getByText('Tech')).toBeInTheDocument()
  })

  it('skips fields not present in field map', () => {
    const sparseLayout: LayoutDefinition = {
      sections: [{ label: 'S', columns: 1, fields: [{ fieldApiName: 'unknown_field' }] }],
    }
    render(
      <LayoutRenderer layout={sparseLayout} fields={fields} record={record} mode="view" />,
      { wrapper: MemoryRouter }
    )
    // should not crash; just renders empty section
    expect(screen.queryByText('unknown_field')).not.toBeInTheDocument()
  })
})

describe('LayoutRenderer — edit mode', () => {
  it('renders form inputs', () => {
    render(
      <LayoutRenderer layout={layout} fields={fields} record={record} mode="edit" />,
      { wrapper: MemoryRouter }
    )
    // getByRole resolves accessible name correctly even when label contains aria-hidden spans
    expect(screen.getByRole('textbox', { name: /Name/i })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: /Industry/i })).toBeInTheDocument()
  })

  it('calls onSubmit with updated record when form submitted', async () => {
    const onSubmit = vi.fn()
    render(
      <LayoutRenderer layout={layout} fields={fields} record={record} mode="edit" onSubmit={onSubmit} />,
      { wrapper: MemoryRouter }
    )
    await userEvent.click(screen.getByRole('button', { name: /save/i }))
    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ name: 'ACME' }))
  })

  it('calls onCancel when cancel button clicked', async () => {
    const onCancel = vi.fn()
    render(
      <LayoutRenderer layout={layout} fields={fields} record={record} mode="edit" onCancel={onCancel} />,
      { wrapper: MemoryRouter }
    )
    await userEvent.click(screen.getByRole('button', { name: /cancel/i }))
    expect(onCancel).toHaveBeenCalled()
  })

  it('shows validation errors for required fields left empty', async () => {
    const emptyRecord = { name: '', industry__c: '' }
    const onSubmit = vi.fn()
    render(
      <LayoutRenderer layout={layout} fields={fields} record={emptyRecord} mode="edit" onSubmit={onSubmit} />,
      { wrapper: MemoryRouter }
    )
    await userEvent.click(screen.getByRole('button', { name: /save/i }))
    // Zod's min(1) message: "String must contain at least 1 character(s)"
    expect(screen.getByText(/at least|character|required/i)).toBeInTheDocument()
    expect(onSubmit).not.toHaveBeenCalled()
  })
})

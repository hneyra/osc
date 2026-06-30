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

describe('LayoutRenderer — Record Types and Layout Assignments (ADR-006)', () => {
  const rtDefault = {
    id: 'rt-default-id',
    tenantId: 't1',
    objectApiName: 'Account',
    apiName: 'Default',
    label: 'Default Type',
    isDefault: true,
    isActive: true,
  }

  const rtPremium = {
    id: 'rt-premium-id',
    tenantId: 't1',
    objectApiName: 'Account',
    apiName: 'Premium',
    label: 'Premium Type',
    isDefault: false,
    isActive: true,
  }

  const rtInactive = {
    id: 'rt-inactive-id',
    tenantId: 't1',
    objectApiName: 'Account',
    apiName: 'Inactive',
    label: 'Inactive Type',
    isDefault: false,
    isActive: false,
  }

  const layoutDefault: LayoutDefinition = {
    sections: [
      {
        label: 'Default Layout Section',
        columns: 1,
        fields: [{ fieldApiName: 'name', required: true }],
      },
    ],
  }

  const layoutPremium: LayoutDefinition = {
    sections: [
      {
        label: 'Premium Layout Section',
        columns: 1,
        fields: [
          { fieldApiName: 'name', required: true },
          { fieldApiName: 'industry__c' },
        ],
      },
    ],
  }

  const layoutsMap = {
    'layout-default-id': layoutDefault,
    'layout-premium-id': layoutPremium,
  }

  const assignments = [
    {
      id: 'la1',
      tenantId: 't1',
      layoutId: 'layout-premium-id',
      recordTypeId: 'rt-premium-id',
      permissionSetId: null,
    },
    {
      id: 'la2',
      tenantId: 't1',
      layoutId: 'layout-default-id',
      recordTypeId: 'rt-default-id',
      permissionSetId: null,
    },
  ]

  const recordTypes = [rtDefault, rtPremium, rtInactive]

  it('renders record type picker during record creation when multiple active record types exist', async () => {
    render(
      <LayoutRenderer
        layout={layoutDefault}
        fields={fields}
        record={{}} // no id (creating)
        mode="edit"
        recordTypes={recordTypes}
      />,
      { wrapper: MemoryRouter }
    )

    // Should display the step title and option labels
    expect(screen.getByTestId('record-type-picker')).toBeInTheDocument()
    expect(screen.getByText('Default Type')).toBeInTheDocument()
    expect(screen.getByText('Premium Type')).toBeInTheDocument()
    // Inactive record types should be excluded
    expect(screen.queryByText('Inactive Type')).not.toBeInTheDocument()
  })

  it('navigates from picker to layout form after selecting a record type and clicking Next', async () => {
    const onSubmit = vi.fn()
    render(
      <LayoutRenderer
        layout={layoutDefault}
        fields={fields}
        record={{}}
        mode="edit"
        layouts={layoutsMap}
        layoutAssignments={assignments}
        recordTypes={recordTypes}
        onSubmit={onSubmit}
      />,
      { wrapper: MemoryRouter }
    )

    // Select premium type
    const option = screen.getByTestId('record-type-option-Premium')
    await userEvent.click(option)

    // Click Next
    await userEvent.click(screen.getByTestId('record-type-next'))

    // Should now render the Premium layout form (which has Premium Layout Section label)
    expect(screen.queryByTestId('record-type-picker')).not.toBeInTheDocument()
    expect(screen.getByText('Premium Layout Section')).toBeInTheDocument()

    // Fill the required field 'name' and save
    const nameInput = screen.getByRole('textbox', { name: /Name/i })
    await userEvent.type(nameInput, 'New ACME Premium')
    await userEvent.click(screen.getByRole('button', { name: /save/i }))

    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({
        name: 'New ACME Premium',
        recordTypeId: 'rt-premium-id',
        record_type_id: 'rt-premium-id',
      })
    )
  })

  it('calls onCancel if clicked in record type picker stage', async () => {
    const onCancel = vi.fn()
    render(
      <LayoutRenderer
        layout={layoutDefault}
        fields={fields}
        record={{}}
        mode="edit"
        recordTypes={recordTypes}
        onCancel={onCancel}
      />,
      { wrapper: MemoryRouter }
    )

    await userEvent.click(screen.getByTestId('record-type-cancel'))
    expect(onCancel).toHaveBeenCalled()
  })

  it('skips picker if a single record type is active', () => {
    const singleRt = [rtDefault]
    render(
      <LayoutRenderer
        layout={layoutDefault}
        fields={fields}
        record={{}}
        mode="edit"
        recordTypes={singleRt}
      />,
      { wrapper: MemoryRouter }
    )

    expect(screen.queryByTestId('record-type-picker')).not.toBeInTheDocument()
    expect(screen.getByText('Default Layout Section')).toBeInTheDocument()
  })

  it('skips picker if recordTypeId is pre-specified on the record', () => {
    render(
      <LayoutRenderer
        layout={layoutDefault}
        fields={fields}
        record={{ recordTypeId: 'rt-premium-id' }}
        mode="edit"
        layouts={layoutsMap}
        layoutAssignments={assignments}
        recordTypes={recordTypes}
      />,
      { wrapper: MemoryRouter }
    )

    expect(screen.queryByTestId('record-type-picker')).not.toBeInTheDocument()
    expect(screen.getByText('Premium Layout Section')).toBeInTheDocument()
  })

  it('resolves correct layout with most-specific-wins including userPermissionSetIds', () => {
    const adminAssignment = {
      id: 'la3',
      tenantId: 't1',
      layoutId: 'layout-premium-id',
      recordTypeId: 'rt-default-id',
      permissionSetId: 'admin-ps-id',
    }
    const rtDefaultAssignment = {
      id: 'la4',
      tenantId: 't1',
      layoutId: 'layout-default-id',
      recordTypeId: 'rt-default-id',
      permissionSetId: null,
    }

    render(
      <LayoutRenderer
        layout={layoutDefault}
        fields={fields}
        record={{ recordTypeId: 'rt-default-id' }}
        mode="view"
        layouts={layoutsMap}
        layoutAssignments={[adminAssignment, rtDefaultAssignment]}
        recordTypes={recordTypes}
        userPermissionSetIds={['admin-ps-id']}
      />,
      { wrapper: MemoryRouter }
    )

    // Should resolve layoutPremium because user has admin permission set, matching (recordTypeId, permissionSetId) over (recordTypeId, null)
    expect(screen.getByText('Premium Layout Section')).toBeInTheDocument()
  })
})


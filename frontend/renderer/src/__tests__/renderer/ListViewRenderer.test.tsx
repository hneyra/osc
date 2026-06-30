import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { ListViewRenderer } from '@/renderer/ListViewRenderer'
import type { FieldDefinition, ListViewDefinition } from '@/types/metadata'
import type { RecordResponse } from '@/types/records'

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

const listView: ListViewDefinition = {
  columns: [
    { fieldApiName: 'name', label: 'Name', sortable: true },
    { fieldApiName: 'industry__c', label: 'Industry', sortable: true },
  ],
  defaultSortField: 'name',
  defaultSortOrder: 'ASC',
  defaultLimit: 25,
}

const response: RecordResponse = {
  data: [
    { id: 'r1', name: 'ACME', industry__c: 'Tech' },
    { id: 'r2', name: 'Beta Corp', industry__c: 'Finance' },
  ],
  totalCount: 2, limit: 25, offset: 0, objectApiName: 'Account',
}

const fields = [nameField, industryField]

describe('ListViewRenderer', () => {
  it('renders column headers', () => {
    render(
      <ListViewRenderer listView={listView} fields={fields} response={response} objectApiName="Account" />,
      { wrapper: MemoryRouter }
    )
    expect(screen.getByRole('columnheader', { name: 'Name' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Industry' })).toBeInTheDocument()
  })

  it('renders a row per record', () => {
    render(
      <ListViewRenderer listView={listView} fields={fields} response={response} objectApiName="Account" />,
      { wrapper: MemoryRouter }
    )
    const rows = screen.getAllByRole('row')
    // header row + 2 data rows
    expect(rows).toHaveLength(3)
  })

  it('renders cell values', () => {
    render(
      <ListViewRenderer listView={listView} fields={fields} response={response} objectApiName="Account" />,
      { wrapper: MemoryRouter }
    )
    expect(screen.getByText('ACME')).toBeInTheDocument()
    expect(screen.getByText('Beta Corp')).toBeInTheDocument()
    expect(screen.getByText('Tech')).toBeInTheDocument()
    expect(screen.getByText('Finance')).toBeInTheDocument()
  })

  it('shows empty state when no records', () => {
    const empty: RecordResponse = { data: [], totalCount: 0, limit: 25, offset: 0, objectApiName: 'Account' }
    render(
      <ListViewRenderer listView={listView} fields={fields} response={empty} objectApiName="Account" />,
      { wrapper: MemoryRouter }
    )
    expect(screen.getByText(/no records/i)).toBeInTheDocument()
  })

  it('calls onSort when sortable header clicked', async () => {
    const onSort = vi.fn()
    render(
      <ListViewRenderer listView={listView} fields={fields} response={response} objectApiName="Account" onSort={onSort} />,
      { wrapper: MemoryRouter }
    )
    await userEvent.click(screen.getByRole('columnheader', { name: 'Name' }))
    expect(onSort).toHaveBeenCalledWith('name', expect.any(String))
  })

  it('renders pagination when totalCount > limit', () => {
    const paginated: RecordResponse = { ...response, totalCount: 50, limit: 25, offset: 0 }
    render(
      <ListViewRenderer listView={listView} fields={fields} response={paginated} objectApiName="Account" />,
      { wrapper: MemoryRouter }
    )
    expect(screen.getByLabelText('Pagination')).toBeInTheDocument()
  })

  it('does not render pagination when all records fit on one page', () => {
    render(
      <ListViewRenderer listView={listView} fields={fields} response={response} objectApiName="Account" />,
      { wrapper: MemoryRouter }
    )
    expect(screen.queryByLabelText('Pagination')).not.toBeInTheDocument()
  })

  it('shows total count label', () => {
    render(
      <ListViewRenderer listView={listView} fields={fields} response={response} objectApiName="Account" />,
      { wrapper: MemoryRouter }
    )
    expect(screen.getByText(/2/)).toBeInTheDocument()
  })

  it('row click calls onRowClick with record', async () => {
    const onRowClick = vi.fn()
    render(
      <ListViewRenderer listView={listView} fields={fields} response={response} objectApiName="Account" onRowClick={onRowClick} />,
      { wrapper: MemoryRouter }
    )
    const rows = screen.getAllByRole('row').slice(1) // skip header
    await userEvent.click(rows[0])
    expect(onRowClick).toHaveBeenCalledWith(expect.objectContaining({ id: 'r1' }))
  })

  it('translates record_type_id to a styled badge with the record type label', () => {
    const rtPremium = {
      id: 'rt-premium-id',
      tenantId: 't1',
      objectApiName: 'Account',
      apiName: 'Premium',
      label: 'Premium Account',
      isDefault: false,
      isActive: true,
    }
    const listViewWithRt: ListViewDefinition = {
      columns: [
        { fieldApiName: 'name', label: 'Name', sortable: true },
        { fieldApiName: 'recordTypeId', label: 'Record Type', sortable: true },
      ],
      defaultSortField: 'name',
      defaultSortOrder: 'ASC',
      defaultLimit: 25,
    }
    const responseWithRt: RecordResponse = {
      data: [
        { id: 'r1', name: 'ACME', recordTypeId: 'rt-premium-id' },
      ],
      totalCount: 1, limit: 25, offset: 0, objectApiName: 'Account',
    }

    render(
      <ListViewRenderer
        listView={listViewWithRt}
        fields={fields}
        response={responseWithRt}
        objectApiName="Account"
        recordTypes={[rtPremium]}
      />,
      { wrapper: MemoryRouter }
    )

    // Should render the label "Premium Account" as a badge instead of raw UUID
    expect(screen.getByTestId('record-type-badge-Premium')).toBeInTheDocument()
    expect(screen.getByText('Premium Account')).toBeInTheDocument()
    expect(screen.queryByText('rt-premium-id')).not.toBeInTheDocument()
  })

  it('resolves list columns dynamically from default layout assignment when columns are empty', () => {
    const layoutDefault = {
      sections: [
        {
          label: 'Default Layout',
          columns: 1,
          fields: [{ fieldApiName: 'name' }, { fieldApiName: 'industry__c' }],
        },
      ],
    }
    const layoutsMap = {
      'layout-default-id': layoutDefault,
    }
    const assignments = [
      {
        id: 'la1',
        tenantId: 't1',
        layoutId: 'layout-default-id',
        recordTypeId: null,
        permissionSetId: null,
      },
    ]

    // empty columns list view
    const emptyColumnsListView: ListViewDefinition = {
      columns: [],
      defaultLimit: 25,
    }

    render(
      <ListViewRenderer
        listView={emptyColumnsListView}
        fields={fields}
        response={response}
        objectApiName="Account"
        layouts={layoutsMap}
        layoutAssignments={assignments}
      />,
      { wrapper: MemoryRouter }
    )

    // Column headers should be resolved from the default layout (Name and Industry)
    expect(screen.getByRole('columnheader', { name: 'Name' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Industry' })).toBeInTheDocument()
    // It should also render cell values for those resolved columns
    expect(screen.getByText('ACME')).toBeInTheDocument()
    expect(screen.getByText('Tech')).toBeInTheDocument()
  })
})


import React, { useState } from 'react'
import type {
  FieldDefinition,
  ListViewDefinition,
  LayoutDefinition,
  LayoutAssignmentDefinition,
  RecordTypeDefinition,
} from '@/types/metadata'
import type { RecordResponse } from '@/types/records'
import { EmptyState } from '@/components/feedback'
import { Pagination } from '@/components/navigation'
import { resolveLayoutAssignment } from './LayoutRenderer'

interface ListViewRendererProps {
  listView: ListViewDefinition
  fields: FieldDefinition[]
  response: RecordResponse
  objectApiName: string
  onSort?: (fieldApiName: string, direction: 'ASC' | 'DESC') => void
  onPageChange?: (offset: number) => void
  onRowClick?: (record: Record<string, unknown>) => void

  // Optional props for record-type and layout assignment support (ADR-006)
  layouts?: Record<string, LayoutDefinition>
  layoutAssignments?: LayoutAssignmentDefinition[]
  recordTypes?: RecordTypeDefinition[]
  userPermissionSetIds?: string[]
}

export function ListViewRenderer({
  listView,
  fields,
  response,
  objectApiName,
  onSort,
  onPageChange,
  onRowClick,
  layouts,
  layoutAssignments,
  recordTypes,
  userPermissionSetIds,
}: ListViewRendererProps) {
  const fieldMap = new Map(fields.map((f) => [f.apiName, f]))
  const [sortField, setSortField] = useState(listView.defaultSortField ?? '')
  const [sortDir, setSortDir] = useState<'ASC' | 'DESC'>(listView.defaultSortOrder ?? 'ASC')

  // Resolve columns: use listView.columns or fall back to resolved layout assignment
  let resolvedColumns = listView.columns || []
  if (resolvedColumns.length === 0 && layouts && layoutAssignments) {
    // Resolve layout based on null recordTypeId (default) as lists are multi-record
    const resolvedLayoutId = resolveLayoutAssignment(
      layoutAssignments,
      null,
      userPermissionSetIds || []
    )
    const resolvedLayout = resolvedLayoutId ? layouts[resolvedLayoutId] : null
    if (resolvedLayout) {
      const layoutFields: { fieldApiName: string; label: string; sortable: boolean }[] = []
      resolvedLayout.sections.forEach((section) => {
        section.fields.forEach((lf) => {
          const fDef = fieldMap.get(lf.fieldApiName)
          if (fDef) {
            layoutFields.push({
              fieldApiName: lf.fieldApiName,
              label: fDef.label,
              sortable: true,
            })
          }
        })
      })
      if (layoutFields.length > 0) {
        resolvedColumns = layoutFields
      }
    }
  }

  // If columns are still empty, fall back to showing all fields
  if (resolvedColumns.length === 0) {
    resolvedColumns = fields.map((f) => ({
      fieldApiName: f.apiName,
      label: f.label,
      sortable: true,
    }))
  }

  const handleSort = (apiName: string) => {
    const newDir = sortField === apiName && sortDir === 'ASC' ? 'DESC' : 'ASC'
    setSortField(apiName)
    setSortDir(newDir)
    onSort?.(apiName, newDir)
  }

  const renderCell = (record: Record<string, unknown>, apiName: string) => {
    const fieldDef = fieldMap.get(apiName)
    const value = record[apiName]

    // Translating record_type_id/recordTypeId to a human-readable badge
    if ((apiName === 'record_type_id' || apiName === 'recordTypeId') && recordTypes) {
      const rt = recordTypes.find((t) => t.id === value)
      if (rt) {
        return (
          <td key={apiName} className="osc-table__cell">
            <span
              className="osc-badge osc-badge--record-type"
              data-testid={`record-type-badge-${rt.apiName}`}
              style={{
                background: 'rgba(59, 130, 246, 0.15)',
                color: '#60a5fa',
                padding: '0.25rem 0.6rem',
                borderRadius: '9999px',
                fontSize: '0.75rem',
                fontWeight: 600,
                border: '1px solid rgba(59, 130, 246, 0.3)',
              }}
            >
              {rt.label}
            </span>
          </td>
        )
      }
    }

    if (!fieldDef) {
      return (
        <td key={apiName} className="osc-table__cell">
          {String(value ?? '')}
        </td>
      )
    }

    let display: React.ReactNode = String(value ?? '—')
    if (value === null || value === undefined || value === '') {
      display = <span className="osc-empty-cell">—</span>
    } else if (fieldDef.fieldType === 'BOOLEAN') {
      display = value ? 'Yes' : 'No'
    }

    return (
      <td key={apiName} className="osc-table__cell">
        {display}
      </td>
    )
  }

  if (response.data.length === 0) {
    return (
      <div className="osc-list-view">
        <EmptyState
          title="No records found"
          description={`No ${objectApiName} records match your criteria.`}
        />
      </div>
    )
  }

  return (
    <div className="osc-list-view">
      <div className="osc-list-view__meta">
        <span className="osc-list-view__count">
          {response.totalCount} record{response.totalCount !== 1 ? 's' : ''}
        </span>
      </div>
      <div className="osc-table-wrapper">
        <table className="osc-table" role="table">
          <thead>
            <tr>
              {resolvedColumns.map((col) => (
                <th
                  key={col.fieldApiName}
                  role="columnheader"
                  className={`osc-table__th ${col.sortable ? 'osc-table__th--sortable' : ''} ${sortField === col.fieldApiName ? `osc-table__th--sorted-${sortDir.toLowerCase()}` : ''}`}
                  onClick={col.sortable ? () => handleSort(col.fieldApiName) : undefined}
                  aria-sort={
                    sortField === col.fieldApiName
                      ? sortDir === 'ASC'
                        ? 'ascending'
                        : 'descending'
                      : col.sortable
                        ? 'none'
                        : undefined
                  }
                >
                  {col.label}
                  {col.sortable && (
                    <span className="osc-table__sort-icon" aria-hidden="true">
                      {sortField === col.fieldApiName ? (sortDir === 'ASC' ? ' ▲' : ' ▼') : ' ↕'}
                    </span>
                  )}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {response.data.map((record) => (
              <tr
                key={String(record.id ?? Math.random())}
                className={`osc-table__row ${onRowClick ? 'osc-table__row--clickable' : ''}`}
                onClick={onRowClick ? () => onRowClick(record) : undefined}
              >
                {resolvedColumns.map((col) => renderCell(record, col.fieldApiName))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <Pagination
        totalCount={response.totalCount}
        limit={response.limit}
        offset={response.offset}
        onPageChange={onPageChange ?? (() => {})}
      />
    </div>
  )
}


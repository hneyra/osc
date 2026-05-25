import React, { useState } from 'react'
import type { FieldDefinition, ListViewDefinition } from '@/types/metadata'
import type { RecordResponse } from '@/types/records'
import { EmptyState } from '@/components/feedback'
import { Pagination } from '@/components/navigation'

interface ListViewRendererProps {
  listView: ListViewDefinition
  fields: FieldDefinition[]
  response: RecordResponse
  objectApiName: string
  onSort?: (fieldApiName: string, direction: 'ASC' | 'DESC') => void
  onPageChange?: (offset: number) => void
  onRowClick?: (record: Record<string, unknown>) => void
}

export function ListViewRenderer({
  listView, fields, response, objectApiName,
  onSort, onPageChange, onRowClick,
}: ListViewRendererProps) {
  const fieldMap = new Map(fields.map((f) => [f.apiName, f]))
  const [sortField, setSortField] = useState(listView.defaultSortField ?? '')
  const [sortDir, setSortDir] = useState<'ASC' | 'DESC'>(listView.defaultSortOrder ?? 'ASC')

  const handleSort = (apiName: string) => {
    const newDir = sortField === apiName && sortDir === 'ASC' ? 'DESC' : 'ASC'
    setSortField(apiName)
    setSortDir(newDir)
    onSort?.(apiName, newDir)
  }

  const renderCell = (record: Record<string, unknown>, apiName: string) => {
    const fieldDef = fieldMap.get(apiName)
    const value = record[apiName]
    if (!fieldDef) return <td key={apiName} className="osc-table__cell">{String(value ?? '')}</td>

    let display: React.ReactNode = String(value ?? '—')
    if (value === null || value === undefined || value === '') {
      display = <span className="osc-empty-cell">—</span>
    } else if (fieldDef.fieldType === 'BOOLEAN') {
      display = value ? 'Yes' : 'No'
    }

    return <td key={apiName} className="osc-table__cell">{display}</td>
  }

  if (response.data.length === 0) {
    return (
      <div className="osc-list-view">
        <EmptyState title="No records found" description={`No ${objectApiName} records match your criteria.`} />
      </div>
    )
  }

  return (
    <div className="osc-list-view">
      <div className="osc-list-view__meta">
        <span className="osc-list-view__count">{response.totalCount} record{response.totalCount !== 1 ? 's' : ''}</span>
      </div>
      <div className="osc-table-wrapper">
        <table className="osc-table" role="table">
          <thead>
            <tr>
              {listView.columns.map((col) => (
                <th
                  key={col.fieldApiName}
                  role="columnheader"
                  className={`osc-table__th ${col.sortable ? 'osc-table__th--sortable' : ''} ${sortField === col.fieldApiName ? `osc-table__th--sorted-${sortDir.toLowerCase()}` : ''}`}
                  onClick={col.sortable ? () => handleSort(col.fieldApiName) : undefined}
                  aria-sort={
                    sortField === col.fieldApiName
                      ? sortDir === 'ASC' ? 'ascending' : 'descending'
                      : col.sortable ? 'none' : undefined
                  }
                >
                  {col.label}
                  {col.sortable && <span className="osc-table__sort-icon" aria-hidden="true">
                    {sortField === col.fieldApiName ? (sortDir === 'ASC' ? ' ▲' : ' ▼') : ' ↕'}
                  </span>}
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
                {listView.columns.map((col) => renderCell(record, col.fieldApiName))}
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

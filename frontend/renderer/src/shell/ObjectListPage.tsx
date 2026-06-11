import { useState } from 'react'
import { useParams } from 'react-router-dom'
import { useFieldDefinitions, useRecordList } from '@/api/hooks'
import { ListViewRenderer } from '@/renderer/ListViewRenderer'
import { Spinner, Alert } from '@/components/feedback'
import { PageHeader } from '@/components/layout'
import { Button } from '@/components/navigation'
import type { ListViewDefinition } from '@/types/metadata'

export function ObjectListPage() {
  const { objectApiName = 'Account' } = useParams<{ objectApiName: string }>()
  const [offset, setOffset] = useState(0)
  const [sortField, setSortField] = useState<string>()
  const [sortDir, setSortDir] = useState<'ASC' | 'DESC'>('ASC')

  const { data: fields, isLoading: fieldsLoading } = useFieldDefinitions(objectApiName)
  const { data: response, isLoading: recordsLoading, isError, error } = useRecordList(objectApiName, { offset, limit: 25 })

  if (fieldsLoading || recordsLoading) {
    return <Spinner label={`Loading ${objectApiName}…`} />
  }

  if (isError) {
    return <Alert variant="error">{(error as Error)?.message ?? 'Failed to load records'}</Alert>
  }

  const listView: ListViewDefinition = {
    columns: (fields ?? []).map((f) => ({ fieldApiName: f.apiName, label: f.label, sortable: true })),
    defaultSortField: sortField,
    defaultSortOrder: sortDir,
    defaultLimit: 25,
  }

  return (
    <div className="osc-object-list-page">
      <PageHeader
        title={objectApiName}
        actions={<Button variant="primary">New</Button>}
      />
      {response && fields && (
        <ListViewRenderer
          listView={listView}
          fields={fields}
          response={response}
          objectApiName={objectApiName}
          onSort={(field, dir) => { setSortField(field); setSortDir(dir) }}
          onPageChange={setOffset}
        />
      )}
    </div>
  )
}

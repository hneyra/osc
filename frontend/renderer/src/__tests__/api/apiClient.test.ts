import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { apiClient } from '@/api/apiClient'
import type { ObjectDefinition, FieldDefinition } from '@/types/metadata'
import type { RecordResponse } from '@/types/records'

const mockObject: ObjectDefinition = {
  id: 'obj-1', tenantId: 't-1', apiName: 'Account',
  label: 'Account', labelPlural: 'Accounts', isCustom: false,
  createdAt: '2024-01-01T00:00:00Z', updatedAt: '2024-01-01T00:00:00Z',
}

const mockField: FieldDefinition = {
  id: 'fld-1', tenantId: 't-1', objectId: 'obj-1', apiName: 'name',
  label: 'Name', fieldType: 'TEXT', storageKind: 'COLUMN', storageKey: 'name',
  isRequired: true, isCustom: false, config: null,
  createdAt: '2024-01-01T00:00:00Z', updatedAt: '2024-01-01T00:00:00Z',
}

const mockResponse: RecordResponse = {
  data: [{ id: 'rec-1', name: 'ACME' }],
  totalCount: 1, limit: 50, offset: 0, objectApiName: 'Account',
}

const server = setupServer(
  http.get('/api/v1/metadata/objects', () => HttpResponse.json([mockObject])),
  http.get('/api/v1/metadata/Account/fields', () => HttpResponse.json([mockField])),
  http.get('/api/v1/data/Account', () => HttpResponse.json(mockResponse)),
  http.get('/api/v1/data/Account/rec-1', () => HttpResponse.json({ id: 'rec-1', name: 'ACME' })),
  http.post('/api/v1/data/Account', () => HttpResponse.json({ id: 'rec-2', name: 'Beta' }, { status: 201 })),
  http.patch('/api/v1/data/Account/rec-1', () => HttpResponse.json({ id: 'rec-1', name: 'ACME Corp' })),
  http.delete('/api/v1/data/Account/rec-1', () => new HttpResponse(null, { status: 204 })),
)

beforeAll(() => server.listen())
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

describe('apiClient', () => {
  it('listObjects returns object definitions', async () => {
    const result = await apiClient.listObjects()
    expect(result).toHaveLength(1)
    expect(result[0].apiName).toBe('Account')
  })

  it('getFields returns field definitions for an object', async () => {
    const result = await apiClient.getFields('Account')
    expect(result).toHaveLength(1)
    expect(result[0].apiName).toBe('name')
  })

  it('listRecords returns RecordResponse envelope', async () => {
    const result = await apiClient.listRecords('Account')
    expect(result.totalCount).toBe(1)
    expect(result.data[0]).toMatchObject({ name: 'ACME' })
  })

  it('getRecord returns a single record', async () => {
    const result = await apiClient.getRecord('Account', 'rec-1')
    expect(result).toMatchObject({ id: 'rec-1', name: 'ACME' })
  })

  it('createRecord sends POST and returns created record', async () => {
    const result = await apiClient.createRecord('Account', { name: 'Beta' })
    expect(result).toMatchObject({ id: 'rec-2', name: 'Beta' })
  })

  it('updateRecord sends PATCH and returns updated record', async () => {
    const result = await apiClient.updateRecord('Account', 'rec-1', { name: 'ACME Corp' })
    expect(result).toMatchObject({ name: 'ACME Corp' })
  })

  it('deleteRecord sends DELETE', async () => {
    await expect(apiClient.deleteRecord('Account', 'rec-1')).resolves.toBeUndefined()
  })

  it('on HTTP error throws with status', async () => {
    server.use(http.get('/api/v1/data/Account', () => HttpResponse.json({ message: 'Not found' }, { status: 404 })))
    await expect(apiClient.listRecords('Account')).rejects.toThrow('404')
  })
})

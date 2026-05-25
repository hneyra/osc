import type { FieldDefinition, ObjectDefinition } from '@/types/metadata'
import type { PageParams, RecordResponse } from '@/types/records'

const BASE = '/api/v1'

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const res = await fetch(url, {
    headers: { 'Content-Type': 'application/json', ...options?.headers },
    ...options,
  })
  if (!res.ok) {
    throw new Error(`${res.status}: ${res.statusText}`)
  }
  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

export const apiClient = {
  listObjects(): Promise<ObjectDefinition[]> {
    return request(`${BASE}/metadata/objects`)
  },

  getFields(objectApiName: string): Promise<FieldDefinition[]> {
    return request(`${BASE}/metadata/${objectApiName}/fields`)
  },

  listRecords(objectApiName: string, params?: PageParams): Promise<RecordResponse> {
    const qs = new URLSearchParams()
    if (params?.limit != null) qs.set('limit', String(params.limit))
    if (params?.offset != null) qs.set('offset', String(params.offset))
    const query = qs.toString() ? `?${qs}` : ''
    return request(`${BASE}/data/${objectApiName}${query}`)
  },

  getRecord(objectApiName: string, id: string): Promise<Record<string, unknown>> {
    return request(`${BASE}/data/${objectApiName}/${id}`)
  },

  createRecord(objectApiName: string, data: Record<string, unknown>): Promise<Record<string, unknown>> {
    return request(`${BASE}/data/${objectApiName}`, {
      method: 'POST',
      body: JSON.stringify(data),
    })
  },

  updateRecord(objectApiName: string, id: string, patch: Record<string, unknown>): Promise<Record<string, unknown>> {
    return request(`${BASE}/data/${objectApiName}/${id}`, {
      method: 'PATCH',
      body: JSON.stringify(patch),
    })
  },

  deleteRecord(objectApiName: string, id: string): Promise<void> {
    return request(`${BASE}/data/${objectApiName}/${id}`, { method: 'DELETE' })
  },

  queryRecords(objectApiName: string, soql: string): Promise<RecordResponse> {
    return request(`${BASE}/data/${objectApiName}/query`, {
      method: 'POST',
      body: JSON.stringify({ query: soql }),
    })
  },
}

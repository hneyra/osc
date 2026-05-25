import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { apiClient } from './apiClient'
import { queryKeys } from './queryKeys'
import type { PageParams } from '@/types/records'

export function useObjectDefinitions() {
  return useQuery({
    queryKey: queryKeys.objects(),
    queryFn: () => apiClient.listObjects(),
    staleTime: 5 * 60 * 1000,
  })
}

export function useFieldDefinitions(objectApiName: string) {
  return useQuery({
    queryKey: queryKeys.fields(objectApiName),
    queryFn: () => apiClient.getFields(objectApiName),
    staleTime: 5 * 60 * 1000,
  })
}

export function useRecordList(objectApiName: string, params?: PageParams) {
  return useQuery({
    queryKey: queryKeys.records(objectApiName, params),
    queryFn: () => apiClient.listRecords(objectApiName, params),
  })
}

export function useRecord(objectApiName: string, id: string) {
  return useQuery({
    queryKey: queryKeys.record(objectApiName, id),
    queryFn: () => apiClient.getRecord(objectApiName, id),
    enabled: !!id,
  })
}

export function useCreateRecord(objectApiName: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (data: Record<string, unknown>) => apiClient.createRecord(objectApiName, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: queryKeys.records(objectApiName) })
    },
  })
}

export function useUpdateRecord(objectApiName: string, id: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (patch: Record<string, unknown>) => apiClient.updateRecord(objectApiName, id, patch),
    onSuccess: (updated) => {
      qc.setQueryData(queryKeys.record(objectApiName, id), updated)
      qc.invalidateQueries({ queryKey: queryKeys.records(objectApiName) })
    },
  })
}

export function useDeleteRecord(objectApiName: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => apiClient.deleteRecord(objectApiName, id),
    onSuccess: (_data, id) => {
      qc.removeQueries({ queryKey: queryKeys.record(objectApiName, id) })
      qc.invalidateQueries({ queryKey: queryKeys.records(objectApiName) })
    },
  })
}

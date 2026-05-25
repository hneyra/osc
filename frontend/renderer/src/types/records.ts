export interface RecordResponse {
  data: Record<string, unknown>[]
  totalCount: number
  limit: number
  offset: number
  objectApiName: string
}

export interface PageParams {
  limit?: number
  offset?: number
}

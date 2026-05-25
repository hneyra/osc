export const queryKeys = {
  objects: () => ['objects'] as const,
  fields: (objectApiName: string) => ['fields', objectApiName] as const,
  records: (objectApiName: string, params?: object) =>
    params ? ['records', objectApiName, params] : ['records', objectApiName],
  record: (objectApiName: string, id: string) => ['record', objectApiName, id] as const,
} as const

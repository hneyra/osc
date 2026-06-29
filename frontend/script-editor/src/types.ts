// Mirrors docs/contracts/metadata-script-schema.json (ADR-005)
export type ScriptKind = 'TRIGGER' | 'BATCH' | 'SCHEDULED' | 'INVOCABLE_ACTION'

export interface ScriptDefinition {
  id?: string
  tenantId?: string
  objectApiName: string
  kind: ScriptKind
  triggerEvent?: string | null
  invocableName?: string | null
  scheduleCron?: string | null
  source: string
  isActive: boolean
  compiledAt?: string | null
  compileErrors: string[]
  timeoutSeconds: number
  generatedByAi: boolean
}

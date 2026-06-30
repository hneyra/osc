export type FieldType =
  | 'TEXT'
  | 'TEXTAREA'
  | 'NUMBER'
  | 'DATE'
  | 'DATETIME'
  | 'BOOLEAN'
  | 'PICKLIST'
  | 'MULTIPICKLIST'
  | 'EMAIL'
  | 'PHONE'
  | 'URL'
  | 'LOOKUP'
  | 'CURRENCY'

export type StorageKind = 'COLUMN' | 'JSONB'

export interface FieldDefinition {
  id: string
  tenantId: string
  objectId: string
  apiName: string
  label: string
  fieldType: FieldType
  storageKind: StorageKind
  storageKey: string | null
  isRequired: boolean
  isCustom: boolean
  config: FieldConfig | null
  createdAt: string
  updatedAt: string
}

export interface FieldConfig {
  options?: string[]        // PICKLIST / MULTIPICKLIST
  precision?: number        // NUMBER
  scale?: number            // NUMBER
  referenceTo?: string      // LOOKUP — target objectApiName
  maxLength?: number        // TEXT / TEXTAREA
}

export interface ObjectDefinition {
  id: string
  tenantId: string
  apiName: string
  label: string
  labelPlural: string
  isCustom: boolean
  createdAt: string
  updatedAt: string
}

export interface LayoutSection {
  label: string
  columns: 1 | 2
  fields: LayoutField[]
}

export interface LayoutField {
  fieldApiName: string
  required?: boolean
  span?: 1 | 2
  readOnly?: boolean
}

export interface LayoutDefinition {
  sections: LayoutSection[]
}

export interface Layout {
  id: string
  tenantId: string
  objectId: string
  name: string
  definition: LayoutDefinition
}

export interface ListViewColumn {
  fieldApiName: string
  label: string
  sortable?: boolean
  width?: string
}

export interface ListViewDefinition {
  columns: ListViewColumn[]
  defaultSortField?: string
  defaultSortOrder?: 'ASC' | 'DESC'
  defaultLimit?: number
}

export interface ListView {
  id: string
  tenantId: string
  objectId: string
  name: string
  definition: ListViewDefinition
}

export interface RecordTypeDefinition {
  id: string
  tenantId: string
  objectApiName: string
  apiName: string
  label: string
  isDefault?: boolean
  isActive: boolean
  createdAt?: string
}

export interface LayoutAssignmentDefinition {
  id: string
  tenantId: string
  layoutId: string
  recordTypeId: string | null
  permissionSetId: string | null
  createdAt?: string
}


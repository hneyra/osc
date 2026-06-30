import React, { useState } from 'react'
import { FieldRenderer } from './FieldRenderer'
import { Section, Grid, GridItem } from '@/components/layout'
import { Button } from '@/components/navigation'
import { generateZodSchema } from '@/api/zodSchemaGenerator'
import type {
  FieldDefinition,
  LayoutDefinition,
  LayoutField,
  RecordTypeDefinition,
  LayoutAssignmentDefinition,
} from '@/types/metadata'
import './LayoutRenderer.css'

export interface LayoutRendererProps {
  layout: LayoutDefinition
  fields: FieldDefinition[]
  record: Record<string, unknown>
  mode: 'view' | 'edit'
  onSubmit?: (data: Record<string, unknown>) => void
  onCancel?: () => void

  // Optional props for record-type and layout assignment support (ADR-006)
  layouts?: Record<string, LayoutDefinition>
  layoutAssignments?: LayoutAssignmentDefinition[]
  recordTypes?: RecordTypeDefinition[]
  userPermissionSetIds?: string[]
}

/**
 * Resolves which layout to render via the metadata-engine's most-specific-wins layout assignments (ADR-006).
 */
export function resolveLayoutAssignment(
  assignments: LayoutAssignmentDefinition[],
  recordTypeId: string | null | undefined,
  userPermissionSetIds: string[]
): string | null {
  if (!assignments || assignments.length === 0) return null

  const rtId = recordTypeId || null

  // 1. (recordTypeId, permissionSetId) where permissionSetId is in userPermissionSetIds
  if (rtId) {
    for (const psId of userPermissionSetIds) {
      const match = assignments.find(
        (a) => a.recordTypeId === rtId && a.permissionSetId === psId
      )
      if (match) return match.layoutId
    }
  }

  // 2. (recordTypeId, null)
  if (rtId) {
    const match = assignments.find(
      (a) => a.recordTypeId === rtId && (a.permissionSetId === null || a.permissionSetId === undefined)
    )
    if (match) return match.layoutId
  }

  // 3. (null, permissionSetId) where permissionSetId is in userPermissionSetIds
  for (const psId of userPermissionSetIds) {
    const match = assignments.find(
      (a) => (a.recordTypeId === null || a.recordTypeId === undefined) && a.permissionSetId === psId
    )
    if (match) return match.layoutId
  }

  // 4. (null, null)
  const match = assignments.find(
    (a) => (a.recordTypeId === null || a.recordTypeId === undefined) && (a.permissionSetId === null || a.permissionSetId === undefined)
  )
  if (match) return match.layoutId

  return null
}

export function LayoutRenderer({
  layout,
  fields,
  record,
  mode,
  onSubmit,
  onCancel,
  layouts,
  layoutAssignments,
  recordTypes,
  userPermissionSetIds,
}: LayoutRendererProps) {
  const activeRecordTypes = recordTypes ? recordTypes.filter((rt) => rt.isActive) : []
  const hasMultipleRecordTypes = activeRecordTypes.length > 1

  // Determine initial record type ID from record or default record type if creating a record
  const initialRtId = (record.record_type_id as string | null) || (record.recordTypeId as string | null) || null

  const [selectedRecordTypeId, setSelectedRecordTypeId] = useState<string | null>(initialRtId)
  const [tempSelectedRtId, setTempSelectedRtId] = useState<string | null>(
    initialRtId || (activeRecordTypes.find((rt) => rt.isDefault)?.id) || (activeRecordTypes[0]?.id) || null
  )

  // Show picker if creating a new record (no record.id) and there are multiple active record types
  const [isPickerStage, setIsPickerStage] = useState<boolean>(
    mode === 'edit' && !record.id && hasMultipleRecordTypes && !initialRtId
  )

  const [formData, setFormData] = useState<Record<string, unknown>>({
    ...record,
    ...(initialRtId ? { record_type_id: initialRtId, recordTypeId: initialRtId } : {}),
  })
  const [errors, setErrors] = useState<Record<string, string>>({})

  const handleChange = (apiName: string, value: unknown) => {
    setFormData((prev) => ({ ...prev, [apiName]: value }))
    if (errors[apiName]) {
      setErrors((prev) => {
        const next = { ...prev }
        delete next[apiName]
        return next
      })
    }
  }

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    const schema = generateZodSchema(fields)
    const result = schema.safeParse(formData)
    if (!result.success) {
      const errs: Record<string, string> = {}
      result.error.issues.forEach((issue) => {
        const path = issue.path[0]
        if (typeof path === 'string') errs[path] = issue.message
      })
      setErrors(errs)
      return
    }

    const submitData = { ...formData }
    if (selectedRecordTypeId) {
      submitData.record_type_id = selectedRecordTypeId
      submitData.recordTypeId = selectedRecordTypeId
    }
    onSubmit?.(submitData)
  }

  const handleProceed = () => {
    if (tempSelectedRtId) {
      setSelectedRecordTypeId(tempSelectedRtId)
      setFormData((prev) => ({
        ...prev,
        record_type_id: tempSelectedRtId,
        recordTypeId: tempSelectedRtId,
      }))
    }
    setIsPickerStage(false)
  }

  // Record Type Picker Screen
  if (isPickerStage) {
    return (
      <div className="osc-record-type-picker-container" data-testid="record-type-picker">
        <div className="osc-record-type-picker-header">
          <div className="osc-record-type-picker-step">Step 1 of 2</div>
          <h2 className="osc-record-type-picker-title">Select a Record Type</h2>
          <p className="osc-record-type-picker-subtitle">
            Choose a record type to configure the appropriate layout and fields for this record.
          </p>
        </div>

        <div className="osc-record-type-options" role="radiogroup" aria-label="Record Types">
          {activeRecordTypes.map((rt) => (
            <div
              key={rt.id}
              className={`osc-record-type-option ${tempSelectedRtId === rt.id ? 'selected' : ''}`}
              onClick={() => setTempSelectedRtId(rt.id)}
              data-testid={`record-type-option-${rt.apiName}`}
            >
              <input
                type="radio"
                name="recordType"
                id={`rt-radio-${rt.id}`}
                value={rt.id}
                checked={tempSelectedRtId === rt.id}
                onChange={() => setTempSelectedRtId(rt.id)}
                className="osc-record-type-radio"
              />
              <div className="osc-record-type-info">
                <label htmlFor={`rt-radio-${rt.id}`} className="osc-record-type-label">
                  {rt.label}
                </label>
                <span className="osc-record-type-desc">
                  Configures {rt.label} specific fields and layouts.
                </span>
              </div>
            </div>
          ))}
        </div>

        <div className="osc-record-type-picker-actions">
          {onCancel && (
            <button
              type="button"
              className="osc-btn osc-btn-secondary"
              onClick={onCancel}
              data-testid="record-type-cancel"
            >
              Cancel
            </button>
          )}
          <button
            type="button"
            className="osc-btn osc-btn-primary"
            onClick={handleProceed}
            disabled={!tempSelectedRtId}
            data-testid="record-type-next"
          >
            Next
          </button>
        </div>
      </div>
    )
  }

  // Resolve layout to render
  let currentLayout = layout
  if (layouts && layoutAssignments) {
    const resolvedLayoutId = resolveLayoutAssignment(
      layoutAssignments,
      selectedRecordTypeId,
      userPermissionSetIds || []
    )
    if (resolvedLayoutId && layouts[resolvedLayoutId]) {
      currentLayout = layouts[resolvedLayoutId]
    }
  }

  const fieldMap = new Map(fields.map((f) => [f.apiName, f]))

  const renderField = (lf: LayoutField) => {
    const fieldDef = fieldMap.get(lf.fieldApiName)
    if (!fieldDef) return null
    return (
      <GridItem key={lf.fieldApiName} span={lf.span}>
        <FieldRenderer
          field={fieldDef}
          value={formData[fieldDef.apiName]}
          mode={mode}
          onChange={mode === 'edit' ? (v) => handleChange(fieldDef.apiName, v) : undefined}
          error={errors[fieldDef.apiName]}
        />
      </GridItem>
    )
  }

  const content = currentLayout.sections.map((section, si) => (
    <Section key={si} title={section.label}>
      <Grid columns={section.columns}>
        {section.fields.map(renderField)}
      </Grid>
    </Section>
  ))

  if (mode === 'view') {
    return <div className="osc-layout">{content}</div>
  }

  return (
    <form className="osc-layout osc-layout--edit" onSubmit={handleSubmit} noValidate>
      {content}
      <div className="osc-layout__actions">
        {onCancel && (
          <Button type="button" variant="secondary" onClick={onCancel}>
            Cancel
          </Button>
        )}
        <Button type="submit" variant="primary">
          Save
        </Button>
      </div>
    </form>
  )
}


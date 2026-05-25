import React, { useState } from 'react'
import { FieldRenderer } from './FieldRenderer'
import { Section, Grid, GridItem } from '@/components/layout'
import { Button } from '@/components/navigation'
import { generateZodSchema } from '@/api/zodSchemaGenerator'
import type { FieldDefinition, LayoutDefinition, LayoutField } from '@/types/metadata'

interface LayoutRendererProps {
  layout: LayoutDefinition
  fields: FieldDefinition[]
  record: Record<string, unknown>
  mode: 'view' | 'edit'
  onSubmit?: (data: Record<string, unknown>) => void
  onCancel?: () => void
}

export function LayoutRenderer({ layout, fields, record, mode, onSubmit, onCancel }: LayoutRendererProps) {
  const fieldMap = new Map(fields.map((f) => [f.apiName, f]))
  const [formData, setFormData] = useState<Record<string, unknown>>({ ...record })
  const [errors, setErrors] = useState<Record<string, string>>({})

  const handleChange = (apiName: string, value: unknown) => {
    setFormData((prev) => ({ ...prev, [apiName]: value }))
    if (errors[apiName]) {
      setErrors((prev) => { const next = { ...prev }; delete next[apiName]; return next })
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
    onSubmit?.(formData)
  }

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

  const content = layout.sections.map((section, si) => (
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

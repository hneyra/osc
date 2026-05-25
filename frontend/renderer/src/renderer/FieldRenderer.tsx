import React from 'react'
import type { FieldDefinition } from '@/types/metadata'
import { TextInput, TextArea, NumberInput, DatePicker, DateTimePicker, Checkbox, Select } from '@/components/forms'

interface FieldRendererProps {
  field: FieldDefinition
  value: unknown
  mode: 'view' | 'edit'
  onChange?: (value: unknown) => void
  error?: string
}

function ViewValue({ field, value }: { field: FieldDefinition; value: unknown }) {
  const label = <span className="osc-field-view__label">{field.label}</span>
  const empty = <span className="osc-field-view__empty">—</span>

  if (value === null || value === undefined || value === '') {
    return <div className="osc-field-view">{label}{empty}</div>
  }

  let display: React.ReactNode

  switch (field.fieldType) {
    case 'BOOLEAN':
      display = value ? 'Yes' : 'No'
      break

    case 'NUMBER':
    case 'CURRENCY':
      display = typeof value === 'number' ? value.toLocaleString() : String(value)
      break

    case 'EMAIL':
      display = <a href={`mailto:${value}`} className="osc-link">{String(value)}</a>
      break

    case 'URL':
      display = <a href={String(value)} className="osc-link" target="_blank" rel="noopener noreferrer">{String(value)}</a>
      break

    case 'PHONE':
      display = <a href={`tel:${value}`} className="osc-link">{String(value)}</a>
      break

    case 'MULTIPICKLIST':
      display = Array.isArray(value) ? value.join(', ') : String(value)
      break

    default:
      display = String(value)
  }

  return <div className="osc-field-view">{label}<span className="osc-field-view__value">{display}</span></div>
}

export function FieldRenderer({ field, value, mode, onChange, error }: FieldRendererProps) {
  if (mode === 'view') {
    return <ViewValue field={field} value={value} />
  }

  const id = `field-${field.apiName}`
  const strVal = value != null ? String(value) : ''

  const handleChange = (newVal: unknown) => onChange?.(newVal)

  switch (field.fieldType) {
    case 'TEXTAREA':
      return (
        <TextArea
          id={id} label={field.label} value={strVal}
          onChange={(e) => handleChange(e.target.value)}
          error={error} required={field.isRequired}
        />
      )

    case 'NUMBER':
    case 'CURRENCY':
      return (
        <NumberInput
          id={id} label={field.label}
          value={value as number ?? ''}
          onChange={(e) => handleChange(e.target.valueAsNumber)}
          error={error} required={field.isRequired}
        />
      )

    case 'BOOLEAN':
      return (
        <Checkbox
          id={id} label={field.label}
          checked={Boolean(value)}
          onChange={(e) => handleChange(e.target.checked)}
          error={error}
        />
      )

    case 'PICKLIST': {
      const options = (field.config?.options ?? []).map((o) => ({ value: o, label: o }))
      return (
        <Select
          id={id} label={field.label} options={options}
          value={strVal}
          onChange={(e) => handleChange(e.target.value)}
          error={error} required={field.isRequired}
        />
      )
    }

    case 'DATE':
      return (
        <DatePicker
          id={id} label={field.label} value={strVal}
          onChange={(e) => handleChange(e.target.value)}
          error={error} required={field.isRequired}
        />
      )

    case 'DATETIME':
      return (
        <DateTimePicker
          id={id} label={field.label} value={strVal}
          onChange={(e) => handleChange(e.target.value)}
          error={error} required={field.isRequired}
        />
      )

    case 'TEXT':
    case 'EMAIL':
    case 'PHONE':
    case 'URL':
    default:
      return (
        <TextInput
          id={id} label={field.label} value={strVal}
          onChange={(e) => handleChange(e.target.value)}
          error={error} required={field.isRequired}
        />
      )
  }
}

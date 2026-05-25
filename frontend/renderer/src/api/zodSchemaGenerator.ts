import { z } from 'zod'
import type { FieldDefinition } from '@/types/metadata'

type FieldSchema = z.ZodTypeAny

function buildFieldSchema(field: FieldDefinition): FieldSchema {
  let base: FieldSchema

  switch (field.fieldType) {
    case 'TEXT':
    case 'TEXTAREA':
    case 'PHONE':
      base = field.isRequired ? z.string().min(1) : z.string()
      break

    case 'EMAIL':
      base = z.string().email()
      break

    case 'URL':
      base = z.string().url()
      break

    case 'DATE':
    case 'DATETIME':
      base = z.string().min(1)
      break

    case 'NUMBER':
    case 'CURRENCY':
      base = z.number()
      break

    case 'BOOLEAN':
      base = z.boolean()
      break

    case 'PICKLIST': {
      const options = field.config?.options
      base = options && options.length > 0
        ? z.enum(options as [string, ...string[]])
        : z.string()
      break
    }

    case 'MULTIPICKLIST': {
      const options = field.config?.options
      const itemSchema = options && options.length > 0
        ? z.enum(options as [string, ...string[]])
        : z.string()
      base = z.array(itemSchema)
      break
    }

    case 'LOOKUP':
      base = z.string().uuid()
      break

    default:
      base = z.string()
  }

  if (!field.isRequired) {
    return base.optional()
  }
  return base
}

export function generateZodSchema(fields: FieldDefinition[]): z.ZodObject<z.ZodRawShape> {
  const shape: z.ZodRawShape = {}
  for (const field of fields) {
    shape[field.apiName] = buildFieldSchema(field)
  }
  return z.object(shape)
}

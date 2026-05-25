import { describe, it, expect } from 'vitest'
import { generateZodSchema } from '@/api/zodSchemaGenerator'
import type { FieldDefinition } from '@/types/metadata'
import { z } from 'zod'

const field = (overrides: Partial<FieldDefinition>): FieldDefinition => ({
  id: '1',
  tenantId: 't1',
  objectId: 'o1',
  apiName: 'name',
  label: 'Name',
  fieldType: 'TEXT',
  storageKind: 'COLUMN',
  storageKey: 'name',
  isRequired: false,
  isCustom: false,
  config: null,
  createdAt: '',
  updatedAt: '',
  ...overrides,
})

describe('generateZodSchema', () => {
  it('required TEXT field → z.string().min(1)', () => {
    const schema = generateZodSchema([field({ apiName: 'name', fieldType: 'TEXT', isRequired: true })])
    expect(schema.shape.name).toBeDefined()
    expect(schema.safeParse({ name: '' }).success).toBe(false)
    expect(schema.safeParse({ name: 'ACME' }).success).toBe(true)
  })

  it('optional TEXT field → z.string().optional()', () => {
    const schema = generateZodSchema([field({ apiName: 'website', fieldType: 'TEXT', isRequired: false })])
    expect(schema.safeParse({}).success).toBe(true)
    expect(schema.safeParse({ website: 'https://example.com' }).success).toBe(true)
  })

  it('NUMBER field → z.number()', () => {
    const schema = generateZodSchema([field({ apiName: 'amount', fieldType: 'NUMBER', isRequired: true })])
    expect(schema.safeParse({ amount: 42 }).success).toBe(true)
    expect(schema.safeParse({ amount: 'not-a-number' }).success).toBe(false)
  })

  it('BOOLEAN field → z.boolean()', () => {
    const schema = generateZodSchema([field({ apiName: 'active', fieldType: 'BOOLEAN', isRequired: false })])
    expect(schema.safeParse({ active: true }).success).toBe(true)
    expect(schema.safeParse({ active: false }).success).toBe(true)
    expect(schema.safeParse({}).success).toBe(true)
  })

  it('EMAIL field → z.string().email()', () => {
    const schema = generateZodSchema([field({ apiName: 'email', fieldType: 'EMAIL', isRequired: true })])
    expect(schema.safeParse({ email: 'test@example.com' }).success).toBe(true)
    expect(schema.safeParse({ email: 'not-an-email' }).success).toBe(false)
  })

  it('URL field → z.string().url()', () => {
    const schema = generateZodSchema([field({ apiName: 'website', fieldType: 'URL', isRequired: true })])
    expect(schema.safeParse({ website: 'https://example.com' }).success).toBe(true)
    expect(schema.safeParse({ website: 'not-a-url' }).success).toBe(false)
  })

  it('DATE field → accepts ISO date strings', () => {
    const schema = generateZodSchema([field({ apiName: 'dob', fieldType: 'DATE', isRequired: true })])
    expect(schema.safeParse({ dob: '2024-01-15' }).success).toBe(true)
    expect(schema.safeParse({ dob: '' }).success).toBe(false)
  })

  it('PICKLIST field with options → z.enum()', () => {
    const schema = generateZodSchema([field({
      apiName: 'industry',
      fieldType: 'PICKLIST',
      isRequired: true,
      config: { options: ['Tech', 'Finance', 'Retail'] },
    })])
    expect(schema.safeParse({ industry: 'Tech' }).success).toBe(true)
    expect(schema.safeParse({ industry: 'Unknown' }).success).toBe(false)
  })

  it('PICKLIST without options → falls back to z.string()', () => {
    const schema = generateZodSchema([field({ apiName: 'industry', fieldType: 'PICKLIST', isRequired: false })])
    expect(schema.safeParse({ industry: 'anything' }).success).toBe(true)
  })

  it('multiple fields → combined object schema', () => {
    const schema = generateZodSchema([
      field({ apiName: 'name', fieldType: 'TEXT', isRequired: true }),
      field({ apiName: 'email', fieldType: 'EMAIL', isRequired: false }),
    ])
    expect(schema.safeParse({ name: 'ACME' }).success).toBe(true)
    expect(schema.safeParse({}).success).toBe(false)
    expect(schema.safeParse({ name: 'ACME', email: 'bad-email' }).success).toBe(false)
  })

  it('LOOKUP field → z.string().uuid()', () => {
    const schema = generateZodSchema([field({ apiName: 'accountId', fieldType: 'LOOKUP', isRequired: true })])
    const id = '123e4567-e89b-12d3-a456-426614174000'
    expect(schema.safeParse({ accountId: id }).success).toBe(true)
    expect(schema.safeParse({ accountId: 'not-a-uuid' }).success).toBe(false)
  })

  it('returns z.object({}) for empty field list', () => {
    const schema = generateZodSchema([])
    expect(schema).toBeInstanceOf(z.ZodObject)
    expect(schema.safeParse({}).success).toBe(true)
  })
})

import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ScriptEditor } from '@/components/ScriptEditor'
import type { ScriptDefinition } from '@/types'

function baseScript(overrides: Partial<ScriptDefinition> = {}): ScriptDefinition {
  return {
    objectApiName: 'Invoice__c',
    kind: 'TRIGGER',
    triggerEvent: 'BEFORE_INSERT',
    source: 'fun execute(ctx: ExecutionContext) {}',
    isActive: false,
    compileErrors: [],
    timeoutSeconds: 5,
    generatedByAi: false,
    ...overrides,
  }
}

describe('ScriptEditor', () => {
  it('disables Activate when there are compile errors', () => {
    render(
      <ScriptEditor
        script={baseScript({ compileErrors: ['unresolved reference: ctx'] })}
        onChangeSource={vi.fn()}
        onActivate={vi.fn()}
      />,
    )

    expect(screen.getByTestId('compile-errors')).toHaveTextContent('unresolved reference: ctx')
    expect(screen.getByRole('button', { name: 'Activate' })).toBeDisabled()
  })

  it('enables Activate on a clean compile', () => {
    render(
      <ScriptEditor script={baseScript()} onChangeSource={vi.fn()} onActivate={vi.fn()} />,
    )

    expect(screen.queryByTestId('compile-errors')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Activate' })).toBeEnabled()
  })

  it('calls onActivate when clicked', async () => {
    const onActivate = vi.fn()
    render(<ScriptEditor script={baseScript()} onChangeSource={vi.fn()} onActivate={onActivate} />)

    await userEvent.click(screen.getByRole('button', { name: 'Activate' }))

    expect(onActivate).toHaveBeenCalledOnce()
  })

  it('flags AI-generated scripts for review', () => {
    render(
      <ScriptEditor
        script={baseScript({ generatedByAi: true })}
        onChangeSource={vi.fn()}
        onActivate={vi.fn()}
      />,
    )

    expect(screen.getByRole('status')).toHaveTextContent('AI-generated, not yet reviewed')
  })
})

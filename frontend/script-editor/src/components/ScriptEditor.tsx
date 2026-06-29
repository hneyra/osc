import { useState } from 'react'
import type { ScriptDefinition } from '@/types'

export interface ScriptEditorProps {
  script: ScriptDefinition
  onChangeSource: (source: string) => void
  onActivate: () => void
}

/**
 * Source editor + compile-error display + Activate control for a Kotlin Scripting
 * unit (md_script, ADR-005). Activation is only possible on a clean compile (NNG-023),
 * and AI-generated scripts are visually flagged for explicit human review (NNG-025).
 *
 * This is the initial scaffold for issue tracking ADR-005 frontend work — syntax
 * highlighting, live recompile-on-save, and richer diagnostics are not implemented yet.
 */
export function ScriptEditor({ script, onChangeSource, onActivate }: ScriptEditorProps) {
  const [source, setSource] = useState(script.source)
  const hasCompileErrors = script.compileErrors.length > 0

  const handleSourceChange = (value: string) => {
    setSource(value)
    onChangeSource(value)
  }

  return (
    <div className="script-editor">
      {script.generatedByAi && (
        <div className="script-editor__ai-banner" role="status">
          AI-generated, not yet reviewed
        </div>
      )}

      <textarea
        className="script-editor__source"
        value={source}
        onChange={(e) => handleSourceChange(e.target.value)}
        spellCheck={false}
      />

      {hasCompileErrors && (
        <ul className="script-editor__errors" data-testid="compile-errors">
          {script.compileErrors.map((error, index) => (
            <li key={index}>{error}</li>
          ))}
        </ul>
      )}

      <button
        type="button"
        onClick={onActivate}
        disabled={hasCompileErrors || script.isActive}
      >
        {script.isActive ? 'Active' : 'Activate'}
      </button>
    </div>
  )
}

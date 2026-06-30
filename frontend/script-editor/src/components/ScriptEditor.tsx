import { useState } from 'react'
import type { ScriptDefinition } from '@/types'
import './ScriptEditor.css'

export interface ScriptEditorProps {
  script: ScriptDefinition
  onChangeSource: (source: string) => void
  onActivate: () => void
}

/**
 * Advanced, premium Script Editor for Kotlin Scripting (ADR-005).
 * Combines high-tech glassmorphic aesthetics, inline compilation error display,
 * metadata visualization (timeout, kind, activation state), and strict gating
 * to prevent activation when compilation errors are present.
 */
export function ScriptEditor({ script, onChangeSource, onActivate }: ScriptEditorProps) {
  const [source, setSource] = useState(script.source)
  const hasCompileErrors = script.compileErrors.length > 0

  const handleSourceChange = (value: string) => {
    setSource(value)
    onChangeSource(value)
  }

  // Format date helper
  const formatCompiledAt = (dateString?: string | null) => {
    if (!dateString) return 'Never'
    try {
      return new Date(dateString).toLocaleString()
    } catch {
      return dateString
    }
  }

  return (
    <div className="script-editor-container">
      {script.generatedByAi && (
        <div className="script-editor__ai-banner" role="status">
          <span className="sparkles">✨</span> AI-generated, not yet reviewed
        </div>
      )}

      {/* Header section with status badge */}
      <header className="script-editor-header">
        <div className="script-editor-title-area">
          <h2>Kotlin Script Editor</h2>
          <div className="script-editor-subtitle">
            <span>Target Object: <strong>{script.objectApiName}</strong></span>
            <span>•</span>
            <span>Type: <strong>{script.kind}</strong></span>
          </div>
        </div>
        <div className="script-editor-status">
          <span className={`badge ${script.isActive ? 'active' : 'inactive'}`}>
            {script.isActive ? 'Active' : 'Inactive'}
          </span>
        </div>
      </header>

      {/* Main Workspace: Editor and Sidebar */}
      <div className="script-editor-workspace">
        <main className="script-editor-main">
          <div className="script-editor-textarea-wrapper">
            <textarea
              className="script-editor__source"
              value={source}
              onChange={(e) => handleSourceChange(e.target.value)}
              spellCheck={false}
              placeholder="// Write your Kotlin script here..."
            />
          </div>

          {hasCompileErrors && (
            <ul className="script-editor__errors" data-testid="compile-errors">
              {script.compileErrors.map((error, index) => (
                <li key={index}>{error}</li>
              ))}
            </ul>
          )}
        </main>

        <aside className="script-editor-sidebar">
          <div className="script-editor-meta-group">
            <div className="script-editor-meta-section">
              <span className="script-editor-meta-label">Timeout Limit</span>
              <div className="script-editor-meta-value">
                <span>⏱️ {script.timeoutSeconds} seconds</span>
              </div>
            </div>

            {script.triggerEvent && (
              <div className="script-editor-meta-section">
                <span className="script-editor-meta-label">Trigger Event</span>
                <div className="script-editor-meta-value">
                  <span className="badge trigger">{script.triggerEvent}</span>
                </div>
              </div>
            )}

            {script.invocableName && (
              <div className="script-editor-meta-section">
                <span className="script-editor-meta-label">Invocable Name</span>
                <div className="script-editor-meta-value">
                  <span>🚀 {script.invocableName}</span>
                </div>
              </div>
            )}

            {script.scheduleCron && (
              <div className="script-editor-meta-section">
                <span className="script-editor-meta-label">Schedule (Cron)</span>
                <div className="script-editor-meta-value">
                  <span>📅 {script.scheduleCron}</span>
                </div>
              </div>
            )}

            <div className="script-editor-meta-section">
              <span className="script-editor-meta-label">Last Compiled</span>
              <div className="script-editor-meta-value">
                <span>⚙️ {formatCompiledAt(script.compiledAt)}</span>
              </div>
            </div>

            {script.generatedByAi && (
              <div className="script-editor-meta-section">
                <span className="script-editor-meta-label">Source Origin</span>
                <div className="script-editor-meta-value highlight">
                  <span>✨ Artificial Intelligence</span>
                </div>
              </div>
            )}
          </div>

          <div className="script-editor-actions">
            <button
              type="button"
              className={`script-editor-activate-btn ${script.isActive ? 'active' : 'inactive'}`}
              onClick={onActivate}
              disabled={hasCompileErrors || script.isActive}
            >
              {script.isActive ? (
                <>
                  <span aria-hidden="true">✓</span> Active
                </>
              ) : (
                <>
                  <span aria-hidden="true">⚡</span> Activate
                </>
              )}
            </button>
          </div>
        </aside>
      </div>
    </div>
  )
}

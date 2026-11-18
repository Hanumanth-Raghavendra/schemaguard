import Editor, { loader, type OnMount } from '@monaco-editor/react';
import * as monaco from 'monaco-editor/editor/editor.api.js';
import 'monaco-editor/languages/definitions/pgsql/register.js';
import EditorWorker from 'monaco-editor/editor/editor.worker.js?worker';
import { useEffect, useId, useState } from 'react';
import type { SqlEditorProps } from './editorProps.ts';

// Bundle Monaco instead of loading it from a CDN; SQL needs only the base worker.
self.MonacoEnvironment = { getWorker: () => new EditorWorker() };
loader.config({ monaco });

monaco.editor.defineTheme('schemaguard-light', {
  base: 'vs',
  inherit: true,
  rules: [],
  colors: { 'editor.background': '#ffffff', 'editorLineNumber.foreground': '#8a99a8' },
});
monaco.editor.defineTheme('schemaguard-dark', {
  base: 'vs-dark',
  inherit: true,
  rules: [],
  colors: { 'editor.background': '#16212c', 'editorLineNumber.foreground': '#5d6f80' },
});

const dark = window.matchMedia('(prefers-color-scheme: dark)');

function useDarkMode(): boolean {
  const [isDark, setDark] = useState(dark.matches);
  useEffect(() => {
    const update = (event: MediaQueryListEvent) => {
      setDark(event.matches);
    };
    dark.addEventListener('change', update);
    return () => {
      dark.removeEventListener('change', update);
    };
  }, []);
  return isDark;
}

/** Monaco with PostgreSQL highlighting; problems from the API are underlined at their line and column. */
export default function MonacoSqlEditor({ label, value, onChange, diagnostics, placeholder }: SqlEditorProps) {
  const labelId = useId();
  const isDark = useDarkMode();
  const [editor, setEditor] = useState<monaco.editor.IStandaloneCodeEditor | null>(null);

  useEffect(() => {
    const model = editor?.getModel();
    if (!model) return;
    monaco.editor.setModelMarkers(
      model,
      'schemaguard',
      diagnostics.map((diagnostic) => ({
        severity: diagnostic.severity === 'ERROR' ? monaco.MarkerSeverity.Error : monaco.MarkerSeverity.Warning,
        message: diagnostic.message,
        startLineNumber: diagnostic.line,
        startColumn: diagnostic.column,
        endLineNumber: diagnostic.line,
        endColumn: model.getLineMaxColumn(Math.min(diagnostic.line, model.getLineCount())),
      })),
    );
  }, [editor, diagnostics]);

  const onMount: OnMount = (mounted) => {
    setEditor(mounted);
  };

  return (
    <div className="flex flex-col gap-1.5">
      <span id={labelId} className="text-sm font-semibold">
        {label}
      </span>
      <div className="h-72 overflow-hidden rounded-md border border-line bg-surface">
        <Editor
          language="pgsql"
          value={value}
          theme={isDark ? 'schemaguard-dark' : 'schemaguard-light'}
          onChange={(text) => {
            onChange(text ?? '');
          }}
          onMount={onMount}
          options={{
            ariaLabel: label,
            placeholder,
            fontFamily: "'JetBrains Mono Variable', ui-monospace, monospace",
            fontSize: 13,
            lineHeight: 20,
            minimap: { enabled: false },
            scrollBeyondLastLine: false,
            wordWrap: 'on',
            automaticLayout: true,
            tabSize: 2,
            padding: { top: 8, bottom: 8 },
            renderLineHighlight: 'none',
            overviewRulerLanes: 0,
          }}
        />
      </div>
    </div>
  );
}

import { useId } from 'react';
import type { SqlEditorProps } from './editorProps.ts';

/** A plain text area: shown while the code editor loads, and used in tests. */
export function PlainSqlEditor({ label, value, onChange, diagnostics, placeholder }: SqlEditorProps) {
  const id = useId();
  const errors = diagnostics.filter((diagnostic) => diagnostic.severity === 'ERROR').length;
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-sm font-semibold">
        {label}
      </label>
      <textarea
        id={id}
        value={value}
        placeholder={placeholder}
        spellCheck={false}
        aria-invalid={errors > 0}
        onChange={(event) => {
          onChange(event.target.value);
        }}
        className="h-72 resize-y rounded-md border border-line bg-surface p-3 font-mono text-[13px] leading-5"
      />
    </div>
  );
}

export default PlainSqlEditor;

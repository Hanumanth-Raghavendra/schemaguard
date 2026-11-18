import { lazy, Suspense } from 'react';
import type { SqlEditorProps } from './editorProps.ts';
import { PlainSqlEditor } from './PlainSqlEditor.tsx';

// Monaco is a few megabytes, so it loads after the page; typing works in the plain editor meanwhile.
const MonacoSqlEditor = lazy(() => import('./MonacoSqlEditor.tsx'));

export function SqlEditor(props: SqlEditorProps) {
  return (
    <Suspense fallback={<PlainSqlEditor {...props} />}>
      <MonacoSqlEditor {...props} />
    </Suspense>
  );
}

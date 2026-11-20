import { lazy, Suspense } from 'react';
import type { SqlEditorProps } from './editorProps.ts';
import { LoadBoundary } from './LoadBoundary.tsx';
import { PlainSqlEditor } from './PlainSqlEditor.tsx';

// Monaco is a few megabytes, so it loads after the page; typing works in the plain editor meanwhile.
const MonacoSqlEditor = lazy(() => import('./MonacoSqlEditor.tsx'));

export function SqlEditor(props: SqlEditorProps) {
  const plain = <PlainSqlEditor {...props} />;
  return (
    <LoadBoundary fallback={plain}>
      <Suspense fallback={plain}>
        <MonacoSqlEditor {...props} />
      </Suspense>
    </LoadBoundary>
  );
}

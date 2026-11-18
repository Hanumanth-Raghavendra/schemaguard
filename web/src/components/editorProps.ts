import type { Diagnostic } from '../api/client.ts';

export interface SqlEditorProps {
  /** Visible label, also the editor's accessible name. */
  label: string;
  value: string;
  onChange: (value: string) => void;
  /** Problems to underline in the text. */
  diagnostics: Diagnostic[];
  placeholder: string;
}

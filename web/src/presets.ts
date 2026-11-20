/** Inputs another page hands to the home page, which loads them into the editors and runs the analysis. */
export type Preset =
  | { mode: 'compare'; before: string; after: string }
  | { mode: 'lint'; schema: string; migration: string };

export function isPreset(value: unknown): value is Preset {
  if (typeof value !== 'object' || value === null) return false;
  const preset = value as Record<string, unknown>;
  if (preset.mode === 'compare') return typeof preset.before === 'string' && typeof preset.after === 'string';
  if (preset.mode === 'lint') return typeof preset.schema === 'string' && typeof preset.migration === 'string';
  return false;
}

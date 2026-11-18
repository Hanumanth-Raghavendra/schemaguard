/** Text and border colour for each risk level, NONE to CRITICAL. */
export const LEVEL_TONE: Record<string, string> = {
  NONE: 'text-none border-none',
  LOW: 'text-low border-low',
  MEDIUM: 'text-medium border-medium',
  HIGH: 'text-high border-high',
  CRITICAL: 'text-critical border-critical',
};

export function levelText(level: string): string {
  return LEVEL_TONE[level]?.split(' ')[0] ?? 'text-muted';
}

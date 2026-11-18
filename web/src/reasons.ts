/** A reason reads "85 SG-R302 Column is dropped with all its values": points, rule, title. */
export function splitReason(reason: string): { points: string | null; rule: string | null; title: string } {
  const match = /^(\d+) (SG-[A-Z]+\d+) (.+)$/.exec(reason);
  if (!match?.[1] || !match[2] || !match[3]) return { points: null, rule: null, title: reason };
  return { points: match[1], rule: match[2], title: match[3] };
}

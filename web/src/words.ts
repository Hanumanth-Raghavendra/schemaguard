/** "1 change", "2 changes": a count with its noun, for English nouns that take an s. */
export function plural(count: number, word: string): string {
  return `${count} ${word}${count === 1 ? '' : 's'}`;
}

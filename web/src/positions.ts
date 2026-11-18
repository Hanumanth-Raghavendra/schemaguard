export interface Position {
  line: number;
  column: number;
}

function atOrBefore(a: Position, b: Position): boolean {
  return a.line < b.line || (a.line === b.line && a.column <= b.column);
}

/** Index of the statement a position falls in: the last one that starts at or before it, or -1. */
export function owner(statements: Position[], position: Position): number {
  let found = -1;
  statements.forEach((statement, index) => {
    if (atOrBefore(statement, position)) found = index;
  });
  return found;
}

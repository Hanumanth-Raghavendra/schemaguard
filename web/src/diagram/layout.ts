import dagre from '@dagrejs/dagre';
import type { ModelColumn, ModelReference, ModelResponse, ModelTable } from '../api/client.ts';

export const HEADER = 34;
export const ROW = 22;
export const PAD_X = 12;
/** Width of one character of the 12 px monospace face the columns are drawn in. */
const CHAR = 7.3;

export interface Box {
  key: string;
  table: ModelTable;
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface Link {
  key: string;
  reference: ModelReference;
  path: string;
}

export interface Layout {
  boxes: Box[];
  links: Link[];
  width: number;
  height: number;
}

export function tableKey(name: { schema: string; name: string }): string {
  return `${name.schema}.${name.name}`;
}

/** Tables in the public schema go by their bare name; others keep the schema. */
export function tableLabel(table: ModelTable): string {
  return table.name.schema === 'public' ? table.name.name : tableKey(table.name);
}

export function typeText(column: ModelColumn): string {
  return column.previousType === null ? column.type : `${column.previousType} → ${column.type}`;
}

function boxWidth(table: ModelTable): number {
  const header = tableLabel(table).length * 8.2 + 70;
  const rows = table.columns.map((column) => (column.name.length + typeText(column).length + 7) * CHAR);
  return Math.round(Math.min(440, Math.max(200, header, ...rows) + 2 * PAD_X));
}

/** Middle of a column's row, or of the header when the column is not in the table. */
function rowY(box: Box, column: string | undefined): number {
  const index = column === undefined ? -1 : box.table.columns.findIndex((each) => each.name === column);
  return index < 0 ? box.y + HEADER / 2 : box.y + HEADER + index * ROW + ROW / 2;
}

/** A curve from the key's column in the child table to the referenced column in the parent. */
function linkPath(child: Box, parent: Box, reference: ModelReference): string {
  const startY = rowY(child, reference.columns[0]);
  const endY = rowY(parent, reference.referencedColumns[0]);
  if (child === parent) {
    const x = child.x + child.width;
    return `M ${x} ${startY} C ${x + 40} ${startY}, ${x + 40} ${endY}, ${x} ${endY}`;
  }
  const childIsRight = child.x + child.width / 2 > parent.x + parent.width / 2;
  const startX = childIsRight ? child.x : child.x + child.width;
  const endX = childIsRight ? parent.x + parent.width : parent.x;
  const bend = Math.max(40, Math.abs(endX - startX) / 2) * (childIsRight ? -1 : 1);
  return `M ${startX} ${startY} C ${startX + bend} ${startY}, ${endX - bend} ${endY}, ${endX} ${endY}`;
}

/**
 * Places the tables left to right, referenced tables before the tables that point at them (dagre's
 * layered layout), and draws each foreign key from its column to the referenced column.
 */
export function layoutModel(model: ModelResponse): Layout {
  const graph = new dagre.graphlib.Graph({ multigraph: true });
  graph.setGraph({ rankdir: 'LR', nodesep: 36, ranksep: 100, marginx: 24, marginy: 24 });
  graph.setDefaultEdgeLabel(() => ({}));
  for (const table of model.tables) {
    graph.setNode(tableKey(table.name), { width: boxWidth(table), height: HEADER + table.columns.length * ROW + 8 });
  }
  model.references.forEach((reference, index) => {
    const from = tableKey(reference.from);
    const to = tableKey(reference.to);
    if (from !== to && graph.hasNode(from) && graph.hasNode(to)) graph.setEdge(to, from, {}, `fk${index}`);
  });
  dagre.layout(graph);

  const boxes = new Map<string, Box>();
  for (const table of model.tables) {
    const key = tableKey(table.name);
    // dagre adds the centre (x, y) to the size given above; its typings leave nodes untyped.
    const node = graph.node(key) as { x: number; y: number; width: number; height: number };
    boxes.set(key, {
      key,
      table,
      x: node.x - node.width / 2,
      y: node.y - node.height / 2,
      width: node.width,
      height: node.height,
    });
  }
  const links: Link[] = [];
  model.references.forEach((reference, index) => {
    const child = boxes.get(tableKey(reference.from));
    const parent = boxes.get(tableKey(reference.to));
    if (child && parent) links.push({ key: `${reference.name ?? 'fk'}-${index}`, reference, path: linkPath(child, parent, reference) });
  });
  const size = graph.graph() as { width?: number; height?: number };
  const selfLoops = model.references.some((reference) => tableKey(reference.from) === tableKey(reference.to)) ? 48 : 0;
  return {
    boxes: [...boxes.values()],
    links,
    width: Math.max(1, (size.width ?? 0) + selfLoops),
    height: Math.max(1, size.height ?? 0),
  };
}

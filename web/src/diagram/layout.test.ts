import { describe, expect, it } from 'vitest';
import type { ModelResponse } from '../api/client.ts';
import { fixtures } from '../test/fakeApi.tsx';
import { HEADER, layoutModel, ROW, tableLabel, typeText, type Box, type Layout } from './layout.ts';

function boxOf(layout: Layout, key: string): Box {
  const box = layout.boxes.find((each) => each.key === key);
  if (!box) throw new Error(`no box for ${key}`);
  return box;
}

describe('layoutModel', () => {
  it('puts the referenced table left of the table that points at it, without overlap', () => {
    const layout = layoutModel(fixtures.model);
    const customers = boxOf(layout, 'public.customers');
    const orders = boxOf(layout, 'public.orders');

    expect(customers.x + customers.width).toBeLessThan(orders.x);
    expect(customers.height).toBe(HEADER + 5 * ROW + 8);
    expect(customers.width).toBeGreaterThanOrEqual(200);
    expect(layout.width).toBeGreaterThanOrEqual(orders.x + orders.width);
  });

  it('draws a foreign key from its column to the referenced column', () => {
    const layout = layoutModel(fixtures.model);
    const orders = boxOf(layout, 'public.orders');
    const customers = boxOf(layout, 'public.customers');
    expect(layout.links).toHaveLength(1);
    const start = `M ${orders.x} ${orders.y + HEADER + 1 * ROW + ROW / 2}`;
    const end = `${customers.x + customers.width} ${customers.y + HEADER + ROW / 2}`;
    expect(layout.links[0]?.path.startsWith(start)).toBe(true);
    expect(layout.links[0]?.path.endsWith(end)).toBe(true);
  });

  it('loops a key that references its own table and skips tables it does not know', () => {
    const model: ModelResponse = {
      tables: [
        {
          name: { schema: 'app', name: 'nodes' },
          status: 'ADDED',
          columns: [
            { name: 'id', type: 'bigint', notNull: true, primaryKey: true, status: 'ADDED', previousType: null },
            { name: 'parent_id', type: 'bigint', notNull: false, primaryKey: false, status: 'ADDED', previousType: null },
          ],
        },
      ],
      references: [
        { name: 'self', from: { schema: 'app', name: 'nodes' }, columns: ['parent_id'], to: { schema: 'app', name: 'nodes' }, referencedColumns: ['id'], status: 'ADDED' },
        { name: 'away', from: { schema: 'app', name: 'nodes' }, columns: ['id'], to: { schema: 'other', name: 'gone' }, referencedColumns: ['id'], status: 'ADDED' },
      ],
      warnings: [],
    };
    const layout = layoutModel(model);
    expect(layout.links.map((link) => link.reference.name)).toEqual(['self']);
    const box = layout.boxes[0];
    if (!box) throw new Error('no box');
    expect(layout.links[0]?.path).toContain(`M ${box.x + box.width} `);
    expect(tableLabel(model.tables[0] as ModelResponse['tables'][number])).toBe('app.nodes');
  });

  it('shows a changed type as old and new', () => {
    const column = { name: 'id', type: 'bigint', notNull: true, primaryKey: true, status: 'CHANGED' as const, previousType: 'integer' };
    expect(typeText(column)).toBe('integer → bigint');
    expect(typeText({ ...column, previousType: null })).toBe('bigint');
  });
});

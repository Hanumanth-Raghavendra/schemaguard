import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { fixtures } from '../test/fakeApi.tsx';
import ErDiagram from './ErDiagram.tsx';

function viewBox(): number[] {
  return (screen.getByRole('img').getAttribute('viewBox') ?? '').split(' ').map(Number);
}

describe('ErDiagram', () => {
  it('draws both tables with what changed, and says so in words', () => {
    const { container } = render(<ErDiagram model={fixtures.model} />);
    expect(screen.getByRole('img')).toHaveAccessibleName(
      'Diagram of 2 tables, 0 added, 0 removed, 2 changed, and 1 foreign keys. The Changes tab lists every change as text.',
    );
    expect(screen.getAllByText('changed')).toHaveLength(2);
    expect(screen.getByText('integer → bigint')).toBeInTheDocument();
    const removed = container.querySelector('[data-table="public.customers"] text[text-decoration="line-through"]');
    expect(removed).toHaveTextContent('− name');
    expect(container.querySelector('path[data-reference="orders_customer_id_fkey"]')).toHaveClass('stroke-low');
  });

  it('zooms with the buttons and the keyboard, and fits again', async () => {
    const user = userEvent.setup();
    render(<ErDiagram model={fixtures.model} />);
    const [, , width] = viewBox();
    await user.click(screen.getByRole('button', { name: 'Zoom in' }));
    expect(viewBox()[2]).toBeLessThan(width ?? 0);
    await user.click(screen.getByRole('button', { name: 'Fit' }));
    expect(viewBox()[2]).toBe(width);

    screen.getByRole('img').focus();
    await user.keyboard('-');
    expect(viewBox()[2]).toBeGreaterThan(width ?? 0);
    await user.keyboard('0{ArrowRight}');
    expect(viewBox()[0]).toBeGreaterThan(0);
  });

  it('fits a new model instead of keeping the old view', async () => {
    const user = userEvent.setup();
    const { rerender } = render(<ErDiagram model={fixtures.model} />);
    await user.click(screen.getByRole('button', { name: 'Zoom in' }));
    const smaller = { ...fixtures.model, tables: fixtures.model.tables.slice(0, 1), references: [] };
    rerender(<ErDiagram model={smaller} />);
    const [x, y] = viewBox();
    expect([x, y]).toEqual([0, 0]);
  });

  it('says so when there is nothing to draw', () => {
    render(<ErDiagram model={{ tables: [], references: [], warnings: [] }} />);
    expect(screen.getByText('Neither schema has a table to draw.')).toBeInTheDocument();
  });
});

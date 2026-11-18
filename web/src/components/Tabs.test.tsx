import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { Tabs } from './Tabs.tsx';

function Harness() {
  const [selected, setSelected] = useState('a');
  return (
    <Tabs
      label="Results"
      selected={selected}
      onSelect={setSelected}
      tabs={[
        { id: 'a', label: 'First', content: <p>first panel</p> },
        { id: 'b', label: 'Second', content: <p>second panel</p> },
        { id: 'c', label: 'Third', content: <p>third panel</p> },
      ]}
    />
  );
}

describe('Tabs', () => {
  it('shows one panel at a time and moves with the arrow keys', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    expect(screen.getByRole('tabpanel', { name: 'First' })).toHaveTextContent('first panel');
    expect(screen.getByText('second panel')).not.toBeVisible();

    await user.click(screen.getByRole('tab', { name: 'First' }));
    await user.keyboard('{ArrowRight}');
    expect(screen.getByRole('tab', { name: 'Second' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: 'Second' })).toHaveFocus();

    await user.keyboard('{ArrowLeft}{ArrowLeft}');
    expect(screen.getByRole('tab', { name: 'Third' })).toHaveAttribute('aria-selected', 'true');
    await user.keyboard('{Home}');
    expect(screen.getByRole('tabpanel', { name: 'First' })).toBeVisible();
  });
});

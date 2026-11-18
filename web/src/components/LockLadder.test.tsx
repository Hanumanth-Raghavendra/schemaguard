import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { LockLadder } from './LockLadder.tsx';

describe('LockLadder', () => {
  it('shows the lock as a step on the eight-step scale with what it blocks', () => {
    render(<LockLadder lock="SHARE" effect="blocks writes, reads continue" />);
    expect(screen.getByRole('img', { name: "SHARE lock, 5 of 8 on PostgreSQL's lock scale" })).toBeInTheDocument();
    expect(screen.getByText(': blocks writes, reads continue')).toBeInTheDocument();
    const segments = screen.getByRole('img').querySelectorAll('span');
    expect([...segments].filter((segment) => segment.className.includes('bg-medium'))).toHaveLength(5);
  });

  it('colours ACCESS EXCLUSIVE as stopping all traffic', () => {
    render(<LockLadder lock="ACCESS EXCLUSIVE" effect={null} />);
    const segments = screen.getByRole('img').querySelectorAll('span');
    expect([...segments].every((segment) => segment.className.includes('bg-critical'))).toBe(true);
  });

  it('says so when there is no table lock', () => {
    render(<LockLadder lock={null} effect={null} />);
    expect(screen.getByText('No table lock')).toBeInTheDocument();
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });
});

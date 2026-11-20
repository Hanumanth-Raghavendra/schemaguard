import { useEffect, useState } from 'react';
import type { Change, PlanResponse, RenameDto, Route } from '../api/client.ts';
import { describeWork } from '../lock.ts';
import { renameKey } from '../renames.ts';
import { LockLadder } from './LockLadder.tsx';

interface Props {
  plan: PlanResponse;
  route: Route;
  onRouteChange: (route: Route) => void;
  candidates: Change[];
  confirmed: ReadonlySet<string>;
  onToggleRename: (rename: RenameDto) => void;
  planning: boolean;
  /** Opens the plan's SQL in "Check a migration", against the current schema. */
  onCheckPlan: () => void;
}

const ROUTES: { value: Route; label: string; hint: string }[] = [
  { value: 'SAFE', label: 'Safe', hint: 'Avoids long locks: concurrent indexes, NOT VALID then VALIDATE, and so on' },
  { value: 'DIRECT', label: 'Direct', hint: 'The plain statements, for an empty or offline database' },
];

/** The migration that turns the current schema into the new one, step by step. */
export function PlanView({ plan, route, onRouteChange, candidates, confirmed, onToggleRename, planning, onCheckPlan }: Props) {
  const [copied, setCopied] = useState(false);
  useEffect(() => {
    if (!copied) return;
    const timer = setTimeout(() => {
      setCopied(false);
    }, 2000);
    return () => {
      clearTimeout(timer);
    };
  }, [copied]);

  return (
    <div className="space-y-6" aria-busy={planning}>
      <fieldset className="space-y-2">
        <legend className="text-sm font-semibold">Route</legend>
        <div className="flex flex-wrap gap-4">
          {ROUTES.map((option) => (
            <label key={option.value} className="flex max-w-sm items-start gap-2 text-sm">
              <input
                type="radio"
                name="route"
                value={option.value}
                checked={route === option.value}
                onChange={() => {
                  onRouteChange(option.value);
                }}
                className="mt-1 accent-elephant"
              />
              <span>
                <span className="font-medium">{option.label}</span>
                <span className="block text-muted">{option.hint}</span>
              </span>
            </label>
          ))}
        </div>
      </fieldset>

      {candidates.length > 0 && (
        <fieldset className="space-y-2">
          <legend className="text-sm font-semibold">Possible renames</legend>
          <p className="text-sm text-muted">
            A rename keeps the data; without one the plan drops the old name and adds the new one. Tick the renames that
            are real.
          </p>
          {candidates.map((change) => {
            if (change.rename === null) return null;
            const rename = change.rename;
            return (
              <label key={renameKey(rename)} className="flex items-start gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={confirmed.has(renameKey(rename))}
                  onChange={() => {
                    onToggleRename(rename);
                  }}
                  className="mt-1 accent-elephant"
                />
                <code className="font-mono">{change.summary}</code>
              </label>
            );
          })}
        </fieldset>
      )}

      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm">
          {plan.steps.length === 1 ? '1 step' : `${plan.steps.length} steps`}
          {plan.complete ? ', reaches the new schema' : ', does not reach the new schema (see below)'}
          {plan.needsDecisions && ', some steps need a decision'}
        </p>
        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            disabled={plan.sql === ''}
            onClick={onCheckPlan}
            className="rounded-md border border-line bg-surface px-3 py-1.5 text-sm font-medium hover:border-elephant disabled:opacity-50"
          >
            Check this plan as a migration
          </button>
          <button
            type="button"
            disabled={plan.sql === ''}
            onClick={() => {
              void navigator.clipboard.writeText(plan.sql).then(() => {
                setCopied(true);
              });
            }}
            className="rounded-md border border-line bg-surface px-3 py-1.5 text-sm font-medium hover:border-elephant disabled:opacity-50"
          >
            {copied ? 'Copied' : 'Copy script'}
          </button>
        </div>
      </div>

      {plan.steps.length === 0 ? (
        <p className="text-sm text-muted">Nothing to run: the schemas are the same.</p>
      ) : (
        <ol className="space-y-4">
          {plan.steps.map((step, index) => (
            <li key={index} className="grid grid-cols-[2rem_1fr] gap-x-2">
              <span className="pt-2 text-right text-sm tabular-nums text-muted">{index + 1}</span>
              <div className="min-w-0 space-y-2">
                <pre className="overflow-x-auto whitespace-pre-wrap break-words rounded-md bg-elephant-soft px-3 py-2 font-mono text-sm">{step.sql}</pre>
                <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
                  {step.operation !== null && (
                    <>
                      <LockLadder lock={step.operation.lock} effect={step.operation.lockEffect} />
                      <span className="text-sm text-muted">{describeWork(step.operation.work)}</span>
                    </>
                  )}
                  {!step.transactional && <span className="text-sm text-medium">Runs outside a transaction</span>}
                </div>
                {step.note !== '' && <p className="text-sm text-muted">{step.note}</p>}
                {step.cautions.map((caution) => (
                  <p key={caution} className="border-l-2 border-medium pl-2 text-sm">
                    {caution}
                  </p>
                ))}
              </div>
            </li>
          ))}
        </ol>
      )}

      {plan.unplanned.length > 0 && (
        <section className="space-y-2">
          <h3 className="text-sm font-semibold">Not in the plan</h3>
          <ul className="space-y-1 text-sm">
            {plan.unplanned.map((item) => (
              <li key={item.change}>
                <code className="font-mono">{item.change}</code>
                <span className="text-muted">: {item.reason}</span>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}

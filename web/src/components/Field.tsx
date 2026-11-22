import { useId, useState, type InputHTMLAttributes } from 'react';

const INPUT =
  'w-full rounded-md border border-line bg-surface px-3 py-2 text-ink placeholder:text-muted focus-visible:border-elephant';

interface FieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'id' | 'className'> {
  label: string;
  hint?: string;
}

/** A labelled text input, with an optional hint under it. */
export function TextField({ label, hint, ...input }: FieldProps) {
  const id = useId();
  return (
    <div className="space-y-1.5">
      <label htmlFor={id} className="block text-sm font-semibold">
        {label}
      </label>
      <input id={id} className={INPUT} aria-describedby={hint ? `${id}-hint` : undefined} {...input} />
      {hint && (
        <p id={`${id}-hint`} className="text-sm text-muted">
          {hint}
        </p>
      )}
    </div>
  );
}

/** A password input with a button that shows what was typed, so a long passphrase can be checked. */
export function PasswordField({ label, hint, ...input }: Omit<FieldProps, 'type'>) {
  const id = useId();
  const [visible, setVisible] = useState(false);
  return (
    <div className="space-y-1.5">
      <label htmlFor={id} className="block text-sm font-semibold">
        {label}
      </label>
      <div className="relative">
        <input
          id={id}
          type={visible ? 'text' : 'password'}
          className={`${INPUT} pr-16`}
          aria-describedby={hint ? `${id}-hint` : undefined}
          {...input}
        />
        <button
          type="button"
          className="absolute inset-y-1 right-1 rounded px-2 text-sm font-medium text-muted hover:text-ink"
          aria-pressed={visible}
          aria-label={visible ? 'Hide password' : 'Show password'}
          onClick={() => {
            setVisible((shown) => !shown);
          }}
        >
          {visible ? 'Hide' : 'Show'}
        </button>
      </div>
      {hint && (
        <p id={`${id}-hint`} className="text-sm text-muted">
          {hint}
        </p>
      )}
    </div>
  );
}

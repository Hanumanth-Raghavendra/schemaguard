import { useEffect, useState } from 'react';
import { ApiError, type SavedAnalysis } from '../api/client.ts';
import { formatDate } from '../dates.ts';
import { rememberToken } from '../tokens.ts';

const SHARE_ERRORS: Record<number, string> = {
  0: 'The server cannot be reached, so the link was not made. Try again.',
  413: 'This analysis is too large to save.',
  503: 'Sharing is switched off on this server.',
  507: 'The server has no room for new links right now. Try again later.',
};

interface Props {
  /** Saves the analysis; absent where it is already saved. */
  save?: () => Promise<SavedAnalysis>;
  onMarkdown: () => void;
  onJson: () => void;
}

/** Share by link, and download the result as Markdown or JSON. */
export function ResultActions({ save, onMarkdown, onJson }: Props) {
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState<SavedAnalysis | null>(null);
  const [error, setError] = useState<string | null>(null);
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

  async function share(run: () => Promise<SavedAnalysis>) {
    setSaving(true);
    setError(null);
    try {
      const result = await run();
      rememberToken(result.id, result.deleteToken, result.expiresAt);
      setSaved(result);
    } catch (cause) {
      const status = cause instanceof ApiError ? cause.status : 0;
      setError(SHARE_ERRORS[status] ?? (cause instanceof Error ? cause.message : 'The link was not made.'));
    } finally {
      setSaving(false);
    }
  }

  const link = saved === null ? null : `${window.location.origin}/a/${saved.id}`;
  const button = 'rounded-md border border-line bg-surface px-3 py-1.5 text-sm font-medium hover:border-elephant disabled:opacity-50';

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap gap-2">
        {save && link === null && (
          <button
            type="button"
            disabled={saving}
            onClick={() => {
              void share(save);
            }}
            className={button}
          >
            {saving ? 'Making a link…' : 'Share'}
          </button>
        )}
        <button type="button" onClick={onMarkdown} className={button}>
          Download Markdown
        </button>
        <button type="button" onClick={onJson} className={button}>
          Download JSON
        </button>
      </div>
      {error !== null && (
        <p role="alert" className="text-sm text-critical">
          {error}
        </p>
      )}
      {saved !== null && link !== null && (
        <div className="space-y-1.5">
          <div className="flex flex-wrap items-center gap-2">
            <label className="sr-only" htmlFor="share-link">
              Link to this analysis
            </label>
            <input
              id="share-link"
              readOnly
              value={link}
              onFocus={(event) => {
                event.target.select();
              }}
              className="min-w-0 flex-1 rounded-md border border-line bg-paper px-3 py-1.5 font-mono text-sm"
            />
            <button
              type="button"
              onClick={() => {
                void navigator.clipboard.writeText(link).then(() => {
                  setCopied(true);
                });
              }}
              className={button}
            >
              {copied ? 'Copied' : 'Copy link'}
            </button>
          </div>
          <p className="text-sm text-muted">
            Anyone with the link can see the inputs and the result until {formatDate(saved.expiresAt)}. You can delete it
            from this browser on the link's page.
          </p>
        </div>
      )}
    </div>
  );
}

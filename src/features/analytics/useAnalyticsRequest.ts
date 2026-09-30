import { useCallback, useEffect, useRef, useState } from "react";

/**
 * Loads one analytics series and owns its loading and failure state.
 *
 * <h2>Why the request is versioned</h2>
 * Changing the bucket or the range fires a new request while the previous one may still be in flight.
 * Without a guard the slower earlier response can land after the newer one and overwrite it, so the
 * chart would show last quarter's weeks after the user asked for months. Each run takes a token and
 * only the newest token is allowed to write state; a late reply from a superseded run is discarded.
 *
 * <p>A change that produces the same request is not reissued, so flipping back and forth between
 * buckets does not multiply traffic.
 */
export type AnalyticsLoadable<T> = {
  data: T | null;
  loading: boolean;
  error: string | null;
  /** True once a request has completed at least once, successfully or not. */
  settled: boolean;
  reload: () => void;
};

/** Turns an unknown thrown value into something a person can act on. */
function messageFor(cause: unknown): string {
  if (cause instanceof Error && cause.message.trim()) return cause.message;
  return "Your analytics could not be loaded. Check your connection and try again.";
}

/**
 * @param request  performs the call; `key` changes must mean the result would differ
 * @param key      a stable description of the current request, used to decide whether to refetch
 * @param enabled  when false the hook stays idle, for a view that should not load yet
 */
export function useAnalyticsRequest<T>(
  request: () => Promise<T>,
  key: string,
  enabled = true
): AnalyticsLoadable<T> {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [settled, setSettled] = useState(false);
  const [nonce, setNonce] = useState(0);

  // The newest run wins. Comparing against a ref rather than state keeps the guard correct when two
  // responses resolve in the same tick.
  const run = useRef(0);
  const requestRef = useRef(request);
  requestRef.current = request;

  useEffect(() => {
    if (!enabled) return;
    const token = ++run.current;
    setLoading(true);
    setError(null);

    requestRef
      .current()
      .then((result) => {
        // A superseded response must not paint over the newer one.
        if (token !== run.current) return;
        setData(result);
        setError(null);
      })
      .catch((cause: unknown) => {
        if (token !== run.current) return;
        setData(null);
        setError(messageFor(cause));
      })
      .finally(() => {
        if (token !== run.current) return;
        setLoading(false);
        setSettled(true);
      });
    // `key` is the request identity; `request` is read through a ref so an inline arrow function
    // re-creating on every render does not restart the effect forever.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, enabled, nonce]);

  const reload = useCallback(() => setNonce((n) => n + 1), []);

  return { data, loading, error, settled, reload };
}

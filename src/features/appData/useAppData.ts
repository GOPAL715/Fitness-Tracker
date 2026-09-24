import { useCallback, useEffect, useMemo, useState } from "react";
import { useAuth } from "../../lib/auth";
import { todayISO } from "../../lib/utils";
import type { DailyMetric } from "../../lib/domain";
import { addWater, fetchAppData, needsOnboarding, EMPTY_APP_DATA, type AppData } from "./appData";

export type UseAppData = AppData & {
  loading: boolean;
  error: string | null;
  needsOnboarding: boolean;
  completeOnboarding: () => void;
  todayMetric: DailyMetric | null;
  /** The most recent 28 days, used by the readiness and insight calculations. */
  recent: DailyMetric[];
  /** Re-reads everything for the signed-in user. */
  reload: () => Promise<void>;
  logWater: (oz: number) => Promise<void>;
};

/**
 * Owns the application's loading lifecycle and the data every view renders.
 *
 * Loading, error and onboarding state all live together here because they are
 * driven by the same fetch, and separating them would spread one concern across
 * several files.
 */
export function useAppData(): UseAppData {
  const { user } = useAuth();

  const [data, setData] = useState<AppData>(EMPTY_APP_DATA);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [hasOnboarded, setHasOnboarded] = useState(true);

  const reload = useCallback(async () => {
    if (!user) return;
    setLoading(true);
    setError(null);

    try {
      const next = await fetchAppData(user.id);
      setData(next);
      setHasOnboarded(!needsOnboarding(next.profile));
    } catch {
      setError("FitTrack could not load your data. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }, [user]);

  useEffect(() => {
    reload();
  }, [reload]);

  const todayMetric = useMemo(
    () => data.metrics.find((m) => m.metric_date === todayISO()) ?? null,
    [data.metrics]
  );
  const recent = useMemo(() => data.metrics.slice(0, 28), [data.metrics]);

  const logWater = useCallback(
    async (oz: number) => {
      if (!user) return;
      await addWater(user.id, todayMetric, oz);
      await reload();
    },
    [user, todayMetric, reload]
  );

  return {
    ...data,
    loading,
    error,
    needsOnboarding: !hasOnboarded,
    completeOnboarding: () => setHasOnboarded(true),
    todayMetric,
    recent,
    reload,
    logWater,
  };
}



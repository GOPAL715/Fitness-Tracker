/**
 * Live sync status for the header indicator.
 *
 * <p>Re-reads the queue on mount, on reconnect and whenever the queue changes, so the indicator
 * reflects reality without polling the server.
 */
import { useCallback, useEffect, useState } from "react";
import { currentStatus, type SyncStatus } from "./syncStatus";
import { syncWorkoutSessions } from "./workoutSync";

const ONLINE: SyncStatus = { state: "online", pending: 0, failed: 0, conflicts: 0 };

export function useSyncStatus(): { status: SyncStatus; refresh: () => Promise<void> } {
  const [status, setStatus] = useState<SyncStatus>(ONLINE);
  const [syncing, setSyncing] = useState(false);

  const refresh = useCallback(async () => {
    setStatus(await currentStatus(syncing));
  }, [syncing]);

  useEffect(() => {
    void refresh();
    const onOnline = async () => {
      // Connectivity returned: drain the queue, then refresh the indicator.
      setSyncing(true);
      try {
        await syncWorkoutSessions();
      } finally {
        setSyncing(false);
        setStatus(await currentStatus(false));
      }
    };
    const onOffline = async () => setStatus(await currentStatus(false));
    window.addEventListener("online", onOnline);
    window.addEventListener("offline", onOffline);
    return () => {
      window.removeEventListener("online", onOnline);
      window.removeEventListener("offline", onOffline);
    };
  }, []);

  return { status, refresh };
}

package com.fittrack.health;

import java.util.Locale;
import java.util.Set;

/**
 * The permission states a Health Connect device may report, and what they are allowed to mean (D13).
 *
 * <h2>These are UX states, not security states</h2>
 * The server <b>structurally cannot</b> verify Android Health Connect permissions. Permissions are
 * granted on the handset, and only the native app can observe them; there is no server-side API that
 * reports another app's grants. So {@link #PERMISSION_REQUIRED} and {@link #PERMISSION_REVOKED} are
 * <b>client claims</b>: useful for showing the user an honest message on the web, and nothing more.
 *
 * <p>They are never consulted to authorize anything. Nothing in the authentication, authorization, or
 * device-ownership path reads this state, it cannot grant access to a record, and it cannot deny one
 * either. A device reporting {@code permission_revoked} is not locked out: the bridge simply stops
 * sending records, and the user's already-imported history is untouched. Presenting a client claim as
 * if the server had verified it would be the actual security failure here, so every consumer is
 * expected to label these states as device-reported.
 *
 * <p>{@link #CONNECTED}, {@link #SYNCING} and {@link #SYNC_FAILED} <em>are</em> server-observable: they
 * derive from FitTrack's own sync state. The set is modelled in one place so the distinction is made
 * once rather than re-derived by each caller.
 */
public final class HealthConnectPermissions {

    /** The bridge has permissions and has synced. Server-observable. */
    public static final String CONNECTED = "connected";

    /** The user removed the connection. Server-observable. */
    public static final String DISCONNECTED = "disconnected";

    /** A sync is in progress. Server-observable. */
    public static final String SYNCING = "syncing";

    /** The last sync failed. Server-observable; the reason is a stable category, never a raw message. */
    public static final String SYNC_FAILED = "sync_failed";

    /** CLIENT-REPORTED. The bridge believes Health Connect permissions are missing. Not verified. */
    public static final String PERMISSION_REQUIRED = "permission_required";

    /** CLIENT-REPORTED. The bridge believes permissions were revoked. Not verified. */
    public static final String PERMISSION_REVOKED = "permission_revoked";

    /** The states a client may report. Anything else is dropped rather than stored. */
    private static final Set<String> REPORTABLE =
            Set.of(CONNECTED, PERMISSION_REQUIRED, PERMISSION_REVOKED);

    /** The states the server can establish on its own. */
    private static final Set<String> SERVER_OBSERVABLE =
            Set.of(CONNECTED, DISCONNECTED, SYNCING, SYNC_FAILED);

    private HealthConnectPermissions() {
    }

    /**
     * Canonicalises a client-reported permission state.
     *
     * @return the stored form, or null when the value is absent or not a recognised state
     */
    public static String canonical(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return REPORTABLE.contains(normalized) ? normalized : null;
    }

    /** True when the state is one only the client can know, and must be labelled as such in the UI. */
    public static boolean isClientReported(String value) {
        return PERMISSION_REQUIRED.equals(value) || PERMISSION_REVOKED.equals(value);
    }

    /** True when FitTrack can establish this state itself, without trusting the client. */
    public static boolean isServerObservable(String value) {
        return SERVER_OBSERVABLE.contains(value);
    }

    /** Every state the UI may need to render. */
    public static Set<String> allStates() {
        return Set.of(CONNECTED, DISCONNECTED, SYNCING, SYNC_FAILED,
                PERMISSION_REQUIRED, PERMISSION_REVOKED);
    }
}

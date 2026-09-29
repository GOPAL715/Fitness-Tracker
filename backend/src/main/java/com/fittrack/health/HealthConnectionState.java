package com.fittrack.health;

import java.util.Locale;

/**
 * The one state a user sees for a provider connection, derived from what is already stored.
 *
 * <h2>Why this exists</h2>
 * The product describes a connection in five states: disconnected, connecting, connected, syncing and
 * sync failed. Nothing in the schema said so. {@code health_devices.status} is a single
 * {@code varchar} that only ever holds the literal {@code "Connected"} the registration route
 * writes, and {@code sync_status} separately holds {@code idle | syncing | synced | error}. A client
 * therefore had to invent a state machine from two loosely related columns, and two clients would
 * not have invented the same one.
 *
 * <h2>Derivation, and why "connecting" is not a new column</h2>
 * <pre>
 *   no row for this provider        -> disconnected
 *   sync_status = idle              -> connecting     (registered, not yet established)
 *   sync_status = syncing           -> syncing
 *   sync_status = synced            -> connected
 *   sync_status = error             -> sync_failed
 * </pre>
 *
 * <p>{@code connecting} is the state a connection is genuinely in between being registered and being
 * usable: it exists, it is owned by the user, and it has never produced data. That is precisely what
 * {@code HealthDeviceResponse.awaitingFirstSync} already means and precisely what the existing UI
 * renders as "Connected, not synced yet". Naming it {@code connecting} adds no new persistence - it
 * gives an existing, already-tested fact a name the API contract can state.
 *
 * <h2>Unknown values resolve to a failure, never to success</h2>
 * A {@code sync_status} this class does not recognise, including a blank one, maps to
 * {@link #SYNC_FAILED}. A connection whose last known state cannot be read is not evidence that
 * anything worked, and defaulting an unknown value to {@code connected} would show a person a green
 * badge on the strength of a value the server does not understand. The stored column is unchanged and
 * still holds whatever it holds; only the reported state is conservative.
 */
public final class HealthConnectionState {

    /** No connection row exists for this provider. */
    public static final String DISCONNECTED = "disconnected";

    /** Registered, but no sync has ever succeeded for it. */
    public static final String CONNECTING = "connecting";

    /** Connected and the last sync succeeded. */
    public static final String CONNECTED = "connected";

    /** A sync pass is running right now. */
    public static final String SYNCING = "syncing";

    /** The last sync pass failed, or its outcome cannot be read. */
    public static final String SYNC_FAILED = "sync_failed";

    /** The stored {@code sync_status} value that means a connection has never synced. */
    public static final String SYNC_STATUS_IDLE = "idle";

    /** The stored {@code sync_status} value that means a sync pass is running. */
    public static final String SYNC_STATUS_SYNCING = "syncing";

    /** The stored {@code sync_status} value that means the last pass succeeded. */
    public static final String SYNC_STATUS_SYNCED = "synced";

    /** The stored {@code sync_status} value that means the last pass failed. */
    public static final String SYNC_STATUS_ERROR = "error";

    private HealthConnectionState() {
    }

    /**
     * The reported state for a connection.
     *
     * @param syncStatus the stored {@code health_devices.sync_status}, or null when there is no row
     * @return one of the five states above; never null and never an empty string
     */
    public static String of(String syncStatus) {
        if (syncStatus == null || syncStatus.isBlank()) {
            return DISCONNECTED;
        }
        return switch (syncStatus.trim().toLowerCase(Locale.ROOT)) {
            case SYNC_STATUS_SYNCING -> SYNCING;
            case SYNC_STATUS_SYNCED -> CONNECTED;
            case SYNC_STATUS_ERROR -> SYNC_FAILED;
            // Idle means the row exists but has never completed a pass, which is the connecting
            // state. A row that is present but idle is not yet a working connection.
            case SYNC_STATUS_IDLE -> CONNECTING;
            // Anything else, including a value written by a future version this build predates.
            default -> SYNC_FAILED;
        };
    }

    /**
     * The state for a provider that may have several connections.
     *
     * <p>One provider can legitimately have more than one row - two watches, or a watch and a scale.
     * The reported state is the one a person most needs to act on, and the priority below is that
     * order: a failure is reported in preference to a healthy sibling, because someone looking at this
     * screen needs to know what is broken before what works.
     *
     * <p>The priority is evaluated as an explicit maximum rather than by "first one wins", so the
     * result does not depend on the order the rows came back in. The controller groups by an ORDER BY
     * rather than by anything meaningful, and a badge that changed with row order would be reporting
     * the database's mood rather than the connection's.
     *
     * <p>With no connections at all the provider is simply disconnected.
     *
     * @param states the state of each connection, in any order
     * @return the single state to report for the provider
     */
    public static String aggregate(Iterable<String> states) {
        String reported = DISCONNECTED;
        for (String state : states) {
            if (state == null) {
                continue;
            }
            if (SYNC_FAILED.equals(state)) {
                return SYNC_FAILED;
            }
            if (priority(state) > priority(reported)) {
                reported = state;
            }
        }
        return reported;
    }

    /**
     * The precedence of a state, higher winning.
     *
     * <p>A registered-but-unsynced connection outranks a fully synced one: it is the one the user has
     * not finished setting up, so it is the one they most need to be told about.
     */
    private static int priority(String state) {
        if (SYNCING.equals(state)) {
            return 4;
        }
        if (CONNECTING.equals(state)) {
            return 3;
        }
        if (CONNECTED.equals(state)) {
            return 2;
        }
        return 0;
    }

    /** Every state a client may be asked to render. */
    public static java.util.Set<String> allStates() {
        return java.util.Set.of(DISCONNECTED, CONNECTING, CONNECTED, SYNCING, SYNC_FAILED);
    }

    /** True when a value is one this build knows how to interpret. */
    public static boolean isKnown(String state) {
        return state != null && allStates().contains(state);
    }
}

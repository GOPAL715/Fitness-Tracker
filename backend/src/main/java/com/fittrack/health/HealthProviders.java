package com.fittrack.health;

import java.util.Set;

/**
 * The set of health integrations this build recognises.
 *
 * <h2>What a provider value is, and is not</h2>
 * A {@code health_devices.provider} value is a <b>declared integration type</b>: it says which
 * pipeline a connection is attributed to. It is <b>not</b> proof that the user owns an account with
 * any external provider, and it is not an attestation. No such verification exists yet, because no
 * provider integration performs an OAuth exchange, so there is nothing to attest against.
 *
 * <p>When a real adapter lands it becomes authoritative: the adapter will establish the provider
 * from the credential it was issued, and the client-supplied value will only select which pipeline
 * to run. Until then, accepting an arbitrary string would let a client label a connection as any
 * provider it liked and would let a typo create a permanently unattributable row, so the value is
 * validated against this list instead.
 *
 * <p>The list mirrors the set of values {@code health_source_priority} ranks, so the two cannot drift.
 * That function ranks by <b>lower number wins</b>: health-connect, then fitbit, then garmin, then
 * apple-health, then any unknown source, with manual ranked last of all.
 */
public final class HealthProviders {

    /** Integrations the backend can attribute data to. */
    public static final Set<String> SUPPORTED = Set.of(
            "health-connect",
            "fitbit",
            "garmin",
            "apple-health",
            "manual",
            /**
             * The key the in-process test provider registers under.
             *
             * <p>It is a real, recognised value rather than a wildcard, so a test connection is
             * attributed consistently and ranks last among device sources. It reaches no external
             * service: the only implementation of that key is the test double, so a row created with
             * it can never be confused with data from a genuine provider.
             */
            "fake-wearable");

    /**
     * The provider used when a caller does not name one.
     *
     * <p>Resolved server-side from the configured provider, so a client cannot make the server
     * default to something it chose.
     */
    public static final String FALLBACK = "manual";

    private HealthProviders() {
    }

    public static boolean isSupported(String provider) {
        return provider != null && SUPPORTED.contains(provider.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * The canonical stored form of a provider value.
     *
     * @return the lower-cased provider, or null when it is not a supported integration
     */
    public static String canonical(String provider) {
        if (provider == null) return null;
        String trimmed = provider.trim().toLowerCase(java.util.Locale.ROOT);
        return SUPPORTED.contains(trimmed) ? trimmed : null;
    }
}

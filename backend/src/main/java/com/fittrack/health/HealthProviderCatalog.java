package com.fittrack.health;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The server-authoritative description of every health provider this build knows.
 *
 * <h2>Why this exists</h2>
 * The provider list used to live only in the browser, in {@code src/lib/healthProviders.ts}. The
 * backend separately held an allowlist of provider <em>keys</em> in {@link HealthProviders}, so the
 * two were free to drift: the browser's catalogue omitted the in-process test key, and nothing
 * asserted the sets were the same. A client could therefore be shown a provider the server would
 * refuse, or omit one it accepts. This class closes that by making the server the only place a
 * provider is described, and by letting a client read that description instead of hardcoding it.
 *
 * <h2>How it relates to {@link HealthProviders}</h2>
 * The two have deliberately different jobs and neither replaces the other.
 * <ul>
 *   <li>{@link HealthProviders#SUPPORTED} is the <b>validation authority</b>: the allowlist a
 *       connection's {@code provider} column is checked against. It is what makes an arbitrary
 *       string a 400 rather than an unattributable row.</li>
 *   <li>This catalogue is the <b>descriptive authority</b>: what a provider is called, whether it can
 *       be reached from this deployment, and what it would take. It is what the API serves.</li>
 * </ul>
 * {@link #disagreementWith(Iterable)} is a unit-test-enforced invariant that the two cover exactly
 * the same keys, so neither can acquire a provider the other has not heard of.
 *
 * <h2>Availability, stated honestly</h2>
 * FitTrack ships no real wearable integration. The availability values say what each provider would
 * actually require, and no descriptor claims a capability that does not exist here. A provider whose
 * only path is a native app says so; a provider that needs a registered OAuth application says that
 * too. Nothing reports {@code available}, because nothing is.
 *
 * <h2>Credentials</h2>
 * {@code credentialModel} is {@code "none"} for every provider in this build, and that is a fact
 * rather than an omission. No adapter performs an OAuth exchange, so FitTrack holds no provider
 * access token, no refresh token and no client secret, and there is no column to hold one. The
 * registration route continues to reject those fields outright. A descriptor may only claim a
 * credential model once a real adapter and its encrypted storage both exist; the field exists so
 * that day is a data change rather than a contract break.
 */
public final class HealthProviderCatalog {

    /** How a provider can be reached from a FitTrack deployment, in wire form. */
    public enum Availability {
        /** A separate native app reads the platform and pushes into FitTrack's own ingest route. */
        NATIVE_BRIDGE("native_bridge"),
        /** Reachable over the web, but only through a server-side OAuth application FitTrack does not ship. */
        SERVER_CREDENTIALS_REQUIRED("server_credentials_required"),
        /** No web API at all; reachable only from a native application on the user's own device. */
        NATIVE_APP_REQUIRED("native_app_required"),
        /** Entered by the user inside FitTrack. Always available. */
        MANUAL("manual"),
        /** The deterministic in-process test double. Never offered to a client. */
        TEST_ONLY("test_only");

        private final String wire;

        Availability(String wire) {
            this.wire = wire;
        }

        /** The stable value served over the API. */
        public String wire() {
            return wire;
        }
    }

    /**
     * One provider, as this build understands it.
     *
     * @param key              the value stored in {@code health_devices.provider}
     * @param label            the name shown to a person
     * @param availability     what reaching this provider actually requires
     * @param authModel        how the connection is established, in plain words
     * @param credentialModel  what credential FitTrack would hold; {@code "none"} throughout Phase 20
     * @param supportedMetrics the canonical metric names the existing pipeline can store today
     * @param connectable      whether a connection row can be created for this provider in this build
     * @param clientVisible    whether this provider may be offered to a browser client
     * @param boundary         the honest explanation of what this provider would need
     */
    /**
     * One provider, as this build understands it.
     *
     * @param key              the value stored in {@code health_devices.provider}
     * @param label            the name shown to a person
     * @param availability     what reaching this provider actually requires
     * @param authModel        how the connection is established, in plain words
     * @param credentialModel  what credential FitTrack would hold; {@code "none"} throughout Phase 20
     * @param supportedMetrics the canonical metric names the existing pipeline can store today
     * @param connectable      whether a connection row can be created for this provider in this build
     * @param clientVisible    whether this provider may be offered to a browser client
     * @param boundary         the honest explanation of what this provider would need
     */
    public record ProviderDescriptor(String key,
                                     String label,
                                     Availability availability,
                                     String authModel,
                                     String credentialModel,
                                     List<String> supportedMetrics,
                                     boolean connectable,
                                     boolean clientVisible,
                                     String boundary) {
        public ProviderDescriptor {
            supportedMetrics = supportedMetrics == null ? List.of() : List.copyOf(supportedMetrics);
        }
    }


    /**
     * The metric names the existing persistence layer can actually store.
     *
     * <p>These are the canonical column names, not a provider's own vocabulary, because the pipeline
     * normalises into them exactly once. Listing anything else here would advertise a metric the
     * backend has nowhere to put.
     */
    private static final List<String> ACTIVITY_AND_BODY_METRICS = List.of(
            "steps", "active_minutes", "calories_burned", "weight_lb", "body_fat_pct");

    /**
     * The metrics the Health Connect push contract accepts.
     *
     * <p>Narrower than the pull contract on purpose: {@code HealthConnectRecordType} is four types,
     * and a catalogue claiming the rest would be describing a bridge that does not exist.
     */
    private static final List<String> HEALTH_CONNECT_METRICS = List.of(
            "steps", "active_calories", "weight", "body_fat");

    private static final String NO_CREDENTIAL = "none";

    private static final Map<String, ProviderDescriptor> BY_KEY = build();

    private static Map<String, ProviderDescriptor> build() {
        java.util.LinkedHashMap<String, ProviderDescriptor> map = new java.util.LinkedHashMap<>();
        put(map, new ProviderDescriptor(
                HealthProviders.HEALTH_CONNECT,
                "Android Health Connect",
                Availability.NATIVE_BRIDGE,
                "A separate Android app on your phone, using your existing FitTrack sign-in",
                NO_CREDENTIAL,
                HEALTH_CONNECT_METRICS,
                true,
                true,
                "Health Connect is an Android-native, on-device API, so it cannot be connected from a"
                        + " browser. A separate FitTrack Android app reads it and pushes steps, active"
                        + " calories, weight and body fat to FitTrack using your existing account. The"
                        + " server half of that path is ready; the Android app is a separate project and"
                        + " is not part of this one."));
        put(map, new ProviderDescriptor(
                "fitbit",
                "Fitbit",
                Availability.SERVER_CREDENTIALS_REQUIRED,
                "An OAuth application registered with Fitbit, held server-side",
                NO_CREDENTIAL,
                ACTIVITY_AND_BODY_METRICS,
                false,
                true,
                "Fitbit is reachable over the web, but only through an OAuth application registered"
                        + " with Fitbit. The client secret must never reach a browser, so the token"
                        + " exchange and the API calls would have to run server-side. FitTrack ships no"
                        + " such application, so no data can be imported from Fitbit today."));
        put(map, new ProviderDescriptor(
                "garmin",
                "Garmin",
                Availability.SERVER_CREDENTIALS_REQUIRED,
                "An OAuth application registered with Garmin Connect, held server-side",
                NO_CREDENTIAL,
                ACTIVITY_AND_BODY_METRICS,
                false,
                true,
                "Garmin Connect exposes data through an OAuth application. As with Fitbit, the"
                        + " credentials and token exchange would have to stay on the server. FitTrack"
                        + " ships no such application, so no data can be imported from Garmin today."));
        put(map, new ProviderDescriptor(
                "apple-health",
                "Apple Health",
                Availability.NATIVE_APP_REQUIRED,
                "A native iOS app using HealthKit",
                NO_CREDENTIAL,
                ACTIVITY_AND_BODY_METRICS,
                false,
                true,
                "Apple Health has no web API. Reading it requires a native iOS app using HealthKit, so"
                        + " it cannot be connected from a browser at all. If FitTrack is ever wrapped in"
                        + " a native shell, that shell would use the same ingest contract."));
        put(map, new ProviderDescriptor(
                HealthProviders.FALLBACK,
                "Manual entry",
                Availability.MANUAL,
                "Nothing to connect: values are typed into FitTrack",
                NO_CREDENTIAL,
                ACTIVITY_AND_BODY_METRICS,
                true,
                true,
                "Entered by hand in FitTrack. Always available, and the fallback a reader falls back"
                        + " to when no device measurement exists for a day."));
        put(map, new ProviderDescriptor(
                "fake-wearable",
                "Test provider",
                Availability.TEST_ONLY,
                "Not applicable: this provider exists only inside the test suite",
                NO_CREDENTIAL,
                ACTIVITY_AND_BODY_METRICS,
                true,
                // Deliberately not client-visible. The key stays in HealthProviders.SUPPORTED so the
                // in-process double produces consistently attributed rows, but a browser must never be
                // offered a provider that reaches no external service.
                false,
                "A deterministic stand-in used by the automated test suite. It reaches no external"
                        + " service and is never offered to a client."));
        return java.util.Collections.unmodifiableMap(map);
    }

    private static void put(Map<String, ProviderDescriptor> map, ProviderDescriptor descriptor) {
        map.put(descriptor.key(), descriptor);
    }

    private HealthProviderCatalog() {
    }

    /** Every descriptor, in a stable order suitable for serving directly. */
    public static List<ProviderDescriptor> all() {
        return List.copyOf(BY_KEY.values());
    }

    /** The descriptors a browser client may be shown. */
    public static List<ProviderDescriptor> clientVisible() {
        return all().stream().filter(ProviderDescriptor::clientVisible).toList();
    }

    /** The descriptor for a provider key, or empty when this build does not recognise it. */
    public static Optional<ProviderDescriptor> find(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_KEY.get(key.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * The keys on which the catalogue and the allowlist disagree.
     *
     * <p>Called by a unit test rather than at runtime, because a mismatch is a build-time defect,
     * not a request-time condition. Throwing here in production would take down a working endpoint
     * over a descriptive inconsistency, which is the opposite of what this guard is for.
     *
     * @param allowed the allowlist to compare against, normally {@code HealthProviders.SUPPORTED}
     * @return every key present in exactly one of the two sets; empty when they agree exactly
     */
    public static java.util.Set<String> disagreementWith(Iterable<String> allowed) {
        java.util.Set<String> allowedSet = new java.util.LinkedHashSet<>();
        for (String key : allowed) {
            allowedSet.add(key);
        }
        java.util.Set<String> described = new java.util.LinkedHashSet<>(BY_KEY.keySet());
        java.util.Set<String> onlyAllowed = new java.util.LinkedHashSet<>(allowedSet);
        onlyAllowed.removeAll(described);
        java.util.Set<String> onlyDescribed = new java.util.LinkedHashSet<>(described);
        onlyDescribed.removeAll(allowedSet);
        java.util.Set<String> disagreement = new java.util.LinkedHashSet<>();
        disagreement.addAll(onlyAllowed);
        disagreement.addAll(onlyDescribed);
        return disagreement;
    }
}
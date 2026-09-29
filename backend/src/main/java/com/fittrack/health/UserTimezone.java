package com.fittrack.health;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Set;
import java.util.TreeSet;

/**
 * The user's IANA timezone, and the only place a health calendar date is derived (D3).
 *
 * <h2>Why this class exists</h2>
 * Phase 1-10 stored no user timezone at all: the single {@code timezone} column in the schema belongs
 * to a reminder and is not the user's. That gap is already recorded in
 * {@code V7__calendar_session_date.sql}. Health Connect records carry absolute instants, and an
 * instant has no date until somebody supplies a calendar, so filing a record under a server-local or
 * UTC day would misfile data for most of the world.
 *
 * <h2>Rules this class enforces</h2>
 * <ul>
 *   <li>A zone is <b>validated against {@link ZoneId}</b>. An unrecognised string is rejected, never
 *       stored, and never silently replaced with a default. A silently-substituted zone would
 *       mis-date health data, which is worse than refusing the request.</li>
 *   <li>There is <b>no implicit default</b>. {@link #resolve(String)} returning null means "not set",
 *       and callers must decide what that means rather than inheriting a guess.</li>
 *   <li>Canonicalisation uses {@link ZoneId#getId()}, so {@code UTC}, {@code Etc/UTC} and
 *       {@code Z}-derived spellings collapse to one stored form and cannot drift.</li>
 * </ul>
 *
 * <h2>Scope discipline</h2>
 * This is applied to <b>health calendar dates only</b>. Phase 1-10 date semantics (analytics, the
 * calendar, reminders) are deliberately left alone in this phase: rewriting them would change
 * existing behaviour far beyond the Health Connect boundary, and a user timezone that was never
 * stored cannot retroactively fix history that was already dated under the old rule.
 */
public final class UserTimezone {

    /**
     * The zone used when a user has genuinely not chosen one and a caller has explicitly opted into
     * a fallback rather than demanding an explicit zone.
     *
     * <p>This exists only so {@code HealthRecordValidator} and other existing Phase 1-10 code paths
     * keep their historical UTC behaviour when no zone is in play. It is <b>not</b> used to date an
     * inbound Health Connect record: that path calls {@link #require}, which has no fallback.
     */
    public static final String FALLBACK_UTC = "UTC";

    private UserTimezone() {
    }

    /**
     * Validates and canonicalises an IANA zone id.
     *
     * @return the canonical zone id, or null when the input is absent or not a real IANA zone
     */
    public static String canonical(String zone) {
        if (zone == null || zone.isBlank()) {
            return null;
        }
        String trimmed = zone.trim();
        try {
            return ZoneId.of(trimmed).getId();
        } catch (DateTimeException e) {
            // RegionId.of is stricter than ZoneId.of and would reject a valid ZoneId alias, so the
            // check above is deliberately ZoneId-based: the database must accept exactly what the
            // JVM will later resolve at aggregation time.
            return null;
        }
    }

    /** True when the value is a zone this JVM can resolve. */
    public static boolean isValid(String zone) {
        return canonical(zone) != null;
    }

    /**
     * Resolves a zone for use, falling back only when the caller accepts a fallback.
     *
     * @param zone          the stored or supplied zone
     * @param allowFallback when true an absent zone yields {@link #FALLBACK_UTC}
     * @return the zone id, or null when absent and no fallback was allowed
     */
    public static String resolve(String zone, boolean allowFallback) {
        String canonical = canonical(zone);
        if (canonical != null) {
            return canonical;
        }
        return allowFallback ? FALLBACK_UTC : null;
    }

    /**
     * Resolves a zone that must be real, for a path that cannot proceed without one.
     *
     * @throws IllegalArgumentException when the zone is absent or invalid, so an unresolvable zone
     *         surfaces as a rejected request rather than as quietly wrong data
     */
    public static String require(String zone) {
        String canonical = canonical(zone);
        if (canonical == null) {
            throw new IllegalArgumentException("timezone must be a valid IANA zone id");
        }
        return canonical;
    }

    /** The zone as a {@link ZoneId}, or null when the value cannot be resolved. */
    public static ZoneId zoneId(String zone) {
        String canonical = canonical(zone);
        return canonical == null ? null : ZoneId.of(canonical);
    }

    /**
     * The zones offered to a client picking one.
     *
     * <p>Sorted so the list is stable and cacheable, and read from the JVM's own zone database
     * rather than a hand-kept copy that would drift from the runtime that has to resolve it.
     */
    public static Set<String> availableZones() {
        return Set.copyOf(new TreeSet<>(ZoneId.getAvailableZoneIds()));
    }
}

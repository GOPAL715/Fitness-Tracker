package com.fittrack.analytics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Reads the caller's stored IANA zone from {@code app_users.timezone}.
 *
 * <h2>Why this is a component and not a static helper</h2>
 * Phase 21 gave every analytics endpoint the same timezone rule, but the lookup lived as a private
 * method inside {@code AnalyticsController}. The Coach needs exactly the same rule, and duplicating
 * the "read the column, canonicalise, fall back to UTC" sequence would create a second resolver that
 * could drift from the first. There is one column, one validator and one fallback, so there is one
 * place that decides which zone a request is answered in.
 *
 * <p>This performs no interpretation of its own: it reads the raw column and delegates to
 * {@link AnalyticsTimezone#resolve}, so the semantics are defined in exactly one class.
 */
@Component
public class AnalyticsTimezoneResolver {

    private final JdbcTemplate jdbc;

    public AnalyticsTimezoneResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Resolves the zone for an authenticated principal.
     *
     * @param principal the JWT subject; never a request-supplied user id
     * @return the stored zone, or the UTC fallback when none is set or the stored value is unusable
     */
    public AnalyticsTimezone.Resolved resolve(String principal) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT timezone FROM app_users WHERE id=CAST(? AS uuid)", principal);
        String stored = rows.isEmpty() || rows.get(0).get("timezone") == null
                ? null : String.valueOf(rows.get(0).get("timezone"));
        return AnalyticsTimezone.resolve(stored);
    }
}

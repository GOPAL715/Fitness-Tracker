package com.fittrack.acceptance.support;

import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Database-row and error-contract assertions shared by the Phase 14 acceptance tests. */
public abstract class AiAssertions extends AbstractAcceptanceTest {

    // ------------------------------------------------------- ai_usage probing

    protected int usageCount(String userId) {
        return jdbc.queryForObject("select count(*) from ai_usage where user_id=CAST(? AS uuid)", Integer.class, userId);
    }

    protected List<Map<String, Object>> usageRows(String userId) {
        return jdbc.queryForList(SELECT_USAGE + " where user_id=CAST(? AS uuid) order by created_at desc, id desc", userId);
    }

    /** The single persisted accounting row for a feature, failing loudly when absent or duplicated. */
    protected Map<String, Object> soleUsageRow(String userId, String feature) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                SELECT_USAGE + " where user_id=CAST(? AS uuid) and feature=?", userId, feature);
        assertThat(rows).as("exactly one ai_usage row for user %s feature %s", userId, feature).hasSize(1);
        return rows.get(0);
    }

    private static final String SELECT_USAGE =
            "select user_id, feature, model, provider, request_id, input_tokens, output_tokens,"
                    + " total_tokens, success, estimated_cost, latency_ms, error_category from ai_usage";

    protected int minuteCounter(String userId, String feature) {
        return counter(userId, feature, "minute");
    }

    protected int dayCounter(String userId, String feature) {
        return counter(userId, feature, "day");
    }

    /**
     * Reads a quota counter directly from PostgreSQL.
     *
     * <p>A missing row means the budget was never touched, which is reported as zero rather than
     * failing the lookup.
     */
    private int counter(String userId, String feature, String windowType) {
        List<Integer> values = jdbc.queryForList(
                "select request_count from ai_quota_counters where user_id=CAST(? AS uuid)"
                        + " and feature=? and window_type=?",
                Integer.class, userId, feature, windowType);
        assertThat(values).as("at most one %s counter row for %s/%s", windowType, userId, feature)
                .hasSizeLessThanOrEqualTo(1);
        return values.isEmpty() ? 0 : values.get(0);
    }

    protected long dailyTokens(String userId) {
        Long value = jdbc.queryForObject(
                "select COALESCE(sum(token_count),0) from ai_quota_counters"
                        + " where user_id=CAST(? AS uuid) and window_type='day'", Long.class, userId);
        return value == null ? 0L : value;
    }

    /** Structured error contract plus a proof that no provider internals escaped. */
    @Override
    protected void assertStructuredError(MvcResult result, int status, String errorLabel) throws Exception {
        super.assertStructuredError(result, status, errorLabel);
        assertNoProviderLeak(result.getResponse().getContentAsString());
    }

    // ------------------------------------------------------ leak assertions

    /** No provider internals, credentials, or stack frames may reach a client response. */
    protected void assertNoProviderLeak(String body) {
        assertThat(body).doesNotContain(FakeAiProvider.SECRET);
        assertThat(body).doesNotContain("sk-live");
        assertThat(body).doesNotContain("upstream");
        assertThat(body).doesNotContain("truncated");
        assertThat(body).doesNotContain("IllegalStateException");
        assertThat(body).doesNotContain("AiProviderException");
        assertThat(body).doesNotContain("at com.fittrack");
        assertThat(body).doesNotContain("Caused by");
        assertThat(body).doesNotContain("\"trace\"");
    }
}

package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AiAssertions;
import com.fittrack.acceptance.support.FakeAiProvider;
import com.fittrack.ai.AiProvider;
import com.fittrack.ai.AiUsageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 14 item 17: REQUIRES_NEW accounting behaviour.
 *
 * <p>The outer business transaction is genuinely rolled back (a real exception is raised after
 * the usage row is written). The accounting row must survive, proving the usage write is not
 * enlisted in the caller's transaction. The annotation is never inspected.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
class AiRequiresNewAcceptanceTest extends AiAssertions {

    @Autowired AiUsageService usage;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("Phase 14 item 17 - usage recorded before an outer rollback still persists")
    void usageSurvivesAFailedOuterTransaction() throws Exception {
        Session user = register("requires-new-");
        String requestId = UUID.randomUUID().toString();
        // The service correlates via MDC, which the servlet filter normally populates.
        MDC.put("request_id", requestId);
        try {
            assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                AiUsageService.Attempt attempt = usage.start(user.id(), "food_scan");
                usage.success(attempt,
                        new AiProvider.ProviderUsage(FakeAiProvider.PROVIDER, FakeAiProvider.MODEL,
                                FakeAiProvider.INPUT_TOKENS, FakeAiProvider.OUTPUT_TOKENS),
                        FakeAiProvider.MODEL);
                // The outer business operation fails *after* usage was recorded.
                throw new IllegalStateException("outer business operation failed after AI accounting");
            })).isInstanceOf(IllegalStateException.class);
        } finally {
            MDC.remove("request_id");
        }

        Map<String, Object> row = soleUsageRow(user.id(), "food_scan");
        assertThat(row.get("user_id").toString()).isEqualTo(user.id());
        assertThat(row.get("request_id").toString()).isEqualTo(requestId);
        assertThat(row.get("success")).isEqualTo(true);
        assertThat((Integer) row.get("total_tokens")).isEqualTo(FakeAiProvider.TOTAL_TOKENS);
        assertThat(row.get("provider")).isEqualTo(FakeAiProvider.PROVIDER);
    }

    @Test
    @DisplayName("Phase 14 item 17 - a failed outer transaction still consumes and keeps its quota reservation")
    void quotaReservationSurvivesAFailedOuterTransaction() throws Exception {
        Session user = register("requires-new-quota-");

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            usage.start(user.id(), "weekly_coach");
            throw new IllegalStateException("outer business operation failed after quota reservation");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(minuteCounter(user.id(), "weekly_coach")).isEqualTo(1);
    }

    @Test
    @DisplayName("Phase 14 item 17 - a failed usage write leaves no partial row")
    void failedUsageWriteLeavesNoPartialRow() throws Exception {
        Session user = register("requires-new-failure-");

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            AiUsageService.Attempt attempt = usage.start(user.id(), "food_scan");
            usage.failure(attempt, new IllegalStateException("provider exploded"), null);
            throw new IllegalStateException("outer business operation failed after failure accounting");
        })).isInstanceOf(IllegalStateException.class);

        Map<String, Object> row = soleUsageRow(user.id(), "food_scan");
        assertThat(row.get("success")).isEqualTo(false);
        assertThat(row.get("error_category")).isEqualTo("application");
    }
}

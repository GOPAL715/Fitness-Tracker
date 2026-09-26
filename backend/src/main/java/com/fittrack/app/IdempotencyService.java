package com.fittrack.app;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Idempotency for retried offline composite writes.
 *
 * <p>An offline client can submit the same logical operation more than once. The ledger is keyed
 * on {@code (user_id, idempotency_key)}, so a repeat submission returns the original aggregate
 * instead of creating a second one. Keys are scoped per user, so one user can never replay
 * another user's operation.
 */
@Service
public class IdempotencyService {

    private final JdbcTemplate jdbc;

    public IdempotencyService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** What a previous submission produced, or null when this key is new. */
    public record Prior(UUID resultId, int childCount) {}

    public Prior find(UUID userId, String key) {
        if (key == null || key.isBlank()) return null;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select result_id,child_count from idempotency_keys where user_id=CAST(? as uuid)"
                        + " and idempotency_key=?", userId, key);
        if (rows.isEmpty()) return null;
        return new Prior((UUID) rows.get(0).get("result_id"),
                rows.get(0).get("child_count") == null ? 0 : ((Number) rows.get(0).get("child_count")).intValue());
    }

    /** Records the outcome. A concurrent duplicate loses the race and is ignored. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID userId, String key, String resource, UUID resultId, int childCount) {
        if (key == null || key.isBlank()) return;
        try {
            jdbc.update("insert into idempotency_keys(user_id,idempotency_key,resource,result_id,child_count)"
                            + " values (?::uuid,?,?,?,?) on conflict (user_id,idempotency_key) do nothing",
                    userId, key, resource, resultId, childCount);
        } catch (DataIntegrityViolationException ignored) {
            // A parallel duplicate already recorded this key; its result is authoritative.
        }
    }
}

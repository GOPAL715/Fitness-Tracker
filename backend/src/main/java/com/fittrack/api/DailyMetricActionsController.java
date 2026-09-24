package com.fittrack.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class DailyMetricActionsController {
    private final JdbcTemplate jdbc;
    public DailyMetricActionsController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record WaterRequest(Integer amount, Integer oz, Integer water_oz, String user_id) {
        public WaterRequest {
            if (user_id != null) throw new IllegalArgumentException("user_id is server controlled");
        }
    }

    @PostMapping("/water")
    @Transactional
    public Map<String,Object> water(@RequestBody WaterRequest request, @AuthenticationPrincipal String userId) {
        int amount = request == null ? 0 : firstPositive(request.amount(), request.oz());
        if (amount <= 0 || amount > 1000) throw new IllegalArgumentException("Water amount must be between 1 and 1000 ounces");
        var user = java.util.UUID.fromString(userId);
        LocalDate today = LocalDate.now();
        jdbc.update("INSERT INTO daily_metrics(id,user_id,metric_date,water_oz) VALUES (gen_random_uuid(),?::uuid,?,?) ON CONFLICT(user_id,metric_date) DO UPDATE SET water_oz = COALESCE(daily_metrics.water_oz,0) + EXCLUDED.water_oz", user, today, amount);
        Integer total = jdbc.queryForObject("SELECT water_oz FROM daily_metrics WHERE user_id=?::uuid AND metric_date=?", Integer.class, user, today);
        return Map.of("metric_date", today, "water_oz", total == null ? amount : total);
    }
    private static int firstPositive(Integer a, Integer b) { if (a != null && a > 0) return a; return b == null ? 0 : b; }
}

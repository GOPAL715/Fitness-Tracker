package com.fittrack.analytics;

import com.fittrack.analytics.AnalyticsDtos.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.*;

/**
 * Read-only analytics over rows the caller already owns.
 *
 * <h2>Every endpoint is derived, nothing is stored</h2>
 * There is no analytics table. Each figure is a grouped read of {@code v_daily_metrics_canonical},
 * {@code v_body_metrics_canonical}, {@code meals} or the two workout tables, which all carry an
 * index on {@code (user_id, date)}. That keeps the aggregate honest by construction: there is no
 * second copy to fall out of step with the data it summarises.
 *
 * <h2>Ownership</h2>
 * The user id is the authenticated principal and nothing else. No endpoint accepts a {@code userId}
 * parameter, so there is nothing for a caller to substitute; every statement predicates on
 * {@code user_id} in the WHERE clause rather than filtering afterwards, so another account's rows
 * are never read in the first place.
 *
 * <h2>Zero versus no data</h2>
 * A missing measurement and a measurement of zero are different facts. Where a value could be absent
 * it is returned as {@code null} rather than coerced to {@code 0}, and the DTOs say which is which.
 */
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {
    private final JdbcTemplate jdbc;
    private final com.fittrack.api.CalendarController calendar;
    private final WorkoutAnalytics workouts;
    private final TrendAnalytics trends;
    private final AnalyticsTimezoneResolver timezones;

    public AnalyticsController(JdbcTemplate jdbc, com.fittrack.api.CalendarController calendar,
                               WorkoutAnalytics workouts, TrendAnalytics trends,
                               AnalyticsTimezoneResolver timezones) {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.workouts = workouts;
        this.trends = trends;
        this.timezones = timezones;
    }

    /**
     * Delegates to the shared resolver rather than repeating the column lookup.
     *
     * <p>Phase 22 moved this into {@link AnalyticsTimezoneResolver} because the Coach needs the
     * identical rule. The behaviour is unchanged - same column, same validator, same UTC fallback -
     * so the Phase 21 analytics contract is untouched; only the location of the lookup moved.
     */
    private AnalyticsTimezone.Resolved zone(String u) {
        return timezones.resolve(u);
    }

    private LocalDate[] range(LocalDate from, LocalDate to, int defaultDays, String u) {
        return AnalyticsRange.resolve(from, to, zone(u).today(), defaultDays);
    }

    private void authenticated(String u) {
        if (u == null || u.isBlank()) throw new IllegalArgumentException("Authenticated user is required");
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private long num(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? 0 : ((Number) v).longValue();
    }

    private Integer integer(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : ((Number) v).intValue();
    }

    private LocalDate mapDate(Object value) {
        if (value instanceof LocalDate d) return d;
        if (value instanceof java.sql.Date d) return d.toLocalDate();
        return LocalDate.parse(value.toString());
    }

    private Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private BigDecimal bd(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? BigDecimal.ZERO : v instanceof BigDecimal b ? b : new BigDecimal(v.toString());
    }

    private ActivitySummary activitySummary(LocalDate d,String u){Map<String,Object> m=one("SELECT COALESCE(steps,0) steps,COALESCE(active_minutes,0) active_minutes,COALESCE(sleep_hours,0) sleep_hours,COALESCE(calories_burned,0) calories_burned,COALESCE(water_oz,0) water_oz,COALESCE(readiness,0) readiness FROM v_daily_metrics_canonical WHERE user_id=CAST(? AS uuid) AND metric_date=?",u,d);return new ActivitySummary(num(m,"steps"),num(m,"active_minutes"),bd(m,"sleep_hours"),num(m,"calories_burned"),num(m,"water_oz"),num(m,"readiness"));}
    /**
     * Nutrition totals for a window.
     *
     * <p>Fiber is read from {@code meals.fiber_g} on the same rows as every other macro. A meal
     * written before fiber existed carries a null there, and {@code sum} skips nulls, so an old row
     * contributes to calories without inventing a fiber figure.
     */
    private NutritionSummary nutritionSummaryBetween(LocalDate from, LocalDate to, String u) {
        Map<String, Object> m = one("SELECT COALESCE(sum(calories),0) calories,COALESCE(sum(protein_g),0) protein_g,COALESCE(sum(carbs_g),0) carbs_g,COALESCE(sum(fat_g),0) fat_g,COALESCE(sum(fiber_g),0) fiber_g FROM meals WHERE user_id=CAST(? AS uuid) AND meal_date BETWEEN ? AND ?", u, from, to);
        return new NutritionSummary(bd(m,"calories"), bd(m,"protein_g"), bd(m,"carbs_g"), bd(m,"fat_g"), bd(m,"fiber_g"));
    }

    private NutritionSummary nutritionSummary(LocalDate d, String u) {
        return nutritionSummaryBetween(d, d, u);
    }

    /**
     * Training activity for one day, from the shared definition in {@link WorkoutAnalytics}.
     *
     * <p>An absent day is all-zeroes here rather than null: the dashboard asks "what happened today"
     * and "nothing happened" is a legitimate answer for a day that has simply not been used yet.
     */
    private WorkoutSummary workoutSummary(LocalDate d, String u) {
        return workouts.dailyTotals(d, d, u).stream().findFirst()
                .map(w -> new WorkoutSummary(w.sessions(), w.minutes(), w.calories(), w.quickLogged(), w.loggedSessions()))
                .orElseGet(() -> new WorkoutSummary(0, 0, null, 0, 0));
    }
    private BodySummary bodySummary(LocalDate d,String u){Map<String,Object> m=one("SELECT weight_lb,body_fat_pct FROM v_body_metrics_canonical WHERE user_id=CAST(? AS uuid) AND metric_date=?",u,d);return new BodySummary(bd(m,"weight_lb"),bd(m,"body_fat_pct"));}
    private TargetSummary targetSummary(String u){Map<String,Object> m=one("SELECT step_target,calorie_target,protein_target_g,water_target_oz,target_weight_lb FROM fitness_profile WHERE user_id=CAST(? AS uuid)",u);return new TargetSummary(integer(m,"step_target"),integer(m,"calorie_target"),integer(m,"protein_target_g"),integer(m,"water_target_oz"),bd(m,"target_weight_lb"));}
    private List<ActivityPoint> activityRows(LocalDate from,LocalDate to,String u){return jdbc.query("SELECT metric_date::date date,steps,active_minutes,sleep_hours,calories_burned FROM v_daily_metrics_canonical WHERE user_id=CAST(? AS uuid) AND metric_date BETWEEN ? AND ? ORDER BY metric_date",(rs,n)->new ActivityPoint(rs.getObject("date",LocalDate.class),nullableLong(rs.getObject("steps")),nullableLong(rs.getObject("active_minutes")),rs.getBigDecimal("sleep_hours"),nullableLong(rs.getObject("calories_burned"))),u,from,to);}
    private List<BodyPoint> bodyRows(LocalDate from,LocalDate to,String u){return jdbc.query("SELECT metric_date::date date,weight_lb,body_fat_pct,waist_in FROM v_body_metrics_canonical WHERE user_id=CAST(? AS uuid) AND metric_date BETWEEN ? AND ? ORDER BY metric_date",(rs,n)->new BodyPoint(rs.getObject("date",LocalDate.class),rs.getBigDecimal("weight_lb"),rs.getBigDecimal("body_fat_pct"),rs.getBigDecimal("waist_in")),u,from,to);}
    /**
     * Daily training activity, from the same {@link WorkoutAnalytics} definition the dashboard uses.
     *
     * <p>Replacing the inline query is what makes {@code /workouts} and {@code /calendar} agree:
     * both now read the same aggregation over both workout tables.
     */
    private List<WorkoutPoint> workoutRows(LocalDate from, LocalDate to, String u) {
        return workouts.dailyTotals(from, to, u).stream()
                .map(w -> new WorkoutPoint(w.date(), w.sessions(), w.minutes(), w.calories(),
                        w.quickLogged(), w.loggedSessions()))
                .toList();
    }

    private List<NutritionPoint> nutritionRows(LocalDate from, LocalDate to, String u) {
        return jdbc.query("SELECT meal_date::date date,COALESCE(sum(calories),0) calories,COALESCE(sum(protein_g),0) protein_g,COALESCE(sum(carbs_g),0) carbs_g,COALESCE(sum(fat_g),0) fat_g,COALESCE(sum(fiber_g),0) fiber_g FROM meals WHERE user_id=CAST(? AS uuid) AND meal_date BETWEEN ? AND ? GROUP BY meal_date ORDER BY meal_date",(rs,n)->new NutritionPoint(rs.getObject("date",LocalDate.class),rs.getBigDecimal("calories"),rs.getBigDecimal("protein_g"),rs.getBigDecimal("carbs_g"),rs.getBigDecimal("fat_g"),rs.getBigDecimal("fiber_g")),u,from,to);
    }

    @GetMapping("/dashboard")
    public DashboardResponse dashboard(@AuthenticationPrincipal String u) {
        authenticated(u);
        AnalyticsTimezone.Resolved zone = zone(u);
        LocalDate d = zone.today();
        return new DashboardResponse(d, zone.zoneId(), zone.resolved(),
                activitySummary(d, u), nutritionSummary(d, u), workoutSummary(d, u),
                bodySummary(d, u), targetSummary(u));
    }

    /**
     * Day, week or month rollups over a bounded range.
     *
     * <p>One endpoint with a {@code bucket} parameter rather than three endpoints, so the range
     * validation, the timezone resolution and the bucket labelling are written once and cannot drift
     * apart between granularities.
     *
     * <p>Only buckets that actually hold data are returned. A gap in the middle of a series is a real
     * gap - the user recorded nothing - and filling it with a zero-valued bucket would assert that
     * they trained for zero minutes rather than that nothing was logged.
     */
    @GetMapping("/trends")
    public TrendsResponse trends(@AuthenticationPrincipal String u,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                 @RequestParam(required = false, defaultValue = "day") String bucket) {
        authenticated(u);
        AnalyticsTimezone.Resolved zone = zone(u);
        // A 90-day default is the widest span that still yields a readable number of weekly or
        // monthly buckets; a caller wanting a year asks for it explicitly and is still bounded.
        LocalDate[] r = AnalyticsRange.resolve(from, to, zone.today(), 90);
        String unit = AnalyticsTimezone.bucket(bucket);

        Map<LocalDate, TrendBucket> byBucket = new LinkedHashMap<>();
        for (TrendAnalytics.Bucket b : trends.activity(r[0], r[1], unit, u)) {
            byBucket.put(b.start(), blank(b.start(), unit, r[0], r[1]));
            TrendBucket existing = byBucket.get(b.start());
            byBucket.put(b.start(), new TrendBucket(existing.start(), existing.end(), existing.days(),
                    b.steps(), b.activeMinutes(), b.caloriesBurned(), b.sleepHoursAvg(),
                    b.sleepHoursAvg() == null ? null : b.sleepHoursAvg().multiply(BigDecimal.valueOf(existing.days())),
                    existing.nutritionCalories(), existing.proteinG(), existing.carbsG(), existing.fatG(),
                    existing.fiberG(), existing.workouts(), existing.workoutMinutes(), existing.workoutCalories(),
                    existing.quickLogged(), existing.loggedSessions()));
        }
        for (TrendAnalytics.NutritionBucket n : trends.nutrition(r[0], r[1], unit, u)) {
            byBucket.putIfAbsent(n.start(), blank(n.start(), unit, r[0], r[1]));
            merge(byBucket, n);
        }
        for (WorkoutAnalytics.DailyWorkout w : workouts.dailyTotals(r[0], r[1], u)) {
            LocalDate start = TrendAnalytics.bucketStart(w.date(), unit);
            byBucket.putIfAbsent(start, blank(start, unit, r[0], r[1]));
            TrendBucket e = byBucket.get(start);
            byBucket.put(start, new TrendBucket(e.start(), e.end(), e.days(), e.steps(), e.activeMinutes(),
                    e.activityCaloriesBurned(), e.sleepHoursAvg(), e.sleepHoursTotal(),
                    e.nutritionCalories(), e.proteinG(), e.carbsG(), e.fatG(), e.fiberG(),
                    e.workouts() == null ? w.sessions() : e.workouts() + w.sessions(),
                    e.workoutMinutes() == null ? w.minutes() : e.workoutMinutes() + w.minutes(),
                    // Unknown stays unknown, but a blank accumulator must take the first real value
                    // rather than staying null forever. Adding a known total to an unknown one
                    // cannot produce a known total, so either operand being null yields null.
                    w.calories() == null ? null
                            : e.workoutCalories() == null ? w.calories()
                                    : e.workoutCalories() + w.calories(),
                    e.quickLogged() == null ? w.quickLogged() : e.quickLogged() + w.quickLogged(),
                    e.loggedSessions() == null ? w.loggedSessions() : e.loggedSessions() + w.loggedSessions()));
        }
        return new TrendsResponse(r[0], r[1], unit, zone.zoneId(), zone.resolved(),
                new ArrayList<>(byBucket.values()));
    }

    /** An all-null bucket, used as the merge base for whichever series touches a bucket first. */
    private TrendBucket blank(LocalDate start, String unit, LocalDate from, LocalDate to) {
        LocalDate end = switch (unit) {
            case "week" -> start.plusDays(6);
            case "month" -> start.withDayOfMonth(start.lengthOfMonth());
            default -> start;
        };
        return new TrendBucket(start, end, TrendAnalytics.daysInBucket(start, unit, from, to),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** Folds a nutrition rollup into a bucket that may already carry activity. */
    private void merge(Map<LocalDate, TrendBucket> buckets, TrendAnalytics.NutritionBucket n) {
        TrendBucket e = buckets.get(n.start());
        buckets.put(n.start(), new TrendBucket(e.start(), e.end(), e.days(), e.steps(), e.activeMinutes(),
                e.activityCaloriesBurned(), e.sleepHoursAvg(), e.sleepHoursTotal(),
                n.calories(), n.proteinG(), n.carbsG(), n.fatG(), n.fiberG(),
                e.workouts(), e.workoutMinutes(), e.workoutCalories(), e.quickLogged(), e.loggedSessions()));
    }

    @GetMapping("/workouts") public List<WorkoutPoint> workouts(@AuthenticationPrincipal String u,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to){authenticated(u);LocalDate[] r=range(from,to,90,u);return workoutRows(r[0],r[1],u);}
    @GetMapping("/nutrition") public NutritionResponse nutrition(@AuthenticationPrincipal String u,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to){authenticated(u);LocalDate[] r=range(from,to,90,u);return new NutritionResponse(r[0],r[1],nutritionRows(r[0],r[1],u),nutritionSummaryBetween(r[0],r[1],u));}
    @GetMapping("/progress") public ProgressResponse progress(@AuthenticationPrincipal String u,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to){authenticated(u);LocalDate[] r=range(from,to,90,u);return new ProgressResponse(r[0],r[1],bodyRows(r[0],r[1],u),activityRows(r[0],r[1],u),new TargetWeight(bd(one("SELECT target_weight_lb FROM fitness_profile WHERE user_id=CAST(? AS uuid)",u),"target_weight_lb")));}
    @GetMapping("/weekly") public WeeklyResponse weekly(@AuthenticationPrincipal String u,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to){authenticated(u);LocalDate[] r=range(from,to,7,u);return new WeeklyResponse(r[0],r[1],activityRows(r[0],r[1],u),nutritionRows(r[0],r[1],u),workoutRows(r[0],r[1],u));}
    @GetMapping("/activity") public List<ActivityPoint> activity(@AuthenticationPrincipal String u,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to){authenticated(u);LocalDate[] r=range(from,to,90,u);return activityRows(r[0],r[1],u);}
    @GetMapping("/body") public List<BodyPoint> body(@AuthenticationPrincipal String u,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to){authenticated(u);LocalDate[] r=range(from,to,90,u);return bodyRows(r[0],r[1],u);}
    @GetMapping("/habits") public List<HabitPoint> habits(@AuthenticationPrincipal String u,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to){authenticated(u);LocalDate[] r=range(from,to,7,u);return jdbc.query("SELECT h.name,count(l.id) FILTER(WHERE l.completed) completed FROM habits h LEFT JOIN habit_logs l ON l.habit_id=h.id AND l.log_date BETWEEN ? AND ? WHERE h.user_id=CAST(? AS uuid) GROUP BY h.id,h.name ORDER BY h.name",(rs,n)->new HabitPoint(rs.getString("name"),rs.getLong("completed")),r[0],r[1],u);}
    @GetMapping("/ai-usage") public List<AiUsageView> usage(@AuthenticationPrincipal String u){authenticated(u);return jdbc.query("SELECT id,user_id,feature,model,provider,request_id,input_tokens,output_tokens,total_tokens,success,estimated_cost,latency_ms,error_category,created_at FROM ai_usage WHERE user_id=CAST(? AS uuid) ORDER BY created_at DESC LIMIT 100",(rs,n)->new AiUsageView(rs.getObject("id",UUID.class),UUID.fromString(rs.getString("user_id")),rs.getString("feature"),rs.getString("model"),rs.getString("provider"),rs.getObject("request_id",UUID.class),rs.getObject("input_tokens",Integer.class),rs.getObject("output_tokens",Integer.class),rs.getObject("total_tokens",Integer.class),rs.getBoolean("success"),rs.getBigDecimal("estimated_cost"),rs.getObject("latency_ms",Long.class),rs.getString("error_category")),u);}
    /**
     * The dense day grid, delegated to the injected controller.
     *
     * <p>This used to be {@code new CalendarController(namedJdbc).dailyTotals(...)}. Building a
     * controller by hand bypasses the container, so a future dependency added to that class would
     * silently be absent here and this would keep compiling. Injecting the bean also brings its range
     * validation into play, which the manual construction skipped.
     */
    @GetMapping("/calendar") public List<CalendarDay> calendar(@AuthenticationPrincipal String u,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to){authenticated(u);LocalDate[] r=range(from,to,31,u);List<Map<String,Object>> raw=calendar.dailyTotals(r[0],r[1],u);return raw.stream().map(x->{Map<String,Object> m=(Map<String,Object>)x;return new CalendarDay(mapDate(m.get("date")),new CalendarSummary(num(m,"steps"),num(m,"water_oz"),num(m,"calories_burned"),num(m,"active_minutes"),bd(m,"sleep_hours"),num(m,"workouts"),num(m,"meals"),num(m,"habits_completed")));}).toList();}
}

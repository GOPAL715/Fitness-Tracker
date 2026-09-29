package com.fittrack.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * The inbound Health Connect endpoint: {@code POST /api/v1/health/devices/{id}/records} (D3, D11).
 *
 * <h2>Why a new endpoint and not the existing sync route</h2>
 * {@code POST /devices/{id}/sync} is a <b>server-pull</b> contract: the body carries only a date and
 * the server calls out to a provider. Health Connect cannot be reached that way at all - it is an
 * on-device Android API with no server endpoint - so overloading the pull route with a record payload
 * would have meant one route with two incompatible contracts, two auth shapes, and two meanings of
 * "cursor". They are separate routes with separate responsibilities.
 *
 * <h2>Security properties</h2>
 * <ul>
 *   <li>JWT required, exactly like every other health route.</li>
 *   <li>The device is resolved through the authenticated user, so a provider record id can never reach
 *       another user's connection, and a cross-user id is a 404 rather than a 403 that would confirm
 *       the row exists.</li>
 *   <li>The <b>registered provider is authoritative</b>. The body carries no provider field, so a
 *       client cannot label its data as any integration it likes, nor push Health Connect records into
 *       a connection registered as something else.</li>
 *   <li>No {@code user_id} is accepted. Ownership comes from the token alone.</li>
 * </ul>
 *
 * <h2>Safety limits (D8)</h2>
 * 500 records and deletions per request, and a 1 MB body. Both are FitTrack's own application limits,
 * not Google quota claims. The body ceiling is enforced by the container, which rejects the upload
 * before it is buffered, so an oversized request never becomes unbounded memory.
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthConnectIngestController {

    private final JdbcTemplate jdbc;
    private final HealthConnectIngestService ingest;
    private final int maxWindowDays;

    public HealthConnectIngestController(JdbcTemplate jdbc, HealthConnectIngestService ingest,
            @Value("${app.health-connect.window-days:30}") int maxWindowDays) {
        this.jdbc = jdbc;
        this.ingest = ingest;
        this.maxWindowDays = Math.max(1, maxWindowDays);
    }

    /**
     * Accepts a bounded batch of source records for a Health Connect device.
     *
     * <p>Returns counts and reason categories only. No health values and no record ids are echoed, so
     * the response is safe to log.
     */
    @PostMapping("/devices/{id}/records")
    public ResponseEntity<Map<String, Object>> ingestRecords(
            @PathVariable UUID id,
            @RequestBody HealthConnectBatch batch,
            @AuthenticationPrincipal String user) {
        UUID owner = uuid(user);
        // An absent or unresolvable zone is refused rather than defaulted. Defaulting would silently
        // file a user's health data under a calendar day that is not theirs (D3). Nothing is written
        // before this check, so a refused batch leaves no records and no aggregates behind.
        String timezone = UserTimezone.canonical(batch == null ? null : batch.timezone());
        if (timezone == null) {
            throw new TimezoneRequiredException(
                    "A valid IANA timezone is required to date health records. Set it with"
                            + " POST /api/v1/health/timezone or include it in this request.");
        }
        ZoneId zone = UserTimezone.zoneId(timezone);
        HealthConnectIngestService.IngestResult result;
        try {
            result = ingest.ingest(id, owner, batch, zone, timezone, earliestAccepted(zone));
        } catch (NoSuchElementException e) {
            // Either the device is not this user's, or it is not a Health Connect connection. Both are
            // reported identically so the response cannot be used to probe for other users' devices.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Health device not found");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "accepted");
        body.put("provider", HealthConnectIngestService.PROVIDER);
        body.put("records_received", result.recordsReceived());
        body.put("records_accepted", result.recordsAccepted());
        body.put("records_rejected", result.recordsRejected());
        body.put("records_deleted", result.recordsDeleted());
        body.put("days_recomputed", result.daysRecomputed());
        body.put("timezone", result.timezone());
        // Surfaced so a partial import is visible rather than implied complete.
        body.put("rejected_reasons", result.rejectedReasons());
        return ResponseEntity.ok(body);
    }

    /**
     * The caller's IANA timezone, and whether one is set (D3).
     *
     * <p>Returned so a bridge can check it has a real zone before attempting a sync, rather than
     * discovering the problem from a rejected request.
     */
    @GetMapping("/timezone")
    public Map<String, Object> timezone(@AuthenticationPrincipal String user) {
        UUID owner = uuid(user);
        List<Map<String, Object>> rows =
                jdbc.queryForList("select timezone from app_users where id=?::uuid", owner);
        String stored = rows.isEmpty() || rows.get(0).get("timezone") == null
                ? null : String.valueOf(rows.get(0).get("timezone"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("timezone", stored);
        out.put("configured", stored != null);
        return out;
    }

    /**
     * Sets the caller's IANA timezone.
     *
     * <p>Validated against {@link ZoneId}: an unrecognised value is rejected and never stored, and no
     * value is ever defaulted. Changing the zone affects how <b>future</b> records are dated; it does
     * not retroactively re-date history already imported under a previous zone.
     */
    @PostMapping("/timezone")
    public Map<String, Object> setTimezone(@RequestBody Map<String, Object> body,
                                           @AuthenticationPrincipal String user) {
        UUID owner = uuid(user);
        String requested = body == null ? null : String.valueOf(body.getOrDefault("timezone", ""));
        String canonical = UserTimezone.canonical(requested);
        if (canonical == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "timezone must be a valid IANA zone id");
        }
        jdbc.update("update app_users set timezone=? where id=?::uuid", canonical, owner);
        return Map.of("timezone", canonical, "configured", true);
    }

    /**
     * The oldest local day accepted for a batch.
     *
     * <p>Bounded so a bridge cannot make the server retain unbounded history, and so the aggregation
     * window stays the one the product decided on (D7).
     */
    private LocalDate earliestAccepted(ZoneId zone) {
        LocalDate today = HealthConnectAggregator.localDayOf(java.time.Instant.now(), zone);
        return today.minusDays(maxWindowDays - 1L);
    }

    private static UUID uuid(String principal) {
        try {
            return UUID.fromString(principal);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
}}

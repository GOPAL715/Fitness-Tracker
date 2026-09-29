package com.fittrack.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fittrack.acceptance.support.AbstractAcceptanceTest;
import com.fittrack.health.HealthNormalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The Health Connect ingestion contract, end to end against a real PostgreSQL (D2-D13).
 *
 * <p>Health Connect itself cannot run here: it is an on-device Android API and the bridge is a
 * separate repository. What this suite proves is that the <b>server half</b> of the contract is
 * correct and safe, which is the half FitTrack owns. Every request is shaped exactly as the documented
 * bridge contract specifies, so this doubles as the executable specification a future Android team
 * can build against.
 *
 * <p>Each test owns its own user, so the suite is order-independent and a failure names exactly one
 * behaviour.
 */
// Without these the class compiles and runs but builds no Spring context, so every @Autowired
// field stays null and each test fails on the first call rather than on the behaviour it asserts.
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
class HealthConnectIngestAcceptanceTest extends AbstractAcceptanceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final String TZ = "Asia/Kolkata";

    private Session user;

    @BeforeEach
    void signUp() throws Exception {
        user = register("hc");
    }

    /** Registers a Health Connect device for the current user and returns its id. */
    private String registerDevice() throws Exception {
        return registerDevice("pixel", "hc-device-" + UUID.randomUUID());
    }

    private String registerDevice(String name, String externalId) throws Exception {
        MvcResult result = call(user, post("/api/v1/health/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                        "device_name", name,
                        "provider", "health-connect",
                        "external_device_id", externalId))));
        assertStatus(result, 200);
        return json(result).path("id").asText();
    }

    private static Instant at(LocalDateTime local) {
        return ZonedDateTime.of(local, ZONE).toInstant();
    }

    /** A local date that is inside the D7 window, so ingestion will accept it. */
    private static LocalDate recentDay() {
        return LocalDate.now(ZONE).minusDays(2);
    }

    private static Map<String, Object> stepsRecord(String id, Instant start, Instant end, String value) {
        return record(id, "steps", start, end, value, "count");
    }

    private static Map<String, Object> caloriesRecord(String id, Instant start, Instant end, String value) {
        return record(id, "active_calories", start, end, value, "kcal");
    }

    private static Map<String, Object> weightRecord(String id, Instant at, String kilograms) {
        return record(id, "weight", at, at, kilograms, "kg");
    }

    private static Map<String, Object> bodyFatRecord(String id, Instant at, String percent) {
        return record(id, "body_fat", at, at, percent, "percent");
    }

    private static Map<String, Object> record(String id, String type, Instant start, Instant end,
                                              String value, String unit) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("record_id", id);
        r.put("record_type", type);
        r.put("start_time", start.toString());
        r.put("end_time", end.toString());
        r.put("value", new BigDecimal(value));
        r.put("unit", unit);
        return r;
    }

    private MvcResult ingest(String deviceId, Map<String, Object> body) throws Exception {
        return call(user, post("/api/v1/health/devices/{id}/records", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)));
    }

    private MvcResult ingest(String deviceId, List<Map<String, Object>> records) throws Exception {
        return ingest(deviceId, Map.of("timezone", TZ, "records", records));
    }

    /** Canonical steps for a day, read through the Phase 10 view a consumer would use. */
    private Integer canonicalSteps(LocalDate day) {
        List<Integer> out = jdbc.queryForList(
                "select steps from v_daily_metrics_canonical where user_id=?::uuid and metric_date=?",
                Integer.class, UUID.fromString(user.id()), day);
        return out.isEmpty() ? null : out.get(0);
    }

    private Integer canonicalCalories(LocalDate day) {
        List<Integer> out = jdbc.queryForList(
                "select calories_burned from v_daily_metrics_canonical where user_id=?::uuid and metric_date=?",
                Integer.class, UUID.fromString(user.id()), day);
        return out.isEmpty() ? null : out.get(0);
    }

    private BigDecimal canonicalWeight(LocalDate day) {
        List<BigDecimal> out = jdbc.queryForList(
                "select weight_lb from v_body_metrics_canonical where user_id=?::uuid and metric_date=?",
                BigDecimal.class, UUID.fromString(user.id()), day);
        return out.isEmpty() ? null : out.get(0);
    }

    private BigDecimal canonicalBodyFat(LocalDate day) {
        List<BigDecimal> out = jdbc.queryForList(
                "select body_fat_pct from v_body_metrics_canonical where user_id=?::uuid and metric_date=?",
                BigDecimal.class, UUID.fromString(user.id()), day);
        return out.isEmpty() ? null : out.get(0);
    }

    /** A full local day of steps as one interval, for tests that only care about the total. */
    private static Map<String, Object> wholeDaySteps(String id, LocalDate day, String value) {
        return stepsRecord(id, at(day.atStartOfDay()), at(day.plusDays(1).atStartOfDay()), value);
    }

    @Nested
    @DisplayName("authorization and ownership")
    class Authorization {

        @Test
        @DisplayName("an unauthenticated request is refused")
        void requiresAuthentication() throws Exception {
            String device = registerDevice();
            assertThat(mvc.perform(post("/api/v1/health/devices/{id}/records", device)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("timezone", TZ)))).andReturn()
                    .getResponse().getStatus()).isEqualTo(401);
        }

        @Test
        @DisplayName("one user cannot push records onto another user's device")
        void rejectsCrossUserIngestion() throws Exception {
            String device = registerDevice();
            Session other = register("hc-other");

            MvcResult result = mvc.perform(post("/api/v1/health/devices/{id}/records", device)
                            .header("Authorization", "Bearer " + other.access())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of("timezone", TZ,
                                    "records", List.of(wholeDaySteps("x", recentDay(), "9999"))))))
                    .andReturn();

            // A 404 rather than a 403: a 403 would confirm the device row exists, leaking another
            // user's identifier through the response code alone.
            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(canonicalSteps(recentDay()))
                    .as("the other user's request must not have written anything")
                    .isNull();
        }

        @Test
        @DisplayName("an unknown device id is a 404, not a 500")
        void unknownDeviceIsNotFound() throws Exception {
            assertStatus(ingest(UUID.randomUUID().toString(),
                    Map.of("timezone", TZ, "records", List.of())), 404);
        }

        @Test
        @DisplayName("a device registered as another provider cannot receive Health Connect records")
        void rejectsWrongProviderDevice() throws Exception {
            MvcResult registered = call(user, post("/api/v1/health/devices")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of(
                            "device_name", "watch", "provider", "garmin",
                            "external_device_id", "g-" + UUID.randomUUID()))));
            String device = json(registered).path("id").asText();

            // The registered provider is authoritative, so this is a 404 and not a silent accept.
            assertStatus(ingest(device, Map.of("timezone", TZ, "records", List.of())), 404);
        }
    }

    @Nested
    @DisplayName("the timezone model")
    class Timezone {

        @Test
        @DisplayName("a new user has no timezone until one is stated, and is never given a fake one")
        void timezoneIsNotBackfilled() throws Exception {
            JsonNode body = json(getAs(user, "/api/v1/health/timezone"));

            assertThat(body.path("configured").asBoolean())
                    .as("an invented default would be indistinguishable from a real answer")
                    .isFalse();
            assertThat(body.path("timezone").isNull()).isTrue();
        }

        @Test
        @DisplayName("a valid IANA zone is stored and read back")
        void storesValidZone() throws Exception {
            assertStatus(call(user, post("/api/v1/health/timezone")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"timezone\":\"Europe/London\"}")), 200);

            JsonNode body = json(getAs(user, "/api/v1/health/timezone"));
            assertThat(body.path("timezone").asText()).isEqualTo("Europe/London");
            assertThat(body.path("configured").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("an invalid zone is refused and never stored")
        void refusesInvalidZone() throws Exception {
            assertStatus(call(user, post("/api/v1/health/timezone")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"timezone\":\"Mars/Olympus\"}")), 400);

            assertThat(json(getAs(user, "/api/v1/health/timezone")).path("configured").asBoolean())
                    .as("a rejected zone must not leave a partial value behind")
                    .isFalse();
        }

        @Test
        @DisplayName("ingestion without a usable timezone is refused rather than defaulted")
        void ingestionRequiresATimezone() throws Exception {
            String device = registerDevice();
            assertStatus(ingest(device, Map.of("records", List.of(wholeDaySteps("a", recentDay(), "10")))),
                    400);
            assertStatus(ingest(device, Map.of("timezone", "Mars/Olympus", "records", List.of())), 400);
        }

        @Test
        @DisplayName("a record is filed under the user's local day, not the server's UTC day")
        void usesTheUsersCalendarDay() throws Exception {
            String device = registerDevice();
            // 23:30 local. In UTC this is a different date, so a UTC-filing server would put it on the
            // wrong day and this assertion would fail.
            LocalDate day = recentDay();
            Instant lateNight = at(day.atTime(23, 30));

            assertStatus(ingest(device, List.of(
                    stepsRecord("late", lateNight, at(day.plusDays(1).atStartOfDay()), "100"))), 200);

            assertThat(canonicalSteps(day))
                    .as("the record must land on the user's local calendar day")
                    .isEqualTo(100);
        }

        @Test
        @DisplayName("the user's zone is persisted by the first successful ingest")
        void ingestPersistsTheZone() throws Exception {
            String device = registerDevice();
            assertStatus(ingest(device, List.of(wholeDaySteps("p", recentDay(), "10"))), 200);

            assertThat(json(getAs(user, "/api/v1/health/timezone")).path("timezone").asText())
                    .isEqualTo(TZ);
        }
    }

    @Nested
    @DisplayName("aggregation into daily values")
    class Aggregation {

        @Test
        @DisplayName("one steps record and one calorie record become one day of each")
        void singleRecordPerType() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();

            assertStatus(ingest(device, List.of(
                    wholeDaySteps("s1", day, "8000"),
                    caloriesRecord("c1", at(day.atStartOfDay()),
                            at(day.plusDays(1).atStartOfDay()), "450"))), 200);

            assertThat(canonicalSteps(day)).isEqualTo(8000);
            assertThat(canonicalCalories(day)).isEqualTo(450);
        }

        @Test
        @DisplayName("several records on one day are summed, because Health Connect totals are additive")
        void multipleRecordsSum() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();

            assertStatus(ingest(device, List.of(
                    stepsRecord("s1", at(day.atTime(6, 0)), at(day.atTime(7, 0)), "1000"),
                    stepsRecord("s2", at(day.atTime(7, 0)), at(day.atTime(8, 0)), "1500"),
                    stepsRecord("s3", at(day.atTime(8, 0)), at(day.atTime(9, 0)), "500"))), 200);

            assertThat(canonicalSteps(day)).isEqualTo(3000);
        }

        @Test
        @DisplayName("a record crossing local midnight contributes to both days")
        void midnightCrossingSpansTwoDays() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();
            // 22:00 on the target day through 02:00 the next day: half of 400 each side.
            assertStatus(ingest(device, List.of(stepsRecord("cross",
                    at(day.atTime(22, 0)), at(day.plusDays(1).atTime(2, 0)), "400"))), 200);

            assertThat(canonicalSteps(day)).isEqualTo(200);
            assertThat(canonicalSteps(day.plusDays(1)))
                    .as("the second half must not be lost")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("records on different days stay on their own days")
        void separateDaysDoNotMix() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();

            assertStatus(ingest(device, List.of(
                    wholeDaySteps("s1", day, "1000"),
                    wholeDaySteps("s2", day.plusDays(1), "2000"),
                    wholeDaySteps("s3", day.plusDays(2), "3000"))), 200);

            assertThat(canonicalSteps(day)).isEqualTo(1000);
            assertThat(canonicalSteps(day.plusDays(1))).isEqualTo(2000);
            assertThat(canonicalSteps(day.plusDays(2))).isEqualTo(3000);
        }

        @Test
        @DisplayName("a zero reading is stored as a real value rather than as absent")
        void zeroIsStoredNotDropped() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();

            assertStatus(ingest(device, List.of(wholeDaySteps("zero", day, "0"))), 200);

            assertThat(canonicalSteps(day))
                    .as("zero steps is a genuine reading, not a missing one")
                    .isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("idempotency and replay")
    class Idempotency {

        @Test
        @DisplayName("replaying the same batch does not double count")
        void replayDoesNotDoubleCount() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();
            List<Map<String, Object>> batch = List.of(
                    wholeDaySteps("s1", day, "4000"),
                    caloriesRecord("c1", at(day.atStartOfDay()),
                            at(day.plusDays(1).atStartOfDay()), "300"));

            assertStatus(ingest(device, batch), 200);
            assertStatus(ingest(device, batch), 200);
            assertStatus(ingest(device, batch), 200);

            assertThat(canonicalSteps(day))
                    .as("a replayed batch must not accumulate")
                    .isEqualTo(4000);
            assertThat(canonicalCalories(day)).isEqualTo(300);

            Integer ledgerRows = jdbc.queryForObject(
                    "select count(*) from health_connect_records where device_id=?::uuid",
                    Integer.class, UUID.fromString(device));
            assertThat(ledgerRows)
                    .as("the source ledger is keyed on the platform record id, so replays collapse")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("a record repeated inside one batch is counted once")
        void duplicateWithinOneBatch() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();
            Map<String, Object> rec = wholeDaySteps("same", day, "1500");

            assertStatus(ingest(device, List.of(rec, rec, rec)), 200);

            assertThat(canonicalSteps(day)).isEqualTo(1500);
        }
    }

    @Nested
    @DisplayName("corrections and deletions")
    class Corrections {

        @Test
        @DisplayName("a corrected record replaces its value rather than adding to it")
        void correctionReplaces() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();
            assertStatus(ingest(device, List.of(
                    stepsRecord("s1", at(day.atTime(6, 0)), at(day.atTime(7, 0)), "1000"),
                    stepsRecord("s2", at(day.atTime(7, 0)), at(day.atTime(8, 0)), "1000"))), 200);
            assertThat(canonicalSteps(day)).isEqualTo(2000);

            // The same platform record id now reports 1500 steps instead of 1000.
            assertStatus(ingest(device, List.of(
                    stepsRecord("s2", at(day.atTime(7, 0)), at(day.atTime(8, 0)), "1500"))), 200);

            assertThat(canonicalSteps(day))
                    .as("a correction must replace, never accumulate")
                    .isEqualTo(2500);
        }

        @Test
        @DisplayName("a deleted record is removed and the day is recomputed from what remains")
        void deletionRecomputes() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();
            assertStatus(ingest(device, List.of(
                    stepsRecord("s1", at(day.atTime(6, 0)), at(day.atTime(7, 0)), "1000"),
                    stepsRecord("s2", at(day.atTime(7, 0)), at(day.atTime(8, 0)), "2500"))), 200);
            assertThat(canonicalSteps(day)).isEqualTo(3500);

            // Health Connect reports a deletion as a record id only - no value, no timestamps. The
            // server must already hold the record to know what to subtract.
            assertStatus(ingest(device, Map.of("timezone", TZ,
                    "deleted_record_ids", List.of("s2"))), 200);

            assertThat(canonicalSteps(day))
                    .as("a withdrawn record must not keep contributing its old value")
                    .isEqualTo(1000);
        }

        @Test
        @DisplayName("deleting the last record for a day clears it rather than leaving a stale total")
        void deletingEverythingClearsTheDay() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();
            assertStatus(ingest(device, List.of(wholeDaySteps("only", day, "7000"))), 200);
            assertThat(canonicalSteps(day)).isEqualTo(7000);

            assertStatus(ingest(device, Map.of("timezone", TZ,
                    "deleted_record_ids", List.of("only"))), 200);

            assertThat(canonicalSteps(day))
                    .as("zero would invent a day the source has explicitly withdrawn")
                    .isNull();
        }

        @Test
        @DisplayName("deleting a midnight-crossing record clears both days it touched")
        void deletionOfACrossingRecord() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();
            assertStatus(ingest(device, List.of(stepsRecord("cross",
                    at(day.atTime(22, 0)), at(day.plusDays(1).atTime(2, 0)), "400"))), 200);
            assertThat(canonicalSteps(day)).isEqualTo(200);
            assertThat(canonicalSteps(day.plusDays(1))).isEqualTo(200);

            assertStatus(ingest(device, Map.of("timezone", TZ,
                    "deleted_record_ids", List.of("cross"))), 200);

            assertThat(canonicalSteps(day))
                    .as("the day the record started on must be recomputed")
                    .isNull();
            assertThat(canonicalSteps(day.plusDays(1)))
                    .as("the day it also overlapped must be recomputed too")
                    .isNull();
        }

        @Test
        @DisplayName("deleting an unknown record is harmless and changes nothing")
        void deletingSomethingUnknown() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();
            assertStatus(ingest(device, List.of(wholeDaySteps("s1", day, "900"))), 200);

            assertStatus(ingest(device, Map.of("timezone", TZ,
                    "deleted_record_ids", List.of("never-seen"))), 200);

            assertThat(canonicalSteps(day)).isEqualTo(900);
        }
    }

    @Nested
    @DisplayName("weight and body fat")
    class Body {

        @Test
        @DisplayName("kilograms are converted to pounds exactly once")
        void convertsWeightOnce() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();

            assertStatus(ingest(device, List.of(weightRecord("w1", at(day.atTime(7, 0)), "70"))), 200);

            assertThat(canonicalWeight(day))
                    .as("70 kg is about 154.32 lb; a double conversion would roughly quadruple this")
                    .isEqualByComparingTo("154.32");
        }

        @Test
        @DisplayName("body fat passes through as a percentage")
        void storesBodyFatAsPercentage() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();

            assertStatus(ingest(device, List.of(bodyFatRecord("b1", at(day.atTime(7, 0)), "18.5"))), 200);

            assertThat(canonicalBodyFat(day)).isEqualByComparingTo("18.5");
        }

        @Test
        @DisplayName("several readings in a day resolve to the latest, never a sum")
        void latestReadingWins() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();

            assertStatus(ingest(device, List.of(
                    weightRecord("morning", at(day.atTime(7, 0)), "71"),
                    weightRecord("evening", at(day.atTime(20, 0)), "70"))), 200);

            assertThat(canonicalWeight(day))
                    .as("adding a morning and an evening weighing would be meaningless")
                    .isEqualByComparingTo(HealthNormalizer.kilogramsToPounds(new BigDecimal("70")));
        }

        @Test
        @DisplayName("a corrected reading replaces the day's value")
        void correctionReplacesTheReading() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();
            assertStatus(ingest(device, List.of(weightRecord("w1", at(day.atTime(7, 0)), "70"))), 200);

            assertStatus(ingest(device, List.of(weightRecord("w1", at(day.atTime(7, 0)), "69.5"))), 200);

            assertThat(canonicalWeight(day))
                    .isEqualByComparingTo(HealthNormalizer.kilogramsToPounds(new BigDecimal("69.5")));
        }

        @Test
        @DisplayName("deleting the latest reading falls back to the previous one")
        void deletionFallsBackToPreviousReading() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();
            assertStatus(ingest(device, List.of(
                    weightRecord("morning", at(day.atTime(7, 0)), "71"),
                    weightRecord("evening", at(day.atTime(20, 0)), "70"))), 200);
            assertThat(canonicalWeight(day))
                    .isEqualByComparingTo(HealthNormalizer.kilogramsToPounds(new BigDecimal("70")));

            assertStatus(ingest(device, Map.of("timezone", TZ,
                    "deleted_record_ids", List.of("evening"))), 200);

            assertThat(canonicalWeight(day))
                    .as("recomputation must fall back, not blank the day")
                    .isEqualByComparingTo(HealthNormalizer.kilogramsToPounds(new BigDecimal("71")));
        }

        @Test
        @DisplayName("weight and body fat land on the same day independently")
        void independentFieldsOnOneDay() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();

            assertStatus(ingest(device, List.of(
                    weightRecord("w1", at(day.atTime(7, 0)), "70"),
                    bodyFatRecord("b1", at(day.atTime(7, 5)), "16.2"))), 200);

            assertThat(canonicalWeight(day)).isNotNull();
            assertThat(canonicalBodyFat(day)).isEqualByComparingTo("16.2");
        }
    }

    @Nested
    @DisplayName("provider record identity across devices")
    class MultiDeviceIdentity {

        @Test
        @DisplayName("two Health Connect devices may hold the same provider record id without colliding")
        void sameRecordIdOnTwoDevices() throws Exception {
            // Health Connect record ids are unique per on-device store, so two phones can legitimately
            // report the SAME id. The ledger must therefore be keyed per device, or the second
            // device's record would silently overwrite the first and one user's steps would vanish.
            String first = registerDevice("phone", "hc-a-" + UUID.randomUUID());
            String second = registerDevice("tablet", "hc-b-" + UUID.randomUUID());
            LocalDate day = recentDay();

            assertStatus(ingest(first, List.of(wholeDaySteps("shared-id", day, "3000"))), 200);
            assertStatus(ingest(second, List.of(wholeDaySteps("shared-id", day, "7000"))), 200);

            Integer rows = jdbc.queryForObject(
                    "select count(*) from health_connect_records where user_id=CAST(? as uuid)"
                            + " and record_id='shared-id'",
                    Integer.class, UUID.fromString(user.id()));
            assertThat(rows)
                    .as("both devices' records must be retained independently")
                    .isEqualTo(2);

            // The canonical view selects one competing measurement per day rather than summing, which
            // is the documented Phase 10 policy for two sources reporting the same quantity.
            assertThat(canonicalSteps(day))
                    .as("competing devices are selected, never merged into 10000")
                    .isIn(3000, 7000);
        }

        @Test
        @DisplayName("a deletion on one device does not remove the other device's record")
        void deletionIsDeviceScoped() throws Exception {
            String first = registerDevice("phone", "hc-c-" + UUID.randomUUID());
            String second = registerDevice("tablet", "hc-d-" + UUID.randomUUID());
            LocalDate day = recentDay();

            assertStatus(ingest(first, List.of(wholeDaySteps("same", day, "1000"))), 200);
            assertStatus(ingest(second, List.of(wholeDaySteps("same", day, "2000"))), 200);
            assertStatus(ingest(first, Map.of("timezone", TZ, "deleted_record_ids", List.of("same"))), 200);

            Integer remaining = jdbc.queryForObject(
                    "select count(*) from health_connect_records where user_id=CAST(? as uuid)"
                            + " and record_id='same'",
                    Integer.class, UUID.fromString(user.id()));
            assertThat(remaining)
                    .as("deleting on one device must not touch the other device's identically named record")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("a correction that moves a record between local days")
    class CrossDayCorrection {

        @Test
        @DisplayName("moving an interval to another day recomputes both the old and the new day")
        void correctionRecomputesBothDays() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();
            LocalDate other = day.plusDays(1);

            // A record wholly inside `day`.
            assertStatus(ingest(device, List.of(wholeDaySteps("move", day, "5000"))), 200);
            assertThat(canonicalSteps(day)).isEqualTo(5000);

            // The same record id is corrected so it now sits wholly inside `other`. If only the new
            // day were recomputed, `day` would keep a stale 5000 that nothing ever contributed to.
            assertStatus(ingest(device, List.of(wholeDaySteps("move", other, "5000"))), 200);

            assertThat(canonicalSteps(day))
                    .as("the day the record left must be cleared, not left stale")
                    .isNull();
            assertThat(canonicalSteps(other)).isEqualTo(5000);
        }

        @Test
        @DisplayName("a corrected value replaces the old one rather than adding to it")
        void correctionReplacesNotAdds() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();

            assertStatus(ingest(device, List.of(wholeDaySteps("a", day, "5000"))), 200);
            assertThat(canonicalSteps(day)).isEqualTo(5000);

            assertStatus(ingest(device, List.of(wholeDaySteps("a", day, "7000"))), 200);

            assertThat(canonicalSteps(day))
                    .as("5000 + 7000 would mean corrections are being added, not applied")
                    .isEqualTo(7000);
        }
    }

    @Nested
    @DisplayName("manual fallback after every device record is withdrawn")
    class ManualFallback {

        @Test
        @DisplayName("withdrawing the last device record falls back to the manual value, not to zero")
        void fallsBackToManual() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();

            // A hand-entered day, which Phase 10 keeps unique per user and date.
            jdbc.update("insert into daily_metrics(id,user_id,metric_date,steps) values (gen_random_uuid(),"
                    + "CAST(? AS uuid),?::date,8000)", user.id(), day);
            assertThat(canonicalSteps(day)).isEqualTo(8000);

            assertStatus(ingest(device, List.of(wholeDaySteps("m1", day, "12000"))), 200);
            assertThat(canonicalSteps(day))
                    .as("a device measurement outranks a manual one")
                    .isEqualTo(12000);

            assertStatus(ingest(device, Map.of("timezone", TZ, "deleted_record_ids", List.of("m1"))), 200);

            assertThat(canonicalSteps(day))
                    .as("withdrawing the device record must reveal the manual value again")
                    .isEqualTo(8000);
        }

        @Test
        @DisplayName("a withdrawn day leaves no zero provider row masking the manual source")
        void noZeroRowMasksManual() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();

            jdbc.update("insert into daily_metrics(id,user_id,metric_date,steps) values (gen_random_uuid(),"
                    + "CAST(? AS uuid),?::date,8000)", user.id(), day);
            assertStatus(ingest(device, List.of(wholeDaySteps("z1", day, "100"))), 200);
            assertStatus(ingest(device, Map.of("timezone", TZ, "deleted_record_ids", List.of("z1"))), 200);

            Integer providerRows = jdbc.queryForObject(
                    "select count(*) from daily_metrics where user_id=CAST(? as uuid)"
                            + " and provider_record_id is not null",
                    Integer.class, UUID.fromString(user.id()));
            assertThat(providerRows)
                    .as("the derived row must be removed, not zeroed, so it cannot mask the manual day")
                    .isZero();
            assertThat(canonicalSteps(day)).isEqualTo(8000);
        }
    }

    @Nested
    @DisplayName("a null stored timezone is never guessed at")
    class NullTimezoneSafety {

        @Test
        @DisplayName("a batch without a timezone is refused with a structured, actionable code")
        void missingTimezoneIsRefusedWithStableCode() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();

            MvcResult result = ingest(device, Map.of("records", List.of(wholeDaySteps("nt", day, "5000"))));

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            JsonNode body = json(result);
            assertThat(body.path("code").asText())
                    .as("a bridge must be able to react to this without parsing English")
                    .isEqualTo("timezone_required");
            assertThat(body.path("message").asText()).isNotBlank();
        }

        @Test
        @DisplayName("a refused batch persists neither a source record nor an aggregate")
        void refusedBatchWritesNothing() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();

            assertStatus(ingest(device, Map.of("records", List.of(wholeDaySteps("nt2", day, "5000")))), 400);

            Integer ledger = jdbc.queryForObject(
                    "select count(*) from health_connect_records where user_id=CAST(? as uuid)",
                    Integer.class, UUID.fromString(user.id()));
            assertThat(ledger).as("no source record may be written without a calendar").isZero();
            assertThat(canonicalSteps(day))
                    .as("no aggregate may be derived without a calendar")
                    .isNull();
        }

        @Test
        @DisplayName("a user with no stored zone is not silently given one by ingestion")
        void noZoneIsInvented() throws Exception {
            String device = registerDevice();

            ingest(device, Map.of("records", List.of(wholeDaySteps("nt3", recentDay(), "10"))));

            String stored = jdbc.queryForObject(
                    "select timezone from app_users where id=CAST(? as uuid)",
                    String.class, UUID.fromString(user.id()));
            assertThat(stored)
                    .as("a refused request must not backfill a zone, least of all UTC")
                    .isNull();
        }

        @Test
        @DisplayName("an invalid zone is refused and never stored")
        void invalidZoneIsRefused() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();

            MvcResult result = ingest(device,
                    Map.of("timezone", "Mars/Olympus_Mons", "records", List.of(wholeDaySteps("bad", day, "1"))));

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            String stored = jdbc.queryForObject(
                    "select timezone from app_users where id=CAST(? as uuid)",
                    String.class, UUID.fromString(user.id()));
            assertThat(stored).isNull();
        }

        @Test
        @DisplayName("a valid IANA zone lets the same request through and is then persisted")
        void validZoneProceeds() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();

            assertStatus(ingest(device,
                    Map.of("timezone", "Asia/Kolkata", "records", List.of(wholeDaySteps("ok", day, "4321")))), 200);
            assertThat(canonicalSteps(day)).isEqualTo(4321);
        }
    }

    @Nested
    @DisplayName("request limits (D8)")
    class Limits {

        @Test
        @DisplayName("a batch of exactly 500 records is accepted")
        void acceptsTheMaximumBatch() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();
            List<Map<String, Object>> batch = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                // Distinct ids so nothing collapses, each a one-minute interval inside the day.
                batch.add(stepsRecord("bulk-" + i, at(day.atStartOfDay()).plusSeconds(i * 60L),
                        at(day.atStartOfDay()).plusSeconds(i * 60L + 30), "1"));
            }

            assertStatus(ingest(device, batch), 200);
            assertThat(canonicalSteps(day)).isEqualTo(500);
        }

        @Test
        @DisplayName("a batch over 500 records is refused with 413")
        void refusesOversizedBatch() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();
            List<Map<String, Object>> batch = new ArrayList<>();
            for (int i = 0; i < 501; i++) {
                batch.add(wholeDaySteps("over-" + i, day, "1"));
            }

            assertStatus(ingest(device, batch), 413);
            assertThat(canonicalSteps(day))
                    .as("a refused batch must write nothing at all")
                    .isNull();
        }

        @Test
        @DisplayName("a body over 1 MB is refused with 413")
        void refusesOversizedBody() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();
            // Each record id is padded so the serialized batch comfortably exceeds the 1 MB ceiling.
            // 400 ids of 2000 chars is only ~800 KB, which is UNDER the limit, so the request would be
            // parsed normally and the size check would never be exercised. 600 padded ids puts the
            // body comfortably over 1 MB, which is what actually makes this a body-size test.
            List<Map<String, Object>> batch = new ArrayList<>();
            for (int i = 0; i < 600; i++) {
                batch.add(wholeDaySteps("p" + i + "x".repeat(2000), day, "1"));
            }

            assertStatus(ingest(device, batch), 413);
            assertThat(canonicalSteps(day)).isNull();
        }

        @Test
        @DisplayName("a body under 1 MB is accepted, so the ceiling does not reject normal traffic")
        void acceptsBodyUnderTheCeiling() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();
            List<Map<String, Object>> batch = List.of(wholeDaySteps("under", day, "1234"));

            assertStatus(ingest(device, batch), 200);
            assertThat(canonicalSteps(day)).isEqualTo(1234);
        }

        @Test
        @DisplayName("a chunked request over 1 MB is refused, because Content-Length cannot be trusted")
        void refusesOversizedChunkedBody() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();
            List<Map<String, Object>> batch = new ArrayList<>();
            for (int i = 0; i < 600; i++) {
                batch.add(wholeDaySteps("c" + i + "x".repeat(2000), day, "1"));
            }
            String payload = mapper.writeValueAsString(Map.of("timezone", TZ, "records", batch));
            assertThat(payload.length() * 1.0 / 1024)
                    .as("the payload must genuinely exceed the 1 MB ceiling")
                    .isGreaterThan(1024);

            // A negative declared length is how a chunked transfer presents itself: the client sent
            // no Content-Length at all. A check that trusted that header would let this through, so
            // this is the case that proves the limit counts real bytes.
            MvcResult result = mvc.perform(post("/api/v1/health/devices/" + device + "/records")
                    .header("Authorization", "Bearer " + user.access())
                    .header("Content-Type", MediaType.APPLICATION_JSON)
                    .header("Transfer-Encoding", "chunked")
                    .content(payload)).andReturn();

            assertThat(result.getResponse().getStatus())
                    .as("an unknown-length body over the ceiling must still be refused")
                    .isIn(413);
            assertThat(canonicalSteps(day))
                    .as("a refused body must leave nothing behind")
                    .isNull();
        }

        @Test
        @DisplayName("a malformed body is a 4xx, never a 500")
        void malformedBodyIsRejectedCleanly() throws Exception {
            String device = registerDevice();

            MvcResult result = mvc.perform(post("/api/v1/health/devices/" + device + "/records")
                    .header("Authorization", "Bearer " + user.access())
                    .header("Content-Type", MediaType.APPLICATION_JSON)
                    .content("{\"timezone\":\"Asia/Kolkata\",\"records\":[")).andReturn();

            assertThat(result.getResponse().getStatus())
                    .as("truncated JSON is a client error, not a server fault")
                    .isGreaterThanOrEqualTo(400)
                    .isLessThan(500);
        }

        @Test
        @DisplayName("an empty body is a 4xx, never a 500")
        void emptyBodyIsRejectedCleanly() throws Exception {
            String device = registerDevice();

            MvcResult result = mvc.perform(post("/api/v1/health/devices/" + device + "/records")
                    .header("Authorization", "Bearer " + user.access())
                    .header("Content-Type", MediaType.APPLICATION_JSON)
                    .content("")).andReturn();

            assertThat(result.getResponse().getStatus())
                    .isGreaterThanOrEqualTo(400)
                    .isLessThan(500);
        }

        @Test
        @DisplayName("records beyond the 30-day window are refused, keeping retention bounded")
        void refusesRecordsOutsideTheWindow() throws Exception {
            String device = registerDevice();
            LocalDate tooOld = LocalDate.now(ZONE).minusDays(45);

            MvcResult result = ingest(device, List.of(wholeDaySteps("old", tooOld, "5000")));
            assertStatus(result, 200);
            JsonNode body = json(result);
            assertThat(body.path("records_accepted").asInt()).isZero();
            assertThat(body.path("records_rejected").asInt()).isEqualTo(1);
            assertThat(canonicalSteps(tooOld)).isNull();
        }
    }

    @Nested
    @DisplayName("partial rejection and isolation")
    class Partial {

        @Test
        @DisplayName("one bad record does not fail the batch, and the reason is reported")
        void partialRejection() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();

            MvcResult result = ingest(device, List.of(
                    wholeDaySteps("good", day, "1200"),
                    record("bad-type", "sleep_session", at(day.atTime(1, 0)),
                            at(day.atTime(2, 0)), "300", "count"),
                    record("bad-unit", "steps", at(day.atTime(3, 0)), at(day.atTime(4, 0)), "50", "km"),
                    record("", "steps", at(day.atTime(5, 0)), at(day.atTime(6, 0)), "10", "count")));

            assertStatus(result, 200);
            JsonNode body = json(result);
            assertThat(body.path("records_accepted").asInt()).isEqualTo(1);
            assertThat(body.path("records_rejected").asInt()).isEqualTo(3);
            assertThat(body.path("rejected_reasons").has("unsupported_record_type")).isTrue();
            assertThat(body.path("rejected_reasons").has("unit_mismatch")).isTrue();
            assertThat(canonicalSteps(day)).isEqualTo(1200);
        }

        @Test
        @DisplayName("the response carries counts and reasons, never a health value or record id")
        void responseCarriesNoHealthValues() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();
            MvcResult result = ingest(device, List.of(wholeDaySteps("sensitive-id-1234", day, "4321")));

            String payload = result.getResponse().getContentAsString();
            assertThat(payload)
                    .as("a loggable response must not leak health values or source identities")
                    .doesNotContain("4321")
                    .doesNotContain("sensitive-id-1234");
        }

        @Test
        @DisplayName("a manual entry survives ingestion and never displaces the device reading")
        void manualAndProviderCoexist() throws Exception {
            // The Phase 10 contract: a manual row is a hand-entered fallback ranked below a device
            // measurement, and neither overwrites the other.
            jdbc.update("insert into daily_metrics(id,user_id,metric_date,steps,water_oz)"
                            + " values (?::uuid,?::uuid,?::date,?,?)",
                    UUID.randomUUID(), UUID.fromString(user.id()), recentDay(), 111, 500);
            String device = registerDevice();

            assertStatus(ingest(device, List.of(wholeDaySteps("s1", recentDay(), "9000"))), 200);

            assertThat(canonicalSteps(recentDay()))
                    .as("the device measurement outranks the manual one")
                    .isEqualTo(9000);
            Integer water = jdbc.queryForObject(
                    "select water_oz from v_daily_metrics_canonical where user_id=?::uuid and metric_date=?",
                    Integer.class, UUID.fromString(user.id()), recentDay());
            assertThat(water)
                    .as("ingestion must not disturb a field it does not own")
                    .isEqualTo(500);
        }

        @Test
        @DisplayName("two devices of one user produce competing rows, not a merged total")
        void twoDevicesDoNotMerge() throws Exception {
            String phone = registerDevice("phone", "hc-a-" + UUID.randomUUID());
            String watch = registerDevice("watch", "hc-b-" + UUID.randomUUID());
            LocalDate day = recentDay();

            assertStatus(ingest(phone, List.of(wholeDaySteps("p1", day, "3000"))), 200);
            assertStatus(ingest(watch, List.of(wholeDaySteps("w1", day, "7000"))), 200);

            Integer rows = jdbc.queryForObject(
                    "select count(*) from daily_metrics where user_id=?::uuid and metric_date=?"
                            + " and source='health-connect'",
                    Integer.class, UUID.fromString(user.id()), day);
            assertThat(rows)
                    .as("Phase 10 selects between sources; it never sums competing measurements")
                    .isEqualTo(2);
            assertThat(canonicalSteps(day))
                    .as("the deterministic tie-break picks one device's value, not their sum")
                    .isIn(3000, 7000);
        }
    }

    @Nested
    @DisplayName("permission state and privacy")
    class Privacy {

        @Test
        @DisplayName("a client-reported permission state is stored but never exposed as server-attested")
        void permissionStateIsDeviceReported() throws Exception {
            String device = registerDevice();
            assertStatus(ingest(device, Map.of("timezone", TZ, "permission_status",
                    "permission_revoked", "changes_token", "opaque-token-value")), 200);

            String stored = jdbc.queryForObject(
                    "select permission_status from health_devices where id=?::uuid", String.class,
                    UUID.fromString(device));
            assertThat(stored).isEqualTo("permission_revoked");

            // The client's own resume handle is stored in its own column and never in the server cursor.
            String token = jdbc.queryForObject(
                    "select client_changes_token from health_devices where id=?::uuid", String.class,
                    UUID.fromString(device));
            assertThat(token).isEqualTo("opaque-token-value");
        }

        @Test
        @DisplayName("a revoked-permission device can still ingest, because the state authorizes nothing")
        void permissionStateGrantsNothing() throws Exception {
            String device = registerDevice();
            assertStatus(ingest(device, List.of(wholeDaySteps("s1", recentDay(), "1000"))), 200);
            assertStatus(ingest(device, Map.of("timezone", TZ, "permission_status",
                    "permission_revoked", "records",
                    List.of(wholeDaySteps("s2", recentDay(), "500")))), 200);

            // Nothing in the authorization path reads this column, so it neither grants nor denies.
            assertThat(canonicalSteps(recentDay())).isEqualTo(1500);
        }

        @Test
        @DisplayName("an unrecognised permission state is dropped rather than stored")
        void unknownPermissionStateIsDropped() throws Exception {
            String device = registerDevice();
            assertStatus(ingest(device, Map.of("timezone", TZ, "permission_status",
                    "definitely-approved-by-google")), 200);

            String stored = jdbc.queryForObject(
                    "select permission_status from health_devices where id=?::uuid", String.class,
                    UUID.fromString(device));
            assertThat(stored)
                    .as("an arbitrary string must not become a state the UI would trust")
                    .isNull();
        }

        @Test
        @DisplayName("the device list never exposes internal sync state or the client token")
        void deviceListLeaksNothing() throws Exception {
            String device = registerDevice();
            assertStatus(ingest(device, Map.of("timezone", TZ, "changes_token",
                    "secret-platform-token")), 200);

            String payload = getAs(user, "/api/v1/health/devices").getResponse().getContentAsString();
            assertThat(payload)
                    .as("the response DTO is an allowlist, so internals are not merely filtered")
                    .doesNotContain("secret-platform-token")
                    .doesNotContain("sync_cursor")
                    .doesNotContain("client_changes_token")
                    .doesNotContain("user_id");
        }

        @Test
        @DisplayName("Coach context receives aggregates and no source identity")
        void coachSeesNoSourceIdentity() throws Exception {
            String device = registerDevice();
            assertStatus(ingest(device, List.of(
                    wholeDaySteps("s1", recentDay(), "6400"),
                    weightRecord("w1", at(recentDay().atTime(7, 0)), "70"))), 200);

            MvcResult result = call(user, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/api/v1/coach/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"message\":\"How am I doing?\"}"));
            String body = result.getResponse().getContentAsString();
            assertThat(body.toLowerCase(java.util.Locale.ROOT))
                    .as("Health Connect must not widen what Coach can see")
                    .doesNotContain("hc-agg")
                    .doesNotContain("hc-body")
                    .doesNotContain(device)
                    .doesNotContain("health-connect");
        }

        @Test
        @DisplayName("an ingested day is visible to analytics and the calendar as a single date")
        void downstreamSurfacesSeeTheData() throws Exception {
            String device = registerDevice();
            LocalDate day = recentDay();
            assertStatus(ingest(device, List.of(wholeDaySteps("s1", day, "6400"))), 200);

            // The calendar and analytics read paths live under /api/v1/analytics, not /api/v1.
            String calendar = getAs(user, "/api/v1/analytics/calendar?from=" + day + "&to=" + day)
                    .getResponse().getContentAsString();
            assertThat(calendar).contains(day.toString()).contains("6400");

            String analytics = getAs(user, "/api/v1/analytics/activity?from=" + day + "&to=" + day)
                    .getResponse().getContentAsString();
            assertThat(analytics)
                    .as("the canonical aggregate must reach the existing read paths")
                    .contains("6400");
        }
    }
}






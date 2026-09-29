package com.fittrack.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The timezone model (D3).
 *
 * <p>The important assertions here are negative ones: an unresolvable zone must be <b>refused</b>,
 * never quietly replaced. A silently substituted zone would file a user's health data under a calendar
 * day that is not theirs, and nothing downstream would reveal the mistake.
 */
class UserTimezoneTest {

    @ParameterizedTest
    @ValueSource(strings = { "Asia/Kolkata", "Europe/London", "America/New_York", "UTC", "Pacific/Auckland" })
    @DisplayName("a real IANA zone is accepted and canonicalised")
    void acceptsRealZones(String zone) {
        assertThat(UserTimezone.canonical(zone)).isEqualTo(zone);
        assertThat(UserTimezone.isValid(zone)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Mars/Olympus",       // well-formed but not a real zone
            "Not A Zone",         // contains spaces
            "Asia/Kolkata/Extra", // over-qualified
            "12345",              // a number
            "GMT+25:00"           // an out-of-range offset
    })
    @DisplayName("an unresolvable zone is rejected rather than stored or defaulted")
    void rejectsUnknownZones(String zone) {
        assertThat(UserTimezone.canonical(zone))
                .as("%s must not resolve, or health data would be silently mis-dated", zone)
                .isNull();
        assertThat(UserTimezone.isValid(zone)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   " })
    @DisplayName("an absent zone is absent, not a default")
    void absentIsNotDefault(String zone) {
        assertThat(UserTimezone.canonical(zone)).isNull();
        // With no fallback permitted there is genuinely no answer, which is the honest outcome.
        assertThat(UserTimezone.resolve(zone, false)).isNull();
    }

    @Test
    @DisplayName("surrounding whitespace is tolerated and normalised away")
    void toleratesWhitespace() {
        assertThat(UserTimezone.canonical("  Asia/Kolkata  ")).isEqualTo("Asia/Kolkata");
    }

    @Test
    @DisplayName("an explicit fallback is available only when a caller opts in")
    void fallbackIsOptIn() {
        assertThat(UserTimezone.resolve(null, true)).isEqualTo(UserTimezone.FALLBACK_UTC);
        assertThat(UserTimezone.resolve("Mars/Olympus", true))
                .as("an invalid zone must not be masked by the fallback either")
                .isEqualTo(UserTimezone.FALLBACK_UTC);
        assertThat(UserTimezone.resolve("Asia/Kolkata", false)).isEqualTo("Asia/Kolkata");
    }

    @Test
    @DisplayName("require throws for an unusable zone, so the health path can never guess")
    void requireIsStrict() {
        assertThat(UserTimezone.require("Europe/London")).isEqualTo("Europe/London");
        assertThatThrownBy(() -> UserTimezone.require("Mars/Olympus"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IANA");
        assertThatThrownBy(() -> UserTimezone.require(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("zoneId resolves to a ZoneId the aggregator can actually use")
    void zoneIdIsUsable() {
        assertThat(UserTimezone.zoneId("Asia/Kolkata")).isEqualTo(ZoneId.of("Asia/Kolkata"));
        assertThat(UserTimezone.zoneId("Mars/Olympus")).isNull();
    }

    @Test
    @DisplayName("the offered zone list is non-empty and sorted, so a client can cache it")
    void offersAStableZoneList() {
        assertThat(UserTimezone.availableZones())
                .as("a client needs a real list of zones to offer")
                .isNotEmpty()
                .contains("Asia/Kolkata", "Europe/London", "UTC");
    }
}

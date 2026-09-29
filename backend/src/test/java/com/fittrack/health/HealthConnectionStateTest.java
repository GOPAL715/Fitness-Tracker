package com.fittrack.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 20: the connection state model.
 *
 * <p>Derived from the {@code sync_status} column that has always existed, so no database is needed.
 * The properties that matter to a user are pinned here: the five states are reachable and
 * distinguishable, and a value the server cannot interpret is never reported as a working connection.
 */
class HealthConnectionStateTest {

    @Test
    @DisplayName("Phase 20 - each stored sync status maps to the state a person would recognise")
    void storedStatusMapsToAState() {
        assertThat(HealthConnectionState.of(null)).isEqualTo(HealthConnectionState.DISCONNECTED);
        assertThat(HealthConnectionState.of("")).isEqualTo(HealthConnectionState.DISCONNECTED);
        // A row that exists but has never completed a pass is genuinely still being set up, which is
        // what the product means by "connecting" and what the UI already called "not synced yet".
        assertThat(HealthConnectionState.of("idle")).isEqualTo(HealthConnectionState.CONNECTING);
        assertThat(HealthConnectionState.of("syncing")).isEqualTo(HealthConnectionState.SYNCING);
        assertThat(HealthConnectionState.of("synced")).isEqualTo(HealthConnectionState.CONNECTED);
        assertThat(HealthConnectionState.of("error")).isEqualTo(HealthConnectionState.SYNC_FAILED);
    }

    @Test
    @DisplayName("Phase 20 - every state a client may render is reachable from some stored value")
    void everyStateIsReachable() {
        // A state a client can never receive is dead UI code. This proves the five the product needs
        // all have a real source in the column that already exists.
        assertThat(List.of(HealthConnectionState.of("idle"), HealthConnectionState.of("syncing"),
                HealthConnectionState.of("synced"), HealthConnectionState.of("error"),
                HealthConnectionState.of(null)))
                .containsExactlyInAnyOrderElementsOf(HealthConnectionState.allStates());
    }

    @Test
    @DisplayName("Phase 20 - a value the server cannot read is reported as a failure, not a success")
    void unknownStatusIsNeverReportedAsWorking() {
        // A connection whose last known state cannot be interpreted is not evidence that anything
        // worked. Defaulting it to "connected" would show a green badge on the strength of a value
        // the server does not understand.
        for (String unreadable : List.of("SUCCESS", "completed", "0", "true", "unknown", "done")) {
            assertThat(HealthConnectionState.of(unreadable))
                    .as("status %s must not read as a working connection", unreadable)
                    .isEqualTo(HealthConnectionState.SYNC_FAILED);
        }
        // Case and padding are the driver's business, not a state change, so they normalise.
        assertThat(HealthConnectionState.of("  SYNCED  ")).isEqualTo(HealthConnectionState.CONNECTED);
        // Blank is absence rather than a corrupt value: a row the driver returned nothing for has
        // said nothing about whether a sync ever worked, so it is reported as not connected rather
        // than as a failure the user has to act on.
        assertThat(HealthConnectionState.of("   ")).isEqualTo(HealthConnectionState.DISCONNECTED);
    }

    @Test
    @DisplayName("Phase 20 - several connections report the one a person most needs to act on")
    void aggregateReportsTheMostActionableState() {
        // One provider can legitimately own several rows - two watches, or a watch and a scale. A
        // single badge has to choose, and a failure outranks a healthy sibling because someone
        // looking at this screen needs to know what is broken before what works.
        assertThat(HealthConnectionState.aggregate(List.of())).isEqualTo(HealthConnectionState.DISCONNECTED);
        assertThat(HealthConnectionState.aggregate(List.of(HealthConnectionState.CONNECTED,
                HealthConnectionState.CONNECTED))).isEqualTo(HealthConnectionState.CONNECTED);
        assertThat(HealthConnectionState.aggregate(List.of(HealthConnectionState.CONNECTED,
                HealthConnectionState.SYNCING))).isEqualTo(HealthConnectionState.SYNCING);
        assertThat(HealthConnectionState.aggregate(List.of(HealthConnectionState.CONNECTED,
                HealthConnectionState.CONNECTING))).isEqualTo(HealthConnectionState.CONNECTING);
        assertThat(HealthConnectionState.aggregate(List.of(HealthConnectionState.CONNECTED,
                HealthConnectionState.SYNC_FAILED))).isEqualTo(HealthConnectionState.SYNC_FAILED);
        assertThat(HealthConnectionState.aggregate(List.of(HealthConnectionState.SYNCING,
                HealthConnectionState.SYNC_FAILED))).isEqualTo(HealthConnectionState.SYNC_FAILED);
    }

    @Test
    @DisplayName("Phase 20 - aggregate does not depend on the order connections are listed in")
    void aggregateIsOrderIndependent() {
        // The controller groups rows by an ORDER BY, not by anything meaningful, so a badge that
        // changed with row order would be reporting the database's mood rather than the connection's.
        assertThat(HealthConnectionState.aggregate(List.of(HealthConnectionState.CONNECTED,
                HealthConnectionState.CONNECTING, HealthConnectionState.SYNCING)))
                .isEqualTo(HealthConnectionState.aggregate(List.of(HealthConnectionState.SYNCING,
                        HealthConnectionState.CONNECTING, HealthConnectionState.CONNECTED)));
    }

    @Test
    @DisplayName("Phase 20 - nulls never make a disconnected provider look connected")
    void nullEntriesAreIgnored() {
        assertThat(HealthConnectionState.aggregate(java.util.Arrays.asList(
                (String) null, HealthConnectionState.DISCONNECTED)))
                .isEqualTo(HealthConnectionState.DISCONNECTED);
        assertThat(HealthConnectionState.isKnown(null)).isFalse();
        assertThat(HealthConnectionState.isKnown("connected")).isTrue();
    }
}

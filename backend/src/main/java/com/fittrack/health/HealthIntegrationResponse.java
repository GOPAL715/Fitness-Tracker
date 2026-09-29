package com.fittrack.health;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * The client-facing view of one health integration: what the provider is, and what this user has
 * connected to it.
 *
 * <p>This is an allowlist like {@link HealthDeviceResponse}, and for the same reason. The alternative
 * - returning catalogue rows and connection rows as loose maps - would mean any column or field added
 * later is published automatically. {@code credentialModel} is a declared constant rather than a
 * read from anywhere, precisely so no descriptor can leak a real credential value by being populated
 * from the wrong source.
 *
 * <p>No provider token, client secret, {@code sync_cursor}, {@code client_changes_token},
 * {@code user_id} or provider record id appears here, and none can: none is a field of this record.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HealthIntegrationResponse(
        String provider,
        String label,
        /** One of the {@code availability} wire values, e.g. {@code native_bridge}. */
        String availability,
        /** How a connection is established, in plain words rather than a protocol name. */
        String authModel,
        /**
         * What credential FitTrack would hold for this provider.
         *
         * <p>{@code "none"} for every provider in this build, and that is a statement of fact: no
         * adapter performs an OAuth exchange, so there is no token, refresh token or client secret
         * for FitTrack to hold and no column in which to keep one. A client may use this to explain
         * why it is never asked for a credential, and must never use it to infer that one exists
         * elsewhere.
         */
        String credentialModel,
        /** Canonical metric names the existing pipeline can store for this provider today. */
        List<String> supportedMetrics,
        /** Whether a connection row can be created for this provider in this build. */
        boolean connectable,
        /** The honest explanation of what reaching this provider would require. */
        String boundary,
        /** One of the five {@link HealthConnectionState} values. */
        String connectionState,
        /** This user's own connections to this provider; never another user's. */
        List<HealthDeviceResponse> connections) {

    public HealthIntegrationResponse {
        connections = connections == null ? List.of() : List.copyOf(connections);
    }
}

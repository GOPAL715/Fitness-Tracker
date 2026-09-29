package com.fittrack.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 20: the provider catalogue.
 *
 * <p>A pure function of code, so it is verified without a database. The properties pinned are the
 * ones a client depends on and the two that keep the server honest: the catalogue must not drift
 * from the allowlist, and no provider may claim a capability or a credential this build does not have.
 */
class HealthProviderCatalogTest {

    @Test
    @DisplayName("Phase 20 - the catalogue and the allowlist describe exactly the same providers")
    void catalogueAgreesWithTheAllowlist() {
        // This is the invariant that makes the server the single source of truth. If either set gains
        // a provider without the other, a client could be shown a provider the server would reject on
        // registration, and nothing else in the build would notice.
        assertThat(HealthProviderCatalog.disagreementWith(HealthProviders.SUPPORTED))
                .as("catalogue and allowlist must cover identical provider keys")
                .isEmpty();
    }

    @Test
    @DisplayName("Phase 20 - the in-process test provider is never offered to a client")
    void testProviderIsNotClientVisible() {
        // The key stays in the allowlist so the test double produces consistently attributed rows, but
        // a browser must never be offered an integration that reaches no external service.
        assertThat(HealthProviders.SUPPORTED).contains("fake-wearable");
        assertThat(HealthProviderCatalog.clientVisible())
                .extracting(HealthProviderCatalog.ProviderDescriptor::key)
                .doesNotContain("fake-wearable");
        assertThat(HealthProviderCatalog.find("fake-wearable"))
                .get()
                .extracting(HealthProviderCatalog.ProviderDescriptor::clientVisible)
                .isEqualTo(false);
    }

    @Test
    @DisplayName("Phase 20 - no provider claims to hold a credential")
    void noProviderClaimsACredential() {
        // FitTrack ships no OAuth exchange, so it holds no access token, refresh token or client
        // secret for any provider, and no column exists to keep one in. A descriptor claiming a
        // credential model would tell a client to expect a flow that cannot exist.
        assertThat(HealthProviderCatalog.all())
                .extracting(HealthProviderCatalog.ProviderDescriptor::credentialModel)
                .containsOnly("none");
    }

    @Test
    @DisplayName("Phase 20 - no provider claims to be reachable in this build")
    void noProviderClaimsAvailability() {
        // Every shipped provider needs a native app or a server-side OAuth application that FitTrack
        // does not ship. Reporting any of them as available would be a capability claim the code
        // cannot back up. The comparison is on the wire form, because that is what a client sees.
        assertThat(HealthProviderCatalog.clientVisible())
                .extracting(d -> d.availability().wire())
                .doesNotContain("available")
                .allSatisfy(wire ->
                        assertThat(wire).isIn("native_bridge", "server_credentials_required",
                                "native_app_required", "manual"));
    }

    @Test
    @DisplayName("Phase 20 - only a provider that can actually be reached is connectable")
    void connectabilityMatchesReachability() {
        // Health Connect and manual entry have a path that works in this build. The OAuth and
        // native-app-only providers do not, so offering a connect action for them would create a row
        // no pipeline could ever fill.
        for (HealthProviderCatalog.ProviderDescriptor descriptor : HealthProviderCatalog.clientVisible()) {
            assertThat(descriptor.connectable())
                    .as("%s connectable", descriptor.key())
                    .isEqualTo(descriptor.availability() == HealthProviderCatalog.Availability.NATIVE_BRIDGE
                            || descriptor.availability() == HealthProviderCatalog.Availability.MANUAL);
        }
    }

    @Test
    @DisplayName("Phase 20 - every descriptor is fully described and says something honest")
    void everyDescriptorIsDescribed() {
        for (HealthProviderCatalog.ProviderDescriptor descriptor : HealthProviderCatalog.all()) {
            assertThat(descriptor.key()).isNotBlank();
            assertThat(descriptor.label()).as("label for %s", descriptor.key()).isNotBlank();
            assertThat(descriptor.authModel()).as("auth model for %s", descriptor.key()).isNotBlank();
            // A boundary that is blank, or that says nothing, is worse than none: stating the
            // integration boundary honestly is the entire purpose of the field.
            assertThat(descriptor.boundary())
                    .as("boundary for %s must be a real explanation", descriptor.key())
                    .isNotBlank()
                    .hasSizeGreaterThan(40);
        }
    }

    @Test
    @DisplayName("Phase 20 - the metric list names only metrics the pipeline can store")
    void metricsAreRealCanonicalNames() {
        List<String> real = List.of("steps", "active_minutes", "calories_burned", "weight_lb",
                "body_fat_pct", "active_calories", "weight", "body_fat");
        for (HealthProviderCatalog.ProviderDescriptor descriptor : HealthProviderCatalog.all()) {
            assertThat(descriptor.supportedMetrics())
                    .as("metrics for %s", descriptor.key())
                    .isNotEmpty()
                    .allSatisfy(metric -> assertThat(real).contains(metric));
        }
    }

    @Test
    @DisplayName("Phase 20 - a provider is found by key regardless of case or surrounding space")
    void lookupNormalisesLikeTheAllowlist() {
        // The same normalisation the allowlist applies, so a lookup here and a validation there can
        // never disagree about whether a key is recognised.
        assertThat(HealthProviderCatalog.find("  FitBit  "))
                .get()
                .extracting(HealthProviderCatalog.ProviderDescriptor::key)
                .isEqualTo("fitbit");
        assertThat(HealthProviderCatalog.find("not-a-provider")).isEmpty();
        assertThat(HealthProviderCatalog.find(null)).isEmpty();
    }

    @Test
    @DisplayName("Phase 20 - a drift between the two sets is reported rather than silently tolerated")
    void driftIsDetectable() {
        // Proves the guard actually detects a mismatch instead of always returning empty, which would
        // make the invariant above pass for the wrong reason. The realistic case is modelled: a
        // provider is added to the allowlist but nobody wrote a descriptor for it.
        List<String> allowlistPlusOne = new java.util.ArrayList<>(HealthProviders.SUPPORTED);
        allowlistPlusOne.add("a-provider-nobody-described");
        assertThat(HealthProviderCatalog.disagreementWith(allowlistPlusOne))
                .containsExactly("a-provider-nobody-described");
        // And the reverse: a descriptor with no allowlist entry would let a connection be created
        // under a key the catalogue does not describe.
        assertThat(HealthProviderCatalog.disagreementWith(List.of("fitbit")))
                .as("a described provider missing from the allowlist is also a drift")
                .contains("apple-health");
    }
}

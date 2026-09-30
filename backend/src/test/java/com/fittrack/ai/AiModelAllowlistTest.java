package com.fittrack.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 22: configured model names are validated against an allowlist.
 *
 * <p>Pure unit tests: the component is constructor-validated, so nothing needs a Spring context and
 * a rejection is provable without a provider, a network call or a credential.
 */
class AiModelAllowlistTest {

    @Test
    @DisplayName("a supported text and vision model is accepted")
    void validModelsAreAccepted() {
        AiModelAllowlist allowlist =
                new AiModelAllowlist("gpt-4o-mini", "gpt-4o-mini", "gpt-4o-mini");

        assertThat(allowlist.supported()).containsExactly("gpt-4o-mini");
        assertThat(allowlist.isSupported("gpt-4o-mini")).isTrue();
    }

    @Test
    @DisplayName("distinct supported vision and text models are both accepted")
    void distinctModelsAreAccepted() {
        AiModelAllowlist allowlist = new AiModelAllowlist("model-a", "model-b", "model-a, model-b");

        assertThat(allowlist.supported()).containsExactlyInAnyOrder("model-a", "model-b");
    }

    @Test
    @DisplayName("a mistyped text model fails startup with a message naming the property")
    void mistypedTextModelIsRejected() {
        assertThatThrownBy(() -> new AiModelAllowlist("gpt-4o-mini", "gpt-4o-minni", "gpt-4o-mini"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.ai-text-model")
                .hasMessageContaining("gpt-4o-minni")
                .hasMessageContaining("gpt-4o-mini")
                .hasMessageContaining("app.ai-supported-models");
    }

    @Test
    @DisplayName("a mistyped vision model fails startup too")
    void mistypedVisionModelIsRejected() {
        assertThatThrownBy(() -> new AiModelAllowlist("gpt-4o-minni", "gpt-4o-mini", "gpt-4o-mini"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.ai-vision-model");
    }

    @Test
    @DisplayName("both bad models are reported in one failure, not one deploy at a time")
    void bothBadModelsFailTogether() {
        assertThatThrownBy(() -> new AiModelAllowlist("vision-typo", "text-typo", "gpt-4o-mini"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.ai-vision-model")
                .hasMessageContaining("app.ai-text-model");
    }

    @Test
    @DisplayName("an empty allowlist is rejected rather than silently disabling validation")
    void emptyAllowlistIsRejected() {
        assertThatThrownBy(() -> new AiModelAllowlist("gpt-4o-mini", "gpt-4o-mini", "  ,  ,"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least one model");
    }

    @Test
    @DisplayName("the test stand-in models are accepted when configured, as the suite does")
    void testStandInsAreConfigurableRatherThanSpecialCased() {
        // There is no test-only bypass in the component; the suite simply lists its stand-ins.
        AiModelAllowlist allowlist = new AiModelAllowlist(
                "fake-vision-model", "fake-text-model", "fake-vision-model,fake-text-model");

        assertThat(allowlist.isSupported("fake-text-model")).isTrue();
        assertThat(allowlist.isSupported("anything-else")).isFalse();
    }

    @Test
    @DisplayName("no API key ever reaches the error message")
    void errorsCarryNoSecret() {
        assertThatThrownBy(() -> new AiModelAllowlist("bad-model", "gpt-4o-mini", "gpt-4o-mini"))
                .satisfies(e -> assertThat(e.getMessage())
                        .doesNotContain("sk-")
                        .doesNotContain("api_key")
                        .doesNotContain("OPENAI_API_KEY"));
    }

    @Test
    @DisplayName("parsing tolerates spacing and drops blanks")
    void parseIsForgiving() {
        assertThat(AiModelAllowlist.parse(" a , b ,, c "))
                .containsExactlyInAnyOrder("a", "b", "c");
        assertThat(AiModelAllowlist.parse(null)).isEmpty();
        assertThat(AiModelAllowlist.parse("   ")).isEmpty();
    }
}

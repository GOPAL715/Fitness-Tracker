package com.fittrack.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Guards the model names a deployment may select (Phase 22).
 *
 * <h2>Why this exists</h2>
 * {@code app.ai-text-model} and {@code app.ai-vision-model} are free-form strings. A typo such as
 * {@code gpt-4o-minni} is not an error at startup: the request is accepted, the provider is called,
 * and the response is billed or refused by the provider. The cost lands before anyone notices, and
 * the failure surfaces as a 502 that looks like a provider outage rather than a configuration typo.
 *
 * <h2>Fail fast, and say what is allowed</h2>
 * Validation happens at construction, so an unsupported name stops the application starting rather
 * than surfacing later as a mysterious provider error. The message names the offending property and
 * lists the supported identifiers, because a configuration error is only cheap to fix if it is
 * specific. It never prints the API key: only property names and model identifiers are involved,
 * and the key is not read by this class at all.
 *
 * <h2>The allowlist is configuration, not code</h2>
 * Supported identifiers come from {@code app.ai-supported-models}, so adding a model the provider
 * already accepts is a configuration change rather than a code change and redeploy. The default
 * lists exactly the models this repository already configures - see {@code application.yml} - so a
 * default install starts cleanly.
 *
 * <p>Tests deliberately run against {@code fake-*} stand-ins rather than a real provider, so they
 * supply their own allowlist through the same property. There is no test-only escape hatch here: a
 * name is either in the configured list or startup fails.
 */
@Component
public class AiModelAllowlist {

    /**
     * Split, trim and drop blanks.
     *
     * <p>An entry is a plain model identifier. Comparison is exact and case-sensitive because
     * provider model ids are case-sensitive; silently lower-casing a name would turn a typo into a
     * different, valid-looking request.
     */
    static Set<String> parse(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(out::add);
        return Set.copyOf(out);
    }

    private final Set<String> allowed;

    public AiModelAllowlist(
            @Value("${app.ai-vision-model}") String visionModel,
            @Value("${app.ai-text-model}") String textModel,
            @Value("${app.ai-supported-models:gpt-4o-mini}") String supportedModels) {

        Set<String> allowed = parse(supportedModels);
        if (allowed.isEmpty()) {
            throw new IllegalStateException(
                    "app.ai-supported-models must list at least one model identifier");
        }
        // Both properties are checked before anything is thrown, so a misconfigured deployment is
        // fixed in one pass instead of revealing a second bad property only after the next redeploy.
        List<String> problems = new ArrayList<>(2);
        reject(allowed, visionModel, "app.ai-vision-model", problems);
        reject(allowed, textModel, "app.ai-text-model", problems);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Unsupported AI model configuration. "
                    + String.join(" ", problems)
                    + " Update the property, or add the model to app.ai-supported-models.");
        }
        this.allowed = allowed;
    }

    /**
     * Records one unsupported property.
     *
     * <p>Collecting rather than throwing is what lets a single startup failure report every bad
     * property, instead of surfacing one and hiding the other until the next deploy.
     */
    private static void reject(Set<String> allowed, String model, String property, List<String> problems) {
        if (model == null || model.isBlank() || !allowed.contains(model.trim())) {
            problems.add(property + " is set to '" + model + "', which is not a supported model. Supported: "
                    + allowed.stream().sorted().collect(Collectors.joining(", ")) + ".");
        }
    }

    /** The supported identifiers, for diagnostics and tests. */
    public Set<String> supported() {
        return allowed;
    }

    /** True when a configured name would be accepted. Never bypasses the constructor check. */
    public boolean isSupported(String model) {
        return model != null && allowed.contains(model.trim());
    }
}
